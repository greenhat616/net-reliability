# 架构

目标游戏版本：1.3.3。所有结论来自 `.decompiled/src/necesse` 的反编译源码，以及 `tools/net-test.sh` 的实测。

## 1. 原版网络层（本 mod 要补的地方）

| 层 | 原版行为 | 位置 |
|---|---|---|
| 传输 | 直连用 UDP `DatagramSocket`，每个 socket 一个阻塞接收线程；Steam P2P 用 `SteamNetworkingMessages`，默认 `SendFlag.Unreliable` | `ClientAddressNetwork`、`SteamServerOpenNetwork` / `DesktopServerOpenNetwork`、`SteamNetworkMessagesInfo` |
| 分片 | 包内容超过 992 字节就拆成多个数据报，每片带 `split 位 + int identifier + u16 序号 + u16 总数` | `NetworkPacket(Packet, NetworkInfo)` |
| 重组 | 在 `PacketManager.submitInPacket` 里合并；**5 秒内没收齐就整包丢弃，没有任何补发** | `PacketManager.incompletePackets = new NetworkPacketList(5000)` |
| 可靠性 | 传输层没有 ACK 和重传。靠应用层兜底：区域请求 2 秒没回应就重新请求整个区域 | `ClientLevelLoading.tickLoading`（`- 2000L`） |
| 统计 | 收发包数和字节数、"incomplete dropped"、根据双方计数估算的丢包 | `PacketManager`、`PacketNetworkUpdate` |

所以一个分片包只要丢了一片，就要等 2 秒让游戏重新请求，然后整包重发。

## 2. 本 mod 的做法：NACK + 选择性重传

```
发送方                                              接收方
NetworkPacket.sendPacket()  ──数据报──▶  PacketManager.submitInPacket()
   │ (NetworkPacketSendPatch)                  │ (PacketManagerSubmitInPatch)
   ▼                                           ▼
SendCache：按 identifier 保存原始数据报         Receiving → ReceiveTracker：记录每个分片包收到了哪几片
   ▲                                           │ 每帧（Server/Client.frameTick）检查：
   │                                           │   收到最后一片后超过 nackDelayMs 还缺片 → 发 NACK
   │◀──────── PacketFragmentNack(id, 缺片区间) ──┘   之后每隔 max(nackRetryMinMs, 2×RTT) 再发，最多 nackMaxRounds 轮
   │
   └─ 原样重发缺的数据报（identifier 不变，接收方的游戏照常合并）
```

关键约束：

- **不替换游戏的重组逻辑。** 游戏照常合并分片，本 mod 只在旁边记录"收到了哪几片"。所有数据报都经过同一个入口（`submitInPacket`），两边的记录不会不一致。
- **补发的是原始字节。** 不重新拆包，所以不依赖分片头的具体格式。读的只是游戏已经解析好的 `NetworkPacket` 字段（经 `GameInternals`）。
- **任何异常都不能抛进游戏。** 每个钩子入口都 catch 住，只记录一次日志，然后退回原版行为。
- **游戏内部改了就自动关闭。** `GameInternals.verify()` 在 `init()` 时检查用到的私有字段，有一个对不上就关掉 NACK，游戏照常运行（原版网络）。
- **双方都必须装本 mod**（`clientside = false`）。NACK 是新注册的包，包 ID 由注册顺序决定，两边的 mod 列表必须一致。游戏本身会强制这一点。
- **修正原版的合并 bug。** 第 0 片晚到时，原版合并后的计数是错的，包永远拼不齐（详见 [GAME-INTERNALS.md](GAME-INTERNALS.md)）。补发第 0 片必然触发这个 bug，所以 `NetworkPacketMergePatch` 在合并后重新计数。
- **NACK 是不可信的输入。** 只回应包原本的接收方（客户端也一样：UDP 客户端会接收任何地址发来的数据报）。一个 NACK 里每片最多重发一次，每个包最多补发 20 轮，同一个包同时最多排队一次补发，每个对端有字节预算（4 MB/s，突发 1 MB），所有补发排队总量不超过 4 MB。补发在本 mod 的工作线程里执行，不占用接收线程（接收线程此时持有游戏 `PacketManager` 的锁）。
- **所有表都有上限。** 任何人都能发看起来像分片的数据报，所以未收齐的包最多跟踪 4096 个，已完成记录最多 65536 条，对端最多 4096 个。超过上限的包不跟踪，照样交给游戏。记录的键包含接收方的 `PacketManager`：新连接有新的 manager，旧连接的记录（对方重启后包号会从 1 重新开始）不会被误用。玩家断开时清掉这个对端的记录；没人 tick 的旧记录在超时后统一清理。
- **"已完成"的判断不会早于游戏。** 游戏的半成品包在最后一片到达 5 秒后才过期；tracker 从第一片到达算起，`nackGiveUpMs`（< 5 s）后就放弃，而且在收到分片时也会检查（不只在 tick 里），所以 tracker 判为完成的包，游戏一定还持有它的半成品。
- **只保留已经收齐的包号，不提前丢包。** 收齐后 10 秒内再收到同一个包的分片（补发晚到的），直接丢掉，不交给游戏，免得游戏为它新建一个永远收不齐的半成品。放弃了的包，晚到的分片照样交给游戏，游戏自己可能还能拼齐。

## 3. 代码分层

| 包 | 内容 | 依赖游戏内部？ |
|---|---|---|
| `patches/` | ByteBuddy advice，每个类只 patch 一个游戏方法，只做一件事：转调 `core/` 或 `steam/` | 是（目标方法签名），全部列在 [GAME-INTERNALS.md](GAME-INTERNALS.md) |
| `compat/` | `GameInternals`：**唯一**使用反射访问私有成员的地方；`WireFormat`：对数据报布局的假设 | 是，集中在这里 |
| `core/` | `Receiving`（收包入口：socket 缓冲区、模拟丢包、交给 tracker、合并修正）、`ReceiveTracker`（NACK 调度）、`SendCache`（补发）、`Sending`、`FrameTick`、`NetThreads`、`HookStatus` | 只用公开 API |
| `packets/` | `PacketFragmentNack`，格式见 [PROTOCOL.md](PROTOCOL.md) | 只用公开 API |
| `kcp/` | 实验性的 KCP 传输（[KCP.md](KCP.md)）：`Kcp`（ikcp.c 的移植）、`KcpSession`、`KcpTransport`、`KcpHooks`、`PacketKcpSegment` | 只用公开 API（加 `GameInternals` 生成包头） |
| `steam/` | Steam P2P 发送路由。**只有这个包能引用 Steam 类**：独立服务器（`Server.jar`）没有 Steam 类，别处一引用就会在服务器上 `NoClassDefFoundError` | 是 |
| `config/` | `NetConfig`（不可变快照，默认值只写在 `Builder` 里；文件里保存的一份和当前生效的一份分开，指令的临时修改不会被保存）、`NetSettings`（Mod Settings Lib 的设置页，加上设置文件里的其余选项） | 公开 API，加 Mod Settings Lib 的 API |
| `stats/` | `NetStats`（进程级计数器）、`StatsLog`（CSV） | 只用公开 API |
| `commands/` | `/netrel`（服务器）、`/netrelc`（本客户端） | 只用公开 API |
| `test/` | `NetTest` 区域加载基准测试（只在 `-Dnetreliability.test=1` 时生效）、`PacketNetTestReady`、`TestCharacter` | 只用公开 API |

## 4. 线程

| 代码 | 运行线程 |
|---|---|
| `Receiving.onDatagram`、`ReceiveTracker.onFragment` | 各 socket 的接收线程（客户端 1 个，服务器每个 socket 1 个，加 Steam 接收线程） |
| `PacketFragmentNack.process*` → `SendCache.onNack` | 注册为 `processInstantly`，在接收线程里校验和查表 |
| 补发（`SendCache.send`） | `NetReliability worker` 线程（`NetThreads`） |
| `SendCache.onSent` | 调 `sendPacket` 的线程（主要是游戏 tick 线程） |
| `ReceiveTracker.tick`、`SendCache.tick`、`StatsLog`、`NetTest` | `Server.frameTick` / `Client.frameTick`（主机同时有两个，都会调用；`tick` 按 `PacketManager` 分开处理各自的包） |

`ReceiveTracker` 用 `ConcurrentHashMap`，每个半成品包单独加锁；`SendCache` 用一把全局锁，补发在锁外进行。

## 5. Steam P2P

NACK 在 `NetworkInfo` 这一层工作，对直连 UDP 和 Steam P2P 都有效。此外 `steamSendMode` 用来做对比实验：

- `VANILLA`：不动。
- `FRAGMENTS_RELIABLE`：分片数据报改用 Steam 自己的可靠发送（`ReliableNoNagle`），其他数据报保持游戏原来的标志。判断是不是分片只看第 0 字节的第 0 位（`WireFormat.isFragment`）。
- `ALL_RELIABLE`：改写全局的 `SteamNetworkMessagesInfo.sendFlag`。原版 `/steamnetwork <flag>` 也是改这个字段。所有数据报都可靠且有序，移动包可能被前面的包堵住（队头阻塞）。

## 6. 已知限制

- **单个数据报的包丢了，NACK 救不回来。** 实验性的 KCP 传输（[KCP.md](KCP.md)）能解决这个问题。 接收方根本不知道这个包存在。区域数据大多只有 1–2 片（1.3.3 实测最多 3 片），所以收益只来自多片包；单片包仍然靠游戏自己的 2 秒重新请求。详见 [RESULTS.md](RESULTS.md)。
- **一个包的分片全丢了**，同样不知道它存在。
- 模拟丢包只作用于本进程**收到的**数据报。
- tracker 在钩子里（游戏合并这一片**之前**）就判定"已完成"。如果接收线程恰好在这两步之间停顿超过约 0.5 秒（`nackGiveUpMs` 和游戏 5 秒超时之间的余量），游戏可能先让它的半成品过期，tracker 随后会丢掉游戏其实还需要的副本。结果和原版一样（包丢了，游戏重新请求）：游戏重新开始的半成品需要所有分片，任何 NACK 补发都不会把它们全部补齐。

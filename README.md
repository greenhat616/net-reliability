# Net Reliability

Necesse 1.3.3 Mod：补上游戏网络层缺的丢包补发，并提供网络测量工具。

## 解决什么问题

原版把超过 992 字节的包拆成多个 UDP 数据报。只要丢了一片，整个包就作废，因为传输层没有任何补发。之后要等游戏自己的超时（区域数据是 2 秒）重新请求整个包。

本 Mod 让接收方发现缺片后马上请求补发（NACK），发送方只把缺的那几片原样重发。直连 IP 和 Steam P2P 都有效。

| 功能 | 说明 |
|---|---|
| NACK 补发 | 分片包停止到达 `nackDelayMs`（默认 40 ms）后仍缺片，就向对方要缺的片；按实测 RTT 重试，最多 8 轮 |
| 修正原版合并 bug | 第 0 片晚到的分片包在原版里永远拼不齐（计数没更新），补发第 0 片时必然遇到 |
| 调大 socket 缓冲区 | 把游戏 UDP socket 的收发缓冲区调到 4 MB（Windows 默认 64 KB），减少突发流量时被系统丢掉的包 |
| Steam 发送模式 | 实验选项：只让分片走 Steam 的可靠通道，或者全部走可靠通道 |
| KCP 传输（实验性） | 直连 IP 时把整个连接跑在 KCP 上，单数据报的包丢了也能重传 |
| 测量 | `/netrel status` 查看游戏和本 Mod 的计数器；可以定时写 CSV；可以模拟丢包 |

**所有玩家都需要安装本 Mod**（`clientside = false`），因为它注册了新的网络包。

实测效果见 [docs/RESULTS.md](docs/RESULTS.md)。一个限制：只占一个数据报的包丢了，接收方根本不知道它存在，NACK 补不回来。这类包仍然靠游戏自己的重新请求。

## 设置

保存在 `cfg/mods/a632079.netreliability.cfg`（第一次启动时按默认值生成，每项都有注释）。每台机器只管自己，不在服务器和客户端之间同步。

| 键 | 默认值 | 说明 |
|---|---|---|
| `fixVanillaMergeBug` | true | 修正原版 bug：第 0 片晚到（乱序或被补发）的分片包永远拼不齐。NACK 依赖它 |
| `nackEnabled` | true | 本机收到的分片包缺片时请求补发。回应对方的补发请求始终开启 |
| `nackDelayMs` | 40 | 分片包多久没有新片到达，就认为剩下的丢了 |
| `nackRetryMinMs` | 120 | 再次请求同一个包前至少等多久（实际取它和 2 × RTT 中较大的） |
| `nackMaxRounds` | 8 | 每个包最多请求几轮 |
| `nackGiveUpMs` | 4500 | 从第一片到达算起，多久后放弃（必须小于游戏自己的 5000 ms 超时） |
| `sendCacheMs` | 6000 | 发出的分片保留多久，用来回应补发请求 |
| `sendCacheMaxKB` | 65536 | 保留的分片最多占多少内存 |
| `socketBufferKB` | 4096 | 请求的 UDP socket 缓冲区大小；0 = 系统默认 |
| `steamSendMode` | VANILLA | Steam P2P：`VANILLA` / `FRAGMENTS_RELIABLE`（分片走可靠通道）/ `ALL_RELIABLE`（全部可靠，可能因队头阻塞让移动卡顿，仅供对比） |
| `simulatedLoss` | 0 | **测试用**：本机故意丢掉的入站数据报比例（0–1） |
| `simulatedLossFragmentsOnly` | false | **测试用**：只丢分片。对走 KCP 的连接无效（KCP 数据报本身不是分片） |
| `statsLogSeconds` | 0 | 每隔几秒往 `<数据目录>/netreliability-stats.csv` 写一行计数器；0 = 关闭 |
| `kcpEnabled` | false | **【实验性】** 客户端通过 IP 加入服务器时改用 KCP 协议（可靠、有序）。服务器必须装本 Mod 且 `kcpAcceptFromClients = true`，否则连不上。Steam P2P 不受影响。详见 [docs/KCP.md](docs/KCP.md) |
| `kcpAcceptFromClients` | true | 作为服务器（或主机）时，回应用 KCP 加入的客户端 |
| `kcpIntervalMs` | 10 | KCP 的更新间隔 |
| `kcpWindow` | 256 | KCP 的收发窗口（段数） |
| `kcpFastResend` | 2 | 一个段之后有几个段被确认，就立即重传它；0 = 关闭 |

## 指令

| 指令 | 权限 | 作用 |
|---|---|---|
| `/netrel status` | ADMIN | 服务器端：设置、钩子是否生效、socket 缓冲区、Steam 模式、游戏和本 Mod 的计数器、在线玩家的延迟 |
| `/netrelc status` | USER | 同上，但是看本客户端 |
| `/netrel(c) reset` | | 计数器清零 |
| `/netrel(c) reload` | | 重新读取设置文件 |
| `/netrel(c) nack <on\|off>` | | 临时开关 NACK（重新读取设置文件后恢复） |
| `/netrel(c) sim <丢包率> [all\|fragments]` | | 临时设置模拟丢包 |
| `/netrel(c) steam <模式>` | | 临时切换 Steam 发送模式。原版的 `/steamnetwork <flag>` 也能直接改 Steam 发送标志 |

主机（自己开世界）的服务器和客户端在同一个进程里，两个指令看到的本 Mod 计数器是同一份。

## 构建与测试

```bash
JAVA_HOME=<JDK 17+> ./gradlew buildModJar          # 输出 build/jar/NetReliability-1.3.3-<版本>.jar
tools/check-internals.sh                            # 核对依赖的游戏成员是否都还在
tools/net-test.sh <标签> [--server k=v ...] [--client k=v ...]   # 一次区域加载基准测试
tools/net-bench.sh [丢包率 ...]                    # 原版 vs 本 Mod 的完整对比
```

测试脚本在本机启动一个独立服务器和一个客户端，经 127.0.0.1 直连，都用临时的数据目录，不会碰你自己的存档。需要 Steam 在运行。

## 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：原版网络层分析、设计、代码分层、线程
- [docs/PROTOCOL.md](docs/PROTOCOL.md)：NACK 包格式和兼容规则
- [docs/GAME-INTERNALS.md](docs/GAME-INTERNALS.md)：依赖的每个游戏成员
- [docs/UPGRADING.md](docs/UPGRADING.md)：游戏更新后的适配步骤
- [docs/KCP.md](docs/KCP.md)：实验性的 KCP 传输
- [docs/RESULTS.md](docs/RESULTS.md)：测量结果

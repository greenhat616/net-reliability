# 依赖的游戏内部

本 mod 依赖的所有游戏成员都列在这里。`tools/check-internals.sh` 会用 javap 逐条核对已安装的 `Necesse.jar` 和 `Server.jar`；本表与脚本必须同步修改。

列说明：**用途**指哪段代码依赖它；**改了会怎样**指游戏更新后这项变了，mod 的表现。

## 私有成员（反射，全部集中在 `compat/GameInternals`）

| 成员 | 用途 | 改了会怎样 |
|---|---|---|
| `NetworkPacket.packetIndex`（`private final int`） | 收到的数据报是第几片 | `verify()` 失败，NACK 关闭，日志打印原因 |
| `NetworkPacket.packets`（`private final NetworkPacket.PacketData[]`） | 总片数（数组长度），以及发出的各片 | 同上 |
| `NetworkPacket.PacketData.packet`（`private final byte[]`） | 每一片完整的原始数据报，补发用 | 同上 |
| `NetworkPacket.complete`（`private int`） | 修正原版合并时的片数计数（见下文"原版 bug"） | 同上 |

## patch 的方法（ByteBuddy，`patches/`）

| 方法 | patch 类 | 改了会怎样 |
|---|---|---|
| `PacketManager.submitInPacket(NetworkPacket)` | `PacketManagerSubmitInPatch`（enter，可以跳过原方法） | 加载时日志 "did not complete any patches"；收包统计、模拟丢包、NACK 全部失效 |
| `NetworkPacket.sendPacket()` | `NetworkPacketSendPatch`（exit） | 不再缓存发出的包，NACK 都变成 cache miss |
| `NetworkPacket.mergePackets(NetworkPacket)` | `NetworkPacketMergePatch`（exit：返回值不是 `this` 时，用 `@Advice.FieldValue("complete")` 读出 `this` 的正确计数，经 `GameInternals.setFragmentCount` 写给返回值） | 第 0 片晚到的包（包括补发的第 0 片）永远拼不齐；游戏的 `incompleteDropped` 计数会上涨 |
| `Server.frameTick(TickManager)` | `ServerFrameTickPatch`（exit） | 服务器端不再发 NACK，CSV 和基准测试驱动停止 |
| `Client.frameTick(TickManager)` | `ClientFrameTickPatch`（exit） | 客户端不再发 NACK |
| `SteamNetworkMessagesInfo.send(byte[])`（仅 `Necesse.jar`） | `SteamSendPatch`（enter，可以跳过原方法） | `FRAGMENTS_RELIABLE` 失效。在 `Server.jar` 上本来就会打印 "Error loading mod patch class ... likely outdated mod"，这是**预期行为**，因为服务器端没有 Steam 类 |

`/netrel status` 里的 `Hooks seen` 一行显示每个 patch 是否真的运行过，是游戏更新后最快的检查方法。

## 原版 bug：第 0 片晚到时包永远拼不齐

`NetworkPacket.mergePackets`：后到的片是第 0 片时（`packet.type != -1`），它把已合并的分片数组复制进第 0 片的对象并返回这个对象，**但没有复制计数 `complete`**，返回的对象计数仍是 1。之后就算所有分片都到齐，`isComplete()` 也永远是 false，最后被 5 秒超时丢掉。

只要第 0 片被乱序或重发，就会触发这个 bug，而 NACK 补发第 0 片正是这种情况。`NetworkPacketMergePatch` 在返回值不是 `this` 时，把 `this` 的计数（已经包含刚合并的这一片）复制给返回值，开销是常数时间。游戏更新后如果修了这个 bug，这个 patch 照样无害。设置 `fixVanillaMergeBug = false` 可以关掉它（只用于对比测试）。

## 依赖的公开成员和行为

| 成员 / 行为 | 用途 |
|---|---|
| `NetworkPacket.identifier`（0 = 没有拆分）、`networkInfo`、`isComplete()`、构造函数 `NetworkPacket(Packet, NetworkInfo)` | 区分分片包、发送 NACK |
| `NetworkInfo.send(byte[])`、`equals` / `hashCode`（`DatagramNetworkInfo` 按地址+端口，`SteamNetworkMessagesInfo` 按 SteamID） | 补发、按对端分组 |
| `DatagramNetworkInfo.socket`（public） | 调大 socket 缓冲区 |
| `PacketManager.submitOutPacket`、各个统计 getter | NACK 计入游戏的发包统计（否则对方的丢包估算会出错）；`/netrel status` 和 CSV |
| **游戏的半成品包超时是 5000 ms**（`new NetworkPacketList(5000)`） | `nackGiveUpMs` 的上限是 4900。脚本会检查 `PacketManager` 里还有 `sipush 5000` |
| **每片 992 字节**（`NetworkPacket` 里的 `sipush 992`） | 只出现在文档和 `WireFormat` 的说明里，代码不依赖它 |
| **分片标志是第 0 字节的第 0 位**（`PacketWriter.putNextBoolean` 从第 0 位开始写） | `WireFormat.isFragment`，只有 Steam 的 `FRAGMENTS_RELIABLE` 用到。脚本查不出这一项，见 UPGRADING.md 第 3 步的手动检查 |
| `PacketRegistry.registerPacket(boolean, boolean, boolean, Class)` | 注册 NACK |
| `ServerClientDisconnectEvent.client.networkInfo` | 玩家断开时清掉这个对端的接收记录 |
| `SteamNetworkMessagesInfo.sendFlag`（public static）、`networking`、`remoteID`、`defaultChannel` | Steam 发送路由 |
| `Client.packetManager`、`Server.packetManager`、`Client.getLocalServer()` | `FrameTick` |
| 仅测试用：`ClientLevelLoading.isLoadingDone()`、`ServerClient.changeLevel(LevelIdentifier, Function<Level, Point>, boolean)`、`Client.loading.isDone()`、`CharacterSave` | `NetTest`、`TestCharacter` |

# 适配新的游戏版本

给接手的 agent 或开发者的检查清单，按顺序做。

## 1. 自动检查依赖项

```bash
tools/check-internals.sh
```

每条 `MISSING` 都对应 [GAME-INTERNALS.md](GAME-INTERNALS.md) 里的一项。全部 `ok` 才继续下一步。

## 2. 重新反编译，对比网络层

```bash
# 仓库根目录的 .decompiled/ 下有 vineflower.jar
java -jar .decompiled/vineflower.jar "<游戏目录>/Necesse.jar" .decompiled/src
```

重点看以下文件是否有改动（可以用 diff 或者 git 对比旧的反编译结果）：

- `necesse/engine/network/NetworkPacket.java`：分片头格式、`packetIndex` / `packets` / `PacketData`、`sendPacket`
- `necesse/engine/network/PacketManager.java`、`NetworkPacketList.java`：`submitInPacket` 的流程、5000 ms 超时
- `necesse/engine/network/client/ClientLevelLoading.java`：2000 ms 的重新请求（影响收益，不影响正确性）
- `necesse/engine/platforms/steam/network/networkInfo/SteamNetworkMessagesInfo.java`
- `necesse/engine/network/server/Server.java`、`client/Client.java` 的 `frameTick`

**如果游戏自己加了可靠传输（ACK、重传）**，本 mod 可能就多余了：先确认原版的 `incompleteDropped` 是否仍会增长，再决定是否保留。

## 3. 手动检查无法自动化的假设

- [ ] `NetworkPacket(Packet, NetworkInfo)` 写的第一个字段仍是 `putNextBoolean(totalPackets > 1)`（`WireFormat.isFragment` 依赖它）
- [ ] 每个数据报仍然都经过 `PacketManager.submitInPacket`（客户端 `ClientAddressNetwork`、服务器 `*ServerOpenNetwork`、两个 Steam 接收线程）。如果有新的接收入口绕过了它，`ReceiveTracker` 会认为有缺片，发出多余的 NACK
- [ ] 每个发往远端的包仍然都经过 `NetworkPacket.sendPacket()`
- [ ] 分片包号仍然来自进程级的全局计数器（`SendCache` 只用包号查找）

## 4. 改代码

- 私有成员改名或改类型：只改 `compat/GameInternals`
- 方法签名变了：改对应的 `patches/*Patch` 注解，以及 `tools/check-internals.sh` 和 GAME-INTERNALS.md
- 数据报格式变了：改 `compat/WireFormat` 及其注释
- `build.gradle` 的 `gameVersion` 改成新版本，`modVersion` 加一

## 5. 测试

```bash
JAVA_HOME=<JDK 17+> ./gradlew buildModJar     # 需要 JDK 17+ 运行 Gradle，产物是 Java 8 字节码
tools/check-internals.sh
tools/net-test.sh smoke                        # 无丢包，确认能连接、传送、加载完成
tools/net-bench.sh                             # 完整对比，约 20 分钟，要求 Steam 在运行
```

通过标准：

- 两边日志都没有 `[NetReliability] error` 或 `game internals changed`
- `/netrel status`（或测试日志）里 `Hooks seen` 全是 true（独立服务器上 `steamSend=false` 是正常的）
- 有丢包时，mod 那一组的 `given up` 明显比 vanilla 组少，`recovered by NACK` 大于 0
- 无丢包时，`NACKs sent` 应该是 0（不为 0 说明有收包入口绕过了 `submitInPacket`，见第 3 步）

把新结果写进 [RESULTS.md](RESULTS.md)，注明游戏版本。

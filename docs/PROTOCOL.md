# 协议

## PacketFragmentNack（双向）

用游戏的 `Packet` 机制注册：`PacketRegistry.registerPacket(processInstantly=true, onlyConnectedClients=false, timestamp=false, ...)`。

- `processInstantly`：在接收线程收到就立刻处理，不排进 tick 队列，补发能快一点。
- `onlyConnectedClients=false`：连接阶段的包也可能被拆分，那时对方还不是 `ServerClient`。

内容（用 `PacketWriter` 写入）：

| 字段 | 类型 | 说明 |
|---|---|---|
| version | u8 | 当前为 1。收到不认识的版本就忽略整个包（新版 mod 发给旧版时不会被误读） |
| identifier | int | 要补的分片包号，即游戏分片头里的 `identifier` |
| rangeCount | u16 | 区间个数，最多 200 |
| ranges | rangeCount × (u16 起始序号, u16 片数) | 缺的分片 |

最大 1 + 4 + 2 + 800 = 807 字节，一定装得进一个数据报，所以 NACK 自己永远不会被拆分。

### 发送方的处理

- 按 `identifier` 在 `SendCache` 里查。游戏的分片包号来自进程级的全局计数器，所以只用包号就能唯一确定。
- 只回应包原本的接收方（比较 `NetworkInfo`：直连按地址+端口，Steam 按 SteamID），别的地址发来的 NACK 计入 `nackWrongPeer` 然后忽略。服务器和客户端都这样处理。代价是：服务器如果从另一个地址回包（多网卡），补发就不起作用，退回原版行为。
- 区间先合并去重，并裁剪到包的实际片数，每片最多重发一次。
- 同一个包距上次补发不到 15 ms、或上一次补发还在排队时，NACK 忽略。每个包最多补发 20 轮。
- 每个对端有字节预算（4 MB/s，突发 1 MB），所有对端排队等待补发的总量不超过 4 MB。请求超过剩余预算时，只补发按序号排在前面、放得下的那些片，剩下的等对方下一次 NACK。一片都放不下时计入 `resendBudgetDenied`，不算作一轮。
- 补发就是在工作线程里对每个缺的序号原样调用 `NetworkInfo.send(原始数据报)`。

### 兼容性规则（升级协议时遵守）

1. 改格式必须提升 `VERSION`。旧版本会忽略新版本的 NACK，只是失去补发，不会出错。
2. 不要改包的注册顺序。所有包的 ID 由注册顺序决定，双方必须一致。新包只能**加在最后**。
3. 本 mod 是 `clientside = false`，游戏会要求服务器和客户端的 mod 列表（包括版本）一致。所以实际上不会出现两个协议版本混用，以上规则只是保底。

## PacketNetTestReady（客户端 → 服务器，仅基准测试用）

`int seq, long durationMs`：第 `seq` 次传送（0 = 刚加入时）之后，客户端加载完区域用了多少毫秒（-1 = 超时，0 = 不需要加载）。只有 `-Dnetreliability.test=1` 时服务器才处理它。它总是会被注册，以保证两边的包 ID 一致。

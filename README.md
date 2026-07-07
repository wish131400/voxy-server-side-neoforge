# Voxy Server Side NeoForge

Voxy Server Side, 简称 VSS，是给 Voxy 使用的服务端远景同步 mod。它让服务器把已经存在、已经缓存或允许服务端生成的 LOD 数据发送给客户端，客户端不再完全依赖本地慢慢生成远景。

简单说，它解决的是大型整合包、服务器或预生成世界里的一个痛点：玩家进入世界、移动或高速飞行时，远景应该尽快按距离补齐，而不是让客户端 CPU 独自把所有 LOD 都算一遍。

Forge 1.20.1 版本见：[wish131400/voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)

下载地址 / Download: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

## 当前版本

| 项目 | 状态 |
| --- | --- |
| Minecraft | `1.21.1` |
| Loader | NeoForge `21.1.x` |
| VSS 版本 | `0.2.7-neoforge-1.21.1` |
| VSS 协议 | `34` |
| 客户端渲染目标 | Voxy + Sodium 兼容环境 |

客户端和服务端的 VSS 协议必须一致。协议不一致时玩家仍可进服，但本次会话不会启用 VSS LOD 同步。

## 主要功能

- 服务端向客户端同步 Voxy LOD 列数据。
- 支持内存热缓存、持久化 `.vcl` 缓存、已加载区块快照、区块 NBT 读取和可选区块生成。
- 客户端按玩家位置由近到远请求 LOD，避免远处任务长期占住近处刷新。
- 服务端使用每玩家发送队列，按距离重排，优先发送更接近玩家的列。
- 支持 Region Presence 摘要，客户端会上报自己已有的 LOD，减少重复下发。
- 支持脏列刷新，方块变化后可以通知客户端刷新对应 LOD。
- 支持 zstd / deflate / raw 的网络压缩回退。
- VSS 会按自身压缩后的 wire bytes 统计流量，而不是依赖外部网络层猜测大小。
- 支持低带宽发送预算和队列背压，避免把大量 LOD 包一次性塞进网络队列。
- 支持远处玩家和载具同步，用于 Northstar 等远距离实体场景。

## 安装

服务端需要：

- NeoForge `21.1.x`
- 本 mod
- 推荐安装 ZstdNet，或任何提供 `com.github.luben.zstd.Zstd` 的 zstd 库

客户端需要：

- NeoForge `21.1.x`
- 本 mod
- Voxy
- Sodium 兼容渲染环境
- 推荐安装 ZstdNet

没有 zstd 时，VSS 会回退到 deflate；如果压缩收益很小，则直接发送 raw 数据。历史 zstd 持久化缓存仍需要 zstd 支持才能读取。

## 同步流程

客户端以玩家所在 chunk 为中心扫描 LOD 请求。距离使用 Chebyshev ring：

```text
ring = max(abs(dx), abs(dz))
```

请求大致按以下路径流动：

```text
客户端扫描视野
-> 上报本地已有 LOD presence
-> 请求缺失或过期的 LOD 列
-> 服务端查询内存缓存 / 持久化缓存 / 已加载区块 / 区块 NBT / 可选生成
-> 进入每玩家发送队列
-> VSS 压缩并按 wire bytes 限速
-> 客户端解压并交给 Voxy ingest
```

服务端查询顺序：

```text
内存列缓存
-> 持久化 .vcl + index.vci
-> 已加载区块快照
-> 可选区块 NBT
-> 可选区块生成
```

## 带宽和压缩

VSS 的带宽限制按“VSS 自己实际发出的压缩后字节数”计算。也就是说，限速、队列大小、诊断日志使用的是 payload 编码和压缩后的 wire bytes，而不是原始 LOD 大小，也不是外部压缩 mod 显示的线路统计。

发送端使用每玩家 token bucket。较大的 LOD 列包允许一次发出，但会产生发送债务，后续 tick 必须等预算恢复后才继续发送。这能避免低带宽下把 Netty 队列灌满，导致客户端看起来“待发送很多但迟迟不刷新”。

需要注意：1 Mbps 和 5 Mbps 对初始 LOD 同步的体感差异会非常明显。LOD 初始同步可能包含几十 MB 的压缩后数据，1 Mbps 接近 `125 KB/s`，即使逻辑正确，也不可能像 5 Mbps 一样秒出。

## 持久化缓存

完整 LOD 列按世界、维度和 Region 存储：

```text
<world>/data/vss-column-cache/<dimension>/<regionX>_<regionZ>/<chunkX>_<chunkZ>.vcl
<world>/data/vss-column-cache/<dimension>/<regionX>_<regionZ>/index.vci
```

例如：

```text
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/12_8.vcl
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/index.vci
```

每个 Region 覆盖 `32 x 32` 个 chunk。`.vcl` 保存单列 LOD 数据，`index.vci` 保存该 Region 内有哪些列存在、时间戳、压缩方式、schema 和长度。服务端会缓存热点 Region 索引，避免频繁遍历目录。

## 常用配置

服务端配置：

```text
config/vss-server-config.json
```

客户端配置：

```text
config/vss-client-config.json
```

常用服务端配置：

| 配置项 | 作用 |
| --- | --- |
| `enabled` | 是否启用 VSS 服务端同步 |
| `lodDistanceChunks` | 服务端允许同步的最大 LOD 半径 |
| `bytesPerSecondLimitPerPlayer` | 每玩家发送带宽上限 |
| `sendQueueLimitPerPlayer` | 每玩家待发送 LOD 列数量上限 |
| `sendQueueBytesLimitPerPlayer` | 每玩家待发送 LOD wire bytes 上限 |
| `nearSyncRateLimitPerTick` | 近距离已有 LOD 请求限速 |
| `midSyncRateLimitPerTick` | 中距离已有 LOD 请求限速 |
| `farSyncRateLimitPerTick` | 远距离已有 LOD 请求限速 |
| `distantSyncRateLimitPerTick` | 超远距离已有 LOD 请求限速 |
| `enableChunkGeneration` | 缺失 LOD 时是否允许服务端生成区块 |
| `generationRateLimitPerPlayer` | 每玩家缺失 LOD 生成速率 |
| `generationConcurrencyLimitPerPlayer` | 每玩家生成并发 |
| `generationConcurrencyLimitGlobal` | 全服生成并发 |
| `generationPackingThreads` | 后台 LOD 打包线程数 |
| `diskReaderThreads` | 持久化缓存和 NBT 读取线程数 |
| `enableNetworkColumnCompression` | 是否启用网络 LOD 压缩 |
| `enablePersistentColumnCache` | 是否启用 `.vcl` 持久化缓存 |
| `dirtyBroadcastIntervalTicks` | 脏列刷新广播间隔 |
| `farPlayerSyncEnabled` | 是否启用远处玩家同步 |

常用客户端配置：

| 配置项 | 作用 |
| --- | --- |
| `receiveServerLods` | 是否接收服务端 LOD |
| `lodDistanceChunks` | 客户端请求距离，`0` 表示自动 |
| `desiredBandwidthKbps` | 客户端期望下载限速，`0` 表示使用服务端限制 |
| `offThreadSectionProcessing` | 是否在线程外处理收到的 LOD 再交给 Voxy |

## 常用命令

```text
/vss stats
/vss bandwidth get
/vss bandwidth set_kbps <kbps>
/vss bandwidth set_mbps <mbps>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <MiB>
/vss request_limits get
/vss request_limits set_near <columns_per_tick>
/vss request_limits set_mid <columns_per_tick>
/vss request_limits set_far <columns_per_tick>
/vss request_limits set_distant <columns_per_tick>
/vss distance get
/vss distance set <chunks>
/vss storage get
/vss storage set_disk_readers <threads>
/vss generation get
/vss generation stats
/vss generation enable
/vss generation disable
/vss generation set_player_concurrency <limit>
/vss generation set_global_concurrency <limit>
/vss generation set_starts_per_tick <limit>
/vss generation set_completions_per_tick <limit>
/vss generation set_packing_threads <threads>
/vss generation set_packing_queue <limit>
/vss generation set_timeout <seconds>
/vss dirty get
/vss dirty set_interval <ticks>
/vss farplayers get
/vss farplayers enable
/vss farplayers disable
/vss farplayers set_interval <ticks>
```

中文别名也可用，常用入口包括 `/vss 状态`、`/vss 带宽`、`/vss 距离`、`/vss 队列`、`/vss 请求限速`、`/vss 存储`、`/vss 刷新`、`/vss 生成`、`/vss 远处玩家`。

## 调试建议

已有缓存 LOD 加载慢：

- 提高 `/vss bandwidth set_kbps` 或客户端 `desiredBandwidthKbps`。
- 提高对应距离段的请求限速。
- 如果瓶颈是磁盘读取，逐步提高 `diskReaderThreads`。
- 查看 `/vss stats` 和 `/vss generation stats` 的缓存、磁盘和队列状态。

缺失 LOD 生成慢：

- 确认 `enableChunkGeneration=true`。
- 提高 `generationRateLimitPerPlayer`。
- 逐步提高 `generationConcurrencyLimitPerPlayer` 或 `generationStartsPerTickLimit`。
- 提高 `generationPackingThreads` 时注意 CPU 和内存压力。

低带宽下刷新慢：

- 先确认实际线路下载是否已经接近配置上限。
- 查看 debug 日志中的 `wireBytes`、`rawBytes`、`queuedWireBytes` 和 `sendCreditBytes`。
- `sendCreditBytes` 为负表示大列包正在消耗发送债务，这是低带宽下的正常限速表现。
- 如果需要 1 Mbps 下也快速成片刷新，后续需要更细粒度的分段 LOD 发送，而不是只调带宽。

## NeoForge 说明

- 1.21.1 端口与 Forge 1.20.1 的 0.2.7 行为对齐。
- 客户端 UI 集成面向当前 Voxy/Sodium 风格选项页。
- 包含 client-only 类加载保护，避免独立服务端加载 `net.minecraft.client.Minecraft`。

## Notes

- VSS `.vcl` 缓存按世界和维度隔离。
- Region 索引是加速查询用的派生数据，缺失或过期时可重建。
- 一个玩家生成或缓存过的完整 LOD 可以被其他玩家复用。
- VSS 负责把 LOD 数据交给 Voxy，实际渲染仍由 Voxy 完成。

## License

MIT. See `LICENSE`.

# Voxy Server Side NeoForge

NeoForge 1.21.1 的 Voxy 服务端远景同步 mod。服务器会把已有、缓存过或允许服务端生成的 LOD 列发送给安装了 Voxy 的客户端，让远景按玩家位置由近到远补齐，而不是完全依赖客户端本地生成。

Forge 1.20.1 版本见：[wish131400/voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)

下载地址 / Download: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

## 当前状态

- Minecraft: `1.21.1`
- Loader: NeoForge `21.1.x`
- 当前版本: `0.2.7-neoforge-1.21.1`
- VSS 协议: `34`
- 客户端渲染目标: Voxy + Sodium 兼容环境

客户端和服务端的 VSS 协议必须一致。协议不一致时玩家仍可进服，但本次会话不会启用 VSS 远景同步。

## 安装

服务端：

- NeoForge `21.1.x`
- 本 mod
- 推荐安装 ZstdNet 或其他提供 `com.github.luben.zstd.Zstd` 的 zstd 库

客户端：

- NeoForge `21.1.x`
- 本 mod
- Voxy
- Sodium 兼容渲染环境
- 推荐安装 ZstdNet

没有 zstd 时，网络压缩会回退到 deflate；如果压缩收益很小则直接发送原始数据。历史 zstd 持久化缓存仍需要 zstd 支持才能读取。

## 同步模型

客户端以玩家为中心扫描 LOD 请求，主顺序仍是切比雪夫距离：

```text
ring = max(abs(dx), abs(dz))
```

当前请求链路分成几类：

- 已有 LOD：按距离分档限速，`0-32`、`33-64`、`65-128`、`129+` 分别控制每 tick 请求数量。
- 近处已有 LOD：`0-32` 默认不限速到单包上限，降低进服和移动时的体感延迟。
- 缺失 LOD：只有服务端允许生成时才进入生成队列，并受生成速率、并发、主线程启动和后台打包限制。
- 脏列刷新：方块变化、爆炸等导致的已知列刷新走优先路径。

服务器侧查询顺序大致为：

```text
内存列缓存 -> 持久化 .vcl + index.vci -> 已加载区块快照 -> 可选区块 NBT -> 可选区块生成
```

客户端还会维护本地 LOD presence 摘要。进服、切维度或视野窗口变化时会快速上报当前窗口摘要；后续收到服务端推送的列会做合并后的低频增量摘要，避免频繁回声包。服务端真正把列通过限速桶发出后，也会立即把该列标记为该玩家已知，减少重复扫描和重复下发。

## 持久化缓存和 Region 索引

完整 LOD 列按世界和维度存储：

```text
<world>/data/vss-column-cache/<dimension>/<regionX>_<regionZ>/<chunkX>_<chunkZ>.vcl
<world>/data/vss-column-cache/<dimension>/<regionX>_<regionZ>/index.vci
```

例如：

```text
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/12_8.vcl
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/index.vci
```

每个 Region 覆盖 `32 x 32` 个 chunk。`.vcl` 保存单列完整数据，`index.vci` 保存该 Region 内哪些列存在、时间戳、压缩方式、schema 和长度。冷启动时服务器优先读取 Region 索引并用内存 LRU 缓存热点索引，避免每次都遍历目录。

## 默认配置

当前默认值偏向低带宽和低内存占用：

| 项目 | 默认值 |
| --- | ---: |
| 服务端 LOD 距离 | `128` chunks |
| 每玩家发送速度 | `500 Kbps` |
| 每玩家发送队列 | `512` columns / `8 MiB` |
| 已有 LOD 0-32 请求 | `0`，表示近距离不限速到单包上限 |
| 已有 LOD 33-64 请求 | `8` columns/tick |
| 已有 LOD 65-128 请求 | `4` columns/tick |
| 已有 LOD 129+ 请求 | `2` columns/tick |
| 缺失 LOD 生成请求 | `8` columns/s |
| 单玩家生成并发 | `4` |
| 全服生成并发 | `32` |
| 每 tick 启动生成 | `2` |
| 每 tick 提交打包 | `4` |
| 后台打包线程 | `2` |
| 后台打包队列 | `32` |
| 读盘线程 | `4` |
| 读盘超时 | `1000 ms` |
| 内存列缓存 | `4096` columns / `32 MiB` |
| 持久化列缓存 | `512 MiB` / `250000` columns |
| 持久化写队列 | `128` |
| 脏列广播间隔 | `10` ticks |
| 远处玩家同步 | enabled, `2` ticks |

旧的 MiB/s 风格带宽配置会在加载时迁移到 Kbps。界面显示低于 `1000` 时使用 Kbps，高于或等于 `1000` 时使用 Mbps。

## 配置文件

服务端：

```text
config/vss-server-config.json
```

客户端：

```text
config/vss-client-config.json
```

常用服务端配置：

| 配置项 | 作用 |
| --- | --- |
| `enabled` | VSS 服务端远景同步总开关。 |
| `lodDistanceChunks` | 服务端允许同步的最大 LOD 半径。 |
| `bytesPerSecondLimitPerPlayer` | 每玩家发送字节上限，命令和 UI 以 Kbps/Mbps 暴露。 |
| `sendQueueLimitPerPlayer` / `sendQueueBytesLimitPerPlayer` | 每玩家待发送 LOD 队列上限。 |
| `nearSyncRateLimitPerTick` / `midSyncRateLimitPerTick` / `farSyncRateLimitPerTick` / `distantSyncRateLimitPerTick` | 已有 LOD 请求分档限速。 |
| `diskReaderThreads` / `diskReadTimeoutMillis` | 持久化缓存和可选区块 NBT 读盘设置。 |
| `enableChunkGeneration` | 缺失 LOD 是否允许触发服务端区块生成。 |
| `generationRateLimitPerPlayer` / `generationConcurrencyLimitPerPlayer` / `generationConcurrencyLimitGlobal` | 缺失 LOD 生成速率和并发限制。 |
| `generationStartsPerTickLimit` / `generationCompletionsPerTickLimit` | 主线程生成启动和提交打包节奏。 |
| `generationPackingThreads` / `generationPackingQueueLimit` | 后台 LOD 打包线程和队列。 |
| `enableChunkNbtColumnSync` | 是否允许从已有区块 NBT 打包 LOD。 |
| `enableColumnCache` / `columnCacheMaxBytes` | 服务端热内存列缓存。 |
| `enablePersistentColumnCache` / `persistentColumnCacheMaxMiB` | 每世界 `.vcl` 持久化缓存。 |
| `enablePersistentColumnCompression` / `enableNetworkColumnCompression` | 持久化和网络 LOD 压缩开关。 |
| `dirtyBroadcastIntervalTicks` | 方块变化后通知客户端刷新 LOD 的间隔。 |
| `farPlayerSyncEnabled` / `farPlayerSyncIntervalTicks` | 远处玩家位置和载具同步。 |

常用客户端配置：

| 配置项 | 作用 |
| --- | --- |
| `receiveServerLods` | 是否接收 VSS LOD。 |
| `lodDistanceChunks` | 客户端请求距离。`0` 表示自动取服务端、Voxy 和客户端硬上限的较小值。 |
| `desiredBandwidthKbps` | 客户端期望下载限速。`0` 表示使用服务端限制。 |
| `offThreadSectionProcessing` | 在渲染线程外处理收到的 LOD，再交给 Voxy。 |

## 命令

```text
/vss stats
/vss bandwidth get
/vss bandwidth set_kbps <kbps>
/vss bandwidth set_mbps <mbps>
/vss bandwidth set_bytes <bytes_per_second>
/vss bandwidth set_mib <MiB_per_second>
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
/vss farplayers get
/vss farplayers enable
/vss farplayers disable
/vss farplayers set_interval <ticks>
/vss dirty get
/vss dirty set_interval <ticks>
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
```

中文别名也可用，常用入口包括 `/vss 状态`、`/vss 带宽`、`/vss 距离`、`/vss 队列`、`/vss 请求限速`、`/vss 存储`、`/vss 刷新`、`/vss 生成`、`/vss 远处玩家`。中文带宽设置默认按 Kbps 理解。

## NeoForge 说明

- 1.21.1 端口与 Forge 1.20.1 的 0.2.7 行为对齐，包括分档请求限速、Region Presence 摘要、Region 索引、压缩回退、生成打包和远处玩家/载具同步。
- 客户端 UI 集成面向当前 Voxy/Sodium 风格选项页；支持时可在 Voxy 选项页里调整单人/内置服务端 VSS 配置。
- 包含 client-only 类加载保护，避免独立服务器加载 `net.minecraft.client.Minecraft`。

## 排查

已有缓存 LOD 加载慢：

- 提高 `/vss bandwidth set_kbps` 或客户端 `desiredBandwidthKbps`。
- 提高 `nearSyncRateLimitPerTick`、`midSyncRateLimitPerTick`、`farSyncRateLimitPerTick`、`distantSyncRateLimitPerTick`。
- 如果瓶颈是持久化缓存读盘，逐步提高 `diskReaderThreads`。
- 观察 `/vss generation stats` 中的读盘、缓存命中和队列状态。

缺失 LOD 生成慢：

- 确认 `enableChunkGeneration=true`。
- 提高 `generationRateLimitPerPlayer`。
- 提高 `generationConcurrencyLimitPerPlayer` 或 `generationStartsPerTickLimit`。
- 提高 `generationPackingThreads` 或 `generationPackingQueueLimit` 时注意 CPU 和内存。

带宽周期性波动：

- 进服冷启动会先同步客户端已有 LOD 摘要和服务端已有 LOD，这是正常的。
- Region presence 增量摘要已做合并节流；真正的 LOD 列下发仍受每玩家带宽桶控制。
- 如果玩家很多，磁盘 Region 索引会共享 LRU 缓存，但每个玩家仍有独立视野窗口和发送队列。

任务管理器内存远高于 Java heap：

- Java heap 工具只看堆。
- 任务管理器还包含已提交堆余量、metaspace、code cache、GC 结构、Voxy/OpenGL/LWJGL native buffer、映射文件和驱动分配。
- 需要拆 native memory 时，可用 `-XX:NativeMemoryTracking=summary` 启动，再用 `jcmd <pid> VM.native_memory summary` 查看。

## Notes

- VSS `.vcl` 缓存按世界和维度隔离。
- Region 索引是加速查询用的派生数据；缺失或过期时会从 Region 目录重建。
- 一个玩家生成或缓存过的完整 LOD 可以被其他玩家复用。
- Northstar 火箭等远处实体/载具同步由 far player sync 负责。

<details>
<summary>English summary</summary>

Voxy Server Side NeoForge syncs Voxy LOD columns from the server to clients. It can serve hot memory cache, persistent `.vcl` cache, loaded chunk snapshots, optional chunk NBT, and optionally generated missing chunks.

Current status:

- Minecraft `1.21.1`, NeoForge `21.1.x`
- Mod version `0.2.7-neoforge-1.21.1`
- VSS protocol `34`

Important 0.2.7 behavior:

- Existing LOD requests are distance-bucketed: `0-32`, `33-64`, `65-128`, `129+`.
- Persistent cache stores per-column `.vcl` files plus a per-`32 x 32` Region `index.vci`.
- Region indexes are cached in memory with an LRU policy.
- Client Region Presence summaries tell the server what the client already has; incremental summaries are debounced.
- Sent columns are also marked known server-side after they leave the bandwidth-limited send bucket.
- Network compression prefers zstd, falls back to deflate, and may send raw data if compression is not useful.

</details>

## License

MIT. See `LICENSE`.

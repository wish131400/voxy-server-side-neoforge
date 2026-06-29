# Voxy Server Side NeoForge

NeoForge 1.21.1 的 Voxy 服务端远景同步 mod。服务端会把已有、缓存过或允许服务端生成的 LOD 列发送给安装了 Voxy 的客户端，让远景按玩家位置由近到远补齐，而不是完全依赖每个客户端自己本地生成。

Forge 1.20.1 版本见：[wish131400/voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)

<details>
<summary>English version</summary>

NeoForge 1.21.1 server-side LOD sync mod for Voxy. The server sends existing, cached, or server-generated LOD columns to clients with Voxy installed, so distant terrain can fill from the player outward instead of relying on each client to generate it locally.

Forge 1.20.1 version: [wish131400/voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)

## Status

- Minecraft: `1.21.1`
- Loader: NeoForge `21.1.x`
- Current mod version: `0.2.5-neoforge-1.21.1`
- VSS protocol: `30`
- Client renderer target: Voxy with Sodium-compatible rendering

Client and server VSS protocol versions must match. If they do not match, the player can still join the server, but VSS LOD sync is disabled for that session.

## Install

Server:

- NeoForge `21.1.x`
- This mod
- Recommended: ZstdNet or another bundled provider of `com.github.luben.zstd.Zstd`

Client:

- NeoForge `21.1.x`
- This mod
- Voxy
- Sodium-compatible renderer stack
- Recommended: ZstdNet

Zstd capability is negotiated by VSS during handshake. This is mostly a clear error path for missing or mismatched packs, because normal modpack usage should install the same zstd support on both sides.

## Sync Model

The client requests columns in a Chebyshev ring frontier around the player:

```text
ring = max(abs(dx), abs(dz))
```

That keeps scan order stable, cheap, and visually centered. Already requested or in-flight columns are deduplicated, and low-priority old work can be dropped when it is no longer relevant to the player's current position.

Server-side lookup order:

```text
memory cache -> persistent .vcl cache -> loaded chunk snapshot -> optional chunk NBT -> optional chunk generation
```

Changed columns inside already visited areas are refreshed with priority. Complete-column validation prevents partial LOD data from permanently overwriting missing client sections.

If `enableChunkGeneration=false`, VSS still serves columns that already exist in memory cache, persistent `.vcl` cache, loaded chunk snapshots, or optional chunk NBT. It just stops creating missing world chunks for new LOD.

## Persistence And Compression

Complete LOD columns are persisted per world:

```text
<world>/data/vss-column-cache/<dimension>/<region>/<chunkX>_<chunkZ>.vcl
```

For example:

```text
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/12_8.vcl
```

Each `.vcl` file has a small VSS header, followed by a zstd frame body. The same encoded zstd body is reused for:

- disk persistence
- server memory column cache
- normal network payloads

This avoids keeping raw section bytes in the hot cache and avoids recompressing hot LOD columns before sending. The client still decompresses received columns before handing them to Voxy.

## Default Config

Current defaults are tuned for low bandwidth and moderate memory use:

| Item | Default |
| --- | ---: |
| Server LOD distance | `128` chunks |
| Per-player bandwidth | `500 Kbps` |
| Per-player send queue | `256` columns / `4 MiB` |
| Existing LOD request rate | `16` columns/s |
| Existing LOD request concurrency | `16` |
| Missing LOD generation rate | `8` columns/s |
| Per-player generation concurrency | `4` |
| Global generation concurrency | `32` |
| Generation starts per tick | `2` |
| Generation completions per tick | `4` |
| Disk reader threads | `4` |
| Disk read timeout | `1500 ms` |
| Memory column cache | `4096` columns / `32 MiB` |
| Persistent column cache | `512 MiB` / `250000` columns |
| Persistent/network column compression | zstd when available |
| Dirty refresh interval | `10` ticks |
| Far player sync | enabled, `10` ticks |

Old MiB/s-style bandwidth config is migrated to Kbps on load. Display text uses Kbps below `1000`, and Mbps at or above `1000`.

## Config Files

Server:

```text
config/vss-server-config.json
```

Client:

```text
config/vss-client-config.json
```

Important server options:

| Key | Meaning |
| --- | --- |
| `enabled` | Master switch for VSS server sync. |
| `lodDistanceChunks` | Maximum server-side LOD sync radius. |
| `bytesPerSecondLimitPerPlayer` | Effective per-player byte limit. Commands and UI expose this as Kbps/Mbps. |
| `sendQueueLimitPerPlayer` / `sendQueueBytesLimitPerPlayer` | Per-player queued payload limits. |
| `diskReaderThreads` | Parallel workers for persistent cache and optional chunk NBT reads. |
| `syncOnLoadRateLimitPerPlayer` / `syncOnLoadConcurrencyLimitPerPlayer` | Rate and concurrency for already existing LOD. |
| `enableChunkGeneration` | Whether missing LOD may trigger server chunk/world generation. |
| `generationRateLimitPerPlayer` / `generationConcurrencyLimitPerPlayer` / `generationConcurrencyLimitGlobal` | Missing-column generation throttles. |
| `generationStartsPerTickLimit` / `generationCompletionsPerTickLimit` | Main-thread generation pacing. |
| `enableChunkNbtColumnSync` | Whether VSS may pack LOD from existing chunk NBT. |
| `enableColumnCache` / `columnCacheMaxBytes` | Hot in-memory encoded column cache. |
| `enablePersistentColumnCache` / `persistentColumnCacheMaxMiB` | Per-world `.vcl` cache. |
| `enablePersistentColumnCompression` / `enableNetworkColumnCompression` | Compression toggles for persisted and network LOD. |
| `dirtyBroadcastIntervalTicks` | How often changed columns are announced for refresh. |
| `farPlayerSyncEnabled` / `farPlayerSyncIntervalTicks` | Far player position/vehicle sync. |

Important client options:

| Key | Meaning |
| --- | --- |
| `receiveServerLods` | Whether the client receives VSS LOD. |
| `lodDistanceChunks` | Client request distance. `0` means automatic: min(server, Voxy, client hard cap). |
| `desiredBandwidthKbps` | Client-requested bandwidth cap. `0` means use the server limit. |
| `offThreadSectionProcessing` | Process received LOD off the render thread before Voxy ingest. |

## Commands

```text
/vss stats
/vss bandwidth get
/vss bandwidth set_kbps <kbps>
/vss bandwidth set_mbps <mbps>
/vss bandwidth set_bytes <bytes_per_second>
/vss bandwidth set_mib <MiB_per_second>
/vss distance get
/vss distance set <chunks>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <MiB>
/vss storage get
/vss storage set_readers <threads>
/vss dirty get
/vss dirty set_interval <ticks>
/vss generation get
/vss generation enable
/vss generation disable
/vss generation stats
/vss far_players get
/vss far_players enable
/vss far_players disable
```

Chinese aliases are also registered. The Chinese bandwidth setter uses Kbps by default, matching player-facing network settings.

## Integrated Server And LAN

Singleplayer and LAN hosts use the same server-side VSS logic as a dedicated server. The local host routes VSS requests directly to the integrated server player, while LAN visitors use normal networking.

The local host still pays for both halves in one JVM: Minecraft client, integrated server, VSS server logic, Voxy render data, OpenGL/LWJGL native memory, and modpack caches.

## NeoForge Notes

- The 1.21.1 port is aligned with the Forge 1.20.1 0.2.5 behavior for request ordering, bandwidth commands, zstd column reuse, persistent cache, config refresh, and integrated-host routing.
- Client UI integration targets the current Voxy/Sodium-style options path and keeps server-side VSS controls available from the Voxy options screen when supported.
- The port includes guards for client-only classes so dedicated servers do not load `net.minecraft.client.Minecraft`.

## Troubleshooting

Existing cached LOD loads too slowly:

- Increase `/vss bandwidth set_kbps`.
- Increase `syncOnLoadRateLimitPerPlayer`.
- Increase `syncOnLoadConcurrencyLimitPerPlayer`.
- Increase `diskReaderThreads` if persistent cache reads are the bottleneck.

Missing LOD generates too slowly:

- Confirm `enableChunkGeneration=true`.
- Increase `generationRateLimitPerPlayer`.
- Increase `generationConcurrencyLimitPerPlayer` or `generationStartsPerTickLimit`.
- Watch `/vss generation stats`.

Changing distance or generation settings seems delayed:

- Config commands refresh the active VSS session and request state.
- Persistent-cache cold scans are disk-bound; increase `diskReaderThreads` gradually if the disk can keep up.
- The client does not need to restart, but Voxy may still need time to rebuild render data.

Task Manager memory is much higher than Java heap:

- Java heap tools show only heap.
- Task Manager also includes committed heap headroom, metaspace, code cache, GC structures, Voxy/OpenGL/LWJGL native buffers, mapped files, and driver allocations.
- Start with `-XX:NativeMemoryTracking=summary` and inspect with `jcmd <pid> VM.native_memory summary` when native memory needs to be separated.

## Notes

- VSS `.vcl` cache is per world and per dimension.
- A `512 MiB` persistent cache currently stores roughly a `160` chunk radius in average tested terrain, but modded terrain can vary.
- VSS serves already persisted LOD to other players once one player has generated or cached it.

## License

MIT. See `LICENSE`.

</details>

## 状态

- Minecraft: `1.21.1`
- 加载器: NeoForge `21.1.x`
- 当前版本: `0.2.5-neoforge-1.21.1`
- VSS 协议: `30`
- 客户端渲染目标: Voxy + Sodium 兼容渲染环境

客户端和服务端的 VSS 协议版本必须一致。版本不一致时，玩家仍然可以进服，但本次会话不会启用 VSS 远景同步。

## 安装

服务端:

- NeoForge `21.1.x`
- 本 mod
- 推荐: ZstdNet，或其他内置 `com.github.luben.zstd.Zstd` 的 zstd 库

客户端:

- NeoForge `21.1.x`
- 本 mod
- Voxy
- Sodium 兼容渲染环境
- 推荐: ZstdNet

VSS 会在握手时检查 zstd capability。正常整合包里双端应该一起安装 zstd 支持，这个检查主要用于在少装、错装或版本不一致时给出更清楚的错误路径。

## 同步模型

客户端按以玩家为中心的切比雪夫环形前沿请求 LOD:

```text
ring = max(abs(dx), abs(dz))
```

这样扫描顺序稳定、计算便宜，而且视觉上会从玩家中心向外扩散。已经请求或正在加载的列会去重；当玩家快速移动时，旧位置不再相关的低优先级请求可以被丢弃。

服务端查找顺序:

```text
内存缓存 -> 持久化 .vcl 缓存 -> 已加载区块快照 -> 可选区块 NBT -> 可选区块生成
```

已经访问过且检测到变化的列会优先刷新。完整列校验会阻止不完整 LOD 长期覆盖客户端缺失 section，减少远景裂缝、黑洞或假空洞残留。

如果 `enableChunkGeneration=false`，VSS 仍会同步已经存在于内存缓存、持久化 `.vcl`、已加载区块快照或可选区块 NBT 里的 LOD，只是不再为了缺失 LOD 主动生成新区块。

## 持久化与压缩

完整 LOD 列按每个存档、每个维度单独持久化:

```text
<world>/data/vss-column-cache/<dimension>/<region>/<chunkX>_<chunkZ>.vcl
```

示例:

```text
saves/New World/data/vss-column-cache/minecraft_overworld/0_0/12_8.vcl
```

每个 `.vcl` 文件包含一个很小的 VSS 文件头，后面直接跟一段 zstd frame body。同一份 zstd 编码后的 body 会复用于:

- 磁盘持久化
- 服务端内存 LOD 缓存
- 常规网络 payload

这样热缓存不需要保存 raw section bytes，发包时也不需要再次压缩。客户端收到后仍会解压，再交给 Voxy ingest。

## 默认配置

当前默认值偏向低带宽和中等内存占用。

| 项目 | 默认值 |
| --- | ---: |
| 服务端 LOD 距离 | `128` chunks |
| 每玩家带宽 | `500 Kbps` |
| 每玩家发送队列 | `256` columns / `4 MiB` |
| 已有 LOD 请求速率 | `16` columns/s |
| 已有 LOD 请求并发 | `16` |
| 缺失 LOD 生成速率 | `8` columns/s |
| 单玩家生成并发 | `4` |
| 全服生成并发 | `32` |
| 每 tick 启动生成 | `2` |
| 每 tick 完成打包 | `4` |
| 读盘线程 | `4` |
| 读盘超时 | `1500 ms` |
| 内存列缓存 | `4096` columns / `32 MiB` |
| 持久化列缓存 | `512 MiB` / `250000` columns |
| 持久化/网络压缩 | zstd 可用时启用 |
| 脏列刷新间隔 | `10` ticks |
| 远处玩家同步 | 启用，`10` ticks |

旧的 MiB/s 风格带宽配置会在加载时迁移为 Kbps。显示时低于 `1000` 显示为 Kbps，高于或等于 `1000` 自动显示为 Mbps。

## 配置文件

服务端:

```text
config/vss-server-config.json
```

客户端:

```text
config/vss-client-config.json
```

常用服务端配置:

| 配置项 | 作用 |
| --- | --- |
| `enabled` | VSS 服务端同步总开关。 |
| `lodDistanceChunks` | 服务端允许同步的最大 LOD 半径。 |
| `bytesPerSecondLimitPerPlayer` | 实际每玩家字节限速；指令和 UI 按 Kbps/Mbps 暴露。 |
| `sendQueueLimitPerPlayer` / `sendQueueBytesLimitPerPlayer` | 每玩家待发送 payload 队列上限。 |
| `diskReaderThreads` | 持久化缓存和可选区块 NBT 读盘并发。 |
| `syncOnLoadRateLimitPerPlayer` / `syncOnLoadConcurrencyLimitPerPlayer` | 已存在 LOD 的请求速率和并发。 |
| `enableChunkGeneration` | 缺失 LOD 是否允许触发服务端区块/世界生成。 |
| `generationRateLimitPerPlayer` / `generationConcurrencyLimitPerPlayer` / `generationConcurrencyLimitGlobal` | 缺失列生成限速与并发。 |
| `generationStartsPerTickLimit` / `generationCompletionsPerTickLimit` | 主线程生成节奏控制。 |
| `enableChunkNbtColumnSync` | 是否允许从已有区块 NBT 打包 LOD。 |
| `enableColumnCache` / `columnCacheMaxBytes` | 热内存 encoded column 缓存。 |
| `enablePersistentColumnCache` / `persistentColumnCacheMaxMiB` | 每世界 `.vcl` 持久化缓存。 |
| `enablePersistentColumnCompression` / `enableNetworkColumnCompression` | 持久化和网络 LOD 压缩开关。 |
| `dirtyBroadcastIntervalTicks` | 区块变化后通知客户端刷新的间隔。 |
| `farPlayerSyncEnabled` / `farPlayerSyncIntervalTicks` | 远处玩家位置/载具同步。 |

常用客户端配置:

| 配置项 | 作用 |
| --- | --- |
| `receiveServerLods` | 是否接收 VSS LOD。 |
| `lodDistanceChunks` | 客户端请求距离；`0` 表示自动取 server、Voxy、客户端硬上限的较小值。 |
| `desiredBandwidthKbps` | 客户端期望带宽；`0` 表示使用服务端限制。 |
| `offThreadSectionProcessing` | 在渲染线程外处理收到的 LOD，再交给 Voxy。 |

## 指令

```text
/vss stats
/vss 状态
/vss bandwidth get
/vss bandwidth set_kbps <kbps>
/vss bandwidth set_mbps <mbps>
/vss bandwidth set_bytes <bytes_per_second>
/vss bandwidth set_mib <MiB_per_second>
/vss 带宽 查看
/vss 带宽 设置 <kbps>
/vss distance get
/vss distance set <chunks>
/vss 距离 查看
/vss 距离 设置 <chunks>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <MiB>
/vss storage get
/vss storage set_readers <threads>
/vss dirty get
/vss dirty set_interval <ticks>
/vss generation get
/vss generation enable
/vss generation disable
/vss generation stats
/vss far_players get
/vss far_players enable
/vss far_players disable
```

中文别名也可用。中文带宽设置默认按 Kbps 理解，更接近玩家常见网络单位。

## 单人和局域网

单人和局域网房主使用和独立服务器一致的 VSS 服务端逻辑。房主本机客户端会把 VSS 请求直接路由给集成服务器里的本地服务端玩家，局域网访客则走正常网络路径。

房主仍然需要在同一个 JVM 里同时承担客户端、集成服务器、VSS 服务端逻辑、Voxy 渲染数据、OpenGL/LWJGL native memory 和整合包缓存。

## NeoForge 说明

- 1.21.1 移植版已对齐 Forge 1.20.1 的 0.2.5 行为，包括请求顺序、带宽指令、zstd 列数据复用、持久化缓存、配置刷新和集成服房主路由。
- 客户端 UI 集成面向当前 Voxy/Sodium 风格的配置页；在支持时，Voxy 选项页里可以继续调整 VSS 服务端配置。
- 已加入 client-only 类加载保护，避免独立服务器加载 `net.minecraft.client.Minecraft`。

## 排查

已有缓存 LOD 加载慢:

- 提高 `/vss bandwidth set_kbps`。
- 提高 `syncOnLoadRateLimitPerPlayer`。
- 提高 `syncOnLoadConcurrencyLimitPerPlayer`。
- 如果瓶颈是持久化缓存读盘，逐步提高 `diskReaderThreads`。

缺失 LOD 生成慢:

- 确认 `enableChunkGeneration=true`。
- 提高 `generationRateLimitPerPlayer`。
- 提高 `generationConcurrencyLimitPerPlayer` 或 `generationStartsPerTickLimit`。
- 观察 `/vss generation stats`。

调整距离或生成设置后像是有延迟:

- 配置指令会刷新当前 VSS 会话和请求状态。
- 持久化缓存冷启动扫描受磁盘限制；如果磁盘能扛住，可以逐步提高 `diskReaderThreads`。
- 客户端不需要重启，但 Voxy 仍可能需要时间重建渲染数据。

任务管理器内存远高于 Java heap:

- Java heap 工具只看堆。
- 任务管理器还包含已提交堆余量、metaspace、code cache、GC 结构、Voxy/OpenGL/LWJGL native buffer、映射文件和驱动分配。
- 需要拆 native memory 时，启动参数加 `-XX:NativeMemoryTracking=summary`，再用 `jcmd <pid> VM.native_memory summary` 查看。

## 备注

- VSS `.vcl` 缓存按每个世界、每个维度分开。
- 按当前测试地形平均值，`512 MiB` 持久化缓存大约能覆盖 `160` 区块半径；mod 地形会有波动。
- 只要一个玩家生成或缓存过某区域，其他玩家也可以复用这些已持久化 LOD。

## 许可证

MIT。详见 `LICENSE`。

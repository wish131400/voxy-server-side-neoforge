# Voxy Server Side Forge

这是 Voxy Server Side 的 Forge 1.20.1 版本。它会把服务端已有或允许生成的 LOD 数据同步给装了 Voxy 的客户端，让玩家不用亲自跑完整张地图，也能逐渐看到服务端远景。

它适合大型整合包、多人服务器和需要统一远景体验的存档。玩家本地仍然负责渲染远景，服务器负责提供远景数据、限速、自动补远景和刷新通知。

## 谁需要安装

服务器需要：

- Forge `47.x`
- 本 mod

客户端需要：

- Forge `47.x`
- 本 mod
- Voxy
- Embeddium/Sodium 兼容环境
- 如果使用 Fabric 版 Voxy：还需要信雅互联、Fabric API 等 Voxy 所需依赖

服务器端不需要安装 Voxy、Fabric API 或信雅互联。客户端如果是通过信雅互联运行 Fabric 版 Voxy，就按客户端自己的 Voxy 环境装齐依赖。

## 主要功能

- 向客户端同步服务端上的 Voxy LOD 数据。
- 玩家附近缺少 LOD 数据时，可以让服务器自动生成对应区块。
- 可以限制每名玩家的同步带宽，避免单个玩家把上传带宽吃满。
- 可以限制全服和单个玩家同时生成多少 LOD，避免生成压力失控。
- 方块放置、破坏、爆炸等服务端区块变动后，会通知客户端刷新对应 LOD。
- 可以显示超出原版实体追踪距离的远处玩家和玩家名。
- 支持单人和开放局域网房主的集成服同步。
- 提供中文配置、中文指令，以及 Voxy/Embeddium 选项界面集成。

## 默认行为

默认配置偏向“能用且不会太激进”：

- 服务端 LOD 同步上限：`256` 区块
- 客户端 LOD 范围：默认自动，取服务端上限、Voxy 设置和客户端上限里的较小值
- 每名玩家带宽上限：`3 MiB/s`
- 单个玩家同时请求生成：`8`
- 全服同时生成：`128`
- 缺失 LOD：允许服务器生成
- 区块变动刷新通知：每 `5` tick 广播一次
- 远处玩家显示：开启，每 `5` tick 同步一次

如果服务器 CPU 压力大，优先降低生成相关配置。如果玩家远景断档多，优先提高生成速度、队列或带宽。

## 安装

把构建出的 jar 放进服务器和客户端的 `mods` 文件夹。

客户端协议版本必须和服务器匹配。版本不匹配时，玩家仍然能进服，但不会使用 VSS 的远景同步功能。

## 推荐搭配

推荐搭配 [ZSTDNET](https://www.curseforge.com/minecraft/mc-mods/zstdnet) 使用。它是客户端和服务器都需要安装的带宽压缩 mod，会用 ZSTD 压缩客户端和服务器之间的网络流量。VSS 同步 LOD 时会产生比较连续、重复度较高的数据包，配合 ZSTDNET 通常可以降低一部分公网带宽压力，尤其适合带宽紧张、内网穿透、转发服或玩家较多的整合包服务器。

ZSTDNET 不是 VSS 的硬依赖；不安装也能正常同步远景。它也不会替代 VSS 自带的 `bytesPerSecondLimitPerPlayer` 限速，建议两者一起用：ZSTDNET 负责压缩，VSS 负责控制每名玩家的带宽峰值。

## 配置文件

服务器配置文件会生成在：

```text
config/vss-server-config.json
```

客户端配置文件会生成在：

```text
config/vss-client-config.json
```

服务端配置既可以改文件，也可以用 `/vss` 指令动态调整。客户端配置可以在 Voxy/Embeddium 选项页里的 Voxy Server Side 页面调整。

## 常用服务端配置

| 配置项 | 默认值 | 怎么理解 |
| --- | ---: | --- |
| `enabled` | `true` | VSS 总开关。关闭后不会给玩家同步 LOD。 |
| `lodDistanceChunks` | `256` | 服务端允许同步多远的 LOD，单位是区块。可配置范围是 `1` 到 `8196`。 |
| `bytesPerSecondLimitPerPlayer` | `3145728` | 每名玩家最多占用多少服务器上传带宽。默认约 3 MiB/s。 |
| `sendQueueLimitPerPlayer` | `1000` | 每名玩家最多排队多少个待发送 LOD 列。 |
| `sendQueueBytesLimitPerPlayer` | `33554432` | 每名玩家最多缓存多少待发送 LOD 数据。太低容易断档，太高会吃内存。 |
| `syncOnLoadRateLimitPerPlayer` | `120` | 每名玩家每秒最多请求多少个已有 LOD 列。 |
| `syncOnLoadConcurrencyLimitPerPlayer` | `24` | 每名玩家最多同时有多少个普通同步请求在处理中。 |
| `enableChunkGeneration` | `true` | 缺少 LOD 数据时，是否允许服务器生成区块来补 LOD。关闭后只同步已经存在的数据。 |
| `generationRateLimitPerPlayer` | `40` | 每名玩家每秒最多请求多少个缺失区块生成。 |
| `generationConcurrencyLimitPerPlayer` | `8` | 单个玩家最多同时推动多少个缺失区块生成。调高更快，也更吃性能。 |
| `generationConcurrencyLimitGlobal` | `128` | 全服最多同时生成多少个缺失区块。服务器吃不消就先降这个。 |
| `generationStartsPerTickLimit` | `4` | 每 tick 最多开始多少个生成任务。调低可以减少瞬间压力。 |
| `generationCompletionsPerTickLimit` | `8` | 每 tick 最多提交多少个已生成区块给后台打包。调低可以减少主线程尖峰。 |
| `generationPackingThreads` | `2` | 用几个后台线程把区块转成 LOD 数据。调高会更快，但会占 CPU。 |
| `generationPackingQueueLimit` | `64` | 后台打包队列长度。太低容易来不及处理，太高会多占内存。 |
| `generationTimeoutSeconds` | `60` | 等一个缺失区块生成多久。超过时间就放弃这次请求，避免一直卡着。 |
| `dirtyBroadcastIntervalTicks` | `5` | 区块变化后，每隔多少 tick 通知客户端刷新 LOD。 |
| `dirtyVersionCacheEnabled` | `true` | 是否记录已经发生变化的 LOD 列，方便玩家之后回来时重新请求。 |
| `dirtyVersionCacheMaxEntries` | `200000` | 最多记住多少个发生过变化的 LOD 列。越大越吃内存。 |
| `dirtyVersionCacheRetentionSeconds` | `86400` | LOD 变化记录保留多久，默认 24 小时。 |
| `farPlayerSyncEnabled` | `true` | 是否显示原版追踪距离外的远处玩家。 |
| `farPlayerSyncIntervalTicks` | `5` | 远处玩家同步间隔。越低越顺滑，也越占一点网络和客户端渲染。 |
| `enableColumnCache` | `true` | 是否缓存最近打包好的 LOD 列，降低重复请求开销。 |
| `columnCacheMaxEntries` | `8192` | LOD 列缓存最多保留多少项。 |
| `columnCacheMaxBytes` | `67108864` | LOD 列缓存最多占用多少内存。默认 64 MiB。 |
| `ftbChunksSafeForceLoad` | `true` | 对 FTB Chunks 强加载恢复做安全兼容，减少同步读区块导致的卡死风险。 |
| `ftbChunksForceLoadTicketsPerTick` | `4` | 每 tick 恢复多少个 FTB Chunks 强加载票据。 |

“LOD 变化记录”不是发送队列。它只记住哪些 LOD 列变过，方便玩家之后回来时重新请求新数据，不会一直替离线玩家攒数据包。

## 常用客户端配置

| 配置项 | 默认值 | 怎么理解 |
| --- | ---: | --- |
| `receiveServerLods` | `true` | 是否接收服务端 LOD。 |
| `lodDistanceChunks` | `0` | 客户端自己请求多远的服务端 LOD。`0` 表示自动。客户端可配置上限是 `512`。 |
| `desiredBandwidthMiB` | `0` | 告诉服务器自己希望的下载限速。`0` 表示使用服务端上限。 |
| `offThreadSectionProcessing` | `true` | 在后台线程处理收到的 LOD 数据，减少客户端主线程卡顿。 |

如果客户端选择自动距离，实际距离会取服务端 `lodDistanceChunks`、Voxy 自己的远景距离和客户端上限 `512` 里的较小值。服务端把上限设到 `8196` 不会让客户端自动请求超过自己的 Voxy/客户端限制。

## 常见调整

远景加载慢：

- 提高 `bytesPerSecondLimitPerPlayer`
- 提高 `syncOnLoadRateLimitPerPlayer`
- 提高 `generationConcurrencyLimitGlobal`
- 提高 `generationPackingThreads`

远景有断档：

- 提高 `sendQueueLimitPerPlayer`
- 提高 `sendQueueBytesLimitPerPlayer`
- 提高 `generationPackingQueueLimit`
- 确认 `enableChunkGeneration=true`

区块变动后刷新慢：

- 降低 `dirtyBroadcastIntervalTicks`
- 提高 `bytesPerSecondLimitPerPlayer`
- 观察 `/vss 刷新 查看` 和 `/vss stats`

服务器卡顿或 CPU 压力大：

- 降低 `generationConcurrencyLimitGlobal`
- 降低 `generationStartsPerTickLimit`
- 降低 `generationPackingThreads`
- 降低 `ftbChunksForceLoadTicketsPerTick`

上传带宽占用太高：

- 服务器和客户端都安装 [ZSTDNET](https://www.curseforge.com/minecraft/mc-mods/zstdnet)
- 降低 `bytesPerSecondLimitPerPlayer`
- 降低 `lodDistanceChunks`

远处玩家看起来不够顺滑：

- 降低 `farPlayerSyncIntervalTicks`
- 如果玩家很多，可以关闭 `farPlayerSyncEnabled`

## 常用指令

所有指令需要 2 级权限。

查看状态：

```text
/vss 状态
/vss stats
```

设置每名玩家的 LOD 同步带宽：

```text
/vss 带宽 查看
/vss 带宽 设置 <每秒字节>
/vss 带宽 设置MiB <每秒MiB>
/vss bandwidth get
/vss bandwidth set <bytes_per_second>
/vss bandwidth set_mib <mib_per_second>
```

设置服务端允许同步的最远距离：

```text
/vss 距离 查看
/vss 距离 设置 <区块>
/vss distance get
/vss distance set <chunks>
```

设置发送缓存：

```text
/vss 队列 查看
/vss 队列 设置数量 <列数>
/vss 队列 设置MiB <MiB>
/vss queue get
/vss queue set_count <columns>
/vss queue set_mib <mib>
```

设置区块变动后的 LOD 刷新通知：

```text
/vss 刷新 查看
/vss 刷新 设置间隔 <tick>
/vss dirty get
/vss dirty set_interval <ticks>
```

开关缺失 LOD 自动生成：

```text
/vss 生成 查看
/vss 生成 状态
/vss 生成 开启
/vss 生成 关闭
/vss generation get
/vss generation stats
/vss generation enable
/vss generation disable
```

调整生成性能：

```text
/vss 生成 设置每玩家 <数量>
/vss 生成 设置全服 <数量>
/vss 生成 设置每tick启动 <数量>
/vss 生成 设置每tick打包 <数量>
/vss 生成 设置打包线程 <线程数>
/vss 生成 设置打包队列 <数量>
/vss 生成 设置超时 <秒>
/vss generation set_player_concurrency <limit>
/vss generation set_global_concurrency <limit>
/vss generation set_starts_per_tick <limit>
/vss generation set_completions_per_tick <limit>
/vss generation set_packing_threads <threads>
/vss generation set_packing_queue <limit>
/vss generation set_timeout <seconds>
```

远处玩家：

```text
/vss 远处玩家 查看
/vss 远处玩家 开启
/vss 远处玩家 关闭
/vss 远处玩家 设置间隔 <tick>
/vss farplayers get
/vss farplayers enable
/vss farplayers disable
/vss farplayers set_interval <ticks>
```

## 许可证

MIT。详见 `LICENSE`。

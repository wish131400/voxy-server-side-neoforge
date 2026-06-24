# Voxy Server Side Forge

Forge 1.20.1 服务端远景同步 mod。服务端把已有、缓存过或允许生成的 LOD 数据发给安装了 Voxy 的客户端，让远景按玩家位置从近到远补齐。

## 安装

服务器：

- Forge `47.x`
- 本 mod
- 可选：ZstdNet 或其他提供 `com.github.luben.zstd.Zstd` 的 zstd 库

客户端：

- Forge `47.x`
- 本 mod
- Voxy
- Embeddium/Sodium 兼容环境
- 可选：ZstdNet

客户端和服务端的 VSS 协议版本必须一致，否则会进服但不启用 VSS 远景同步。

## 同步逻辑

客户端在同步范围内按玩家为中心从近到远请求 LOD。服务端处理顺序：

```text
内存缓存 -> 持久化磁盘缓存 -> 已加载区块 -> 可选原版 NBT -> 可选区块生成
```

已经加载过且被标记为变动的 LOD 会优先刷新。完整列校验会阻止不完整 LOD 覆盖客户端缺失 section，避免远景裂谷、黑洞、假空洞长期残留。

缺失 LOD 生成会触发服务端区块加载/世界生成；已有 VSS 缓存命中不会触发生成。

## 默认值

当前默认值偏向稳内存：

| 项目 | 默认值 |
| --- | ---: |
| 服务端同步距离 | `128` 区块 |
| 每玩家带宽 | `2 MiB/s` |
| 每玩家发送队列 | `500` 列 / `16 MiB` |
| 已有 LOD 请求 | `80` 列/秒，`16` 并发 |
| 缺失生成请求 | `20` 列/秒 |
| 单玩家生成并发 | `4` |
| 全服生成并发 | `32` |
| 每 tick 启动生成 | `2` |
| 每 tick 提交打包 | `4` |
| 变动刷新广播 | `10` tick |
| 远处玩家同步 | 开启，`10` tick |
| 内存 LOD 缓存 | `4096` 列 / `32 MiB` |
| 持久化 LOD 缓存 | 开启，`512 MiB` / `250000` 列 |
| LOD 压缩 | 磁盘和网络开启，zstd 可用时等级 `7` |

## 配置

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
| `lodDistanceChunks` | 服务端允许同步的最大 LOD 距离。 |
| `bytesPerSecondLimitPerPlayer` | 每名玩家的 VSS 上传限速。 |
| `sendQueueLimitPerPlayer` / `sendQueueBytesLimitPerPlayer` | 每名玩家待发送 LOD 队列上限。 |
| `syncOnLoadRateLimitPerPlayer` / `syncOnLoadConcurrencyLimitPerPlayer` | 已有 LOD 的请求速率和并发。 |
| `enableChunkGeneration` | 缺失 LOD 时是否允许服务端生成区块。 |
| `generationConcurrencyLimitPerPlayer` / `generationConcurrencyLimitGlobal` | 缺失区块生成并发。 |
| `generationStartsPerTickLimit` / `generationCompletionsPerTickLimit` | 每 tick 生成启动和打包提交上限。 |
| `enableChunkNbtColumnSync` | 是否尝试从原版区块 NBT 打包已有区块。默认关闭。 |
| `enablePersistentColumnCache` | 是否把完整 LOD 持久化到服务器磁盘。 |
| `persistentColumnCacheMaxMiB` / `persistentColumnCacheMaxEntries` | 持久化缓存清理上限。 |
| `enablePersistentColumnCompression` / `enableNetworkColumnCompression` | VSS 自带磁盘/网络 LOD 压缩。 |
| `dirtyBroadcastIntervalTicks` | 区块变化后通知客户端刷新 LOD 的间隔。 |
| `farPlayerSyncEnabled` / `farPlayerSyncIntervalTicks` | 远处玩家显示和同步间隔。 |

常用客户端配置：

| 配置项 | 作用 |
| --- | --- |
| `receiveServerLods` | 是否接收服务端 LOD。 |
| `lodDistanceChunks` | 客户端请求距离。`0` 表示自动取服务端、Voxy、客户端上限的较小值。 |
| `desiredBandwidthMiB` | 客户端希望的下载限速。`0` 表示使用服务端上限。 |
| `offThreadSectionProcessing` | 后台处理收到的 LOD 数据，减少客户端主线程压力。 |

## 持久化和压缩

完整 LOD 列会写入：

```text
<world>/data/vss-column-cache/
```

持久化缓存只保存完整列，旧格式或不完整数据会被忽略或删除。zstd 可用时，VSS 的磁盘和网络 LOD 压缩使用等级 `7`；不可用时会回退到 deflate 或原始数据。

ZstdNet 可以继续压缩整体网络流量，但如果 VSS payload 已经压缩，ZstdNet HUD 的压缩率变化不一定等于实际节省比例。

## 常用指令

```text
/vss 状态
/vss stats
/vss 带宽 查看
/vss 带宽 设置MiB <MiB/s>
/vss 距离 查看
/vss 距离 设置 <区块>
/vss 队列 查看
/vss 队列 设置数量 <列数>
/vss 队列 设置MiB <MiB>
/vss 刷新 查看
/vss 刷新 设置间隔 <tick>
/vss 生成 查看
/vss 生成 状态
/vss 生成 开启
/vss 生成 关闭
/vss generation stats
/vss 远处玩家 查看
/vss 远处玩家 开启
/vss 远处玩家 关闭
```

英文别名也可用，例如 `/vss bandwidth get`、`/vss distance set <chunks>`、`/vss generation stats`。

## 排查

远景已有数据加载慢：

- 提高 `bytesPerSecondLimitPerPlayer`
- 提高 `syncOnLoadRateLimitPerPlayer`
- 提高 `syncOnLoadConcurrencyLimitPerPlayer`

缺失远景补得慢：

- 确认 `enableChunkGeneration=true`
- 提高生成并发或 `generationStartsPerTickLimit`
- 观察 `/vss generation stats`

服务端内存/CPU 高：

- 降低 `lodDistanceChunks`
- 降低生成并发
- 降低或关闭 `enableChunkGeneration`

客户端任务管理器内存比 sparkc 高很多：

- sparkc 主要看 Java heap
- 任务管理器还包含 JVM 已提交堆、Voxy/OpenGL/LWJGL 显存和 native buffer
- 需要 native 分类时启动参数加 `-XX:NativeMemoryTracking=summary`

Voxy 关闭再打开后：

- VSS 会触发重新同步，日志应出现 `VSS LOD resync requested: Voxy became available again`
- Voxy 自己仍需要重建 render system 和 mesh，不会瞬间全部出现

## 许可证

MIT。详见 `LICENSE`。

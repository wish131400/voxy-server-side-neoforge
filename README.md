# Voxy Server Side Forge

这是 Voxy Server Side 的 Forge 1.20.1 版本。它的作用很简单：让服务器把远景地形数据发给装了 Voxy 的客户端，玩家不用亲自跑遍地图，也能逐渐看到服务器已经保存或允许生成的远景。

它适合大型整合包、多人服务器和需要统一远景体验的存档。玩家本地仍然负责渲染远景，服务器负责提供这些远景数据。

## 谁需要安装

服务器需要：

- Forge `47.x`
- 本 mod

客户端需要：

- Forge `47.x`
- 本 mod
- Voxy及其fabric API 和 Embeddium（voxy的版本为m3t4f1v3的1.20.1 fabric 环境）
- 信雅互联

如果客户端是通过信雅互联运行 Fabric 版 Voxy，那么客户端还需要自己的 Voxy、Fabric API 和信雅互联环境。服务器端不需要安装 Voxy、Fabric API 或信雅互联。

## 主要功能

- 向客户端同步服务器上的 Voxy 远景数据。
- 玩家附近缺少远景数据时，可以让服务器自动生成对应区块。
- 可以限制每名玩家的同步带宽，避免一个玩家把上传带宽吃满。
- 可以限制全服和单个玩家同时生成多少远景，避免生成压力失控。
- 方块被修改、爆炸或区块被保存后，会通知客户端刷新对应远景。
- 可以显示超出原版实体追踪距离的远处玩家和玩家名。
- 提供中文配置、中文指令，以及 Voxy/Embeddium 选项界面集成。

## 默认行为

默认配置偏向“能用且不会太激进”：

- 每名玩家带宽上限：`3 MiB/s`
- 单个玩家远景范围：最多 `256` 区块
- 单个玩家同时请求生成：`8`
- 全服同时生成：`128`
- 缺失远景：允许服务器生成
- 远处玩家显示：开启

如果服务器 CPU 压力大，优先降低生成相关配置。如果玩家远景断档多，优先提高生成速度、队列或带宽。

## 安装

把构建出的 jar 放进服务器和客户端的 `mods` 文件夹。

客户端协议版本必须和服务器匹配。版本不匹配时，玩家仍然能进服，但不会使用 VSS 的远景同步功能。

## 配置文件

服务器配置文件会生成在：

```text
config/vss-server-config.json
```

常用配置说明：

| 配置项 | 默认值 | 怎么理解 |
| --- | ---: | --- |
| `lodDistanceChunks` | `256` | 服务器允许同步多远的远景，单位是区块。客户端自己的设置不能超过这个上限。 |
| `bytesPerSecondLimitPerPlayer` | `3145728` | 每名玩家最多占用多少服务器上传带宽。默认约 3 MiB/s。 |
| `sendQueueBytesLimitPerPlayer` | `33554432` | 每名玩家最多缓存多少待发送远景数据。太低容易断档，太高会吃内存。 |
| `enableChunkGeneration` | `true` | 缺少远景数据时，是否允许服务器生成区块来补远景。关闭后只同步已经存在的数据。 |
| `generationConcurrencyLimitPerPlayer` | `8` | 单个玩家最多同时推动多少个缺失区块生成。调高更快，也更吃性能。 |
| `generationConcurrencyLimitGlobal` | `128` | 全服最多同时生成多少个缺失区块。服务器吃不消就先降这个。 |
| `generationStartsPerTickLimit` | `4` | 每 tick 最多开始多少个生成任务。调低可以减少瞬间压力。 |
| `generationCompletionsPerTickLimit` | `8` | 每 tick 最多处理多少个生成完成的区块。调低可以减少主线程尖峰。 |
| `generationPackingThreads` | `2` | 用几个后台线程把区块转成远景数据。调高会更快，但会占 CPU。 |
| `generationPackingQueueLimit` | `64` | 后台打包队列长度。太低容易来不及处理，太高会多占内存。 |
| `generationTimeoutSeconds` | `60` | 等一个缺失区块生成多久。超过时间就放弃这次请求，避免一直卡着。 |
| `dirtyBroadcastIntervalSeconds` | `2` | 方块变化后，多久通知客户端刷新远景。 |
| `dirtyVersionCacheMaxEntries` | `200000` | 最多记住多少个发生过变化的远景列。越大越吃内存。 |
| `dirtyVersionCacheRetentionSeconds` | `86400` | 远景变化记录保留多久，默认 24 小时。 |
| `farPlayerSyncEnabled` | `true` | 是否显示原版追踪距离外的远处玩家。 |
| `farPlayerSyncIntervalTicks` | `5` | 远处玩家同步间隔。越低越顺滑，也越占一点网络和客户端渲染。 |

“远景变化记录”不是发送队列。它只记住哪些远景列变过，方便玩家之后回来时重新请求新数据，不会一直替离线玩家攒数据包。

## 常见调整

远景加载慢：

- 提高 `bytesPerSecondLimitPerPlayer`
- 提高 `generationConcurrencyLimitGlobal`
- 提高 `generationPackingThreads`

远景有断档：

- 提高 `sendQueueBytesLimitPerPlayer`
- 提高 `generationPackingQueueLimit`
- 确认 `enableChunkGeneration=true`

服务器卡顿或 CPU 压力大：

- 降低 `generationConcurrencyLimitGlobal`
- 降低 `generationStartsPerTickLimit`
- 降低 `generationPackingThreads`

上传带宽占用太高：

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

设置每名玩家的远景同步带宽：

```text
/vss 带宽 查看
/vss 带宽 设置mib <每秒MiB>
/vss bandwidth get
/vss bandwidth set_mib <mib_per_second>
```

设置服务器允许同步的最远距离：

```text
/vss 距离 查看
/vss 距离 设置 <区块>
/vss distance get
/vss distance set <chunks>
```

设置发送缓存：

```text
/vss 队列 查看
/vss 队列 设置mib <MiB>
/vss queue get
/vss queue set_mib <mib>
```

开关缺失远景生成：

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
/vss 生成 设置单人并发 <数量>
/vss 生成 设置全服并发 <数量>
/vss 生成 设置每tick开始 <数量>
/vss 生成 设置每tick完成 <数量>
/vss 生成 设置打包线程 <线程数>
/vss 生成 设置打包队列 <数量>
/vss 生成 设置超时 <秒>
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

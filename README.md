# Voxy Server Side NeoForge

Voxy Server Side（VSS）让服务端负责读取、生成、缓存并发送 Voxy 远景 LOD。客户端只请求缺失或过期的列数据，再交给 Voxy 渲染，适合多人服务器、大型整合包和高速移动场景。

## 支持版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| Loader | NeoForge `21.1.x` |
| VSS | `0.2.9-neoforge-1.21.1` |

- Forge 1.20.1 版本：[voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)
- 下载：[CurseForge](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

客户端和服务端必须安装协议一致的 VSS，不要混用旧版 jar。

## 安装

服务端需要：

- NeoForge `21.1.x`
- VSS
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)

客户端需要：

- NeoForge `21.1.x`
- VSS
- Voxy
- Sodium 兼容渲染环境
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)


## 工作方式

1. 客户端完成握手，取得服务端距离、速率、生成和带宽限制。
2. 客户端以玩家位置为中心扫描 Voxy LOD，并上报本地已有列，避免重复下载。
3. 服务端依次尝试内存缓存、持久化缓存、已加载区块、区块 NBT；允许时再提交区块生成。
4. 完整列进入该玩家的发送队列；服务端按距离和优先级选择数据，再通过全服共享带宽池公平轮询发送。
5. 客户端组装、解压并解码列数据，再交给 Voxy 或注册的 VSS API 消费者。

方块发生变化时，服务端会广播脏列版本，客户端只刷新受影响的 LOD。

## 远处玩家与兼容模组

VSS 可在原版实体跟踪范围外显示简化的玩家和载具，并同步原版装备、皮肤部件、披风开关以及部分模组附加数据。
远处玩家的位置和视角使用低频更新以节省带宽，因此不会像近处原版实体一样逐 tick 平滑同步。

## 配置

- 服务端：`config/vss-server-config.json`
- 客户端：`config/vss-client-config.json`
- 本地服务器可通过 Voxy/Sodium 设置页调整；专用服务器可编辑 JSON 或使用 `/vss` 命令。

### 服务端默认值

下表是新配置首次加载并完成默认迁移后的有效值。旧版自定义带宽值会保留为全服总带宽，旧默认 `1 Mbps` 会升级到新默认值。

| 配置 | 默认值 | 允许上限 |
| --- | ---: | ---: |
| LOD 距离 | `128` 区块 | `8192` |
| 全服总带宽 | `8 Mbps` | `100 Mbps` |
| 发送队列数量 | `1024` 列 | `8192` |
| 发送队列内存 | `16 MiB` | `128 MiB` |
| 磁盘读取线程 | `4` | `16` |
| 近/中/远/超远请求 | `0 / 8 / 4 / 2` 每 tick | 手动值各 `256` |
| 单玩家生成并发 | `4` | `128` |
| 全服生成并发 | `32` | `1024` |
| 脏列广播间隔 | `10` tick | `600` tick |

近距离请求的 `0` 是特殊值，表示不限速并可使用单个协议批次的 `1024` 请求容量。手动填写时四档最高均为 `256/tick`；中、远、超远三档的 `0` 表示关闭该档请求。

全服总带宽由所有玩家共享。发送器逐玩家轮询；没有待发数据、被距离分档限制或设置了较低个人下载上限的玩家不会占用额度，剩余带宽会自动供其他玩家使用。单人游戏可独占完整的 `8 Mbps`。

缺失 LOD 生成没有额外的每玩家每秒限速。单玩家与全服并发仍可手动调整；每 tick 启动量、完成量、打包线程、打包队列和超时由可用逻辑处理器与全服并发自动派生，可通过 `/vss generation get` 查看当前值。

### 客户端默认值

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `receiveServerLods` | `true` | 接收服务端 LOD |
| `lodDistanceChunks` | `0` | 自动跟随 Voxy 距离 |
| `desiredBandwidthKbps` | `0` | 不设个人下载上限，仍受全服总带宽限制 |
| `offThreadSectionProcessing` | `true` | 在线程外解码和处理收到的列 |

## 常用命令

所有命令需要管理员权限，可使用游戏内自动补全查看完整参数。

```text
/vss stats
/vss bandwidth get
/vss bandwidth set_mbps <mbps>
/vss queue get
/vss request_limits get
/vss distance get
/vss generation get
/vss generation stats
/vss generation set_player_concurrency <数量>
/vss generation set_global_concurrency <数量>
/vss storage get
/vss dirty get
/vss farplayers get
```

并发命令修改会立即保存配置并刷新玩家会话限制，其余生成后台参数会自动重新计算。

## 缓存与排错

持久化缓存位于：

```text
<世界>/data/vss-column-cache/<维度>/<regionX>_<regionZ>/
```

`.vcl` 保存列数据，`index.vci` 保存 Region 索引。需要清空缓存时先关闭服务器，再删除 `vss-column-cache`；之后的 LOD 会重新读取或生成。

排错建议：

- 先执行 `/vss stats`、`/vss generation stats` 查看请求、队列和生成状态。
- 确认两端 VSS 版本与协议一致，并确认客户端已加载 Voxy。
- LOD 到达很慢时检查全服总带宽、在线玩家数量和客户端个人下载上限；默认 `8 Mbps` 约等于 `1 MB/s`，由活跃玩家动态共享。
- 待发送数量持续增长时检查发送队列、客户端期望带宽和网络拥塞。
- 生成排队但吞吐低时检查每 tick 启动限制，而不只是提高全服并发。

## License

MIT

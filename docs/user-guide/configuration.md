# 配置

[文档目录](../README.md)

- 服务端：`config/vss-server-config.json`
- 客户端：`config/vss-client-config.json`
- 本地服务器可通过 Voxy/Sodium 设置页调整；专用服务器可编辑 JSON 或使用 `/vss` 命令。

编辑文件前退出相应的游戏或服务器；直接编辑的文件没有热重载。支持的在线命令会立即保存配置。

## 服务端默认值

| 配置 | 默认值 | 允许上限 |
| --- | ---: | ---: |
| LOD 距离 | `128` 区块 | `8192` |
| 全服总带宽 | `8 Mbps` | `100 Mbps` |
| 发送队列数量 | `1024` 列 | `8192` |
| 发送队列内存 | `16 MiB` | `128 MiB` |
| 磁盘读取线程 | `4` | `16` |
| 近/中/远/超远请求 | `0 / 8 / 4 / 2` 每 tick | 手动值各 `256` |
| 单人游戏每玩家生成并发 | `2` | `128` |
| 独立服务器每玩家生成并发 | `4` | `128` |
| 全服生成并发 | `32` | `1024` |
| 脏列广播间隔 | `10` tick | `600` tick |

单人游戏（含开放局域网）使用 `singleplayerGenerationConcurrencyLimit`，默认 `2`；独立服务器继续使用 `generationConcurrencyLimitPerPlayer`，默认 `4`。已有服务器自定义值保留；旧配置缺少单人字段时使用新默认值。这里控制真实区块自动生成，显式 `/vss chunky` 任务沿用自身额度。

近距离请求的 `0` 是特殊值，表示不限速并可使用单个协议批次的 `1024` 请求容量。手动填写时四档最高均为 `256/tick`；中、远、超远三档的 `0` 表示关闭该档请求。

全服总带宽由所有玩家共享。发送器逐玩家轮询；没有待发数据、被距离分档限制或设置了较低个人下载上限的玩家不会占用额度，剩余带宽会自动供其他玩家使用。单人游戏可独占完整的 `8 Mbps`。

## 客户端默认值

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `receiveServerLods` | `true` | 接收服务端 LOD |
| `lodDistanceChunks` | `0` | 自动使用服务端上限；超过 Voxy 设置时扩展渲染范围 |
| `desiredBandwidthKbps` | `0` | 不设个人下载上限，仍受全服总带宽限制 |
| `offThreadSectionProcessing` | `true` | 在线程外解码和处理收到的列 |
| `enableXaeroMapBridge` | `true` | 将服务端真实远景写入 Xaero 世界地图；预测转地图暂时停用 |
| `enablePrediction` | `true` | 开启种子驱动的预测采样，由 Voxy 统一渲染 |
| `predictionDistanceBlocks` | `4096` | 预测范围，单位方块，独立于服务端真实列同步距离 |
| `predictionSpyglassLoading` | `true` | 允许望远镜额外加载远处精细地形、植被与建筑 |
| `rememberTerrain` | `true` | 本地压缩保存预测地形与地表内容，允许释放闲置细节 |

VSS 真实远景范围使用区块为单位，预测范围使用方块为单位。渲染范围取 Voxy 自身设置、VSS 当前协商有效范围和已启用预测范围中的最大值；例如 Voxy 2048 区块、VSS 有效范围 4096 区块时，渲染器按 4096 区块工作。服务端协商上限仍然生效。

进入世界后可执行 `/vssclient stats details`：`profile` 表示世界生成快照是否完成解码，`tiles`、`pending`、`failed` 表示预测瓦片与队列状态，`voxelBytes` 表示保留的预测体素数据量。渲染后端固定为 Voxy；GPU 网格、上传、遮挡与绘制状态由 Voxy 管理。VSS 性能记录只提供自身 CPU 工作、队列和发布开销，不把缺失的 Voxy GPU 计时当作零开销。

`surface` 诊断包含地表候选与完成状态、实际生成块数、发布块数和跳过的 feature/结构数。预测设置不再提供植被、地表建筑和独立细化距离配置，旧字段被忽略并在保存时移除。完整植被与建筑保持启用，最终构网、LOD、遮挡和绘制由 Voxy 处理。关闭望远镜远处加载后仍可缩放观看已有远景，并暂停望远镜作用下的新预测采样；已有数据与普通排队工作仍可继续使用。

服务端的 `enablePredictionSync` 控制是否发送 `worldgen_profile`。客户端可用 `/vssclient stats details` 查看 `profile`、`tiles`、`pending` 和当前 exact/预测会话状态；地形后端诊断会显示当前 Rust 算法标识或 Java。

预测总开关与暂停加载的区别见 [预测与远景](prediction.md)，缓存位置见 [缓存与排错](cache-and-troubleshooting.md)。

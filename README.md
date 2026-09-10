# Voxy Server Side NeoForge

Voxy Server Side（VSS）让服务端负责读取、生成、缓存并发送 Voxy 远景 LOD。客户端只请求缺失或过期的列数据，再交给 Voxy 渲染，适合多人服务器、大型整合包和高速移动场景。

## 支持版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| Loader | NeoForge `21.1.x` |
| VSS | `0.3-neoforge-1.21.1` |

- Forge 1.20.1 版本：[voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)
- 下载：[CurseForge](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

VSS 主版本号统一固定为 `0.3`，保留加载器与 Minecraft 版本标识，完整版本号为 `0.3-neoforge-1.21.1`，输出文件为 `vss-0.3-neoforge-1.21.1.jar`。后续构建也沿用此版本号；不同构建可通过构建时间与 JAR 的 SHA-256 区分。配置结构和网络协议使用独立的内部版本标记。

客户端和服务端必须安装协议一致的 VSS；更新时请使用同一次构建的 JAR，不要仅凭固定版本号判断两端兼容。

## 安装

服务端需要：

- NeoForge `21.1.x`
- VSS
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)

客户端需要：

- NeoForge `21.1.x`
- VSS
- Voxy，或仅安装 Xaero's World Map 作为远景地图消费者
- Sodium 兼容渲染环境
- 可选但推荐(带宽压缩模组)：[ZstdNet](https://www.curseforge.com/minecraft/mc-mods/zstdnet)


## 工作方式

1. 客户端完成握手，取得服务端距离、速率、生成和带宽限制。
2. 客户端以玩家位置为中心扫描 Voxy LOD，并上报本地已有列，避免重复下载。
3. 服务端依次尝试内存缓存、持久化缓存、已加载区块、区块 NBT；允许时再提交区块生成。
4. 完整列进入该玩家的发送队列；服务端按距离和优先级选择数据，再通过全服共享带宽池公平轮询发送。
5. 客户端组装、解压并解码列数据，再交给 Voxy 或注册的 VSS API 消费者。

方块发生变化时，服务端会广播脏列版本，客户端只刷新受影响的 LOD。

## VSS 远处预测

客户端收到服务端同步的世界生成信息后，用世界种子在本地预测并渲染远景地形，不必等服务端把远处的 Voxy 列全部传输完成，地平线附近即可显示地形、植被和地表建筑。该功能默认开启，可用 `enablePrediction` 关闭。

预测范围由 `predictionDistanceBlocks` 控制（默认 8192 方块），近处地形之外还会按 `predictionSurfaceDistanceBlocks`（默认 768 方块）细化地表内容，植被和建筑分别由 `predictionTrees`、`predictionStructures` 开关。地形采样优先使用随包的原生 Rust 后端，不可用时回退 Java；预测结果默认缓存在本地（`rememberTerrain=true`），重进世界可恢复已有精度。

预测只是对世界生成的近似：不执行完整雕刻、装饰与结构地形融合，也不包含玩家改动，需要完全准确时以服务端下发的真实列为准。更细的范围与缓存行为见下方客户端配置。

## 图形与系统兼容性

普通预测渲染需要 OpenGL 3.2 的纹理缓冲、3D 遮罩和顶点数组；Iris/Voxy 光影路径沿用 Voxy 的 OpenGL 4.6、计算着色器和间接绘制要求。运行时不满足时会自动停用预测层，Voxy 和原版渲染不受影响；部分 AMD 驱动的深度采样缺陷会使 Voxy 停用。

预测渲染保留地表、外露侧面、水面、雪冰和 biome 着色，不绘制地下洞穴与建筑下表面；水面沿用资源包 `water_still` 的实际透明度，水下光照按水深估算。Iris 路径下预测深度和颜色在光影缓冲内合成，没有可用接口时不写入。

植被与地表建筑在近处及望远镜目标内按原版规则生成，密集区域按预算简化。地形模组按各自的适配结果工作（Tectonic、FreeTerraForged、史诗地形等），不代表可以任意叠加，具体版本与边界见下文。

Windows/NVIDIA RTX 4070 Ti SUPER 已通过普通和 Iris GPU 回归；Linux、AMD、Intel 只完成接口与离线着色器检查，macOS 的 Voxy OpenGL 4.6 路径通常不可用，因此不是完整的 Voxy 平台。

## Xaero 世界地图加载

客户端安装 [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 后，VSS 会把服务端发送的远景列写入世界地图，使地图覆盖范围不再受原版渲染距离限制。桥接完全在客户端完成，不修改协议，也不要求安装 Voxy；原版已经加载的近处区块仍由 Xaero 自己绘制。

该功能默认开启，可在 VSS 的 Embeddium/Sodium 设置页控制，需要临时关闭地图写入时执行 `/vssclient xaero disable`，恢复时执行 `/vssclient xaero enable`。执行 `/vssclient xaero reload` 会清除当前服务器所有维度的客户端列存在性记录并自动重新请求已有 LOD 缓存。

Xaero `1.40.x`、`1.41.x`、`1.42.x`、`1.43.x`、`1.44.x` 和 `1.45.0` 已适配；低于 `1.40.0` 的版本不受支持，高于 `1.45.0` 的版本尚未验证。反射接口不兼容时，Xaero 桥接会自动停用，不影响 VSS/Voxy 的 LOD 功能。

## 远处玩家与兼容模组

VSS 可在原版实体跟踪范围外显示简化的玩家和载具，并同步原版装备、皮肤部件、披风开关以及部分模组附加数据。
远处玩家的位置和视角使用低频更新以节省带宽，因此不会像近处原版实体一样逐 tick 平滑同步。

## 配置

- 服务端：`config/vss-server-config.json`
- 客户端：`config/vss-client-config.json`
- 本地服务器可通过 Voxy/Sodium 设置页调整；专用服务器可编辑 JSON 或使用 `/vss` 命令。

### 服务端默认值

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

### 客户端默认值

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `receiveServerLods` | `true` | 接收服务端 LOD |
| `lodDistanceChunks` | `0` | 自动跟随 Voxy 距离 |
| `desiredBandwidthKbps` | `0` | 不设个人下载上限，仍受全服总带宽限制 |
| `offThreadSectionProcessing` | `true` | 在线程外解码和处理收到的列 |
| `enableXaeroMapBridge` | `true` | 将服务端远景写入 Xaero 世界地图 |
| `enablePrediction` | `true` | 使用 VSS 自己的种子驱动远处预测 |
| `predictionDistanceBlocks` | `8192` | 独立预测远景范围，单位方块，独立于 VSS |
| `predictionSurfaceDistanceBlocks` | `768` | 实际近处地形外的地表细化宽度，范围 128–2048 方块 |
| `predictionTrees` | `true` | 近处及望远镜目标的树木、草等植被 |
| `predictionStructures` | `true` | 近处及望远镜目标的地表建筑 |
| `rememberTerrain` | `true` | 本地压缩保存预测地形与地表内容，允许释放闲置细节 |

进入世界后可
执行 `/vssclient stats`，其中 `profile` 表示世界生成快照是否解码完成，
`tiles`/`pending`/`failed` 表示预测 tile 队列状态，`rendered` 表示最近的
渲染阶段实际提交了多少预测网格单元；`render=...` 是累计的渲染诊断，
其中 `packedQuads` 是 greedy 顶面/水面四边形数量，`triangleVertices` 是
墙体与 feature 补充通道的顶点数量，`depthBoundCulled`/`occlusionCulled`
表示被远景边界和四帧遮挡迟滞过滤的 tile。

`surface` 诊断包含地表候选/完成网格数、基础远景与近处地形是否就绪、实际生成块数、进入网格的块数，以及跳过的 feature/结构数。自动日志受 `debugLogging` 控制；也可通过 `/vssclient stats` 主动查询。Voxy/Sodium 设置页提供植被开关、地表建筑开关与地表内容范围。

服务端的 `enablePredictionSync` 控制是否发送 `worldgen_profile`。客户端可用 `/vssclient stats` 查看 `profile`、`tiles`、`pending` 和当前 exact/预测会话状态。

### FreeTerraForged 预测适配

已接入 [ETcodehome/FreeTerraForged](https://github.com/ETcodehome/FreeTerraForged) 发布版 `0.0.6005-neoforge-1.21.1` 的 Java 生成路径。两端需使用同次构建的 VSS，并安装对应 FreeTerraForged。服务器同步实际预设与噪声注册表，客户端按维度设置其初始化上下文。采样间距至少 32 方块的粗模使用模组自己的点估算；更细地形读取逐方块侵蚀缓存，继续沿用近处/望远镜优先调度。河流、湖泊与湿地水面使用模组的水文高度函数；预测专用地形缓存会在工作线程结束后释放。

FreeTerraForged 的侵蚀、平滑、坡度、海滩检测和海滩修正已由预测专用挂钩批量交给 Rust，普通服务器生成器不注册该挂钩。Perlin/Perlin2、Simplex/Simplex2、白噪声、基础组合噪声、密度量化和线性样条也已有原生实现。大陆、河网、气候、部分噪声及其地表/装饰扩展仍使用 Java，完整 FreeTerraForged 生成器尚未全部迁入 Rust。已用发布 JAR 验证数值，整合包中的 Mixin 转换及最终画面仍需实机验证。瀑布流动、河岸补块等区块后处理不保证逐方块复现；已有真实列仍由 Voxy 覆盖。此适配不代表原始 RTF 的所有分支、任意地形模组叠加或 TerraBlender 扩展都已兼容。迁移边界见 `tools/rust/MOD_MIGRATION_2026-09-09.md`。

### 地形模组版本与史诗地形

本项目 JAR 的游戏版本仍为 **Minecraft 1.21.1 / NeoForge**。上游地形模组提供其他 Minecraft 版本的下载，不表示本 JAR 可以跨游戏版本使用。当前验证目标：

| 地形模组 | 1.21.1 验证版本 | 预测路径与边界 |
| --- | --- | --- |
| Tectonic | `3.0.26-neoforge-21.1`，配合 Lithostitched `1.8.0+beta6-neoforge-21.1` | 按实际运行图及自定义注册表重建；未验证所有旧版，不能声称整个 3.x 系列均支持 |
| FreeTerraForged | `0.0.6005-neoforge-1.21.1` | Java 生成上下文加 Rust 瓦片过滤；基础噪声和密度算子部分原生化。`0.0.6001`、`0.0.6002`、`0.0.6003R2`、`0.0.6004R1` 缺少当前适配必需的 `RTFWorldGenContext` 接口，不在当前支持范围 |
| [ETN 史诗地形](https://www.mcmod.cn/class/15808.html) / Epic Terrain | 发布名 `v0.1.4b-Beta-1.20.5~1.21.1`，文件 `epicterrain-0.1.4.jar` | Rust 重建密度缓存访问顺序、基础柱与扩展高度；已与发布数据包的 Minecraft NoiseChunk 结果对照 |
| Epic Terrain Compatible | `1.0.3+mod`，文件 `epic-terrain_compatible-1.0.3.jar` | 三维 `cache_2d`、插值切片和单元格批量填充已在 Rust 实现，并验证基础柱液体结果。此处不包含仅标注 Forge 的 `1.0.3b-1.21.1` |

Rust 样条现在保留数据包内重复/未排序控制点，并按 Minecraft `CubicSpline` 的二分查找求值；不会擅自排序或合并。对于随高度变化的 `cache_2d`，原生图构建返回明确的不支持原因，客户端保留已经解码的 Minecraft Java 采样器。诊断仍受 debug 模式控制。

这些是各自生成配置的适配结果，不代表 Tectonic、FreeTerraForged 与史诗地形可以同时叠加生成；它们之间的数据包覆盖、TerraBlender 扩展和特定整合包的光影仍需分别验证。复现命令和采样验证见 `tools/prediction/EPIC_TERRAIN_2026-09-09.md`。

## 常用命令

所有命令需要管理员权限，可使用游戏内自动补全查看完整参数。

```text
/vss help
/vss 帮助
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

`/vss help` 和 `/vss 帮助` 会显示每个根指令及其重要子指令的用途。

Xaero 地图命令是客户端命令，不需要管理员权限：`/vssclient xaero disable`、`/vssclient xaero enable`、`/vssclient xaero reload`。使用 `/vssclient stats` 查看会话、预测布局、tile、样本、feature/structure 和渲染统计。`/vssclient prediction capture` 用于显式导出参考数据；重复的 `/vssclient prediction` 状态入口已移除。

并发命令修改会立即保存配置并刷新玩家会话限制，其余生成后台参数会自动重新计算。

## 缓存与排错

持久化缓存位于：

```text
<世界>/data/vss-column-cache/<维度>/<regionX>_<regionZ>/
```

`.vcl` 保存列数据，`index.vci` 保存 Region 索引。Biome 快照修复会自动提高缓存 schema，升级后旧格式列会被视为无效并重新读取或生成；不需要手动清理。需要手动清空缓存时先关闭服务器，再删除 `vss-column-cache`。

排错建议：

- 先执行 `/vss stats`、`/vss generation stats` 查看请求、队列和生成状态。
- 确认两端 VSS 版本与协议一致，并确认客户端已加载 Voxy。
- LOD 到达很慢时检查全服总带宽、在线玩家数量和客户端个人下载上限；默认 `8 Mbps` 约等于 `1 MB/s`，由活跃玩家动态共享。
- 待发送数量持续增长时检查发送队列、客户端期望带宽和网络拥塞。
- 生成排队但吞吐低时检查每 tick 启动限制，而不只是提高全服并发。

## License

MIT

预测缓存会保存 8／16／32／64 格各级地形网格，重进世界直接恢复已保存的精度，再继续近处优先细化。原生生物群系颜色随样本保存，并通过草／叶颜色表的指纹识别资源包变化：颜色表变化只重算颜色，地形仍可复用。世界生成快照和密度引用按稳定的 JSON 对象字段顺序生成标识，数组顺序和实际参数变化仍会使缓存失效。缓存命中仍需解压、建立渲染网格和上传 GPU，但不再重新执行地形和相同颜色表下的生物群系颜色采样。

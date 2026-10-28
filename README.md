# Voxy Server Side NeoForge

[0.3.6 更新日志](CHANGELOG.md) · [使用与升级说明](docs/release-0.3.6-player-guide.md) · [模组适配核对](docs/release-0.3.5-mod-compatibility-20261006.md)

Voxy Server Side（VSS）让服务端负责读取、生成、缓存并发送 Voxy 远景 LOD。客户端只请求缺失或过期的列数据，再交给 Voxy 渲染，适合多人服务器、大型整合包和高速移动场景。

## 支持版本

| 项目 | 版本 |
| --- | --- |
| Minecraft | `1.21.1` |
| Loader | NeoForge `21.1.x` |
| VSS | `0.3.6-neoforge-1.21.1` |
| 本轮核对的 Voxy | `0.2.15-beta`，NeoForge 1.21.1 |

- Forge 1.20.1 版本：[voxy-server-side-forge](https://github.com/wish131400/voxy-server-side-forge)
- 下载：[CurseForge](https://www.curseforge.com/minecraft/mc-mods/voxy-server-side-forge-neoforge)

当前版本为 `0.3.6-neoforge-1.21.1`，输出文件为 `lib/vss-0.3.6-neoforge-1.21.1.jar`。当前 NeoForge 网络协议为 53。客户端与服务端必须使用同一次 0.3.6 构建；旧协议不兼容。缓存升级按记录校验和迁移，无需清空世界或 Voxy 缓存。

客户端和服务端必须安装协议一致的 VSS；更新时请使用同一次构建的 JAR，不要仅凭模组版本号判断两端兼容。

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

0.3.6 将预测体素交给 Voxy 统一构网、细化和渲染，继续使用 Rust / Java 地形与植被计算。真实列优先，预测不写入 Voxy 的真实持久缓存；Voxy 适配未就绪时等待恢复。放大与望远镜使用实际主画面投影，初始覆盖和细化请求可独立推进。用法与升级边界见 [0.3.6 说明](docs/release-0.3.6-player-guide.md)。

客户端收到服务端同步的世界生成信息后，用世界种子在本地预测并渲染远景地形，不必等服务端把远处的 Voxy 列全部传输完成，地平线附近即可显示地形、植被和地表建筑。该功能标记为 Beta，默认关闭，可用 `enablePrediction` 开启。

预测范围由 `predictionDistanceBlocks` 控制（默认 4096 方块），配置范围内完整覆盖，不额外留空最外圈。地形按屏幕像素误差渐进细化；植被和地表建筑随可见细节需求补全，发布完整生成的方块体素，不再生成简化方块树冠或代表性草木。完整树形缓存继续复用。望远镜额外加载目标区域由 `predictionSpyglassLoading` 控制，默认开启。地形采样按「原生 Rust → Java」的顺序选择后端：可用时使用随包的原生 Rust 世界生成核心，否则退回解码后的 Java 上下文；Rust 会处理受支持的生物群系、地表规则与装饰放置，未支持的装饰保留 Java 回退；建筑结构沿用 Minecraft 的 Java 布局与模板处理器，并与两种地形后端共享虚拟区块。预测结果默认缓存在本地（`rememberTerrain=true`），重进世界可恢复已有精度。

生物群系快照支持 TerraBlender 区域和 Blueprint 切片的嵌套组合，Java 与 Rust 都保留内部区域选择，避免石岸被预测为玄武岩悬崖。缺少必要快照时不启用该维度预测。

预测缓存按存档或服务器、维度和种子固定保存，不再因世界生成档案、Java/Rust 后端或超采样设置变化另建目录。单人缓存位于 `<存档>/.vss/prediction/surface-v1/<维度命名空间>/<维度路径>/seed-<种子十六进制>/`；多人缓存位于 `<游戏目录>/.vss/servers/<服务器标识哈希>/prediction/surface-v1/` 下的相同维度目录。已有精细地形与植被优先读取，由 Voxy 根据体素重新构网；缺失或无法解码的记录才重建；方块和生物群系按资源名称恢复，真实区块更新仍执行局部脏列刷新。首次升级会迁移能够确认匹配的旧目录，未确认来源的历史目录不自动合并；旧版缺少方块编号表的真实区块采样会重新采集。更改地形配置后，已缓存区域保留原有预测，新区域使用新配置；需要全部重新预测时，应退出世界后清理对应的预测缓存目录。

预测只是对世界生成的近似：不执行完整雕刻、装饰与结构地形融合，也不包含玩家改动，需要完全准确时以服务端下发的真实列为准。更细的范围与缓存行为见下方客户端配置。

## Xaero 世界地图加载

客户端安装 [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 后，VSS 会把服务端发送的真实远景列写入世界地图，不要求安装 Voxy。原版已经加载的近处区块仍由 Xaero 自己绘制。

预测地形和植被转换为 Xaero 地图的模块暂时停用。VSS 的 Rust / Java 远景预测继续使用 Voxy 渲染；已经保存到 Xaero 的预测地图内容会保留，之后到达的真实数据仍可覆盖。

该功能默认开启，可在 VSS 的 Embeddium/Sodium 设置页控制，需要临时关闭地图写入时执行 `/vssclient xaero disable`，恢复时执行 `/vssclient xaero enable`。执行 `/vssclient xaero reload` 会清除当前服务器所有维度的客户端列存在性记录并自动重新请求已有 LOD 缓存。

Xaero 支持下限仍为 1.40.0。本次分别检查了 NeoForge 1.21.1 和 Forge 1.20.1 对应的 1.40.16、1.45.0、1.46.0 发布 JAR，写入、保存及来源识别接口均通过检查；其他版本和整合包组合仍需验证。反射接口不兼容时，Xaero 桥接会自动停用，不影响 VSS/Voxy 的 LOD 功能。当前状态见 [0.3.6 说明](docs/release-0.3.6-player-guide.md#xaero-世界地图)。

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
| `lodDistanceChunks` | `0` | 自动使用服务端上限；超过 Voxy 设置时扩展渲染范围 |
| `desiredBandwidthKbps` | `0` | 不设个人下载上限，仍受全服总带宽限制 |
| `offThreadSectionProcessing` | `true` | 在线程外解码和处理收到的列 |
| `enableXaeroMapBridge` | `true` | 将服务端真实远景写入 Xaero 世界地图；预测转地图暂时停用 |
| `enablePrediction` | `false` | 开启种子驱动的预测采样，由 Voxy 统一渲染 |
| `predictionDistanceBlocks` | `4096` | 独立预测远景范围，单位方块，独立于 VSS |
| `predictionSpyglassLoading` | `true` | 允许望远镜额外加载远处精细地形、植被与建筑 |
| `rememberTerrain` | `true` | 本地压缩保存预测地形与地表内容，允许释放闲置细节 |

VSS 真实远景范围使用区块为单位，预测范围使用方块为单位。渲染范围取 Voxy 自身设置、VSS 当前协商有效范围和已启用预测范围中的最大值；例如 Voxy 2048 区块、VSS 有效范围 4096 区块时，渲染器按 4096 区块工作。服务端协商上限仍然生效。

进入世界后可执行 `/vssclient stats details`：`profile` 表示世界生成快照是否完成解码，`tiles`、`pending`、`failed` 表示预测瓦片与队列状态，`voxelBytes` 表示保留的预测体素数据量。渲染后端固定为 Voxy；GPU 网格、上传、遮挡与绘制状态由 Voxy 管理。VSS 性能记录只提供自身 CPU 工作、队列和发布开销，不把缺失的 Voxy GPU 计时当作零开销。

`surface` 诊断包含地表候选与完成状态、实际生成块数、发布块数和跳过的 feature/结构数。预测设置不再提供植被、地表建筑和独立细化距离配置，旧字段被忽略并在保存时移除。完整植被与建筑保持启用，最终构网、LOD、遮挡和绘制由 Voxy 处理。关闭望远镜远处加载后仍可缩放观看已有远景，并暂停望远镜作用下的新预测采样；已有数据与普通排队工作仍可继续使用。

服务端的 `enablePredictionSync` 控制是否发送 `worldgen_profile`。客户端可用 `/vssclient stats details` 查看 `profile`、`tiles`、`pending` 和当前 exact/预测会话状态；地形后端诊断会显示当前 Rust 算法标识或 Java。

### FreeTerraForged 预测适配

已接入 [ETcodehome/FreeTerraForged](https://github.com/ETcodehome/FreeTerraForged) 发布版 `1.0.0-neoforge-1.21.1`，并保留 ReTerraForged `0.0.6005-neoforge-1.21.1` 支持。上述两个 NeoForge 发行包已有实际接口与数值验证；Forge 1.20.1 的共享代码同步不计作这些发行包的跨平台实测。自动识别新旧命名空间，服务器同步实际预设与噪声注册表，客户端按维度设置其初始化上下文，并保留新版地下生物群系设置。两端需使用同次构建的 VSS，并安装对应生成模组。粗模使用模组自己的点估算；更细地形读取逐方块侵蚀缓存，继续沿用近处/望远镜优先调度。河流、湖泊与湿地水面使用模组的水文高度函数；预测专用地形缓存会在工作线程结束后释放。

FreeTerraForged 的侵蚀、平滑、坡度和海滩检测已由预测专用挂钩批量交给 Rust；旧版保留海滩修正，新版按预设执行高山高度压缩，并使用独立的地形高度缩放与完整种子转换。普通服务器生成器不注册该挂钩。Perlin/Perlin2、Simplex/Simplex2、白噪声、基础组合噪声、密度量化和线性样条也已有原生实现。大陆、河网、气候、部分噪声及其地表/装饰扩展仍使用 Java，完整 FreeTerraForged 生成器尚未全部迁入 Rust。已用发布 JAR 验证数值，整合包中的 Mixin 转换及最终画面仍需实机验证。瀑布流动、河岸补块等区块后处理不保证逐方块复现；已有真实列仍由 Voxy 覆盖。此适配不代表原始 RTF 的所有分支、任意地形模组叠加或 TerraBlender 扩展都已兼容。验证范围见 `docs/freeterraforged-compatibility.md`。

### 地形模组版本与史诗地形

本项目 JAR 的游戏版本仍为 **Minecraft 1.21.1 / NeoForge**。上游地形模组提供其他 Minecraft 版本的下载，不表示本 JAR 可以跨游戏版本使用。当前验证目标：

| 地形模组 | 1.21.1 验证版本 | 预测路径与边界 |
| --- | --- | --- |
| Tectonic | `3.0.26-neoforge-21.1`，配合 Lithostitched `1.8.0+beta6-neoforge-21.1` | 按实际运行图及自定义注册表重建；未验证所有旧版，不能声称整个 3.x 系列均支持 |
| FreeTerraForged / ReTerraForged | `1.0.0-neoforge-1.21.1` / `0.0.6005-neoforge-1.21.1` | Java 生成上下文加 Rust 瓦片过滤；适配新版命名空间、64 位种子及高山高度压缩。其他发布版尚未逐一验证 |
| [ETN 史诗地形](https://www.mcmod.cn/class/15808.html) / Epic Terrain | 发布名 `v0.1.4b-Beta-1.20.5~1.21.1`，文件 `epicterrain-0.1.4.jar` | Rust 重建密度缓存访问顺序、基础柱与扩展高度；已与发布数据包的 Minecraft NoiseChunk 结果对照 |
| Epic Terrain Compatible | `1.0.3+mod`，文件 `epic-terrain_compatible-1.0.3.jar` | 三维 `cache_2d`、插值切片和单元格批量填充已在 Rust 实现，并验证基础柱液体结果。此处不包含仅标注 Forge 的 `1.0.3b-1.21.1` |

Rust 样条现在保留数据包内重复/未排序控制点，并按 Minecraft `CubicSpline` 的二分查找求值；不会擅自排序或合并。对于随高度变化的 `cache_2d`，原生图构建返回明确的不支持原因，客户端保留已经解码的 Minecraft Java 采样器。诊断仍受 debug 模式控制。

这些是各自生成配置的适配结果，不代表 Tectonic、FreeTerraForged 与史诗地形可以同时叠加生成；它们之间的数据包覆盖、TerraBlender 扩展和特定整合包的光影仍需分别验证。复现命令和采样验证见 `tools/prediction/EPIC_TERRAIN_2026-09-09.md`。

## 常用命令

服务端 `/vss` 命令需要 OP 2，可使用游戏内自动补全查看参数。直接输入 `/vss` 或 `/vssclient` 显示帮助；中文别名和英文命令使用同一逻辑。

```text
/vss help
/vss 帮助
/vss help generation
/vss help chunky
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

普通生成并发命令立即保存配置并刷新玩家会话限制。它们控制普通玩家请求；显式 chunky 任务使用独立的速度优先路径。

### 指定区域生成 Voxy 列

```text
/vss chunky <x> <z> <radius>
/vss chunky start <x> <z> <radius>
/vss chunky here <radius>
/vss chunky rect <x1> <z1> <x2> <z2>
/vss chunky status
/vss chunky pause
/vss chunky resume
/vss chunky cancel
```

例如 `/vss chunky 0 0 1024` 生成以方块坐标 `(0, 0)` 为中心、半径 1024 方块的正方形区域，并保存可同步给 Voxy 的真实列；已有有效列直接复用。中文入口为 `/vss 预生成`，支持 `开始`、`当前位置`、`矩形`、`状态`、`暂停`、`继续`、`取消`。

进度每 60 秒报告，任务不随玩家移动或退出而结束。chunky 不应用 VSS 普通生成、打包与磁盘队列的性能限制，也没有 `concurrency` 子命令；区块快照仍在服务器线程安全取得。坐标和半径单位均为方块，半径 0～8192、矩形最多 4,194,304 列，同时运行一个任务。详细行为见 [指令与 chunky 说明](docs/vss-commands-and-chunky-20261006.md)。

### 客户端帮助与诊断

客户端命令不需要管理员权限：

```text
/vssclient help
/vssclient stats
/vssclient stats details
/vssclient xaero disable
/vssclient xaero enable
/vssclient xaero reload
/vssclient prediction capture
```

`stats` 显示简明状态，`stats details` 显示会话、预测布局、瓦片、样本、feature／structure 与渲染统计。`prediction capture` 显式导出参考数据；`prediction` 根节点显示对应帮助。

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

## 开发与模块边界

开发前请阅读 [贡献约定](CONTRIBUTING.md) 和 [模块架构](docs/architecture.md)。日常执行 `gradlew verifyModules` 检查依赖方向与关键生命周期回归；各生产包的 `package-info.java` 记录职责、线程和资源所有权。

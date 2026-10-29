# 森林远景优化实施与验证（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

后续发现显示计划额外保留坐标数组，已修复并重新打包。请使用 [内存修正版报告](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/prediction-memory-ownership-20261010.md) 中的 `memory-ownership-20261010` 成品；以下记录保留上一轮包的验证数据。

已完成上一轮森林调查提出的四项优化，并同步到 NeoForge 1.21.1 与 Forge 1.20.1：接缝与静态提交 CPU 减负、规则矩形紧凑 GPU 编码、按屏幕误差选择的显示 LOD、固定归属的轻量 shader 路径。两个平台均已构建新 JAR。

本报告记录实现后源码、离线真实网格验证和 OpenGL 回归。此前调查中的游戏 FPS、JFR 和约 10 ms 不透明 GPU 区间来自旧包，保持在原调查报告中。本轮没有取得新版完整游戏的同位置 FPS 对照；未替换用户客户端、未启动或关闭游戏、未提交或推送 GitHub，也未使用子 agent。

**接缝与静态提交 CPU 减负**

生产接缝构造改为 `PredictionPackedMesh.seamRecords()`，省去接缝实际不采用的显示变体构建。普通网格的 `terrainRecords()` 保留构建显示表示的语义，接缝几何仍与当前地块版本交接。此项减少渲染线程上的无收益计算；本轮没有把整个接缝算法迁到后台。

`PredictionIndirectBatch.replayPass()` 在完整绘制计划有效时按批次重放，省去逐 draw 的 `add()`、命令数计算及 `PlanItem.matches()`。相机偏移和 morph 时间继续按当前帧更新；Hi-Z 仍按原近远 bucket 在当前帧重建。

完整计划同时检查 PreparedPass revision、GPU residency revision、ownership revision、program/material/culling/average 状态和可变 packed metadata epoch。morph 或 pass 上传元数据变化时，已进入提交计划的网格会推进 epoch；后台创建尚未提交的新网格不会让全场景计划失效。覆盖撤销、GPU 网格替换和资源变化后会重新准备，以恢复应绘制的预测范围。

驻留使用记录在绘制列表变化或每 500 ms 刷新，整次刷新使用同一个时间值，减少逐 draw 的重复时钟和哈希查询。诊断新增 `wholePlanHits`，便于确认整批快速路径实际命中。

**规则矩形 GPU 编码**

在已有 32 bytes/quad 颜色/光照字典格式之上，规则矩形新增 20 bytes/quad GPU 记录，颜色/光照字典另计。四个对齐 words 保存两角坐标、绕序和属性；每面的 cell/palette 索引位于独立的一 word 段，避免跨 texel 的交错解码。

矩形保留四个角的原坐标与绕序，着色器直接恢复当前顶点。斜面、交叉植物和特殊模型保留完整精确坐标 fallback。若收益不足、面数少于 64 或没有适用字典，则沿用原格式。opaque 与 water 分别编码，继续使用各自的颜色表和 pass-local 偏移；morph、直接绘制、MDI 和 Iris 路径采用同一编码契约。

新布局只用于 GPU 上传。磁盘 canonical 12-int 记录和 CPU palette 8-int 记录保持兼容，现有完整缓存可以恢复。压缩缓存解码、显示变体和上传数据仍由原工作线程路径准备。

**按屏幕误差选择显示 LOD**

`PredictionDisplayGeometry` 在网格准备阶段构建连续的显示表示。普通地形、明确识别的 `LeavesBlock` 树冠、雪层与雪块可以使用共同的 XYZ 格点进行有界简化；水平步长按地块实际 spacing 的 2/4/8 倍，垂直步长为 1/2/4 blocks。每个表示记录实际最大顶点位移，所有源记录继续保留。

`PredictionDisplayLod` 的屏幕几何误差上限为 1.5 px，进入更粗表示使用 1.2 px 阈值，以形成滞回。选择使用实际相机 eye depth 和保守的视口边缘投影放大，避免单用水平或欧氏距离低估屏幕误差。近处、望远镜、正在 morph、混合父子归属或表示尚未就绪时使用原表示。

地块外缘一行不移动。水体、自定义模型和真实边界所在列受到保护，顶点跨越这些列的水平移动也受保护。CUTOUT、unshaded/model 和 fluid 几何保持原顶点；符合严格条件时仍可做精确共面合并。只有明确识别的树叶/雪 feature 才进行近似，未知 feature 保持原几何。强颜色梯度和不同 skylight 受到合并保护；适用的地形/树冠/雪允许有限颜色量化。

显示几何重新排列成连续 face group，并重新计算植物整株保留前缀。水草抽稀等级与几何显示等级分开。附加表示的面数合计不超过源面的 50%；单个表示至少净减 64 面，且剩余面数不超过源面的 65%，避免少量减面引入大量驻留。

删除塌缩面的判断使用实际索引三角形 `(0,1,2)` 和 `(0,2,3)` 的三维面积。水平 XZ 投影为零而三维仍有面积的陡坡继续保留；新增生产构建回归专门覆盖这种情形。

近似表示使用移动后、向面内偏置的片元位置取得归属 cell；原始表示继续使用来源 cell。只有完整归属才选择近似表示，以防父子地块局部交接出现空洞。

**轻量 shader 路径**

固定来源 cell 的顶点阶段已经读取归属 bits，现在通过 flat varying `vOwnershipBits` 交给片元阶段复用，减少同一面的重复纹理读取。混合归属与合并后的动态 cell 继续在片元阶段查询。feature 几何跳过仅用于地形替换分类的 realGround 导数计算。

真实深度、Voxy/原版覆盖、边界、接缝和水面交接检查继续执行。回归使用禁用固定归属复用的参考 shader，逐像素比较颜色与深度；本轮完成的是有明确适用条件的路径精简。

**离线真实森林网格验证**

从上一轮客户端捕获的 3,890 个压缩网格恢复原始记录，调用当前生产的 GPU 编码逻辑，对 opaque/water 新旧布局逐 word 比较。全部通过无损还原验证，共含 22,667,547 个源面。这是捕获的独立网格总量，包含当时没有绘制的范围。

| 指标 | 原方案 | 新方案 | 差值 |
| --- | ---: | ---: | ---: |
| 全部源网格的 GPU 上传载荷 | 716.67 MiB | 471.22 MiB | 减少 245.45 MiB，34.25% |
| 显示 LOD 分层样本的潜在远景面数 | 1,511,614 | 843,016 | 减少 44.23% |
| 同一样本的载荷，计入所有追加显示表示 | 48.72 MiB | 40.89 MiB | 减少 7.83 MiB，16.07% |

显示 LOD 从 spacing 和面数分层选取可准确恢复 face group 的 114 个网格，其中 48 个产生适用表示。表中的远景面数假设每个样本都采用最粗可用表示；实际选择还受屏幕误差、距离、望远镜、morph、完整归属等条件约束。该抽样用于验证，不将其比例外推到整张世界或当前每帧。

245.45 MiB 是几何载荷的离线差值；实际 GPU 页面分配量还受页容量、对齐、驻留保护和追加表示影响。它不能直接换算 FPS。

OpenGL 顶点微基准使用 RTX 4070 Ti SUPER，128 个瓦片、171,776 个候选面、rasterizer discard。最新 NeoForge 测试中，32→20 字节编码的 MDI GPU 中位数为：全 face 0.049152→0.054272 ms，部分 face 0.020480→0.022528 ms。这说明紧凑解码仍有小量指令成本。该小型 fixture 验证了布局和载荷；真实森林的净 GPU 收益需要与 LOD 减面、显存读取和完整片元成本一起测量。

**回归与分发包核验**

| 平台 | CPU 回归 | 真实 OpenGL 回归 | 分发构建 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 / JDK 21 | 112 通过，1 条件跳过，0 失败 | 13 通过，0 跳过，0 失败 | jar 成功 |
| Forge 1.20.1 / JDK 17 | 109 通过，1 条件跳过，0 失败 | 12 通过，1 条件跳过，0 失败 | jar + reobfJar 成功 |

CPU 跳过项为既有 `PredictionGpuEncodingTest.realCorpusRoundTripAndEncodingCost`，未传该测试专用 corpus。本轮独立离线验证已经对 3,890 个真实网格执行完整 roundtrip。Forge GPU 跳过项为读取实际安装 Voxy JAR 的 final-blit 深度约定测试，该平台未提供实际 Forge Voxy JAR；通用真实边界、深度、水面和交接回归已执行。NeoForge 传入实际使用的 Voxy 0.2.15-beta，该项通过。

回归覆盖矩形所有轴向/绕序/缩放/端点/颜色/特殊坐标、完整计划重放、morph 和 residency 失效、当前帧 Hi-Z bucket 输出、CUTOUT/solid 显示表示、混合/缺失归属、opaque/water、独立缓冲/arena、压缩冷恢复、GPU staging、OpenGL 状态交接与颜色/深度参考。陡坡塌缩用例在两个平台都已通过。

两个 JAR ZIP 完整性检查通过；五个平台的 Rust 库逐文件与仓库资源相符。NeoForge 分发包所有主类与最新编译输出逐字节一致；Forge 分发包与最新 `reobfJar/output.jar` 完全一致。七个新增/增强测试及隔离验证入口在两个仓库一致，共同算法也已核对；Minecraft/loader API、Java 版本与此前平台行为差异予以保留。

**可复现入口**

NeoForge 使用 JDK 21；在仓库目录运行：

```powershell
./gradlew.bat --no-daemon --project-cache-dir .gradle-forest-optimization-20261010 `
  -I tools/prediction/feature-handoff-tests.gradle `
  -I tools/prediction/forest-optimization-tests.gradle `
  forestOptimizationTest forestOptimizationGpuTest jar `
  '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar'
```

Forge 使用 JDK 17；在 Forge 仓库运行：

```powershell
./gradlew.bat --no-daemon --project-cache-dir .gradle-forest-optimization-20261010 `
  -I tools/prediction/feature-handoff-tests.gradle `
  -I tools/prediction/forest-optimization-tests.gradle `
  forestOptimizationTest forestOptimizationGpuTest jar reobfJar
```

测试结果位于各仓库 `build-forest-optimization-20261010/test-results/forest-optimization` 和 `forest-optimization-gpu`。诊断 A/B 的 JVM 参数为 `-Dvss.disableRectangleGpu=true`、`-Dvss.disablePredictionDisplayLod=true`、`-Dvss.disableFixedOwnershipReuse=true`；默认均启用新路径。完整离线脚本、原始输出、平台差异和构建日志保存在本机 `C:/Users/Administrator/.codex/tmp/vss-forest-optimization-20261010`。

本轮实现文件共 11 个：[PredictionPackedMesh.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionPackedMesh.java)、PredictionLodSeams、PredictionGpuEncoding、PredictionRenderer、PredictionIndirectBatch、PredictionGpuResidency、PredictionTerrainProgram、PredictionDisplayGeometry、VssLodSpriteTable、PredictionDisplayLod 和 PredictionWorkView。逐文件 SHA256 与结构化验证结果已保存到同名 JSON 报告。

**交付包**

版本号仍为 0.3.5。本轮成品位于专用的 `lib/forest-optimization-20261010` 目录，每个目录附 `SHA256SUMS.txt`。

| 平台 | 新 JAR | 字节数 | SHA256 |
| --- | --- | ---: | --- |
| NeoForge 1.21.1 | [vss-0.3.5-neoforge-1.21.1.jar](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/forest-optimization-20261010/vss-0.3.5-neoforge-1.21.1.jar) | 5,876,927 | `032fe84f91ea10932501a8ce6ff4d7e7a3f56709aed1f72d6ef4b32b4491fd24` |
| Forge 1.20.1 | [vss-0.3.5-forge-1.20.1.jar](C:/Users/Administrator/Desktop/voxyserverside/lib/forest-optimization-20261010/vss-0.3.5-forge-1.20.1.jar) | 6,549,098 | `cc82a06ed89de0426d7020d82466e44f66345190a92735716ba84bddc9a0142b` |

用户自行替换后，同一森林位置和朝向下应重点记录 `wholePlanHits`、`geometricDisplayTiles`、实际候选量、预测不透明 GPU 时间、提交 CPU 时间以及长帧。再移动、转身、望远镜放大并观察 Voxy/预测交界和水面，可核验显示等级与覆盖恢复。

# VSS 与实际安装 Voxy 的远景优化对照（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

后续森林实测已完成，见 [森林视角性能调查](../performance/prediction-forest-render-profile-20261010.md)。该轮候选主要来自地形和雪材质，普通网格的共面合并显示变体仅有 3 个，规则矩形约占 99%。下面的实现差距仍有参考价值；优化顺序应按补充报告调整，先减少无收益的 CPU 工作，再推进紧凑面格式和覆盖地形、雪、树冠的显示层级选择。

结论：Voxy 的真实区块渲染已经使用自身的远景优化；VSS 的预测几何有独立的网格和绘制通路，尚未实现同等的屏幕误差驱动粗几何选择、规则面编码和 GPU 层级命令生成。森林出现后持续低帧，应优先检查预测树冠的实际绘制复杂度，而不是只继续减少后台任务或更换 Hi-Z 算法。

本轮为安装包、日志、配置、Java 字节码和源码审计。没有重新采集当前客户端帧时间，没有修改生产源码、构建新 JAR 或替换客户端。下述实现差距已确认；每项在当前场景占多少毫秒尚未量化。

## 核验对象

| 对象 | 核验结果 |
| --- | --- |
| 实例 | F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249 |
| VSS | vss-0.3.5-neoforge-1.21.1.jar，5,869,014 bytes |
| VSS SHA256 | 6C7AD3159E1DE7E2ED6C4DA04200D9A40E98EFDCAB87649C5BF84BC674AEB35A |
| Voxy | voxy-0.2.15-beta.jar，12,626,286 bytes |
| Voxy SHA256 | 802D49EDC07C4E4A7AF15FFD59337A82AF383DFD551B67C278233098737E2F52 |
| VSS 包版本检查 | 安装包散列与上一轮 frame-fixes 交付记录一致 |
| 最新配置文件读取 | 预测距离 2048 方块、精细距离 1536、地表距离 512、植被 medium、树木开启、性能档位 high；这是文件值，未读取运行时状态 |
| 日志 | 本轮读取的 latest.log 在 18:30:35 记录关闭光影，Voxy 随后使用 NormalRenderPipeline / MDICSectionRenderer；较早的 Iris 回调记录 viewport 为 3840×2066，不能将此当作最新帧的分辨率 |
| Voxy 配置 | enabled/enable_rendering 为 true，sub_division_size 为 64；section_render_distance 的原始值为 16.0，此报告不将它直接解释为 Minecraft 区块数 |

对照源码来自 C:/Users/Administrator/Desktop/voxy-compare/neoforge-voxy。它与实际安装包存在差异，因此从实际 JAR 提取并检查了六个 shader，并反汇编了 Mipper、MDICSectionRenderer、RenderDataFactory。VSS 的 RenderSnapshot 也使用安装包字节码确认。

只有 cmdgen.comp 与该本地源码逐行一致。其他五个 shader 存在改动，报告关于深度、粗父回退和 alpha discard 的结论以实际包为准。实际 Mipper 是从 2×2×2 八个体素中按透明度相关优先级选代表；本地源码额外的按层级要求多个非空气样本的逻辑没有出现在安装包中。

## 主要差距

| 优化 | 实际安装的 Voxy | 当前 VSS 预测通路 | 含义 |
| --- | --- | --- | --- |
| 屏幕大小驱动的粗细切换 | GPU 遍历树，节点屏幕面积不足以继续细分时绘制粗节点 | 生成规划已有屏幕误差判断；普通渲染归属优先选择已驻留的最细数据，显示阶段另做受限合并 | 已完成细化的网格不会单纯因为看起来更小而自动退回粗驻留网格 |
| 树冠粗几何 | 体素逐级合成，再对所选层级生成外露面 | 植被粗体素大小与地形 spacing 耦合；细网格保留 1 方块木本体素 | 地形需要精细时，树冠也可能长期使用较细几何 |
| 规则面数据 | 每面 8 bytes，存起点、尺寸、方向和材质相关 ID，顶点由 shader 恢复 | 每面 32 bytes 加共享颜色/光照字典，未满足压缩条件则为 48 bytes；四个角的坐标仍占 24 bytes | 候选面很多时，几何读取和存储成本较大；这不是全显存或 FPS 的简单倍数关系 |
| GPU 命令生成 | GPU 选择 section、六方向和双面组，压缩生成命令数量；使用 indirect-count draw | CPU 管理可见列表及静态提交计划；GPU 测试 meshlet 和工作组 bounds，隐藏命令将 count 置零 | VSS 已经做了 GPU 剔除，但没有同等的完整 GPU 层级选择及有效命令压缩 |
| 内部面剔除 | 使用邻居模型、同类剔除和面遮蔽信息 | 木本体素占据检查删除内部面，同状态暴露侧面可合并 | VSS 已有这项基础优化，不能把掉帧解释成所有内部叶面都在画 |
| 背面与透明镂空 | 六方向命令选择，保留双面组；实际片元 shader 仍对 cutout 进行纹理 alpha 测试 | CPU 方向组加顶点阶段逐面方向判断；远处 cutout 继续读取材质和纹理 alpha | 不能归因于“Voxy 把所有叶子变实心”或“VSS 完全没有背面剔除” |

## 最直接的实现差距：加载精度与绘制精度尚未充分分离

NeoForge 源码证据：

- PredictionTileManager.java:1859 的 coveringTile 按实际 spacing 选择最细驻留瓦片。
- 同文件 :1872 的 coveringTileAtDetail，在 desiredLod <= 0 或不存在 scope-only 望远镜瓦片时，直接返回上述最细数据。
- 同文件 :1882 的规则明确保持普通加载的精度单调提高；只有望远镜专用细数据可以回到普通表示。
- 同文件 :1890 的 coveringFeatureTileAtDetail 对普通植被也优先选择装饰就绪且 spacing 更细的瓦片。
- PredictionRenderer.java:1690 的普通归属不使用当前请求精度；:1926 的 ownership 将普通归属视角归一为固定值。
- 已从安装的 VSS JAR 确认 RenderSnapshot 的相关分支，不只是根据注释推测。

这样有利于稳定交接、复用归属缓存和保留细节，但它还不是完整的显示 LOD：CPU 可以长期保留精细缓存，GPU 绘制则应该能够在粗表示已经就绪时按当前屏幕误差选择较少的几何。现有显示合并只弥补了部分差距。

这不表示细网格永远不会被淘汰：CPU 源快照、GPU 预算和替代交接仍可使它退休。这里指出的是“在它仍驻留且可见期间，普通归属不会仅因屏幕误差变小而切回粗网格”。

## 树叶已经减面，但不等于完整树冠 LOD

PredictionVegetationRuns.java:35–58 对一方块、可合并植被检查六向邻居，仅产生暴露面，并合并相同状态的连续竖面。PredictionMeshBuilder.java:961 和 :1018 使用这些结果及特殊形状占据检查生成植物。PredictionVegetationTraits 识别 LeavesBlock、树叶和原木标签等木本状态。

PredictionVegetationDisplayCache.java:155 的粗植被初始大小为 max(1, spacing / 2)。PredictionVegetation.java:288 的 boundedTile 在 spacing <= 2 时最终仍调用 voxelSize=1 的 Tile.of；细路径的 thinning 主要减少地表草和竹子，不把健康树叶改成另一状态。spacing 较大时才按粗体素合成木本植被，并可因构建预算增加体素大小。

PredictionVegetation.java:815 的 withExteriorEnvelope 当前返回 withoutExteriorEnvelope。最高叶面包络曾不能正确表示多层云杉、金合欢树冠和裸露树干，所以当前依靠邻居占据和面合并维护几何。不能把该方法名称当作“树冠外壳压缩正在生效”的证据。

PredictionDisplayGeometry.java 有三级显示变体，PredictionPackedMesh.java:224 及 PredictionRenderer.java:202 确实选择这些变体，不能说显示 LOD 没接入。不过它的作用是合并符合条件的共面矩形：

- 不同平面不能合并；材质、颜色/光照和相关 flags 也要匹配。
- 模型 UV、特殊纹理缩放、非轴对齐几何等不进入该合并。
- 每面只在有限 recent 窗口中寻找合并候选。
- 只有足够净减面、范围碎片不过多且附加存储不超预算的变体才被采用。
- morph 存在时不使用合并变体，以保持形变正确。

因此，平整的大块树叶可以受益，高低错落、有孔洞、多材质或特殊模型的树冠未必能显著减少。没有当前森林网格统计，不能给出实际受益或被拒绝比例。

普通屏幕规划本身已经实现（PredictionScreenDetail、PredictionLodPlanner）。树木生成使用约 8 方块特征的投影大小作为代理，它判断“是否值得生成表面细节”，尚不是逐树冠的显示几何误差层级。

## 已有 Hi-Z 与剩余可见绘制成本

VSS PredictionOcclusionCuller 用当前深度构建金字塔，对连续约 256 面的 meshlet 和每组 64 命令的父 bounds 做保守测试。固定命令槽隐藏时只将 index count 置零，不压缩命令数组。已有静态提交计划避免重复编码和上传；相机、时间及当前帧遮挡仍必须更新。

森林大面积可见时，遮挡剔除无法消除树冠本身的表面。即使后台构建停止、相机不动，每帧仍要处理相同的可见几何和 cutout alpha。PredictionTerrainProgram.java:556–563 对远处实心材质可以跳过材质读取，但 cutout 和流体继续保留材质路径；这保证树叶孔洞和水面显示，也保留了成本。

实际 Voxy 同样有 alpha discard，而且 MDIC 不透明及透明绘制也会关闭全局 GL_CULL_FACE。因此不能直接打开 VSS 全局硬件背面剔除来照抄 Voxy：VSS 三角流存在混合绕序，应该先在方向分组/命令阶段优化，保留草的双面和坡面/接缝的正确性。

## 为什么同样精细，VSS 仍可能更重

可见细节程度不是绘制成本的直接计数。即使保留同样的细网格，当前预测路径仍有单面数据、命令准备和每像素交接处理方面的差距。因此“树冠太细”只能解释其中一部分，不能用它替代对渲染效率的检查。

PredictionTerrainProgram.java:466 的顶点入口先读取两组 uvec4 的坐标/flags 数据，再读取第三组通用数据或颜色字典。每个角都执行该入口；GPU 缓存可能复用读取，不能把源码调用次数简单当作实际显存流量。实际 Voxy 的规则面记录为 8 bytes，并另有共享模型、颜色和光照等元数据；它不是总共只读取 8 bytes。

VSS 同一通用顶点路径随后处理来源归属、坐标、morph、材质、方向与覆盖信息。某些分支只在对应属性启用时执行。片元路径则包含真实深度解码/转换、深度表面导数、预测父子归属、Voxy 与原版覆盖、接缝归属和材质 alpha 测试：

- :788 附近进入实际深度重建和 Voxy 深度判定；
- :852 对不透明片元计算真实表面方向；
- :867–880 获取预测归属掩码；
- :895 起按条件处理 Voxy 覆盖和接缝墙；
- :938 起检查原版三维覆盖和特殊边界；
- :980 起处理材质颜色与 cutout。

Voxy 同样有模型查询、深度测试和 alpha discard，不能声称其每像素没有额外工作。VSS 的问题是两套数据及多级预测要交接，目前较多规则集中在一个通用 shader 中。对已经明确归属、完整处于真实覆盖范围外、没有 morph/接缝的普通远景，值得建立经过证明的轻量 shader/提交路径；边界、混合所有权和回退区域保留完整处理。不能直接删除正确性检查来追求速度。

此外，普通预测通道独立管理 reversed-depth 目标及深度交接，开启预测抗锯齿时还可能增加全屏处理。这是代码可确认的额外阶段，不是当前帧的实测占比。

因此，同等画质优化应同时验证两个方向：减少看不出区别的多余几何，以及降低必须保留的几何/像素处理成本。上一轮修复的预算与缓存热点主要减少 CPU 重复工作，尚未把 GPU 几何格式和通用着色器改成 Voxy 的效率模型。

## 建议的优化顺序与验收指标

1. 先统计当前森林的原始/提交树叶面数、spacing 分布、显示变体减面和拒绝原因；同时测新版预测准备 CPU、绘制提交 CPU 和不透明 GPU 时间。旧包 39.62 FPS、旧准备耗时，以及 10 月 6 日的候选面快照不能当作新包的实测数据。
2. 为树冠增加与地形 spacing 分离的显示几何 LOD，并允许在已就绪表示之间按屏幕误差切换。CPU 保留精细数据，远处 GPU 绘制粗表示；近处和望远镜恢复细表示。采用世界对齐、多层树冠保留和滞回，版本、覆盖与接缝保持统一交接，粗表示未就绪时继续画完整旧表示。
3. 为规则矩形设计更紧凑的起点/尺寸/方向记录；复杂植物模型、斜面和特殊接缝保留通用格式。对归属和深度关系已经明确的远景区域设计轻量提交/着色器路径，边界保留完整判断。先统计可编码面比例和通用着色器阶段成本，再估算收益。
4. 当新的 CPU/GPU 数据表明命令处理仍显著时，推进六方向 GPU 命令选择、有效命令压缩和更完整的层级遍历。

第一项生成统计后，最值得实现的几何方向是第二项。不要简单恢复最高树叶包络、删除所有下层树冠或把草与透明水面一起重新排序。建议复测同一森林位置、同一视角、静止/转向/移动三种状态，报告实际提交几何、GPU 时间、准备 CPU 和长帧，而不是只记录总 FPS。

## 双仓库核对和证据保存

Forge 1.20.1 与 NeoForge 1.21.1 的 PredictionDisplayGeometry、PredictionScreenDetail、PredictionGpuEncoding、PredictionCoverageOwners、PredictionOcclusionCuller、PredictionMeshletBounds、PredictionSpatialOrder 七个文件逐行一致。PredictionVegetation 有平台 API、方块命名及 Forge 的 Wythers 装饰适配差异；已单独检查本报告引用的 boundedTile、占据、包络和归属规则，两仓库对应行为一致。没有覆盖这些既有差异。

本报告和 prediction-voxy-distance-audit-20261010.json 同步保存到两仓库 docs。实际包提取的 shader、差异和字节码位于 C:/Users/Administrator/.codex/tmp/vss-voxy-distance-audit-20261010，voxy-package-check.json 记录六个 shader 的散列和匹配结果。

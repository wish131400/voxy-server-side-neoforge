# vss voxy sodium design audit 20261012

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 对照 Voxy / Sodium 的后续渲染设计审查 — 2026-10-12**

上一轮无明确收益的空间父组、额外层级、可见命令压缩、空批次计数跳过和 NV Mesh Shader 原型已退出生产路径。本轮在两个仓库核验 `PredictionIndirectBatch.java`、`PredictionOcclusionCuller.java`、`PredictionOcclusionPolicy.java`，六份生产文件均与该轮开始前保存的对应基线逐字节相同。不是回退到 Git HEAD；此前已经验收的页组缓存、溢出池、接缝索引、局部 PreparedPass、命令方向剔除、工作量版本比较、地形和植物 LOD 继续保留。

实验材料留在 `tools/prediction/cluster-render-experiments/`，仅由显式测试 init script 引入，不属于普通 main source set。没有再次实现或重复测试已拒绝的方案。本轮新增的是本报告、机器可读统计和离线审查脚本；没有修改生产 Java、构建新 JAR、替换游戏或推送 GitHub。

**本次源码依据和数据范围**

Voxy 参考本地 `C:/Users/Administrator/Desktop/voxy-263-git2`，核验提交 `b0207f6fcc0a55fa6b33f5c916932e8560a71845`，HEAD 为 `origin/263`；工作树干净。Sodium 参考已下载的 `C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592`，该目录为源代码归档。提交及 0.8.12-beta.2+mc1.21.1 标签对应关系沿用上一轮已核验记录，本次没有把归档目录冒称为 Git checkout，也没有查询上游最新版本。

统计读取 2026-10-10 的 immutable geometry 捕获：`C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry/geometry.json`，manifest SHA256 为 `9a4d4bf4164f265c24ec4cfbfcb6c60f79a4da3aa49ce501ebf9d888ae0c4153`。共有 2,019 个非接缝不透明 mesh，原 canonical 不透明面 22,405,691；捕获的 CPU 所选范围合计 15,501,420 面。这里不是新客户端的 GPU 最终面数、同帧光栅贡献或游戏 FPS。捕获的 camera 是字符串 `null`；没有利用不存在的相机数据做方向或遮挡收益计算。

相比之前 21,344,411 的重放面数，本次 22,405,691 是原 canonical 统计，没有重新执行当时相同的 exact scan merge；两者计数阶段不同。捕获还早于 10 月 12 日的新植物分类和 LOD，不能用这份数据宣称新版本没有减面。

**第一优先候选：让面方向选择覆盖直绘模式，并单独组织上、下表面**

Voxy 的 [cmdgen.comp](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp:95) 为六方向和双面几何选择命令；Sodium 的 [DefaultChunkRenderer.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer.java:269) 在生成批次时选择六方向与 unassigned，并在共享索引模式合并连续可见范围。这两者都在顶点程序运行前减少提交。

VSS 已经有 meshlet 方向掩码，不能说缺少方向剔除。但 [PredictionIndirectBatch.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionIndirectBatch.java:242) 仅在 `culling && !water` 时分割 meshlet、运行 filter。自适应策略选择直绘以后，方向掩码不会独立执行。[VssLodFaceGroup.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/VssLodFaceGroup.java:13) 仍是上下共用 HORIZONTAL 的五组；[PredictionRenderer.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:1122) 发现底面时还会保守保持整个水平组。

历史 CPU 所选范围按当前 `PredictionMeshletBounds.direction` 规则解码：

| 面的性质 | 面数 | 所选范围占比 |
|---|---:|---:|
| 可确定为轴对齐向上 | 7,849,183 | 50.64% |
| 可确定为轴对齐向下 | 959,500 | 6.19% |
| 可确定为其他四方向 | 6,560,415 | 42.32% |
| 双面、未知或不能保守判断 | 132,322 | 0.85% |

按捕获 ranges 假设执行 256 面分段，并沿用整个 meshlet 的保守 mask，共 100,995 条假设命令；其中 81,725 条单方向、17,794 条已知混合方向、1,476 条包含未知或双面。混合方向命令承载 3,412,241 个所选面。这个统计没有重放页分组、超大 tile 的命令上限回退或自适应模式，因此不是实际 MDI 命令数。单方向也不等于背向相机，向下面也不等于本帧可以全部删除。

值得试验的具体实现是：工作线程把已证明单面的轴对齐面组织为六方向连续段；坡面、双面植物、特殊模型和无法证明的记录单独保留。直绘模式也能按保守 bounds 选择方向段，继续合并相邻已选范围；开启 Hi-Z 时再做 meshlet 遮挡。每次选择必须覆盖 morph 的实际 bounds，不能用未形变的平面替代；水面、接缝、颜色表、ownership 和 display variant 的记录重排须保持对应关系。

这里没有主张在直绘模式强制运行完整 Hi-Z，或无条件把所有范围切成大量小命令。应比较额外命令/段数、准备 CPU、完整 GPU 区间和实际方向删除量。之前被拒绝的面级 vertex shader 提前返回不能代替这种命令选择；之前的命令方向 GPU 正收益也不能当作此新设计的已测收益。

**第二优先候选：将地形误差与显示选择细分到局部区域**

Voxy 先用 2×2×2 体素降采样生成各层数据，然后重新提取外露面；[traversal_dev.comp](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp:134) 按节点屏幕大小选择继续下探或提交自身 mesh。未准备好子节点时仍有自身覆盖。它减少进入绘制阶段的细几何数量，不只是让更多既有命令接受遮挡判断。

VSS 当前已经会选 display LOD，并且真正改变 MDI ranges；问题是粒度。[PredictionDisplayGeometry.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionDisplayGeometry.java:118) 累积整块 tile 的最大位移误差，每个 tier 保存一个 error；[PredictionRenderer.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:321) 用整块 bounds 的最近 eye depth 和最远角点保守投影选择一个 display tier。单处较大误差或靠近镜头的部分可以使其余平坦地形也留在细表示。

适合的下一步是少量地形子区域独立记录误差、bounds 和静态命令段，保留外缘精确环或明确的连接方案；水、建筑、特殊模型和接缝仍有各自保护。先验证局部几何选择能减少实际范围，再考虑让 GPU 接管该选择。它需要几何误差和内容版本证明；仅依据父级网格间距小于若干像素替代整个 tile，仍存在此前已经验证的高度差、水位差及缺少植被问题。

这条候选应统计每块 tile 被哪个内容/误差挡在细 tier、按子区域选择后的 MDI 面数、边缘接缝、版本交接及存储增长。还没有本轮性能 A/B，不能承诺额外减面比例。

**植被方向：草、水草继续用范围抽稀，树冠和复杂植物考虑独立粗表示**

历史 CPU 所选范围中，叶材质 758,658 面，已知原版陆生草/蕨 58,144 面，水草/海带 35,228 面，合计约 5.50%。石头 4,109,409 面加雪 3,900,029 面约占 51.67%。这是这一份地形语料的材质统计，不能推广到其他整合包或森林视角；叶片的 alpha/填充成本也不能按面数比例直接推算。缺少 registry tags 和共享材质使用者，未把全部模组植被归入这些名字。

当前 [PredictionVegetationRanges.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionVegetationRanges.java:77) 的独立抽稀只处理满足 crossed/UNSHADED 等保守条件的植物；轴对齐木本和特殊模型继续保留在 exact cohort。VSS 已有木本体素投票降采样、邻接内部面删除和叶簇，不能再将其作为尚未实现的优化。[PredictionVegetation.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionVegetation.java:395) 还保护整株与原始票数，不能为了面数把树干改成叶片，或简单把所有 LOGS 当树删除。

可借鉴 Voxy 模型的六方向颜色/深度代理和局部占用掩码，对远处已确认的树冠/复杂模型准备少量粗表示，按投影尺寸选择真正更少的几何。Sodium 的 [BlockOcclusionCache.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockOcclusionCache.java:48) 则说明可以缓存邻接面的形状比较结果；VSS 的已有 UNIT/occupancy 快速判断继续保留，只有复杂形状被证明存在大量多余相邻面时才扩展比较。

代理需要保留树冠分层、剪影、离散枝叶、树干和资源包材质。一个最高叶包络不能代表有上下分离的树冠。现有植被密度、远景地形变体和 GPU 驻留预算已经存在，不能再计算重复收益。

另发现一个有明确源码依据、但本语料无法量化的 CPU 候选：[PredictionBlockModel.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionBlockModel.java:19) 每个竹子位置重新取得模型、按方向调用 getQuads、分配 positions/normal。可以研究缓存不可变局部模板，再单独应用位置偏移和 tint。不过位置 seed、加权/多部件模型、资源重载和材质表 revision 都影响正确性，不能仅按 BlockState 缓存任意模型。本次捕获的 selected model-UV 为零，因此不把竹子缓存列为这份语料的确定热点。

**Sodium 透明排序：保留独立几何，谨慎评估触发规则**

Sodium 的 [SortType.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortType.java:13) 区分无需排序、静态顺序和动态排序；[SortTriggering.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/trigger/SortTriggering.java:76) 按面平面或直接距离/角度触发。[ChunkBuilderSortingTask.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderSortingTask.java:21) 只生成索引，RenderRegionManager 分开更新顶点和索引的缓存。

VSS 已有专用水面页，移动排序不重建水面几何和 ownership ranges；剩余明确冗余在 [PredictionRenderer.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:174)：任何 XYZ 相机变化都会调用 sortWater，而 comparator 只使用 X/Z。sortWater 先 `revision++`，即使最终顺序不变；完整静态 pass 重放又依赖这个 revision。

为判断收益边界，用捕获的 1,028 条水面记录重放 600 帧。原相机缺失，测试假设原点为记录中心的 X/Z 中位数 (3968,1040)，轨迹为合成路径；逐帧比较原距离排序和“相邻顺序精确校验后再排序”，每帧完整条目顺序一致。

| 合成路径 | 当前规则排序/推进版本 | 实际顺序变化 | 精确校验仍需完整排序 |
|---|---:|---:|---:|
| 仅 Y 变化 | 600 | 0 | 0 |
| 水平约 72 方块 | 600 | 591 | 591 |
| 半径 64 方块转弯 | 600 | 598 | 598 |

忽略纯 Y 的排序触发、仅在确实重排时推进排序版本是值得独立试验的小修正；相机 Y 仍参与覆盖、深度和绘制，网格/ownership/材质等更新仍须正常失效。完整相邻校验还需要 O(n) 工作；水平运行中仍几乎每帧排序，可能增加 CPU 成本，不能作为普遍优化直接保留。Sodium 的几何平面触发规则对应它的排序算法，不能把 1 方块/10 度阈值直接套在 VSS 的中心距离排序上并声称顺序严格不变。

Voxy 的 [buildtranslucents.comp](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/resources/assets/voxy/shaders/lod/gl46/buildtranslucents.comp:49) 使用 section 相对位置的三维曼哈顿距离桶，也不是 VSS 的现有 X/Z 精确距离顺序。是否采用另一套透明排序需要独立视觉与 GPU 验证，不能为了按页合批而改乱当前水面顺序。本轮只是结构计数/顺序验证，没有 CPU 时间或 GPU 收益测量。

**加载和显存：有条件借鉴，暂不继续叠加限制或搬迁**

Sodium 的 [RenderSectionManager.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager.java:568) 同时估计工作线程执行时间、结果大小和上传时间，在生成任务入队之前消费对应容量；已有样本会更新估计。VSS 当前有后台 CPU 采样、入队预约/退避、128 MiB 构建工作区预约、32 MiB 可选 staging 和 4 MiB/2 ms 每帧软上传预算；不是没有控制。区别是尚未按预计结果的上传时间和大小为任务提前预约交付容量。

只有后续进入世界/跑图采样显示“成品持续积压，生成明显快于 GPU 交付”时，才值得用分内容、分 spacing 的预测估计改善这个环节。目标是减少无效成品/内存压力并优先交付缺失覆盖，不能让缓存命中、普通粗级回退或必要接缝失去前进机会。这是客户端预测交接的研究，不涉及 /vss chunky 的用户配置。

Sodium 的 [GlBufferArena.java](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/gl/arena/GlBufferArena.java:58) 通过重新分配/复制已用段整理空洞，并更新 section 地址和批次。VSS 的专用水面页、8 MiB 溢出页和预算退休已经实现。在线搬迁可以进一步研究，但需要证明能腾空整页并真实释放 GPU buffer，计入复制、fence、地址与缓存失效成本；仅减少 free-list 空洞、把全部页缩小，不能称为已获得显存或 FPS 收益。

Sodium CPU 连通遮挡图依赖可靠的近区块连通关系；不完整预测区域不能把 unknown 当空或封闭。Voxy 的 8-byte quad 依赖规则体素及模型代理，VSS 已有 20-byte GPU 规则面加颜色表和例外坐标，不能重复宣称 48→32，或把所有预测面直接套成 8 bytes。层级加深、持久读回和 Mesh Shader 是否有价值取决于工作负载，当前已拒绝原型不继续留在生产路径。

**后续验收顺序**

| 次序 | 候选 | 保留依据 |
|---|---|---|
| 1 | 直绘也能选择六方向静态范围 | 相同像素/深度与交接正确；额外命令成本低于省下的完整 GPU 工作，朝向开放场景无明显回退 |
| 2 | 地形子区域的误差与几何选择 | 各距离带实际 MDI 范围减少；边缘、覆盖撤销、morph、父子切换正确，计入额外存储及准备成本 |
| 3 | 独立远景树冠/复杂植物表示 | 森林和模组植物样本中的面/命令、vertex 和完整 GPU 成本下降；资源包、树冠分层和整株显示可接受 |
| 4 | 水面排序触发/版本小修正 | 当前生产提交路径静止、纯 Y、水平移动分别 A/B；只为实际收益保留，不能按计数直接宣称变快 |
| 条件项 | 上传结果估计、在线页整理、竹子模板缓存 | 分别先有成品积压、可释放整页或模型调用栈热点证据，再做组件 A/B |

使用同一工作树基线、交替 A/B、预热及重复测量，计入命令生成、过滤、打包和完整绘制；像素、深度、状态和版本回退必须通过。游戏验收另外对齐同存档、同位置/朝向、画质、加载阶段，记录准备/提交 CPU、各通道 GPU、MDI 面/命令、上传量、驻留及 P95/P99。没有稳定收益的候选按本次规则撤回；组件时间或历史材质比例均不能直接换算为新客户端 FPS。

原始结构计数、材质清单和逐路径排序结果在 [JSON](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-voxy-sodium-design-audit-20261012.json)。复现脚本 [audit-render-design.py](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/tools/prediction/audit-render-design.py) 只读取捕获，不接入游戏：

```powershell
python tools/prediction/audit-render-design.py --capture 'C:/Users/Administrator/.codex/tmp/vss-distance-render-20261010/geometry' --output docs/vss-voxy-sodium-design-audit-20261012.json
```

脚本需要 Python 3.14 的 `compression.zstd` 与 NumPy，解码该历史捕获的 48/32-byte 布局；遇到新 20-byte 记录会显式拒绝，避免误解码。范围边界、重叠、面数和逐帧排序等价性均执行断言。本报告、JSON、脚本同步保存于两个仓库。

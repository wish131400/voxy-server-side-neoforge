# vss render followup 20261011

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 渲染后续审查：Sodium、Voxy 与新 Spark — 2026-10-11**

本轮确认了三个值得优先验证的方向：按当前画面的接缝依赖管理 GPU 驻留、使用持久映射的异步读回、让接缝候选与绘制准备按局部变化更新。上一轮批次缓存已经缩小逐字节比较热点，但加载中的场景仍在频繁重建；过宽的接缝保护还会阻止视野外几何回收，增加池满后的独立缓冲直绘。

这是一轮源码审查和运行数据调查。本轮没有修改生产渲染代码，也没有新的游戏 FPS A/B 或候选实现收益测试。下文的驻留规模、样本占比和候选方案不能当成已经获得的显存或帧率提升。上一轮已实施、已通过组件 A/B 的内容见 [渲染架构调整报告](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-render-architecture-20261011.md)。

**样本与版本核验**

- 新样本：[lNvcOAWHJs](https://spark.lucko.me/lNvcOAWHJs)。香港时间 2026-10-11 23:28:54.195 至 23:30:54.484，约 120.289 秒，仅采 Render thread，间隔 4,000 μs，总样本权重 120,000 ms，约 30,000 个样本。
- 解码得到 10,354 个节点。引用合法、唯一父节点、自身权重非负；各窗口自身权重之和等于根节点权重。
- 客户端 PID 43912，Java 25.0.1，NeoForge 21.1.249；VSS 0.3.5-neoforge-1.21.1、Voxy 0.2.15-beta、Sodium 0.8.12-beta.2+mc1.21.1、Iris 1.8.14-beta.1+mc1.21.1。
- 安装 VSS 与上一轮产物 SHA256 一致：`22db2613e4628b60258f872bfabb7eaa22799f0d0c49957737e6d46ed59502c6`。本轮调查的是上一轮架构修改后的包。
- 现有渲染计数每 2 秒读取一次，共 75 份；采集任务持续约 150 秒，快照覆盖 23:30:18.325 至 23:32:46.508。与 Spark 重叠的只有最后约 36 秒；后段出现暂停，不能用完整计数窗口充当同场景对照。
- 现有计时按每 30 帧抽样启用，采集结束已恢复原属性，记录为 `restoredTimingProperty=null`。
- 三份渲染线程 census 在 Spark 结束后拍摄，只读取既有元数据，没有逐面解码或修改 GL。第三份为 23:45:31.646 的暂停快照，读取约 59.67 ms。它只用于分析数据布局和保护条件，读取开销不在这份 Spark 内。

Spark 原文件 SHA256：`9110c06400bd2e57de10e04c586f9395c70ad13c1ecc4e7942bb632082a80c1d`。原始 profile、解码树、计数和 census 位于 `C:/Users/Administrator/.codex/tmp/vss-render-followup-20261011`；同目录 `finalize.py` 对汇总执行了样本树、计数、采样帧和字节数校验。仓库 JSON 保存各输入哈希与汇总，未将大 profile 放入仓库。

**CPU：旧热点缩小，场景准备仍很重**

以下为 Render thread 的样本占比。inclusive 包含子调用，不是独立 CPU 时间，不能相加；self 为节点自身样本。

| 调用路径 | inclusive | self | 含义 |
|---|---:|---:|---|
| VSS 出现在调用栈中 | 78.270% | — | 包含 VSS 的 Java 与原生调用 |
| Scene.prepare | 25.877% | 2.173% | 覆盖、接缝、候选与可见列表准备 |
| PreparedPass.prepare | 8.400% | 4.050% | 几何范围、归属和通道准备 |
| PredictionIndirectBatch.add | 8.380% | 1.263% | 批次检查、组装及子调用 |
| PredictionLodSeams.apply | 5.860% | 0.020% | 包含接缝计算 |
| PredictionLodSeams.stitch | 4.507% | 0.420% | 接缝拼接 |
| PredictionVisiblePlan.selectStable | 5.097% | 0.057% | 可见条目选择 |
| resolveCoverage | 4.320% | 0.047% | 覆盖更新 |
| PredictionRefinementOcclusion.sample | 3.787% | 0.003% | 包含 GPU 结果读回 |
| BatchSlot.upload | 3.057% | 0.170% | 批次比较与必要的 GPU 上传 |
| PredictionCoverageOwners.version | 2.077% | 2.077% | 局部版本查询 |
| PredictionOwnershipRuns.staticCoverage | 1.980% | 1.980% | 静态归属过滤 |
| BatchSlot.same | 0.257% | 0.257% | 逐字节比较 |

旧 [CuvzdaFFdW 样本](https://spark.lucko.me/CuvzdaFFdW) 中，`same` 为 3.367%，`add` 为 11.747%，`BatchSlot.upload` 为 5.940%；本次分别为 0.257%、8.380%、3.057%。旧比较热点缩小，与上一轮减少重复编码的方向一致，但两段场景不同，不能据此计算 FPS 提升或归因全部变化。

Sodium inclusive 为 66.923%，Voxy inclusive 为 61.583%，大量栈包含 VSS 回调。按“栈不含 VSS”筛选后，Sodium 为 2.037%，Voxy 为 1.300%，Xaero 为 3.327%。这不是严格的模组 exclusive CPU 百分比，也不代表后台线程占用；不能把 inclusive 数字相加，或将嵌套的 VSS 工作归到钠/Voxy 本身。

**与 Spark 对齐的 36 秒：持续加载使局部缓存和整计划复用呈现不同结果**

23:30:18.325 至 23:30:54.358，共 19 份未暂停快照，跨度 36.033 秒。

| 指标增量 | 结果 |
|---|---:|
| 实际渲染帧 | 708，约 19.65 帧/秒 |
| 游戏 FPS 读数 | 6–27，中位 21 |
| 绘制计划重建 / 复用 | 709 / 0 |
| 网格上传 | 1,354 |
| 页组缓存命中 / 重建 | 214,489 / 256,308 |
| 重新编码的条目 | 1,508,945 |
| 完整提交计划命中 | 0 |
| 排序编辑 / 可见列表发布 | 99,797 / 682 |
| 接缝地表更新 | 26,705 |
| 覆盖重算 | 11,571 |
| 预算回收 / 冷恢复请求 | 447 / 175 |

局部页组仍有大量命中，但整场景计划在这段加载期无法复用。两者并不矛盾：新缓存减少了未变组的编码，持续到达的网格、接缝和归属仍会让上层重新准备。下一轮应针对具体条目和接缝候选的失效范围，继续减少全列表扫描。

同窗口计时计数差分得到：准备 CPU 19.784 ms、接缝 CPU 6.086 ms、不透明 CPU 8.945 ms、水面 CPU 2.623 ms；不透明 GPU 区间 10.839 ms、水面 GPU 区间 2.687 ms。这六项各有 24 个抽样。间接构建 CPU 为 3.886 ms/次，48 次；单个网格上传 CPU 为 0.366 ms/次，53 次。单次 HZB reduce GPU 为 0.0945 ms、filter 为 0.0573 ms，各 166 次。阶段有包含关系、抽样单位不同，CPU/GPU 还会重叠，不能累加成帧时间；GPU 区间也不能视为没有等待因素的纯执行时间。

**第一优先级：接缝记录把大量视野外几何标为保护对象**

已核对两个仓库的同一逻辑：

- [上传准入](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:763) 中，只要 `seamBatch.draws` 或 `realSeamBatch.draws` 存在该 tile 的记录，就将冷地块视为 required，即使当前处于视野外。
- [驻留观察](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:796) 中，相同条件将地块设为 protectedTile，没有检查接缝当前是否参与画面。
- [回收候选](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionGpuResidency.java:68) 直接跳过 protectedTile，因此超过软预算也不能回收这些记录。
- Forge 对应入口为 `PredictionRenderer.java:761`、`:794`；接缝 required/protected 条件分别在 `:774`、`:797`，回收逻辑相同。

第三份暂停 census 中，共 6,306 个地块带有接缝保护，计 3,140,926,288 字节；其中驻留策略标为视野外的 4,947 个地块，计 2,721,700,000 字节。进一步要求 `fallback=true` 后，仍有 **4,945 个地块、2,720,457,440 字节，约 2,594 MiB / 2.53 GiB**。

这里的 visible、protected、fallback 字段来自最近一次驻留扫描，并非 census 时重新执行的视锥测试。`hasFallback` 表示驻留表中存在普通父级、其 packed payload 非空且面数大于零；它不能替代对全部植被、建筑、水面和接缝交接条件的检查。因此，2.53 GiB 是需要复核的保护数据规模，**不是已经回收的空间，也不是可无条件删除的空间**。此前进度消息中的“约 2.59 GiB”应按这里的字节数修正为约 2.53 GiB。

可实施方向是将“保留 CPU 接缝记录”与“当前画面确实依赖对应 GPU 几何”分开。上传准入、保护与回收必须同时调整，以免删除后下一帧又被 required 上传恢复。可见接缝、近处、望远镜范围、必要的粗级覆盖和真实区块边界继续有明确依赖；移入或转身时，父级、细节、覆盖掩码和接缝须保持版本一致。测试应覆盖 Voxy 归属撤销，以及 features/水面尚未准备好的交接。

运行窗口的 liveBytes 中位数为 4,181,802,224 字节，约 3,988 MiB / 3.895 GiB；committedGeometryBytes 中位约 4,213 MiB / 4.114 GiB。软预算是 1,024 MiB，live 与 committed 不是同一指标，前者也不代表显卡全部资源。主不透明池 32×64 MiB、水面池 2×64 MiB、overflow 64×8 MiB 均已到页数上限。再扩 overflow 只能延后独立缓冲回退，不能解决保护对象过多的根因。

驻留源网格中位约 1.52 亿面，额外显示变体约 3,369 万面；源地形约 9,609 万、features 约 5,467 万、preview 约 101 万、水体约 32.5 万。这些是存储统计，不是当帧提交量；features 包含植被与建筑，不能全部称作树叶。暂停 census 中 LOD1 独占约 2.15 十进制 GB，源面约 8,045 万，是重要驻留层级。运行设置为总距离 8,192、fine 1,536、surface 512 方块，植被 medium，并非突然将范围开到数十公里。

**Hi-Z 有明确剔除量，但大量直绘几何绕过了这条路径**

同一采样提交帧 `sampleSubmissionFrame=14932`，在 23:30:28.339 被读到：

| 该采样帧的不透明几何 | 四边形数 |
|---|---:|
| 共享页 MDI 候选 | 25,886,361 |
| 独立缓冲直绘候选 | 23,938,323 |
| 总候选 | 49,824,684 |
| Hi-Z 剔除 | 21,149,461 |
| Hi-Z 后剩余，包含直绘 | 28,675,223 |

这组数字来自保存的同一提交样本，不能换成“当前准备候选减旧 hidden”。共享页 MDI 候选约 81.7% 被剔除，剩余 MDI 为 4,736,900 面；另有 23,938,323 面通过独立缓冲直绘，未经过该命令过滤。因此，减少不必要驻留和池满直绘，既可减少提交成本，也可能让更多保留的几何进入已有 Hi-Z 通道。

第二个匹配样本 `sampleSubmissionFrame=15652` 的总候选为 51,729,977，Hi-Z 剔除 22,188,361，剩余 29,541,616，其中直绘 25,989,127。实际收益仍需同场景测试，不应把所有直绘候选都假定为可遮挡几何。

36 秒内的独立快照中，直绘 tile 为 182–1,583，MDI 命令为 26,168–139,501，不透明 flush 为 143–418。读计数时可能处于一帧中间，当前 PreparedPass 和最近提交的数量不保证属于同一帧，故 JSON 将这些记录为独立分布。

**第二优先级：借鉴 Voxy 的持久映射异步读回**

`GL45C.nglGetNamedBufferSubData` 自身占 2.587%，样本权重 3,104 ms，全部该热点调用栈来自 [细化可见性反馈 poll](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRefinementOcclusion.java:93)。

现有实现已经使用零超时 fence 查询，确认完成后才读回；**本次证据不能说明它在等待一个尚未完成的 fence**。成本发生在读回/驱动调用本身。系统每 250 ms 最多提交 1,024 个 probes，三槽输出最多各 20 KiB，读完整间接命令后只检查每条的 count。结果仅用于生成优先级，不决定画面几何归属。

Voxy 263 的 [DownloadStream](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/java/me/cortex/voxy/client/core/rendering/util/DownloadStream.java:110) 使用持久映射 staging，GPU copy 后 barrier，fence 完成后 CPU 直接读取映射地址；[RawDownloadStream](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/java/me/cortex/voxy/client/core/rendering/util/RawDownloadStream.java:30) 也采用持久读映射。VSS 可以使用固定的小槽实现相同生命周期，槽忙时跳过，保留版本、epoch、视角和过期校验。Voxy 满流时可能使用 glFinish；VSS 的优先级提示应继续采用不阻塞策略。也可测试只输出必要标志以减少读回量。

下一轮须比较同一 probe 数据下原路径与映射路径的 CPU、GPU copy 和尾帧成本，并验证销毁、世界切换及 busy slot；本轮没有这项 A/B 结果。

**第三优先级：借鉴 Sodium 的局部列表与缓存生命周期，先收窄接缝候选**

Sodium 的 [DefaultChunkRenderer](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer.java:77) 按 region/pass 缓存命令，拆分六方向和 unassigned；[ChunkRenderList](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderList.java:58) 用成员位图与截断的 region 相对相机坐标限制失效。[RenderRegionManager](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager.java:60) 对上传区域、通道局部失效，透明排序更新索引数据。

VSS 已有空间索引、稳定距离桶、局部 ownership stamp 和页组缓存；下一步要解决的是仍超出这些局部机制的工作。一个明确入口是 [Scene.drawCandidates](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:898)：地形先经过空间候选选择，随后却把所有 `seamBatch.draws` 和 `realSeamBatch.draws` 的 key 加进候选集，再逐项做精确视锥测试。

`VisiblePlan.updateStable` 路径下的 `FrustumIntersection.cubeInFrustum` 自身占全部样本 3.780%。适合先给接缝建立独立空间索引，按 patch 的真实 bounds 查询；接缝可能高于或伸出源地块，不能只套用地形 tile bounds。随后让 PreparedPass 按成员、payload、归属和显示选择版本更新对应记录，减少一个条目变化导致全列表遍历。水面仍须保持透明顺序。

Sodium 的 [MappedStagingBuffer](C:/Users/Administrator/Desktop/sodium-render-comparison-20261011/sodium-ec15afd7c85f850552577739eb30858faea1e592/common/src/main/java/net/caffeinemc/mods/sodium/client/gl/arena/staging/MappedStagingBuffer.java:90) 也提供了映射环、GPU copy、fence 归还的上传设计参考。但本次单次网格上传 CPU 抽样约 0.366 ms，而批次上传调用中的 `nglBufferData` 自身占 2.113%；应区分两种上传，先量化需要优化的缓冲路径。

**GPU 候选：更早筛背面，之后再考虑更深层级选择**

VSS 顶点程序在材质/UV/颜色与形变处理后，才于 [surfaceVisible / constantFacePlane](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java:685) 处理背面。Sodium 在 CPU 命令生成中筛方向；Voxy 的 [cmdgen.comp](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/resources/assets/voxy/shaders/lod/gl46/cmdgen.comp:86) 在 GPU 生成六方向与双面命令。

较小的实验是对确认具有常量平面的单面几何，在材质表读取前提前返回。较大的实验是记录 meshlet 方向，在命令级跳过不可见方向，进而减少顶点调用。交叉植物、模型、坡面、morph、水面/岸线及混合绕序必须保留原语义；不能用普通 OpenGL 背面剔除替代当前全部判断。该方案有 GPU 潜力，但尚未实施或计时。

Voxy 的 [traversal_dev.comp](C:/Users/Administrator/Desktop/voxy-263-git2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp:124) 按屏幕尺寸决定是否访问子级，子级未就绪时保守使用已有层级。VSS 的 [coveringTileAtDetail](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTileManager.java:1876) 普通归属通常选择已就绪最细层级，望远镜数据有特殊回退处理。

VSS 已有 1.5 像素误差的 display LOD，以及规则面约 20 字节、通用面 32/48 字节的格式，不应把已有能力列为新增收益。显示变体只针对一定规模的网格，mixed ownership、scope 和 morph 条件也会保守使用源几何。第三份暂停快照含 2,698 个绘制条目、10,363,210 个选中面，全部 displayTier=0；2,330 个条目没有显示变体，计 7,575,790 面，其中包含接缝。快照没有 hasMorph 条目，因此“结束的 morph 仍阻止 LOD”在这份数据中的候选收益为零。

真正按屏幕选择普通父级需要一起规划地形、features、水面和接缝，检查父级准备与交接；不能直接将最细归属换成粗父级。这比前三项的范围更大，应先按真实场景的几何类别与误差量化收益，再决定是否施工。

**次要发现：自适应 Hi-Z 的收益比较可能被加载更新反复重启**

75 份计数快照全部为 `measuring-hiz`，`directGpuMs=0.0,culledGpuMs=0.0`；与 Spark 对齐的 19 份也全部如此。因此，本窗口没有观察到一组已经完成的直绘/剔除收益比较，不能说策略已为该加载场景选出了最佳模式。

[PredictionOcclusionPolicy.choose](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionOcclusionPolicy.java:19) 在任意驻留/覆盖 revision 变化、相机移动超过 8 方块或矩阵差超过阈值时重启。它需要先稳定四帧，然后每四帧取样，每种模式八个样本；加载更新使比较难以稳定完成。本轮没有记录 epoch/reset 计数，不能报告精确重启次数，也不能排除其他导致样本未完成的原因。

适合补测按实际可见工作量变化判断性能比较是否失效，避免无关的后台驻留更新打断比较。性能比较的有效性与当前帧可见性有效性应分开，仍每帧执行需要的遮挡判断，不复用旧画面来裁掉新几何。

**下一轮实施与保留标准**

| 顺序 | 实验 | 验收重点 |
|---|---|---|
| 1 | 按可见接缝依赖调整保护与上传准入 | 驻留和直绘下降；转身、Voxy 撤销、粗级/植被/水面交接完整；不出现回收恢复循环 |
| 2 | 小槽持久映射异步读回 | 同 probe 的 CPU 成本下降，GPU copy 不抵消收益；busy 时继续绘制 |
| 3 | 接缝空间候选与 PreparedPass 局部更新 | 准备耗时、精确视锥次数、编码量和尾帧下降；透明顺序正确 |
| 4 | 保守提前背面处理 / 方向命令 | GPU 不透明区间下降，颜色深度和特殊面语义一致 |
| 5 | 可见 workload 的收益比较 / 屏幕父级选择 | 完成可靠对照；画质误差、覆盖、features 和接缝一起验收 |

遵循用户已有要求：候选需在相同输入或同场景 A/B 中有明确收益才保留，负收益回退。既有正收益架构改动继续保留；本轮没有加入未经测量的生产候选。报告与机器汇总已同步 Forge 和 NeoForge 两仓库。

源码参考固定到 Voxy 263 commit `b0207f6fcc0a55fa6b33f5c916932e8560a71845` 与 Sodium `mc1.21.1-0.8.12-beta.2` commit `ec15afd7c85f850552577739eb30858faea1e592`。VSS 工作树含此前未提交改动，NeoForge base 为 `1c9fd92c25db18605ba62d3ecbaf703248953f73`，Forge base 为 `8c6e8f6d74f6b84f27cbe0225060fb94c39345c7`；安装包身份以上述 SHA256 为准。

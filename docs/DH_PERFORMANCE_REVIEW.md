# DH 性能设计对照与 VSS 预测 LOD 掉帧调查

日期：2026-09-19。性质：源码调查与优化排序，不是玩家现场 CPU/GPU 性能测量。

## 范围和结论

玩家反馈开启预测后损失约 1/3–2/3 帧率，加载期间及看似加载完成后都存在，涉及 NVIDIA 和部分 AMD。持续渲染开销应排在采样优化之前；尚不能由显卡品牌或 FPS 单独确定 CPU/GPU 瓶颈。视觉覆盖完成也不保证全部后台细化已停止。

本机此次没有运行中的 Minecraft 可供采样。检查了 VSS 当前工作树（包括上一次实体闪烁状态隔离修复），以及 DH 主仓库 `c2339b1` 和该提交锁定的 core 子模块 `dc62dea`。这是调查时的开发源码，不等同于所有 DH 发布版。

例如同样从 120 FPS 开始，降低 1/3 是 80 FPS，帧时间从 8.33 ms 增至 12.5 ms；降低 2/3 是 40 FPS，帧时间增至 25 ms。这说明应衡量新增的每帧毫秒，而不能从采样吞吐提升推算 FPS。

## DH 值得借鉴的具体实现

1. **同一帧复用绘制列表。** `LodRenderer` 只在 firstPass 调用 `buildRenderList`；延后透明通道复用排序/剔除结果。`RenderBufferHandler` 复用临时列表和矩阵，按视锥筛选已启用节点。
2. **提交前选好父子层级。** `LodQuadTree` 在子节点全部可绘制时停用父节点，否则保留可用父节点并递归停用子节点。这减少层级重叠；代价是局部细化需要等待同组子节点。VSS 不能照搬这种等待策略，否则可能恢复此前的“一圈一圈等待”观感。
3. **远处材质更简单。** 当前 DH 已支持近处及放大后的纹理，不能说它完全没有纹理。其配置明确远处使用平面颜色，并限制纹理的最高 LOD 层级，默认 maxTexturedLodDetailLevel=2。普通着色器在顶点阶段采样光照贴图；简单远处片元不需要完整近处材质逻辑。实际 Iris 光影补丁仍可能增加成本。
4. **合并几何并启用实体地形背面剔除。** `LodQuadBuilder.mergeQuads` 合并相容面，按方向/透明性组织数据；普通 GL 地形流程开启 face culling。VSS 已有合并四边形和面组筛选，不应将这些重复宣传为新方案。双面植被、洞穴面方向需要单独处理。
5. **渲染线程任务集中限时。** `RenderThreadTaskHandler` 根据帧率上限分配软预算，并用最近任务时长估算是否还能开始下一任务。单个大任务依然可能超时，不是硬实时保证；不要直接照抄其“半帧”预算。
6. **后台统一优先级与占空比。** `ThreadPoolUtil` 将多个阶段接到 PriorityTaskPicker，按队列积压/移动速度等暂停部分工作；RateLimitedThreadPoolExecutor 可在任务间按运行时间比例让出资源。DH 默认均衡档也是约一半逻辑线程且运行比例 1.0，并不是默认所有线程只运行半程。
7. **持久化和对象复用。** 压缩数据存储、线程本地 quad builder、复用数组/缓冲池减少重复加载与分配。这主要帮助加载及 GC，不能单独解释加载完成后的每帧绘制成本。

## VSS 已确认的源码问题与需要计时的候选项

| 项目 | 源码证据 | 意义及边界 |
| --- | --- | --- |
| 光影双通道重复准备 | PredictionIrisBridge 的 opaque / translucent 都调用 renderIris → renderStage；后者遍历 tile、调用 visible 排序、准备覆盖/接缝 | 复用 meshSnapshot 不等于复用完整绘制列表。透明通道确实重复了准备流程；覆盖命中时不重复求全部单元，不能说每次重算所有网格 |
| 覆盖预算不能约束所有失效工作 | PredictionRenderer 先判断 ownershipCurrent，else-if 中又允许 !ownershipCurrent，因此所有未命中均立即求值 | MAX_COVERAGE_RESOLVES_PER_FRAME=32 不能限制这些失效任务。原意是保持父子交接同帧正确；不能直接截断而造成空洞/重叠 |
| 接缝工作在渲染线程且绕过上传预算 | appendSeams 调用两个 seam.update，再 ensureSeams / updateCoverage；只有普通 tile.ensureMesh 记入 uploadBudget | 接缝有缓存，并非静止时每帧完整重建；视野集合、覆盖或边界改变时仍可在一帧构建及上传多块 |
| GPU 接收了最终被遮掉的几何 | resolveCoverage 只选择预测 tile 归属；真实覆盖主要由片元深度和遮罩决定 | 数据存在不代表画面已有真实深度，不能按“缓存有列”直接删预测。可以只在有可靠可见性证据时提前剔除，并减少父子部分覆盖的无效提交 |
| 片元路径较重 | PredictionTerrainProgram 含真实深度重建、覆盖掩码、原版 3D mask、Yield、材质/透明裁切，再调用光影包片元输出 | 远处材质已有 detailWeight 降级，并非所有像素都完整采样材质；但平均色路径仍可能查材质表，cutout 仍可能读取 alpha。应按远处不透明地形/精细材质/双面植被/水面分路径 |
| 地形全局关闭背面剔除 | renderStage disableCull；虽然有 CPU 面组过滤，不能替代每个三角面的背面剔除 | 候选 GPU 优化，必须先统一绕序，并保留花草/双面面片及洞口回归，不能直接全局 enableCull |
| 光影状态与深度复制的固定成本 | State 查询多个纹理目标、sampler 和 MRT blend，回调前后恢复；不透明阶段复制一张全分辨率深度 | 需要驱动与 GPU 计时；glGet 不必然每次都等待 GPU。先缩小到确实会改动的状态、缓存稳定元数据，不能删除刚修好的状态隔离 |
| 帧率熔断过于宽松 | medium 低于 30 FPS 才减载，low 低于 45 FPS 才暂停普通细化，high 不熔断；部分优先工作豁免 | 120 降到 50 FPS 在 medium 下仍不会触发。应该按目标帧时间及 CPU 压力调节，而非只看是否低于 30；GPU 瓶颈不能靠降后台线程解决 |

普通无光影预测着色器还显式写 gl_FragDepth，可能限制早期深度优化；Iris 分支本身不写该值，不能把这一点用于解释全部光影问题。

## 推荐实施顺序

### P0：先建立帧时间证据

- CPU 分段记录：快照/列表准备、覆盖求值、接缝构建、GPU 上传提交、状态保存恢复、draw 提交。
- GPU 使用异步 timer query 环形队列，延后读取已完成结果；分别记录不透明预测、透明预测及深度复制。不要用 glFinish 或同步读回制造新的卡顿。
- 同时记录 draw call、提交 quad 数、接缝重建数、上传字节、后台 active/pending；避免误把“看起来加载好了”当作没有后台任务。
- 同位置、同朝向和同分辨率比较关闭预测/开启并预热完成/移动细化三种状态。光影单独开关；至少 NVIDIA 一套、AMD 一套。记录帧时间中位数及 p95/p99，不仅平均 FPS。

### P1：优先删除不影响画面的重复准备

- 每个实际 frame + view + dimension + mesh residency revision 只准备一次不可变 FramePlan，不透明/透明通道复用。不能仅按帧号缓存，需考虑多视图、阴影和切维度。
- 接缝按地形/归属/真实边界版本触发；后台构建，主线程只取已完成结果。已有可用接缝继续保留，更新与对应地形原子发布。
- 所有几何及掩码上传纳入统一预算；拆分超大任务，避免当前首个 tile 必须放行导致预算被突破。
- 将必须同帧发布的父子归属变化作为一致性组，其余失效分帧。保持之前修好的空洞、长墙及真实 LOD 交接行为。

### P2：减少每帧 GPU 成本

- 建立远处不透明地形简化路径，近处/望远镜保留材质；水面和透明植被独立处理。
- 可靠覆盖和层级选择尽量前移至提交前，减少部分覆盖父网格的无效面。对没有真实深度的区域保留兜底。
- 修正并验证绕序后，对单面地形开启背面剔除；花草等双面内容使用独立批次。
- 数据确认 draw call 成为瓶颈后，再合并小 tile/接缝提交；不能仅换用某个 GL API 就承诺解决。

### P3：再处理加载期的 CPU 竞争

- 用户在实施前明确要求优先解决渲染性能，因此本轮不调整帧率熔断。后台竞争是否仍需处理，依据分段实测决定。
- 借鉴 DH 的共享资源预算和任务间占空比，避免预测、预生成与其它世界生成线程各自取满。
- 继续改 Rust 采样属于这一阶段，不能用采样吞吐测试替代持续渲染 FPS 验证。

## 来源（固定源码快照）

- [同帧可见列表复用](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/render/renderer/LodRenderer.java#L181)
- [可见性、临时数组复用](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/render/RenderBufferHandler.java#L68)
- [父子节点替换](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java#L679)
- [渲染任务软预算](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/render/RenderThreadTaskHandler.java#L127)
- [任务占空比](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/util/threading/RateLimitedThreadPoolExecutor.java#L77)
- [线程预设实际默认值](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/config/eventHandlers/presets/ThreadPresetConfigEventHandler.java#L44)
- [远处纹理限制](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java#L660)
- [合并四边形](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/dc62dea71dc211a2a0faa18d35e73053daa98de4/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder.java#L257)
- [普通 GL 面剔除](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/c2339b1e849e919b8a83f3a8dccac87c16e22886/common/src/main/java/com/seibel/distanthorizons/common/render/openGl/GlDhMetaRenderer.java#L176)

调查阶段未改动生产渲染代码。后续已按用户要求实施第一批渲染优化，且不调整帧率熔断；范围、测试与未完成项见 [预测渲染性能实现记录](prediction-render-performance.md)。

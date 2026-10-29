# vss engine render next research 20261014

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**Unity／虚幻可借鉴的下一轮渲染优化（2026-10-14）**

本次继续检查两个仓库当前源码，并直接核对 Unity／Epic 官方文档。推荐先验证“真正按变更记录更新常驻场景”和“Iris 不透明合批”，再验证有限的植被 shader 专门化。重复模型共享及远景树冠表示属于独立研究，不能从引擎演示直接推算 VSS 帧率。

本轮是源码与文档调查，没有修改生产代码或运行新的性能基准。以下严格区分源码已确认的行为、以往实验结果和待验证的方案。Unity 文档使用 6.0／6000.0；本次 Epic 返回的相关页面标识为 Unreal Engine 5.8，Nanite Foliage 页面明确标为 Experimental。

**已有能力与仍缺少的环节**

当前已有静态命令重放、相机／时间与静态数据分离、局部 mesh／GPU revision、当前帧 Hi-Z、meshlet／工作组 bounds、按收益启用的 GPU 候选选择、前后距离桶、地形显示 LOD、无损扫描合并、矩形／十字面紧凑格式、水面专用页及驻留预算。这些不是本轮新发现的优化。

上一轮正式整合已经复用未变的常驻候选对象，但全局版本变化时仍扫描候选元数据；PreparedPass 与整套命令重放也仍有全局版本门槛。普通渲染和 Iris 的提交方式尚不相同。

| 优先级 | 参考技术 | VSS 当前缺口 | 首要改善对象 | 判断 |
| --- | --- | --- | --- | --- |
| 1 | Unity DOTS／BRG 常驻数据，UE retained Mesh Draw Commands／GPUScene | 局部更新仍可能触发列表扫描与全计划验证 | 更新期间的渲染线程 CPU、分配、上传 | 下一轮首选，延续已验证方向 |
| 1（使用光影时） | UE 相同绑定的 draw 合并，Unity BRG 批次描述 | Iris 仍逐瓦片绑定／提交，未接入普通路径的 MDI 重放 | 光影路径 CPU 提交与驱动调用 | 明确源码缺口，收益待测 |
| 2 | Unity 静态 shader 分支／有限变体 | 通用 shader 同时处理地形、水、接缝与植被；植被短路径仍是动态分支 | 顶点／片元指令及可能的寄存器压力 | 值得小规模 A/B，增加批次可能抵消收益 |
| 3 | Unity mesh instancing、UE Assemblies 部件共享 | 草花／模型最终展开成瓦片面记录，未共享整株 GPU 几何 | 几何驻留、上传、部分顶点读取 | 先统计可复用比例；逐面实例化已有反例 |
| 4 | UE Impostor Baker／Nanite Foliage 聚合表示 | 远处叶片仍有大量分离面；粗二值树冠代理质量不合格 | 持续的远景森林 GPU 成本 | 两条替代研究路线，先做质量准入 |
| 辅助 | Unity alpha coverage mipmaps；Unity／UE Render Graph | 覆盖率、空 pass 和资源依赖可进一步核查 | 远景稳定性、无效拷贝／状态调用 | 不作为固定 FPS 收益承诺 |

**1．把局部版本检查推进到真正的增量场景更新**

Unity DOTS Instancing 明确把实例数据持久留在 GraphicsBuffer，只在实例实际变化时设置数据，并把实例数据准备与 draw 准备分开。UE Mesh Drawing Pipeline 则缓存静态 FMeshBatch／FMeshDrawCommand，要求持久绑定与正确的失效依赖；其 GPUScene 用 PrimitiveID 索引场景级数据。

当前对应源码：

- `PredictionGpuSelectionInput.update` 的快速返回之后仍遍历 recordCount，重组 selected 并比较候选列表身份。局部记录复用减少对象重建，没有消除无关全局 epoch 下的扫描。
- `PredictionRenderer.PreparedPass.prepare` 先检查全局 GPU／packed epoch；失效后仍遍历 draws，虽然各条目可局部复用。
- `PredictionIndirectBatch.replayPass` 仍以 wholeVersion、wholeEpoch、整个 residency／ownership revision 判断整计划命中。后续已有局部 item／region 复用，但整计划失效会重新走验证和组织路径。

下一步应沿用现有 Scene 的 changes、上传交接和覆盖变更，发布带局部 revision 的变更记录，为活跃记录分配稳定槽位；只更新变化记录及受影响的 region／page batch。相机、morph 时间继续通过帧参数更新。视锥／遮挡候选测试仍需执行，不能把静态提交表误解成永久可见列表。

实现时需要槽位代次或同等防护，避免删除后复用 ID 却消费旧命令；geometry、GPU slice、mask 和接缝版本仍按现有完整交接发布。Voxy 接管与撤销必须立即改变归属，不能只延长旧计划寿命。CPU 只发布不可变变更，OpenGL 更新仍由渲染线程执行；不能直接借用 UE 的 RHI 线程模型。

验证应覆盖 4,096／8,192 条记录的 0、1、1%、100% 变化，另含重排、移除、GPU 回收、资源重载及 Voxy 覆盖撤销。分别记录扫描条数、更新条数、CPU 准备／提交 p50/p95、分配量、上传字节和包含 GPU 完成的总时间。稠密变更允许直接重建，不能为局部更新收益牺牲全量替换。上一轮特意改变无关全局 epoch 的结果只证明该失效压力下有优化空间，不代表当前每个游戏帧都发生它。

**2．让 Iris 路径也能复用批次描述**

UE 文档强调不同 per-draw 绑定及 loose shader 参数会阻止 draw 合并；相同绑定配合场景索引可合并。这个原则适合检查 VSS 的两条路径，而不要求移植 UE。

`PredictionRenderer.drawIris` 已复用 PreparedPass，但仍对每个 PreparedDraw 执行 bindTerrain、bindYield、setTile、setMorph、submitRanges。`PredictionTerrainProgram.createIris` 使用 irisVertex／irisFragment，没有组合普通 MDI 的 batchVertex／batchFragment。普通路径已有 replayPass 和 arena page 合批，因此不能把两条路径描述成已经拥有相同提交成本。

建议先为 Iris 不透明 pass 增加与当前 Voxy shader adapter 相容的批次记录，用索引读取 tile／mask／morph 数据；同一 page、兼容 shader 绑定的记录合批。第一步保留现有几何和可见集合，不同时改遮挡算法。水面的排序与深度契约单独处理。

另一个源码机会是 `PredictionIrisBridge.State`：每个 texture unit 保存五种 texture target 和 sampler，恢复时逐个重绑；draw buffer 的 blend 状态也逐项保存。可研究由 pass 声明实际修改的绑定，只保存／恢复这些状态。这个调用数量是源码事实，尚未测得它占用多少毫秒，不能把 glGet 一律称为 GPU 阻塞。

启用 MDI 后还必须覆盖新增 SSBO、indirect／parameter buffer 等状态。外部 Iris／Voxy 会改变 GL 状态，所以不能简单删掉恢复或跨 pass 盲信 VSS 的缓存。桥接接口未知时保留直绘回退。

离线验证使用实际适配的 Voxy 0.2.14／0.2.15 契约、普通／反向深度、clip depth 模式、TAA 偏移、多 viewport、资源重载、morph、混合归属及撤销；检查颜色、深度和 GL 状态，并分别统计直绘次数、MDI 次数、uniform／绑定调用及完整提交时间。离线 adapter 测试不能替代任意实际 shader pack 的兼容测试。

**3．只为差别足够大的材质类别编译专用 shader**

Unity 官方区分动态分支和静态 shader variants：静态变体可缩小编译后的程序；动态分支更适合不太悬殊的分支。变体组合会增长编译成本，因此不宜为每种树叶／配置都生成独立程序。

VSS 已有 `renderFeature` 短路径、alpha 检查后才读取光照，也已避免地形 shader 写 gl_FragDepth。这些优化不能再算一遍。当前短路径要求 footprint 不超过 0.0625，并排除水、接缝、地形侧壁和近似显示面；微小远景 cutout 继续通用路径。以前“统一提前采 alpha 并保留样本”的实验更慢，已回撤；不能只删除该门槛。

可试验在后台打包时生成有限的范围类别，例如实体地形、alpha-tested 植被、特殊／接缝；对前两类分别编译删除无关逻辑的 shader，水保持独立。目标是让整个批次拥有固定语义，而非继续往同一 shader 添加条件。

仅凭源码不能证明驱动未消除无关逻辑，也不能证明寄存器压力就是当前瓶颈。必须对真实森林、开阔地和小集合做完整 CPU／GPU 对照；计入更多 ranges、program 切换、MDI 批次、构建及驻留成本。近处／远处、原地／转向、普通／Iris 分开测。保持 UV 导数、alpha 阈值、真实深度、覆盖、光照和雾一致。

选择性 depth-only prepass 可作为后续对照变量：它可能减少昂贵片元的重复着色，也会再做一遍顶点、alpha 和归属检查。现有前后距离桶和当前帧深度已经提供遮挡，不能默认整个森林增加 prepass 就更快。强制 early_fragment_tests 还可能让被 discard 的像素先写深度，不能直接套到含透明孔洞／归属 discard 的 shader。

**4．共享整株模型／重复部件，先算盈亏**

Unity instancing 与 UE Nanite Assemblies 都利用重复几何。Epic 也说明传统大量微小实例本身有场景／根 cluster 开销；Assemblies 在远处会合并简化层级，不是永远逐枝叶独立绘制。

VSS 已有 CPU 树生成复用和材质／UV 字典，但草花十字面、竹子模型和其他植被最终仍写入瓦片几何。潜在新方向是保存共享的整株草花／相同 baked model／确实重复的枝叶部件，只让实例保存位置、颜色、光照和归属索引；不能用统一树模板替换原有不同树形。

需要先统计“当前精确合并之后”的重复率，保留已经合并的大矩形和当前 20 字节十字面作为基线。两张十字面目前固定记录合计 40 字节，另计字典／对齐；如果一个新实例及其引用、归属、光照数据已接近或超过这份开销，就没有显存优势。特殊 UV、四角颜色、放置偏移及不同模型不能强行同模板化。

2026-09-22 的逐面实例化实验已经给出反例：4,096 面、128 次提交的顶点阶段 GPU 时间约 0.145 → 0.393 ms。该结果不证明整株共享一定失败，但足以排除原样重做逐面实例化。共享模型也不会自然减少透明片元数，所以它首先是驻留／上传研究，不能当作森林持续低帧的既定解法。

**5．远景森林需要单独的表示研究**

UE 提供两条不同参考路线，不能混称为同一种优化。

一条是已有生产应用的 Impostor Baker。官方描述用多个方向的图像、深度等数据，在运行时选择／混合最接近视角的帧，并支持视差；Fortnite 在关闭 Nanite 时使用上半球 impostor 表示远处树木。它减少顶点，但混合多帧的片元更贵，而且需要纹理、烘焙及失效管理。

对 VSS，可先离线为固定森林小组烘焙多方向颜色／覆盖／深度，再测试上半球深度 impostor，避免只用永远朝向相机的单张树贴图。按组烘焙能保留输入树形，但随机树组可能几乎没有复用，纹理量和运行时构建负担必须统计。地下、近处、望远镜、接缝、morph 和部分 Voxy 归属场景不能直接使用整组替代。

另一条是 Experimental Nanite Foliage。其近像素体素表示、独立体素光栅化、误差选择、前后深度桶和法线统计属于整套系统；简单二值网格不等价。VSS 若参考此方向，需要记录覆盖率／方向分布及误差，验证孔隙、轮廓和深度，再判断是否值得引入新的解码／光栅路径。

上一轮统一 alpha 材质后的 2／4 方块代理，新增覆盖的中位数仍约 22.2%／52.6%，因此没有进入正式包；仅做精确共面叶面聚合又只多减少约 0～3.8%，未通过额外驻留准入。这两项反例继续有效。

两条新表示都应先在历史真实几何上测试多水平角、多俯仰、多个距离、移动／转向连续帧和望远镜，记录新增／丢失覆盖、轮廓、颜色、深度、时序稳定性、构建成本和额外驻留。通过质量门槛后再接入真实 Voxy／父子／接缝事务。普通模式没有完整 TAA 历史，不能直接依赖逐帧随机透明／随机法线来掩盖误差；单次静态 IoU 也不是完整质量判断。

**辅助项与不优先采用的技术**

Unity Preserve Coverage 会按 alpha-test reference 缩放 mip 的 alpha，避免低分辨率 mip 变透明；它可能增加通过测试的像素，首先解决覆盖稳定性。VSS 已用真实 Atlas、textureGrad、mip 边界与 0.1 阈值，SpriteTable 的 NEAREST 是元数据／合成 sprite 表，不能据此声称树叶没有 mipmap。若研究覆盖率，需要基于真实资源包 mip 测量，并只管理 VSS 自己的数据；不直接修改其他渲染器共用的 Minecraft 图集。单层贴图覆盖率也不等于多层树冠的体积覆盖率。

Unity／UE Render Graph 的可借鉴部分是明确 pass 的读写、资源版本和生命周期。`PredictionIrisBridge.draw` 当前在 renderIris 内确认有没有实际 pass draws 之前就复制深度，存在先判断空 pass、再决定私有深度拷贝的机会。场景维护、上传和必要的桥接副作用仍需正确执行。普通路径的水面 before／after depth history 已经只在 deferredWater 时生成，不能重复计为新优化。

有预测提交时，Iris 不透明与透明阶段之间真实深度会改变，两次 snapshotDepth 不能随意合并；直接采样正在写的 depth attachment 也不能替代安全副本。借鉴依赖声明不等于重写完整 Render Graph，更不等于 OpenGL 自动获得 Vulkan／D3D12 的异步计算队列或显式内存别名能力。临时贴图复用不负责消除常驻几何大头。

Unity 的双帧保守遮挡参考是“当前帧或上一帧任一可见则绘制”，不是只按旧深度隐藏新出现的东西。它可能提高可见性稳定性，却多画一些对象并引入额外深度／阶段成本。VSS 当前帧 Hi-Z 已有净收益门槛；在稀疏森林／开阔视角下，不把时间复用当作第一优先级。任何时序实验都需验证转身、传送和新增／撤销覆盖不会漏画。

已有深层级遍历、额外粗节点、Mesh Shader 等实验未证明稳定净收益。软件光栅／visibility buffer、Lumen、VSM、完整 Nanite／Render Graph 重构也不进入当前优先队列：它们涉及新的渲染架构或 VSS 当前并不承担的工作，不能解决问题就默认引入。

**推荐的下一轮实验顺序**

1. 常驻记录的稀疏变更队列／稳定槽位，对比现有正式输入缓存及整提交路径。
2. 使用光影时独立验证 Iris 不透明 MDI 与按需状态保护；无光影场景不预期从此项受益。
3. 有限的植被 shader 专门化，对比当前短路径，测完整代价，失败即保留原路径。
4. 统计共享模型可复用率和每实例实际字节，只有账面有空间才做 GPU 原型。
5. 两种远景森林表示先独立通过质量、构建和内存准入，再决定是否实施。

所有性能判定同时包含 CPU 准备／提交、GPU 时间和完整提交总时间，测试交替 A/B、预热、记录分位数；GPU 测试在两仓库间串行运行。新方案与既有控制场景都要测，不把少了命令、少了字节或更低顶点数直接换算成游戏 FPS。当前没有重新采样客户端，所以尚不能在这些剩余成本间给出实机毫秒占比。

**官方资料**

- [Unity GPU Resident Drawer](https://docs.unity3d.com/6000.0/Documentation/Manual/urp/gpu-resident-drawer.html)
- [Unity DOTS Instancing](https://docs.unity3d.com/6000.0/Documentation/Manual/dots-instancing-shaders.html)
- [Unity BatchRendererGroup 工作方式](https://docs.unity3d.com/6000.0/Documentation/Manual/batch-renderer-group-how.html)
- [Unity shader 动态分支与变体](https://docs.unity3d.com/6000.0/Documentation/Manual/shader-conditionals.html)
- [Unity coverage-preserving alpha mipmapping](https://docs.unity3d.com/6000.0/Documentation/ScriptReference/TextureImporterSettings-mipMapsPreserveCoverage.html)
- [Unity GPU occlusion culling](https://docs.unity3d.com/6000.0/Documentation/Manual/urp/gpu-culling.html)
- [Unity Render Graph](https://docs.unity3d.com/6000.0/Documentation/Manual/urp/render-graph-introduction.html)
- [UE Mesh Drawing Pipeline／GPUScene](https://dev.epicgames.com/documentation/en-us/unreal-engine/mesh-drawing-pipeline-in-unreal-engine)
- [UE Nanite Assemblies](https://dev.epicgames.com/documentation/en-us/unreal-engine/nanite-assemblies)
- [UE Nanite Foliage，Experimental](https://dev.epicgames.com/documentation/en-us/unreal-engine/nanite-foliage)
- [UE Impostor Baker](https://dev.epicgames.com/documentation/en-us/unreal-engine/impostor-baker-plugin-in-unreal-engine)
- [UE Render Dependency Graph](https://dev.epicgames.com/documentation/en-us/unreal-engine/render-dependency-graph-in-unreal-engine)

同名 sources.json 保存抓取时间、上述官方页面的文本／HTML SHA256，以及两个仓库当前相关源码 SHA256 和定位行号。本报告与来源索引同步两个仓库；没有从引擎文档复制生产代码。

后续实施记录：本报告保留调查当时的判断。资源变更日志、Iris 不透明 MDI 和植被专用 shader 的 A/B、取舍及新 JAR 已记录在 [实施与测试结果](../../vss-engine-render-next-results-20261014.md)。

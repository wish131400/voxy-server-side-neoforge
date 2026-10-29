# vss camera turn voxy dh audit 20261014

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 转向开销与 Voxy、Distant Horizons 源码对照（2026-10-14）**

本轮按用户要求只检查源码，没有录制 JFR、Spark、GPU 时间或连续帧，也没有启动、关闭或替换客户端。结论针对两个仓库的当前工作树；历史采样仅作为调查背景，不用于认定当前版本的耗时。

可以确认：VSS 已经有空间索引、稳定距离排序、局部归属缓存、页面命令复用和 GPU meshlet 剔除，但视角变化仍可能把可见成员的小变化传递成整份列表整理、重新分桶和页面命令编码。Hi-Z 模式另有按距离桶反复重建深度金字塔的固定成本。源码能够说明这些工作何时触发，不能说明当前掉帧主要来自 CPU、GPU 或它们各占多少毫秒。

**检查对象与版本**

| 对象 | 本轮核对的版本 |
| --- | --- |
| NeoForge VSS | 当前工作树；HEAD 为 1c9fd92c25db18605ba62d3ecbaf703248953f73，包含既有未提交改动 |
| Forge VSS | 当前工作树；HEAD 为 8c6e8f6d74f6b84f27cbe0225060fb94c39345c7，包含既有未提交改动 |
| 当前安装 VSS | 与 lib/near-detail-20261013/vss-0.3.5-neoforge-1.21.1.jar 的 SHA256 相同：35bc9f8f5b646707d4d4f95bdb1ce3a44cb1e436d9f2c900e5bc50011b42e2ff |
| Voxy 阅读源码 | C:/Users/Administrator/Desktop/voxy-263-git2，commit b0207f6fcc0a55fa6b33f5c916932e8560a71845，0.2.20-beta |
| 实际安装 Voxy | voxy-0.2.15-beta.jar；直接核对 JAR 内的层级遍历、屏幕尺寸选择、透明排序 shader，并用 javap 核对 GPU 命令生成和 indirect-count 提交 |
| DH 阅读源码 | C:/Users/Administrator/.codex/tmp/vss-dh-merge-review-20261012/upstream-dh-core，commit 285de488b10995951529a31ca1df2c92628e5397 |

Voxy 研究源码晚于安装包，但本报告用于比较的关键机制已在实际 0.2.15 包中验证。DH 是固定提交的源码对照，本轮没有运行 DH，也没有做相同画面、相同设置的性能对比。安装 VSS 的包身份核验不能代替当前工作树与该 JAR 的逐方法一致性验证。

本报告的页面命令与多次 Hi-Z 重建分析针对普通 MDI 渲染路径。Iris 光影专用路径 drawIris（PredictionRenderer.java:1877）仍逐条绑定瓦片、设置参数并提交范围，没有走这里的 MDI/Hi-Z 批次路径；可见列表、PreparedPass 和客户端 tick 问题仍适用。本轮没有确认实际启用哪条光影路径，安装 Iris 本身不等于当前启用光影。

下面 VSS 行号以 NeoForge 仓库为准。Forge 的可见列表、分桶、页面命令和遮挡策略算法相同；FramePlan 的成员位置、TileManager 的移动节奏和平台调用有差别，两仓库不应整文件互相覆盖。随附 JSON 保存本轮关键文件的内容哈希和上游提交。

**三个项目如何处理视角变化**

| 环节 | 当前 VSS | Voxy | DH |
| --- | --- | --- | --- |
| 层级与需求管理 | 后台生成几何；客户端 tick 仍执行需求分类、优先级刷新和调度 | CPU 更新节点与资源，GPU 遍历层级并按屏幕尺寸选父子层 | 独立客户端管理任务更新四叉树、启用节点和异步缓冲 |
| 每帧可见性 | CPU 空间树粗筛及瓦片视锥选择，GPU 再做 meshlet 方向、视锥与 Hi-Z 判断 | GPU 层级遍历结合视锥与 Hi-Z，后续构建绘制命令 | 渲染线程逐帧筛选已准备好的渲染节点并排序 |
| 绘制命令 | 缓存 page/bucket/part 中的有序条目；成员变化可能重编码该段 | GPU 构建命令与数量，MDIC 路径采用 glMultiDrawElementsIndirectCountARB | 复用准备好的 LodBufferContainer，渲染线程整理可见容器列表 |
| 透明内容 | 专用水页；保持现有远近顺序，纯转向且成员不变可复用 | GPU 进行 section 的三维曼哈顿距离分桶 | 可见容器列表按与四叉树中心的 XZ 曼哈顿距离排列 |
| 几何表示 | 有真实显示 LOD、植被档位和紧凑编码，但仍要处理接缝、混合归属、morph 和特殊模型 | 统一 section 几何存储和紧凑面记录，选择合适层级的网格 | 将完整数据转换为按细节等级限制竖向片段数量的列状渲染数据 |

Voxy 也有 CPU 节点维护、深度处理、多个 GPU dispatch 和绘制阶段；DH 也有逐帧 CPU 剔除与排序。差别是动态视角是否迫使 CPU 重组静态提交数据，以及选中后实际绘制的几何量。

**确认的转向路径一：可见成员变化传播到分桶和命令段**

1. PredictionFramePlan.getStable（PredictionFramePlan.java:26）要求相机、modelView 和 projection 都相同。转向时整体帧计划失配是必要行为，不能沿用旧视角的可见性。
2. Scene.prepare 使用 PredictionSpatialIndex 和接缝 bounds 索引筛掉整片区域（PredictionRenderer.java:1200）。在 stableOwnership 成立时，已存在的归属面掩码继续复用，不会因为转向就清空所有归属。
3. PredictionVisiblePlan.selectStable（PredictionVisiblePlan.java:121）按当前视锥更新成员。纯转向不会把全部条目重新计算距离；成员变化后仍会构造完整的新列表（:168）。
4. PreparedPass.prepare 的快速判断要求 source == draws（PredictionRenderer.java:180）。新列表会进入准备路径，重新整理 entries、ranges 和统计。单条网格的范围缓存已经存在，范围不变时不再逐面重算（:225-260），但列表级工作仍需遍历可见集合。
5. 不透明桶数量由当前列表长度决定，最多 8 桶；桶号是 index * bucketCount / draws.size()（PredictionOpaqueBatches.java:52-65）。因此视野边缘少量条目进出，就可能挪动桶边界，让其他仍可见条目换桶。
6. 页面缓存键为 page/bucket/part（PredictionIndirectBatch.java:591）。段内条目数量、顺序或范围不匹配时，重新编码该段；首个不匹配之前已验证的前缀也会重新编码（:445-467）。

这不是每次转向都重建所有网格，也不是全局 GPU 驻留版本一变就清空所有命令。现有局部缓存有效，但稳定性仍依赖当前可见列表的组成和位置。

应优先把不透明几何的静态记录绑定到稳定空间区域或常驻槽位，保持近景优先的粗排序；当前视角只更新可见选择。CPU 可管理粗区域，GPU 逐 meshlet 选择。水面的排序约束继续独立维护。

**确认的转向路径二：暂时离开视野也会丢掉部分提交缓存**

PreparedPass 在准备完成后交换 cachedEntries 和 nextEntries（PredictionRenderer.java:298），只保留本次候选中的准备条目。转出视野再转回来时，部分范围条目需要重新建立。

PlanCache.finishFrame（PredictionIndirectBatch.java:605）在当帧关闭并移除未使用的命令段，包括其 GPU 命令缓冲和条目引用。预测几何可能仍在 GPU 上，但对应提交缓存已经退休，转回时需要重新准备。

可考虑保留少量近期使用的命令段，设置明确的容量、字节预算和淘汰期限。页释放、网格替换、归属变化、材质变化时仍按版本验证或失效。不能无限保留 Draw、GPU tile 和 buffer 引用来提高命中率，否则会重新放大内存和显存压力。

**确认的 GPU 成本：Hi-Z 每个距离桶重建整条 mip 链**

开启当前剔除模式时，nextOcclusionBucket（PredictionIndirectBatch.java:185）先提交较近的桶，再调用 occlusion.rebuild。完整计划复用路径跨桶时也会重建（:205-209），所以静态命令复用不会消除这项成本。

PredictionOcclusionCuller.rebuild（PredictionOcclusionCuller.java:178）从当前深度生成完整金字塔，每一级执行 compute dispatch 和内存屏障（:195-201）。普通不透明路径最多 8 个桶，因此一帧内可能重建 8 次；关闭该剔除模式时没有这项重建。重建在帧内发生，并不是复用旧帧深度。

设计目的是让刚画出的预测近景继续遮挡远景，具有实际作用。它也使整条渲染路径更串行：绘制近桶、重建金字塔、过滤下一桶、再绘制。转向后的可见内容不同，额外遮挡收益也可能不同。

本轮读取的 Voxy primary path 在 innerPrimaryWork 内调用一次 buildMipChain，再进行 GPU 层级遍历（AbstractRenderPipeline.java:200）；其完整管线另有不透明、命令生成和 temporal 等工作，不能据此宣称 Voxy 每个屏幕帧只有一个阶段。实际安装 0.2.15 的字节码也包含这条调用链。

下一步适合比较“少量固定阶段更新 Hi-Z”和“当前每桶更新”的收益，或仅在近景深度有足够改变时重建。减少重建应使用本帧已有的保守深度：漏掉新遮挡只会多画，不能拿旧视角的遮挡证明剔除新视角内容。

VSS 目前通过把被剔除命令的 count 写为 0 来屏蔽绘制（PredictionOcclusionCuller.java:103-104），仍按完整 commandCount 提交普通 MDI（PredictionIndirectBatch.java:440）。Voxy 在 GPU 生成命令与实际数量，采用 indirect-count 提交。进一步压缩命令流可能有收益，也有 prefix-sum、atomic 和调度成本，应在稳定记录之后再评估，不能直接认定压缩一定更快。

**确认的客户端 tick 工作：网格后台生成不等于准备全部后台化**

NeoForge 的 ClientTickEvent.Post 调用 ClientPredictionState.tick（VSSClientNetworking.java:500-517），后者调用 manager.tick（ClientPredictionState.java:467）。这些客户端 tick 与渲染运行在同一个客户端线程，消耗同一帧的 CPU 时间。

PredictionWorkView.changedFrom（PredictionWorkView.java:74）在相机位移约 8 块、方向累计约 3 度、视锥平面或屏幕比例发生足够变化时要求更新。PredictionTileManager.refreshViewWork（PredictionTileManager.java:454）遍历 desiredKeys、分类 band、整理依赖并发布新的 Map/Set。父子依赖路径已经有缓存，不能说每次都重建全部路径。

完整层级规划目前通常每五个客户端 tick 进行一次（:549）；其他 tick 仍可能因为视角变化刷新需求和调度（:554-560）。这不是每一个渲染帧都规划，也不是另一个“主线程”抢占渲染线程，而是同一线程上渲染之外的任务占用帧预算。

DH 的 DhClientWorld 使用独立 Timer 每 100 ms 调用 clientTick（DhClientWorld.java:86，IDhClientWorld.java:28），进而更新四叉树。enabledSections 双列表用短锁交换（LodQuadTree.java:403-411）；渲染线程取已经准备的节点。可借鉴这种分工，把纯需求分类、依赖整理和优先级计算移到后台，输入应是不可变视角、网格、配置与覆盖快照。Minecraft 可变世界状态和 GPU 资源生命周期仍需正确线程交接。

**策略稳定性：转动期间收益比较可能反复重启**

PreparedPass.workloadRevision 在条目次序、条目数量、网格或实际范围改变时增长（PredictionRenderer.java:259、280、301），并作为遮挡策略比较的工作量 token（:1742）。

PredictionOcclusionPolicy.choose（PredictionOcclusionPolicy.java:19）还检查矩阵差异、相机距离和分辨率。变化达到阈值会清空比较样本并增长 epoch。每种模式需要 8 个有效样本，前 4 帧等待，之后每 4 帧取样一次。理想情况下两种模式合计约 72 个稳定渲染帧，GPU 查询返回延迟还可能延长它。

持续转向和成员进出可能让比较未完成就重启。这是源码支持的风险，不是本轮实测结果。它交替使用直绘和剔除模式做比较，不会为了比较在同一帧绘制两份完整场景；重启也会保留上次胜出模式。

适合把“算法收益是否需要重新比较”和“当前帧可见性是否需要重算”分开。小范围成员变化可按工作量变化程度合并，剧烈转向时保留上次决策，稳定后再重新比较；本帧正确可见性仍更新。

**已经存在、不能再当成缺失功能的优化**

- 空间索引粗筛和接缝 bounds 索引。
- 不透明距离排序桶及纯转动的成员更新。
- 单条网格范围、局部归属和按页面命令缓存。
- 工作组粗 bounds、meshlet 方向、视锥与当前帧 Hi-Z 剔除。
- 水面专用几何页与不透明/水面数据拆分。
- 20 字节规则面 GPU 编码及复杂面回退。
- 显示 LOD、植被档位、屏幕误差和 morph/混合归属保护。

水面 prepare 命中时只因 X/Z 相机位置改变而重新排序（PredictionRenderer.java:187、412）；纯转动且可见成员不变不会触发这个排序。成员变化使 pass 重新准备时仍需整理水面顺序。方向掩码主要依赖相机位置与几何关系，也不能把纯视线旋转直接等同于全量方向范围重算。

DH 的 RenderBufferHandler.buildRenderList 明确在渲染线程逐帧清理复用列表、检查视锥并按距离插入已有缓冲（RenderBufferHandler.java:135-255）。它并非完全依靠 GPU 剔除或从不重建可见列表。更值得学习的是后台节点管理、已准备的粗表示，以及父子覆盖齐备后再交接（LodQuadTree.java:684-700）。

**建议实施顺序与验收条件**

1. 稳定不透明静态记录和空间分组，切断可见列表名次对其他页面命令的影响；给近期提交缓存有界保留。验收应看转向时 encodedItems、regionRebuilds、准备耗时和分配量，而不仅是 cacheHits。
2. 降低 Hi-Z 全金字塔重建频率，同时保留本帧深度正确性；稳定直绘/剔除比较策略。验收应分别看 HZB_REDUCE、GPU_FILTER、INDIRECT_SUBMIT、整段不透明 GPU 时间、重建次数与剔除收益。减少 dispatch 不能以明显增加着色工作为代价。
3. 将纯需求与优先级计算从客户端 tick 分离，发布不可变、带版本的结果；确认快速转向、望远镜、资源刷新和 Voxy 覆盖撤销后仍能立即获得安全回退。
4. 在提交稳定后完善同源粗层表示，减少远处实际选中的几何；复杂植被、透明内容、接缝和混合归属继续按需保护。现有 LOD 已经存在，重点是粗层减少多少实际面和读取量。
5. 若命令处理成本仍明显，再评估 GPU 命令压缩和 indirect-count；若 GPU 层级测试成本成为热点，再增加更深层级。每一步用相应证据决定，不先叠加更复杂的遮挡算法。

本轮交付为源码审查报告和可复核的文件清单，没有修改生产代码、构建新 JAR 或采样当前客户端。不能给出当前每项耗时、FPS 提升幅度，也不能仅凭源码把全部掉帧归因于 CPU。

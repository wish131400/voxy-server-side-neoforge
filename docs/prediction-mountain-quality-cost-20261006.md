> 后续实施：A/B/C/D 的第一阶段已落地，详见 [实施与验证记录](prediction-mountain-implementation-20261006.md)。本报告保留调查时的历史状态。原 build 下采样证据后来被并行构建清理，当前不再存在于下述旧路径；数值属于当时采样，不是改动后的 FPS。

预测 LOD 山体画质与剩余渲染成本审计（2026-10-06）
=================================================

本次交付：客户端运行时结构采样、代码原因、可独立交接的实施任务。没有修改生产源码，没有替换客户端 JAR。以下是待实施方案，不能视为已完成优化或实测收益。

用户已将三项 CPU 工作交给其他对话：PredictionSubmissionCoverage 全局缓存失效、PredictionVisiblePlan 移动重排传播、PredictionIndirectBatch.BatchSlot 字节数组分配。本报告不接管这三项。当前工作区存在大量未提交改动；实施前需与最新代码合并，不能整文件覆盖。

**结论**

当前“远山显粗”和“面向预测更慢”不是一个简单的精度开关问题。稳态下生成与上传已经完成，仍有大量细网格地面及水生植被候选面；现有剔除组空间跨度过大，限制遮挡剔除。同时，远处普通地形会主动退化到概括颜色，LOD 规划的误差指标也尚非真实山体几何误差。提高全局网格密度会加重前两项。

推荐顺序：A 水生植被显示 LOD → B 空间紧凑分组 → C 保形地形合并；D 材质距离可独立实施；E 几何误差规划和 F 混合压缩格式在前述结果稳定后处理。B/F 应各自作为整体改动串行集成，不宜拆给多个对话同时修改 packed mesh 的契约。

**证据与测量边界**

- 工作区 HEAD：`bbc4f3d74bec2b2e1fc1661520d2f493a0a4b232`，不代表未提交代码已进入运行包。
- 本轮运行包为 `vss-0.3.5-neoforge-1.21.1.jar`，SHA-256：`345e67dc552a18184b088e40134ad11d877b2ce96989512a36f5ce90914dd47e`。客户端 PID 49236。
- 证据目录：NeoForge 项目 `build/mountain-audit-20261006/`。`capture/capture.json` 保存相机、配置、队列；`capture/geometry.json` 保存 1611 个 pass 条目；`capture/sprites.json` 将运行时 sprite ID 映射为名称；`geometry-summary.json` 保存汇总。
- 可复算：`python build/mountain-audit-20261006/analyze_geometry.py`；20 秒窗口可由 `build/movement-profile-20261006/analyze_window.py` 分析本目录 `window/`。
- 几何快照为香港时间 01:52:39，相机 `(-4007.7995, 73.6200, -970.0790)`。材质映射随后只读取得；没有资源重载证据，但并非同一帧原子抓取。
- 主线程抓取引用、mask 和元数据耗时 29.66 ms，后续后台解码也有 CPU/分配成本。这次结构抓取不能充当性能基准。下面的 20 秒计时窗口在结构抓取之前，不能混为一段。
- quad 统计来自 CPU prepared ranges，在 Voxy 提交过滤之后、GPU HiZ 和 shader 拒绝之前；不等于最终可见面，也不是 605 万面都通过 GPU 光栅化。
- GPU hidden 统计有 13 帧采样延迟，并且包括该 culler 的视锥外拒绝，不能全部称为遮挡收益。
- 中心射线仅与预测 CPU 原始几何相交，未处理 Voxy/原版深度、透明纹理、背面和 morph。它不是最终像素命中证据。

**本次实际状态**

配置：normal，预测半径 5120 格，fine 半径 1536 格，surface 半径 512 格，2 个后台工作线程，未启用 supersample。normal 的 tile 阈值为 128 像素，即标准 64 格轴对应的 2 像素/cell 参数。

manager 的 pending=0、failedAt=0、meshLimits=0。1611 个 pass 条目全部引用 ready 中同一个 tile；有 target 的 1023 个条目没有 targetAxis 大于实际 axis；最年轻的驻留 tile 也已生成约 927 秒。因此，当前快照没有“生成排队/上传尚未追上细化”的证据。这不排除跑图时再次出现延迟。

之前 20.07 秒静止窗口共 1706 帧，约 84.99 FPS，游戏稀疏读数 82～86 FPS。preparedPlans、meshUploads、orderEdits、listPublications、coverageResolves 的增量全部为 0，1706 帧复用计划。采样 PREPARE CPU 约 0.020 ms，OPAQUE CPU 1.220 ms，OPAQUE GPU 区间 6.920 ms，WATER CPU 0.781 ms。阶段存在嵌套，GPU timer 槽有大量 dropped；缺失 WATER/AA GPU 样本不等于零，不应把这些区间相加，也不能由此给出与 Voxy 等场景的成本倍数。

| 不透明候选分类 | quad 数 | 占不透明候选 |
| --- | ---: | ---: |
| 非 cutout、非标记侧壁的朝上面 | 2,106,342 | 34.81% |
| cutout 面 | 1,984,397 | 32.80% |
| 显式标记地形侧壁 | 1,279,809 | 21.15% |
| 朝下面 | 82,606 | 1.37% |
| 其他，含接缝 | 597,136 | 9.87% |
| 合计 | 6,050,290 | 100% |

另外水 pass 为 29,421 面；不透明接缝条目为 56,341 面，占 0.93%。接缝依然有正确性价值，不应为了减面直接删除。

间隔为 1 格的候选面 2,848,704，2 格为 2,276,569，合计 84.71%。64×64 是标准轴大小，并非所有驻留 tile 固定 64×64；本次实际间隔包括 1、2、4、8、16、32、64、128 格。

最近的中心射线候选依次约为 950 格/spacing 1、1297 格/spacing 2、1458 格/spacing 2、1735 格/spacing 2、1910 格/spacing 4。均已 ready；不能据此断定用户所见的那座山恰好是哪一个候选。

**A. 优先交接：水生植被的显示 LOD 与地形网格精度解耦**

证据：按运行时 sprite ID 合计，kelp_plant=1,104,596，kelp=202,442，tall_seagrass_top=195,746，tall_seagrass_bottom=193,628，seagrass=190,168；总计 1,886,580 面，占不透明候选 31.18%。其中 1,642,910 面所在 tile 的最近边也在相机 512 格以外；1,482,036 面来自 spacing 1，404,544 面来自 spacing 2。

原因链：`PredictionTileManager` 对非 surface 构建仍调用 `cachedDisplayForRendering`；`PredictionVegetation` 对 spacing≤8 可复用已生成装饰，spacing≤2 尽量保留 1 格模型。已有 `fineBlocks/reduceBlocks` 会在网格压力下按稳定世界网格稀疏草/海带等，但没有独立的视距/投影尺寸显示等级。surfaceRadius 限制生成需求，不是已缓存植被的硬显示截止。走过的区域继续保留高细节是设计结果，不能用清掉世界生成缓存来“修复”。

修改范围：`PredictionVegetation`、`PredictionVegetationDisplayCache`、`PredictionVegetationRuns`、生成/mesh 身份参数。尽量避免重写 TileManager 调度，让它只传递离散 display tier。

实施要求：

1. 为 aquatic/低矮植物建立独立的 display tier。依据目标投影尺寸及距离等级选择细节，近处和望远镜保真，远处使用稳定稀疏柱/代表簇或经验证的简化表达；不能仅因相机在水上就删除所有水下物体。
2. 复用现有整株 XZ 稀疏能力，保留海带顶部、连续茎和双格海草成对关系。使用稳定世界坐标选择并加等级滞回，避免移动时随机闪烁。
3. 显示 tier 进入 display cache 和 finished mesh identity；原始装饰/真实捕获缓存保持独立。不能把显示降级写回生成数据。
4. 不能仅在片元 shader 中 discard：需要减少实际 mesh/提交 ranges。可考虑后台预构建有限级别显示网格，切换时按预算发布；避免每帧重建、整圈同时重建和取消循环。
5. 水面是半透明层，不能把水面深度直接当作“下方全不可见”的 HiZ 证据；清澈水、浅水、水下和 Iris 都要保持正确。

验收：记录各植被类型、display tier、候选/剔除/实际提交面数；同一冻结场景单项 A/B，分别测顶点和片元负担。远距 aquatic 提交面数应有显著下降，近景/望远镜与冷缓存、暖缓存恢复一致；转身、游泳、潜水、来回跨 tier 不闪烁。31.18% 是候选占比，不能承诺 31.18% FPS 收益。

**B. 复杂整体工程：先改空间分组，再考虑层级剔除树**

证据：去掉水 pass 重复引用后，573 个普通 packed payload 共 32,011 个 meshlet。按 meshlet 数加权，其 AABB 最长水平边/所属 tile span 的均值为 0.93494；所有 573 个 payload 都存在最长水平边≥90% tile 宽度的组。这是最长边指标，不是包围盒面积覆盖率，统计范围也是完整 payload，而非仅已选 ranges。

原因：`PredictionPackedMesh` 先按面向组排序；`PredictionMeshletBounds.QUADS=256` 对连续记录每 256 个构建 AABB。生成顺序包含跨行顶面和按 plane/material 排列的墙，记录相邻不保证空间紧凑。当前 GPU culler 需要整个屏幕包围矩形都被可靠深度盖住；大盒子覆盖天空孔洞或前景时必须保留。因此增加一层树只能减少测试次数，不能自动改善宽叶子的剔除率。

这次 culler 报 testedQuads=6,050,290、hiddenQuads=1,306,019（约 21.59%），说明 HiZ 已经生效，不是没有剔除。不能把剩余 78.41% 全称为浪费。

第一阶段实施：在后台最终 pack 时，在同一 opaque/water 与 face group 内按空间排序或建立空间簇，生成显式 cluster descriptor `{first,count,bounds}`；普通簇用局部格块/Morton 作为候选方案并以紧致度与命令预算选择大小，跨很大区域的 greedy quad 单独处理。不能只改常量 256 为 64，因为条带顺序仍会产生长盒且命令可能暴涨。

依赖与约束：

1. 重排必须同时维持 face ranges、source cell ownership、water offset、sprite 属性及 mesh cache 解码契约。`PredictionOwnershipRuns` 在最终顺序上重建，比较 run 数，避免 GPU 获益被 CPU 归属过滤碎片抵消。
2. 新 cluster 的 first/count 替代 `first/256` 假设；提交时与已选择/归属 ranges 求交，防止多画、漏画或重复。与已分配给其他对话的 BatchSlot/coverage 工作约定接口后再集成。
3. 保留页边界、命令上限与 whole-tile fallback；不要在渲染帧里解码或排序 mesh。水 pass 先维持原有顺序，不能无审查复用 opaque 重排。
4. `putBounds` 对有效 morph 使用全 tile Y 范围作保守扩张，会进一步放大盒子。应在后台计算覆盖实际 morph 目标的局部 bounds；需要同时覆盖所有合法插值。运行时采集的 draw.morph>0 不代表 packed.morph 必然存在，本报告没有给出有效 morph 膨胀的比例。
5. 保留当前帧深度、near-plane 保守可见、NaN/未知深度可见、遮挡物移除立即恢复。近远 bucket 仍须保证先有可信遮挡深度。
6. 不应先引入上一帧全量可见性复用；如随后采用 Voxy 式 temporal 方案，必须有本帧新暴露区域补绘与失效机制。

验收：先离线比较旧/新 AABB 最长边、面积和命令/run 数，再用生产 GPU 路径在山前、山后、天空、贴地、潜水、近裁面和大坐标场景做开关对照。隐藏更多不是唯一标准：画面/深度不能缺面，CPU 提交、上传、compute 和总 GPU 区间均要记录。通过后再加上层 BVH/层级节点，测它是否真正减少遍历成本。

**C. 可独立交接：保留形状与颜色的地形合并**

证据：2,106,342 个 flat-up 面中只有 684,856 个四角颜色/光照相同，约 67.49% 不满足现有 uniform 条件。分类使用 packed 颜色去掉 coverage flag 后比较；这不是“67.49% 可以删掉”的估计，也不能由 quad 统计直接推出可合并比例。

原因：`PredictionQuadMesh.from` 只合并完整、同高度、四角颜色完全相等的顶面；侧壁 `WallKey` 包含上下高度、法线、四角颜色、材质分组、unitLength，同 plane 同 key 才能延伸。`emitColumnWall` 已做邻居暴露判断，并把侧壁分为表层、下层、深层材质。不存在一个安全的“删掉所有侧壁/打开面剔除”开关。

实施分两步：先做严格等价的共面、同材质、共享端点颜色/光照连续的合并。要验证新三角化对内部旧顶点及分片线性场的表达，不能仅比较矩形四个外角。第二步才评估把 tint/light 从几何键中分离到局部属性网格，或引入可测的远距颜色误差阈值；后一方案会影响 shader、cache 和 morph，应单独评审。

验收：保存合并前后覆盖域、边界高度、材质、光照、coverage 归属；悬崖、雪层、洞顶、建筑截面和水边无缝隙。增加“原始面/各原因拒绝合并/合并结果”统计，不能用只测平原的合成大矩形当全场收益。

**D. 可独立交接：材质清晰度与 geometry fineRadius 解耦**

原因确定：`PredictionRenderer.bindMaterialTextures` 把 `DetailDistance` 设成非望远镜时 `max(256, predictionFineDistanceBlocks)`；本配置为 1536。`PredictionTerrainProgram` 超出水平距离后令普通 solid 的 `vMaterialValid=0`，切到已打包概括颜色；cutout、fluid 例外。原 shader 已有 `textureGrad`、footprint 淡出和独立 mip sampler，不能再次将“添加 mipmap”当作新优化。

影响边界：这会损失表面纹理感，不会自行改变山脊几何。没有最终画面深度命中证据，不能将该规则断言为本次准星山体变粗的唯一原因。

方案：把材质最大距离/投影足迹阈值与网格 fineRadius 分开；在仍可解析纹理的表面保留纹理，结合现有 footprint 平滑退出。远处已有不可解析细节继续使用平均颜色。处理好同一个 quad 跨距离阈值的过渡，防止 flat varying 造成三角形突变；保持资源包、cutout alpha、水纹、Iris 和望远镜一致。不要一键取消全部远距材质限制来换取新的 GPU 开销。

验收：冻结几何和相机只切材质路径，录制 1536 附近前后移动、近远截图；同时记录材质 sample 数/fragment GPU 区间。可分辨纹理恢复且不把画质改进伪装成几何细化。

**E. 后续交接：真实几何误差驱动的山体细化**

原因：`PredictionLodPlanner.runtimeNode` 主要按 tileBlocks×投影尺度/距离决定分裂，`PredictionDetailBands` 再选 16/32/64 轴。`PredictionRelief` 只记相邻高度最大跳变/结构标志并给 planner 1.5 倍倾向；不是父子网格高度残差，也不知道山脊轮廓与材质边界细节。预算优先近圈/望远镜，普通规划不按当前视锥集中所有预算。预算常量也不是最终叶数硬上限：还叠加 radial skeleton 并 balance，不能看到超过 1024 就报告泄漏。

已核实 `PredictionRelief` 的样本契约：TileManager 正常发布和磁盘恢复均 crop 为 `(cellAxis+1)^2`；没有证据支持“relief 因采样长度不匹配永远为零”的猜测。

方案：在生成/细化 worker 中保存父子网格最大几何偏差和受保护边界误差，以 `errorBlocks × projectionScale / distance` 进入有限预算选择。未知误差保守处理，避免用零代表平坦；平缓区域少分配，山脊/陡崖多分配。视锥只作带滞回优先级，保留周边低级覆盖，避免转身重新生成；维持父子原子交接、邻级平衡与接缝。计算不能放到每帧规划循环遍历列数据。

验收：用现有 mountain replay 与当前固定种子/相机比较投影轮廓误差；相同 quad/生成预算下看山体是否改善，再测相同画质下成本是否降低。区分预测采样本身与真实已记录地形的差异：Voxy 保存真实区块数据，预测并不保证拥有同等洞穴、结构或玩家改动信息。

**F. 复杂整体工程：混合 quad 编码，最后做**

本次 573 个普通 payload 去重后共有 8,122,845 个完整驻留 quad，已选 ranges 只用其中一部分；GPU 存储字节 267,793,744，约 255.4 MiB、32.97 字节/quad，573 个均已采用现有 dictionary 编码。它不是整个进程显存；arena 的 1 GiB 是页分配容量也不是当帧上传量。

VSS canonical 为 48 字节，当前 compact 为 32 字节主记录加颜色/光照字典。本次全部 selected 面中 67.37% 是几何上的轴对齐矩形，42.50% 同时四角颜色/光照一致。这些只是专用格式候选上界，并非全部可合并或都能塞进 Voxy 的格式。

Voxy 使用 32³ section 的局部坐标和 8 字节主 quad，另有 model/material/biome 数据，并通过层级可见性和当前帧命令生成避免提交不可见 section。不能将 8 与 33 的比值称为 FPS 比值，也不能直接复制只支持较窄坐标/尺寸域的 quad 位布局。

方案：先为能严格表达的矩形引入 origin+extent+axis+material 的 compact 类别，其余斜面、细坐标、模型、边界保留通用格式。先做离线可编码率/净字节/解码成本实验，再确定 16/24 字节等实际布局，不预先承诺 8 字节。GPU encoding、canonical restore、mesh codec 版本、sprite/light 字典、morph offsets、ownership CPU 解码、cluster bounds、arena 上传和 GLSL 读取要作为同一契约修改；normal/Iris/fallback 与两加载器一起验证。

验收：随机与真实 mesh round-trip 检查坐标、coverage、材质、morph 完全一致；负坐标/远世界、超高维度、细水高程、资源重载、冷热缓存、异步上传生命周期和 GPU 渲染回归全部通过。旧缓存应有明确迁移或版本拒绝，不能静默按新格式解读。

**与 Voxy 的正确对照**

Voxy 本地比较源码：`C:/Users/Administrator/Desktop/voxy-compare/neoforge-voxy/`；安装包 shader 提取在 NeoForge 项目 `build/render-visibility-20261005/voxy-shaders/`。源码参考版本不保证与安装版本逐行一致，应把共同架构与具体性能测量分开。

VSS 已有邻居消面、face group 范围选择、shader 法线背面拒绝、CPU Voxy 归属过滤和当前帧 HiZ。GL_CULL_FACE 关闭是因为混合 winding/双面植物，不能直接全局启用。更有价值的借鉴是让细节、可见性单元、几何记录都能按空间与屏幕贡献一起降级，而不是只降低地形高度网格精度，却继续支付全细节植被和宽剔除盒的成本。

**集成与统一验收**

先完成其他对话的三个 CPU 改动并建立新的基线，再对 A～F 单项测量；禁止把两个不同方向的 FPS 差当作纯算法 A/B。冻结世界、视角、渲染距离、分辨率、资源包、Iris 状态；稳定后逐项切换，再补正常跑图测试。记录 CPU 准备/提交、每类候选/通过面数、命令数、上传字节、GPU query 有效样本数和逐帧时间。如果没有逐帧时间序列，不报告 p95/p99。

共享 prediction 代码需同步 NeoForge 与 `C:/Users/Administrator/Desktop/voxyserverside` 的适用实现，并各自执行构建与渲染回归；不能整目录覆盖加载器差异。本报告已复制到两个项目，原始运行证据仍保存在 NeoForge 的 build 目录。复制报告不表示上述优化代码已同步或已实施。

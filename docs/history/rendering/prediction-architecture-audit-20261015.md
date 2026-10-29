# VSS 渲染架构目标审计与重复路径清理（2026-10-15）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

> 后续实现已完成并验证：见 [参照 Voxy 的渲染架构补齐与验证](prediction-voxy-alignment-20261015.md)。本文保留补齐之前的审计证据；同源父层、GPU 准入门槛和分项计时的当前状态以新报告为准。

本报告对应 Forge 1.20.1 与 NeoForge 1.21.1 的当前工作区。检查内容是实际生产入口、调用关系、版本交接与打包结果；本轮没有采样游戏内 FPS。旧报告描述的是各次改造时的状态，本报告作为当前七项目标完成度的汇总。

结论：第 5、7 项的主要路径已经落地，第 1、2、4、6 项部分完成，第 3 项的核心仍未实现。这里不按文件数量或代码行数计算百分比，避免把局部优化误当作整体架构完成。

## 七项目标的实际状态

| 目标 | 状态 | 当前已经接入生产的内容 | 尚未完成或适用范围 |
| --- | --- | --- | --- |
| 1. Voxy 空间索引和覆盖列作为唯一边界来源 | 部分完成 | 生成规划使用稳定真实列索引；`PredictionAuthoritativeCoverageView` 统一 Scene 资格、提交范围和驻留判断；覆盖撤销会恢复预测候选；边界证明包括维度、三维范围与稳定性 | VSS 仍管理预测瓦片、父子 owner、几何和可见成员；混合瓦片仍需局部范围过滤，尚未成为只维护 Voxy 缺失区域的单一空间场景 |
| 2. 共享稳定 GPU scene | 部分完成 | `PredictionGpuScene` 保留固定 metadata 槽与命令区间，局部版本日志驱动更新，删除清零自身槽；有约 64 MiB 保留上限；本轮统一了静止、移动和转向的资格判断 | 当前仅覆盖合格的不透明、完整 GPU 范围；仍有大场景门槛、GL 能力、共享 arena、接缝与特殊渲染路径限制，上游 CPU 候选和 `PreparedPass` 仍存在 |
| 3. 同源细数据生成真实父子 LOD | 核心未完成 | 已有预测四叉树、父级回退、渐进网格分辨率、树冠/立面/地形显示代理 | 这些不是从同源细数据聚合的完整父粗层；父子源版本证明与 GPU 原子切换尚未形成完整体系，不能称为 Voxy 的体素 mip 或 DH 的粗列层级 |
| 4. CPU 区域管理，GPU meshlet 选择 | 部分完成 | meshlet bounds、方向与遮挡测试、GPU 视锥、命令压缩及 indirect-count 已有可用路径 | CPU 仍做大量瓦片候选、范围和部分屏幕误差选择；GPU 尚未统一执行完整父子层级、误差与细粒度选择 |
| 5. 有限阶段 Hi-Z | 主要路径完成 | `PredictionHiZBudget.MAX_REBUILDS = 2`；普通 opaque 路径和 refinement hint 共享预算，距离桶推进不再无限重建完整金字塔 | 预算按 opaque submission token 管理，不能声称所有多视口、Iris 和特殊调用都严格等价于每游戏帧两次；缺少预算时允许少剔除，不能据旧深度误删几何 |
| 6. 水面、植被、接缝独立计时和预算 | 部分完成 | 水面专用存储、透明顺序和独立 CPU/GPU query pool；接缝后台准备、准备阶段计时、必要上传记账与版本交接 | 植被还没有完整独立的 GPU 计时/预算体系；接缝准备计时不等于接缝最终绘制的独立 GPU 时间 |
| 7. 纯需求计算移出渲染线程 | 主要路径完成 | `PredictionPlanPreparation`、`PredictionDemandPreparation`、`PredictionSchedulePreparation` 和 `PredictionLoadingPreparation` 使用版本化输入，在共享低优先级 control worker 计算，客户端线程发布结果 | 安全快照、版本校验、发布、预算/队列维护、部分新条目的分类和 GPU 生命周期仍在客户端线程；尚未实现所有 CPU 选择完全后台化 |

GPU 常驻选择的当前门槛包括：至少 3072 个地形瓦片、1,572,864 个候选面、524,288 个可见候选面、Hi-Z 可用且无接缝。策略允许已建立 scene 的完全静止帧继续使用，也允许受限的移动和足够大的转向；这不意味着所有视角和所有画面都进入该路径。传送、窗口和投影变化仍需要重新验证，GPU 始终执行当前视图的测试。

## 本轮已经删除或合并的重复逻辑

### 渲染与接缝

- `Scene` 不再同时维护同步和异步两套准备模式，移除了旧标志与分支。
- 删除 renderer 中旧的 `lodSeams`、`realSeams` 实例；接缝计算只由 `PredictionSeamPreparation` 的隔离 builder 持有。
- 删除未使用的 `publishPatches` 及 `CachedCoverage` 失效时间参数。
- 删除 `SeamBatch.prepare(List)`、`previous` 与 `prepared` 全列表缓存。生产与 GPU 回归均使用 `apply(Changes)`；重复 patch 不上传，删除立即生效，再出现时重新准备。
- GPU 回收通过已发布的 `scene.publishedTerrain` 和引用关系判定，保持旧地形与匹配接缝在新版本提交前可用。

接缝仍需要暂存中的 `terrain` 与正在绘制的 `publishedTerrain`。它们是同一个版本化事务的两种状态：后台计算未完成时，旧地形、旧遮罩和旧接缝继续配套绘制，完成后一起交接。删除其中一份会破坏回退保护，不属于可以移除的重复算法。

### 需求、调度与覆盖

`PredictionTileManager` 中以下旧计算入口已经从生产包移除：

- `refreshViewPaths`、`refreshViewWork`、`refreshOccludedPriorities`。
- `foregroundDebt`、`refreshPreviewWork`、`refreshLoadingProgress`。
- `ordinaryAxis`、`makeRoomForSurface`、`buildRequest`。
- `viewPaths`、`viewPathLevels`、`VIEW_NEIGHBOURS` 字段。
- 旧同步 `fullyAuthoritative`、`fullyVoxyOwned`、`refreshVoxyOwnership`。
- 无调用的 `hasCoverage`、`queuedHeightSamples`、`previewBuildNeeded`。

整块归属和真实覆盖判断只保留 `PredictionPlanPreparation` 内实际参与生产规划的实现。旧回归改为从生产输入构造器取得快照，运行相同 planner 并验证客户端发布；测试夹具按场景固定被测区域，不再在主源码保留另一套归属计算供测试调用。日志中的 `foregroundDebt` 键仍可用于诊断，其值是已发布状态，不再对应一个独立扫描算法。

`PredictionGpuSelectionPolicy` 只保留 `residentFrame`；旧 `choose`、`turning`、`residentMotion` API 已移除。原先已建立 scene 在静止帧退出常驻选择的情况得到修正，网格/材质/归属与动态视图的校验仍独立执行。

## 审计中修复的实际交接问题

可选 idle 精化原本也会调用加载状态的完整失效流程，把已完成的普通目标重置为 `INITIALIZING`，重新声明存在前台工作。这会让后台可选质量提升与普通加载准入互相影响。

现在，仅在已经存在普通、非脏、达到普通目标的 resident，且当前任务确实是可选精化时，发布新网格保留已完成的普通 readiness；仍更新 loading version、覆盖 epoch、snapshot delta 并拒绝过期后台结果。新覆盖、真实编辑、资源变化和移除仍按原有保守规则失效。

回归中还修正了两类旧夹具：直接修改叶集合时必须同时提供生产 planner 的目标分辨率；直接修改 ready map 时必须通过变化集合发布不可变表的增量。它们没有改变生产的分辨率目标或降低覆盖断言。帧率环形计数目前仅做诊断，因此可选精化测试使用现行 CPU/内存准入契约，不再假设未知 FPS 本身禁止调度。

## 明确保留的正确性保护

- **整块资格与局部范围过滤**：前者可以快速排除完全接管的普通几何，后者处理混合瓦片和过渡边缘；二者使用相同覆盖证据，不能删掉后者来掩盖尚未统一的数据组织。
- **覆盖索引与当前帧深度**：索引说明空间数据是否存在，深度说明当前画面实际遮住了什么。边缘、空列、特征和水体不能只凭二维索引断言已完全替代。
- **父级回退、撤销恢复与 GPU 引用保护**：细层不齐、覆盖撤回和接缝待提交时都需要它们。
- **普通不透明、常驻 GPU scene 与水面路径**：是按适用条件选择的绘制实现。水面要保持透明顺序，特殊 GL/Iris 与不完整范围需要安全回退；当前不能无条件删除旧绘制后端。
- `tools/` 与 `src/test/` 中的历史基线用于离线对照，不属于打包生产实现。没有为了减少文件数量删除历史证据。

## 验证与产物

当前测试入口：`tools/prediction/architecture-audit-tests.gradle`。输出隔离在 `build-architecture-audit-20261015/`，JAR 位于各仓库 `lib/architecture-audit-20261015/`。

NeoForge 使用 JDK 21，Forge 使用 JDK 17，分别执行：

```powershell
.\gradlew.bat --no-daemon -I tools/prediction/architecture-audit-tests.gradle architectureAuditTest architectureAuditGpuTest jar --console=plain
```

Forge 的 `jar` 会继续执行 `reobfJar`。测试日志中故意注入的 control failure 与空接缝 surface 用于验证失败后保留 fallback 并允许重试；是否通过以 JUnit XML 的 failure/error/skipped 计数为准。

两端最终构建与打包审计均已通过，失败、错误和跳过计数全部为 0：

| 平台 | CPU 回归 | 隐藏 OpenGL 回归 | JAR 大小 | Java class major |
| --- | --- | --- | --- | --- |
| NeoForge 1.21.1 / JDK 21 | 31 组，229 项通过 | 10 组，11 项通过 | 6,100,500 字节 | 65 |
| Forge 1.20.1 / JDK 17 | 30 组，226 项通过 | 10 组，11 项通过 | 6,770,320 字节 | 61 |

产物均在各仓库的 `lib/architecture-audit-20261015/`：

- `vss-0.3.5-neoforge-1.21.1.jar`：SHA256 `719fe4b8b5dee2e1ba9d4543c8e4aa304baafaf6567ccc79300fd340a19ad7e4`。
- `vss-0.3.5-forge-1.20.1.jar`：SHA256 `58bc43724f518c0836a0522e9b8015c00b923742424e022906a76b109a6867ad`。

`tools/prediction/audit-architecture-artifacts.py` 已联合核对两个仓库：9 个共用生产源码字节一致；上述旧入口从 JAR 字节码消失；旧 `PredictionSubmissionCoverage.class`、测试类与历史基线未入包；Java class major 正确；五个平台的原生库与源码资源逐一散列一致。每个产物目录另有 `validation.json` 和 `SHA256SUMS.txt`，可复查测试汇总与包内容。

本轮没有运行游戏内 A/B，因此没有修复后的 FPS、1% low 或最差帧改善值。这里的通过代表上述回归范围内的功能和交接正确性，不能换算成接近 Voxy 的性能幅度。

## 剩余工作优先级

1. 继续把场景资格、候选和范围准备收敛到按区域变更的单一输入，让常驻 GPU scene 覆盖更多普通画面；关注转向/移动时实际编码与更新记录数。
2. 建立同源父粗层、源版本与父子完整性证明，再做 GPU 层级/屏幕误差选择。只简化当前 mesh 或扩大缓存不会完成这一步。
3. 补齐植被与接缝最终 GPU 绘制计时、分项预算，并验证特殊渲染路径的 Hi-Z token 范围。
4. 在相同位置、分辨率、画质和视距下测静止、转向、跑图和覆盖交接，记录渲染准备时间、槽位更新量、上传量、通过剔除的面数和 GPU 阶段时间。

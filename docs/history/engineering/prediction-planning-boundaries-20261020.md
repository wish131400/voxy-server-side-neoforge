# 2026-10-20 预测规划内部解耦与验证

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮继续上一轮[模块边界重构](module-boundaries-verification-20261020.md)，在 Forge 1.20.1 与 NeoForge 1.21.1 同步拆分预测规划的任务生命周期和结果校验。目标是明确状态所有者并删除重复路径，不是重写生成或渲染算法。工作区原有未提交功能全部保留；本地 JAR 包含当前整个生产工作区，不是仅包含本轮修改的最小补丁。

## 实际拆分边界

| 类型 | 本轮后的职责 | 所有权与线程 |
| --- | --- | --- |
| PredictionPlanningPipeline | 组织计划、视角、调度、加载统计四条准备通道及其计数 | 客户端线程提交、轮询和失效；共享原有串行低优先级执行器 |
| PredictionClientPreparation | 唯一任务状态机、代次失效、非阻塞完成轮询和异常处理 | 每通道最多一个保留任务；旧任务实际退出前不重用槽位 |
| PredictionPlanningValidity | 根据输入版本、当前相机及快照身份，判断结果是否仍可交接 | 纯校验，无全局状态或独立缓存；当前数据由客户端线程提供 |
| PredictionPlanPreparation | 根据不可变输入及显式覆盖快照计算目标 | 不再直接查询 ClientPredictionState；覆盖读取器由装配入口注入，并在后台获取快照 |
| PredictionDemandPreparation | 视角分带、近期可见分类和覆盖依赖路径计算 | 只保留工作线程使用的不可变路径缓存，不再管理 future、执行器或代次 |
| PredictionLodPlanner | 规划叶节点并展开按父先子后排列的祖先覆盖 | 从 TileManager 迁入唯一祖先展开实现，调用方全部迁移 |
| PredictionTileManager | 捕获输入、消费校验通过的结果、维护需求、居民瓦片与资源预算 | 继续是 CPU 场景状态所有者，不保留四套准备器及计数副本 |

传送、暂停和关闭统一调用 pipeline.invalidate()。失效只使旧结果不可交接，不阻塞客户端线程，也不取消已发布居民覆盖。旧任务保留槽位至实际退出，避免连续传送重复排队。四通道共用原执行器，没有增加线程池。

普通计划仍允许 128 方块内的小幅移动；配置、布局、网格版本、覆盖撤销版本、聚焦和转向变化按原规则拒绝旧结果。Voxy 的实际上传证据仍在客户端线程验证。加载统计额外要求计划列表、居民/可绘制快照、视角分带身份及加载版本一致。调度结果本轮补充显式暂停/关闭拒绝，与其他通道保持一致。

## 删除及调用迁移

- 删除视角通道独有的 CompletableFuture、executor、generation、pendingGeneration、request、poll、clear 实现，统一使用现有状态机。
- 删除 TileManager 中 plan/demand/schedule/loading 的旧准备器字段和作业计数副本。既有诊断字段名不变，由唯一 pipeline 状态提供数值。
- 删除 TileManager.withCoarseCoverage 门面，生产和测试均直接调用 PredictionLodPlanner 中的唯一算法。
- 测试对内部字段的访问集中到 PredictionPlanningTestSupport；没有为了反射夹具在生产中保留旧字段。
- 新增字节码边界检查，禁止规划、视角计算及有效性检查类型直接依赖 ClientPredictionState、客户端网络实现、Voxy 兼容实现或 Minecraft 客户端类型。

两平台分别保留既有 Minecraft API、Java 版本、植被构造器和 editedCells 键编码等差异，没有将一仓生产目录整树覆盖到另一仓。

## 回归结果

| 平台 | 架构检查 | 固定模块回归 | 规划调用方回归 | 本地打包 |
| --- | ---: | ---: | ---: | --- |
| NeoForge 1.21.1 | 11 | 268 | 32 | 成功 |
| Forge 1.20.1 | 11 | 267 | 32 | 成功，含 reobfJar |

表中最终运行均为零失败、零错误、零跳过。固定模块集合比上一轮每仓增加 62 项：新管线与有效性共 10 项，以及主线程准备、转向、粗层交接、传送、需求生命周期和视角精化的 52 项既有回归。不要把这 62 项再与模块总数相加。

调用方回归任务为 planningConsumerTest，准确选择七个类：PredictionLoadingBenefitTest（7）、PredictionDemandProfileTest（1）、PredictionMediumCoverageTest（4）、PredictionMemoryLifecycleTest（7）、PredictionSurfacePipelineTest（4）、PredictionTileManagerTest（2）、PredictionLodPlannerTest（7）。各类使用独立 JVM；诊断写入测试在类初始化前取得独立临时目录。该任务不依赖本机游戏、GPU 或外部模组 JAR。

### 发现并修正的旧测试条件

初次补充运行发现诊断测试缺少 opt-in 输出目录；隔离 JVM 后又发现 Forge 的规划 fixture 在 Minecraft bootstrap 前初始化注册表键。正式任务补齐诊断输入；规划测试改为 bootstrap 后读取维度键，保留全部行为断言。

两项内存生命周期测试原先使用 132 MiB / 144 MiB 总预算，但当前 BUILD_BYTES 为 192 MiB，因而无法启动一个工作任务。用保存的修改前源码在 Forge 隔离输出目录重跑这两个方法，同样得到两项失败：受限预算无法建立覆盖、暂停测试无法进入采样器。修改前对照确实执行了两个用例，XML 记录为 tests=2、failures=2、errors=0、skipped=0。

正式测试预算改为 BUILD_BYTES + 4 MiB、BUILD_BYTES + 16 MiB，继续验证内存上限、覆盖连续性、粗级回退、静态收敛、暂停后的过期发布拒绝、恢复和关闭释放。没有修改生产内存预算、删除断言或增加测试排除。

早期 baseline init script 曾因相对路径解析得到 NO-SOURCE，未计为验证结果；修正为显式项目路径并检查非空源后，才取得上述有效对照。初次使用普通 test 筛选和逐类 JVM 的运行，会启动大量无关候选类进程；在保留日志后已停止，最终以准确 include 七类的正式任务完整重跑。早期部分运行不计入通过数。

## 可复现命令

在两个仓库分别执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules --no-daemon
.\gradlew.bat planningConsumerTest architectureTest --no-daemon
.\gradlew.bat -I tmp/planning-boundaries-20261020/package-local.gradle jar --no-daemon
```

普通发布可使用 .\gradlew.bat jar --no-daemon。本轮本地 init script 只将 JAR 输出重定向至独立目录，保留 Forge 的 jar → reobfJar 图。固定模块回归通过后，最终额外的生产改动只有删除无用 import；更正 fixture 后的调用方回归、架构检查及打包均使用最终源码重新编译。

Forge 修改前内存对照：

```powershell
.\gradlew.bat `
    -I tmp/planning-boundaries-20261020/baseline-local.gradle `
    -I tmp/planning-boundaries-20261020/baseline-consumer-local.gradle test `
    --tests '*PredictionMemoryLifecycleTest.constrainedBudgetKeepsCoarseCoverageAndClosesWithoutLeakingReservations' `
    --tests '*PredictionMemoryLifecycleTest.pauseCancelsQueuedWorkAndPreventsStalePublicationThenResumes' `
    --no-daemon
```

本地记录位于各仓 tmp/planning-boundaries-20261020/：verify-modules.log、planning-consumer-complete.log、package-complete.log、verification-results.json、round-diff.patch 和逐文件修改前备份。Forge 另有 baseline-memory-regression.log、baseline-source/ 与 baseline-build/test-results/test/。两仓的原始备份哈希及本轮新增行空白检查均通过。

## 构建产物

| 平台 | 路径 | SHA256 |
| --- | --- | --- |
| NeoForge | `lib/planning-boundaries-20261020/vss-0.3.5-neoforge-1.21.1.jar` | `2af1d0ebce310327ece81bb896fa3e4d6b075913d5a0403ff9530c729f5c5e35` |
| Forge | `lib/planning-boundaries-20261020/vss-0.3.5-forge-1.20.1.jar` | `6f5c2a638bc9824afb3a716998886971156234a4e313e3693b3561da4add72ec` |

已检查平台 metadata、Java 类版本（NeoForge 65 / Java 21、Forge 61 / Java 17）、新管线与校验类。包内实际字段确认视角计算器只保留路径缓存，TileManager 的四个旧准备器及计数已消失。主 JAR 没有测试类、JUnit、Mockito、ArchUnit 或历史实验实现。Forge 的非空 refmap、JarJar 元数据和 LZ4 内嵌包均存在。

## 尚未拆完及验证范围

这轮完成的是后台规划通道和规划结果有效性边界。TileManager 的采样/网格构建输入、publishTile 网格结果和租约交接、居民缓存仍由原类协调；PredictionRenderer 与 LodRequestManager 的内部职责也未完成分解。后续边界见[架构说明](../../development/architecture.md#预测规划的内部边界)，不能把本轮结果描述为所有大类已经拆完。

本轮未执行完整普通 test/check、GPU 画面测试、真实游戏或专用服务器启动、FPS 对照。六个依赖旧接口或外部 baseline 的历史测试源继续按原清单精确排除，未扩大范围；排除不是通过。上轮的 Voxy 字节码/Mixin 回归结果没有冒充本轮重跑结果。

没有提交或推送 GitHub，没有替换游戏实例 JAR。本轮架构重构不提供帧率提升结论。

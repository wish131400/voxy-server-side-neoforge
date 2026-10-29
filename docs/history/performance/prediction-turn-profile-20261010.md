# 转动视角掉帧：当前客户端实测（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮完成实时采样和源码定位，没有修改生产源码、构建或替换游戏 JAR，也没有提交或推送 GitHub。两个仓库同步的是此报告；实际游戏测量只覆盖 NeoForge 1.21.1。

## 结论

原地转向可以复现掉帧。更直接的热点是渲染线程整理候选、发布可见列表、准备绘制范围，以及视野细化调度的重复 CPU 工作。静止时也仍有间接绘制计划签名检查、完整源快照复制和后台生成成本。转向扩大了其中部分工作，移动还会进一步触发归属过滤。

当前证据支持先优化这些 CPU 路径。新增 HiZ 调度探测自身只占原地转向渲染线程样本约 2.0%，但 CPU 消费遮挡反馈后的分类、祖先查询与重排仍有开销。不能把所有掉帧归因于该 GPU 探测，也不能据此认定 GPU 完全没有成本。

## 运行版本和采样方法

- 客户端 PID：32104；Java 25.0.1；Minecraft 1.21.1，NeoForge 21.1.249。
- 处理器：AMD Ryzen 7 7700，8 核 / 16 逻辑线程。
- 实例：`F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249`。
- 实际加载 `vss-0.3.5-neoforge-1.21.1.jar`：5,824,815 字节，SHA256 `FF58764FD5980FEC8824C376ECF5CE7248C9A7E06017B2F1F95585B6E27A171E`。
- 与仓库 `lib/vegetation-handoff-20261010/vss-0.3.5-neoforge-1.21.1.jar` 一致。它包含前一轮视锥、屏幕细节及遮挡调度实现，另有植被交接更新；不是仅凭版本号确认。
- 渲染、调度、视野、规划、遮挡反馈和间接批次等 8 个关键主类的字节码 hash 与 `build-screen-detail-20261010` 匹配，详细结果在 `loaded-package-check.json`。检查的源码另存独立快照，避免后续编辑影响证据。
- 游戏日志确认预测已就绪，Rust 后端启用；Voxy 使用普通 `NormalRenderPipeline`。
- JFR 共三段，时长分别为 65、35、30 秒；执行 / native 采样周期配置 5 ms，monitor / park 门槛 2 ms。
- 独立后台诊断线程约每 2 秒读取计数、不可变视角对象及线程 CPU / 分配计数；不读取或改写世界区块，不变换生产类，不在诊断线程调用 GL。临时开启 `vss.renderTimings`，三个诊断任务均已结束并恢复原属性值；`JFR.check` 确认没有剩余录制。
- CPU/GPU 计时使用原有每 30 帧抽样，均由本轮计数增量计算。GPU 数字是 timestamp 时间区间，并非纯 GPU 忙碌时间；CPU、GPU 及包含的子阶段不能直接相加。

前两段诊断工具的 `getFps` 反射调用未成功，原始行保留该错误。离线解析仅将缺失数字修复为 JSON null；其帧率由 `frameStarts` 增量 / 单调时间计算。第三段已修正工具类，并同时取得游戏 FPS。没有将失败的 FPS 读取伪造成实测值。

## 观测窗口

香港时间：

1. 首段 JFR：15:48:41.553–15:49:46.592，包含原地观察、移动转向和短暂停。有效移动转向计数窗口为 15:49:15.359–15:49:23.363 及 15:49:29.367–15:49:45.378，共约 24.015 秒；精确时间戳见 `windows.tsv`。暂停及其边缘窗口未参与该比较。
2. 静止 JFR：15:51:44.876–15:52:19.907；有效计数窗口为 15:51:46.562–15:52:18.583，共约 32.021 秒。位置固定在 `(529.703, 84.620, 317.907)`，镜头除轻微初始变化外保持不动；后台仍生成和上传，没有达到完全静态稳态。
3. 原地转向 JFR：15:55:03.785–15:55:33.822；有效计数窗口为 15:55:07.247–15:55:31.263，共约 24.016 秒，期间每个诊断点的 XYZ 均与静止段相同。末尾暂停窗口及起始边缘未参与比较。

| 指标 | 静止，继续加载 | 原地左右转向 | 移动并转向 |
| --- | --- | --- | --- |
| 有效窗口（秒） | 32.021 | 24.016 | 24.015 |
| 实际渲染帧率 | 58.87 FPS | 53.71 FPS | 44.43 FPS |
| 绘制计划准备 / 复用 | 382 / 1,504 | 643 / 647 | 1,066 / 0 |
| 可见列表发布 | 237 | 594 | 662 |
| 排序树插入 | 4,746 | 213,192 | 58,583 |
| 候选精确可见性访问 | 37,020 | 4,370,473 | 7,129,952 |
| 视野调度刷新 | 209 | 339 | 253 |
| 网格上传 | 406 | 232 | 165 |
| 渲染线程分配速率 | 86.98 MiB/s | 96.10 MiB/s | 121.73 MiB/s |
| 准备 CPU 抽样均值 | 1.977 ms | 2.802 ms | 5.645 ms |
| 不透明 CPU 抽样均值 | 3.526 ms | 2.917 ms | 4.996 ms |
| 不透明 GPU 时间区间 | 4.314 ms | 4.335 ms | 3.765 ms |
| 不透明 GPU 新样本 | 62 | 43 | 36 |
| 提交次数 / 帧 | 114.0 | 108.3 | 84.4 |
| 提交四边形 / 帧 | 4.77 百万 | 4.03 百万 | 4.00 百万 |

这些是三个真实状态的诊断对照，不是冻结场景的严格 A/B。静止和原地转向虽然位置相同，但看的方向、后台进度和遮挡提示不同。不能将平均 FPS 差值全部归因于镜头操作，也不能据此预测修复后的固定 FPS。

原地转向有效段的两秒窗口约 37.0–75.5 FPS，多次落在 37–47 FPS；静止段约 51.0–67.5 FPS。首段移动转向中曾有约 28 FPS 的两秒窗口。帧率范围是两秒平均，不是单帧最小值或 1% low。

首段朝地面看的另一窗口约 212 FPS，预测准备 CPU 约 0.111 ms；这一视角提交的几何和场景不同，不能用作同方向静止基线。

## 1. 原地转向最直接的渲染热点：候选和可见列表整理

原地转向有效段共 2,574 个渲染线程执行 / native 样本，其中：

- `PredictionRenderer$Scene.prepare`：644 个，25.0%。
- `PredictionVisiblePlan.selectStable`：292 个，11.3%，包含在前述路径中。
- `PredictionRenderer$PreparedPass.prepare`：284 个，11.0%，是后续 pass 准备路径。

这三个数字不是互斥耗时；不能将包含的前两项相加。

24 秒中发布可见列表 594 次，排序树插入 213,192 次，精确可见性访问 4,370,473 次。静止 32 秒中分别为 237、4,746、37,020。插入计数包含成员进入、退出后的重新加入及更新，不能全部叫作不必要的距离排序。

`PredictionFramePlan.getStable` 要求相机和投影 / 视图矩阵相同；旋转后重新确认可见性是正确的。然而，当前 `Scene.prepare` 转向时新建 broad candidate HashSet 和 DrawKey 集合，再逐项维护可见树并发布完整列表。列表 / 成员变化向下游传播后，prepared pass 和间接计划继续重建。原地转向准备 643 次、复用 647 次；移动转向准备 1,066 次、复用 0 次。

分配样本也支持这条路径：原地转向中 `Scene.prepare` 第 890 行的 DrawKey 约 149 MiB 加权分配；其他候选节点 / 集合还有额外分配。该数字是采样估算，不替代精确总分配计数。

优化应保留当前帧的正确可见性判断，让静态记录、候选存储、稳定 ID 和命令槽复用，只更新进出画面、网格或归属确实变化的部分。不要通过沿用旧相机矩阵或延迟恢复覆盖来换取缓存命中。

对应源码：`PredictionRenderer.java:800`、`:887`、`:983`；`PredictionVisiblePlan.java:130`；`PredictionFramePlan.java:26`；`PredictionRenderer.java:133`。

## 2. 视野细化调度刷新频繁，额外工作在主线程执行

原地转向有效段：

- `PredictionTileManager.tick` 533 / 2,574 样本，20.7%。
- `scheduleWork` 241 个，9.4%，包含在 tick 中。
- `refreshViewWork` 94 个，3.7%，也属于这一调度链。
- 视野刷新 339 次，约 14.1 次/秒；静止仍有约 6.5 次/秒。

完整规划按原有 5 tick 节流；轻量路径在视角变化或遮挡反馈快照身份变化时，还会执行视野分类、依赖构建、进度统计、preview 判断及 `scheduleWork`。后者重建 BuildRequest 列表、排序、复制 resident key 集合和检查 / 尝试入队。

`refreshViewWork` 对所需键计算父级和邻接依赖，构建新 Map / Set。遮挡 `Snapshot.hidden` 每次沿祖先创建 PredictionTileKey，并重复校验视角、版本和时效；它还可在规划 PriorityQueue 的比较中被多次调用。

原地转向的加权分配中，`refreshViewWork:425` 的瓦片键约 164 MiB，`Snapshot.hidden:23` 的祖先瓦片键约 141 MiB。这并非仅后台生成的分配，发生在渲染主线程的调度路径。

优化重点是对同一规划版本、视角分类和有效遮挡结果缓存每键分类 / 优先级，复用父子邻接关系和工作列表；只对变化区域更新，不因反馈对象换了身份就全量刷新。新画面缺失覆盖、遮挡提示到期 / 撤销及 CPU 容量恢复仍需及时调度。此处无需撤掉“看得见区域优先”功能。

对应源码：`PredictionTileManager.java:411`、`:517`、`:658`；`PredictionVisibilityFeedback.java:17`；`PredictionLodPlanner.java:105`。

## 3. 静态提交缓存仍逐项生成完整签名

静止有效段共 3,469 个渲染线程样本：

- `PredictionIndirectBatch.add` 573 个，16.5%。
- `PlanItem.key` 336 个，9.7%，包含在 add 中。

静止仍每帧约 114 次提交，已记录大量 plan replay。但 `PlanItem.matches` 首先重新计算长签名，再检查 tile / GPU / 范围 / slice 等身份和内容。签名读取许多静态字段，调用 `Arrays.hashCode`；开启 segmented 时还会扫描 meshlet bounds 数组。

因此，已上传且静态的记录虽能复用，验证成本仍按条目逐帧支付。准备和重建路径还出现 `PredictionDrawRanges` 构造，以及 PlanItem / PreparedDraw 分配。

应在身份与网格、驻留、归属、显示档位版本未变时先短路，静态签名按这些版本缓存，变更时才做内容校验和重新编码。不能省略会影响颜色表、morph、掩码、slice 或水面排序的必要变化检查。

对应源码：`PredictionIndirectBatch.java:544`、`:570`；`PredictionPackedMesh.java:221`、`:274`。

## 4. 源快照失效后仍有完整复制和索引重建

采样时 manager 保有约 8,400–9,600 个 CPU ready tile。`renderSnapshot` 在失效后先 `Map.copyOf(ready)` 和 `Map.copyOf(coverageEpochs)`；非分页输入的 RenderSnapshot 构造随后又复制为 LinkedHashMap，并扫描全部 tile 建立各 LOD 索引。

静止有效窗口中 `PredictionTileManager.renderSnapshot` 出现在 271 / 3,469 样本，7.8%。上传和归属变化继续使快照更新，即使相机没有移动，也会支付这项成本。渲染驻留已有分页索引路径，但 manager 的普通源快照仍走完整复制路径。

应扩展不可变分页 / 局部快照发布与版本检查，一次发布合并同窗口完成的更新，保留网格、epoch、归属的一致性。不要把镜头重分类本身当作整个网格数据集合变化。

对应源码：`PredictionTileManager.java:1722`、`:1752`；`PredictionRenderResidency.java:137`。

## 5. 移动时，归属过滤仍因覆盖快照更新扩大失效

移动转向有效段中 `PredictionSubmissionCoverage.filter` 377 / 3,050 样本，12.4%；`PredictionOwnershipRuns.filter` 223 个，7.3%，后者包含在前者中。

上一轮已将相机 8 方块网格变化与静态覆盖证明分开，当前不能再说“每次移动都清空整套静态缓存”。仍存在的是：`index != next` 或 radius 变化时清空整个 WeakHashMap，而索引按异步覆盖快照发布；新快照会重建每网格 Entry、数组和静态覆盖证明。

本轮没有独立计数 cache.clear 的次数，不能把所有重算精确归到它。但 JFR、分配样本和源码共同支持局部覆盖失效仍有优化价值。应使用受影响覆盖区域 / 本地版本，只重算与变化相交的网格；覆盖撤销必须立即恢复预测。

对应源码：`PredictionSubmissionCoverage.java:37`、`:45`；`PredictionExactCoverageMask.java:90`。

## GPU、后台和 GC 的边界

- 原地转向预测不透明 GPU 区间均值 4.335 ms，静止为 4.314 ms。二者有数百万候选四边形，GPU 绘制仍有实际成本；本轮未测全游戏各通道 GPU 帧时间，不能宣称全帧已完全 CPU bound。
- 转向有效段 `PredictionRefinementOcclusion.sample` 52 / 2,574 样本，2.0%；三个有效窗口各有 91、124、92 个探测完成增量表明反馈正常工作。零超时 fence 轮询之后才读回，当前未抓到其阻塞长等待。CPU 消费反馈的重复查询计入调度热点，二者要分开。
- 静止时后台预测线程在 32 秒内累计约 189.3 CPU 秒，约 5.9 个核心的工作量，临时对象分配约 257.9 MiB/s；原地转向约 2.8 个核心、151.9 MiB/s。后台仍会争用 CPU / 缓存 / 内存带宽，但主线程自身可避免的工作已有明确证据。
- 精确渲染线程分配计数：静止 2,785 MiB / 32 秒；原地转向 2,308 MiB / 24 秒；移动转向 2,923 MiB / 24 秒。这是累计分配，不是同时驻留或泄漏。
- 有效原地转向窗口 GC 暂停累计 73.4 ms，最长 14.47 ms；静止 174.7 ms，最长 13.00 ms；移动转向 98.2 ms，最长 15.94 ms。GC 会放大部分长帧，但不能单独解释持续低帧。
- 三个有效窗口均无超过 2 ms 门槛的 Render thread monitor 等待事件。移动段有约 12.5 ms park。未复现之前 Windows CPU 采样持预算锁导致的 60–129 ms 主线程阻塞；这不表示所有更短等待都不存在。

## 建议实施顺序

1. 同步优化静态间接计划的版本 / 身份短路，以及转向候选集合与命令记录的复用。这两项分别针对静止残余成本和旋转额外成本。
2. 将视野 / 遮挡分类结果和工作优先级按局部版本缓存，减少主线程创建祖先键、重建依赖及完整排序；保留可见区域优先和必要覆盖即时恢复。
3. 源快照改为分页 / 增量发布；覆盖过滤改为局部区域失效，减少上传和索引更新向整个渲染准备链传播。
4. 复测相同位置的静止、原地转向和移动，核对 CPU 准备时间、签名调用、候选访问、列表发布、分配量及 GPU 时间；仍高的 GPU 成本再按几何类别优化。

本轮无需通过降低画质、关闭可见优先调度或重新叠加复杂遮挡算法来解释这些热点。具体收益必须修改后重新测量。

## 原始证据

目录：`C:/Users/Administrator/.codex/tmp/vss-turn-profile-20261010-154822`。

- `turn.jfr`、`still.jfr`、`rotate-only.jfr`：原始三段录制。
- `turn/counters.ndjson`、`still/counters.ndjson`、`rotate-only/counters.ndjson`：原始计数；前两段另有 repaired 文件。
- `window-counters.json`、`still-counters.json`、`rotate-only-counters.json`：有效窗口增量。
- `turn-windows.json`、`still-windows.json`、`rotate-only-windows.json`：按窗口的调用栈、分配和等待统计。
- `windows.tsv`、`still-ranges.tsv`、`rotate-only-ranges.tsv`：精确比较区间。
- `loaded-vss.jar`、`loaded-package-check.json`：加载包冻结副本及核验。
- `inspected-source/`、`inspected-source-manifest.json`：读取的源码快照和哈希。
- `TurnProfileAgentV2.java`、`JfrWindowReport.java`、`turn.jfc` 及 Python 汇总脚本：采集 / 分析方式，可复核。

采样本身有少量开销；后台诊断读取原地转向每次最长约 0.74 ms，静止最长约 0.96 ms，首段第一次诊断读取约 13.6 ms，均在独立诊断线程。不能据当前两秒计数提供单帧极值、1% low、精确互斥 CPU 毫秒或纯 GPU 忙碌比例。

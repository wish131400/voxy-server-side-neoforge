# prediction vegetation cache read 20261016

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**植被修改生效检查与缓存读取调查，2026-10-16**

当前客户端安装的是 10 月 15 日的小植物 LOD 优化包；运行日志确认植被显示选择确实减少了候选四边形。缓存恢复偏慢的突出成本出现在读取记录之后：重新平面合并、重新生成显示 LOD、重建范围及网格元数据。现有数据不支持把磁盘带宽或独立缓存线程数量列为第一原因。

本轮检查生产源码、已保存的运行日志及 JFR，未修改生产源码、构建 JAR 或替换实例文件。两个仓库同步保存本报告；实时结论来自 NeoForge 1.21.1，未对 Forge 客户端进行在线采样。

**客户端与测量范围**

- 实例：`F:\NovaEngineering-World整合包\.minecraft\versions\1.21.1-NeoForge_21.1.249`。
- 模组：`mods/vss-0.3.5-neoforge-1.21.1.jar`。
- 安装包和仓库 `lib/ground-cover-20261015/vss-0.3.5-neoforge-1.21.1.jar` 的 SHA256 相同：`ff07b5ff0496fe933ebd4aba03cb696a57f9e7208a291f5bc36059a41a4ebbc3`。
- 预测及预测加载开启，树木开启，植被数量 `medium`；预测范围 8192 方块，配置精细范围 1536 方块，完整地表显示保护距离 512 方块；网格压缩开启，后台任务分类上限 2。
- JFR：`tools/prediction/cache-read-20261016/live-cache.jfr`，本地时间 00:09:15.147 至 00:10:15.188，约 60.04 秒。
- JFR 共 196527 个事件，其中 Java / native 执行样本 30459 个；Render thread 5407 个，8 个 `vss-prediction-overworld` 线程合计 13543 个。
- 日志在 00:09:24.631、00:09:41.332 记录过暂停。不能把本次窗口当作连续、无菜单的 FPS 对照，亦不能用它评价本轮优化带来的 FPS 增益。
- 后续核查时 Java / Minecraft 进程已退出，日志最后为 00:11:02.465 的 `VoxyInstance: Instance shutdown`；没有再次启动客户端或附加新的采样。

**植被修改：已确认的效果与尚未单独量到的部分**

实际运行调用栈中可见 `PredictionVegetationSelection.select`、`PredictionVegetationDisplayCache.selectSources`、`PredictionRenderer.PreparedPass.prepareAquatic`、`PredictionDisplayLod.update` 等路径。

两次不透明通道快照如下。数字是各次快照的候选量，不是这一分钟累计绘制量，也不是同一固定场景的 A/B。

| 日志时间 | 完整源候选面 | 选定地形 LOD 后、植被筛选前 | 植被筛选后 | 植被排除面 | 使用地形显示 LOD 的瓦片 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 00:09:18.784 | 12710974 | 12710974 | 12465050 | 245924 | 0 |
| 00:10:14.042 | 18696637 | 17783528 | 15837338 | 1946190 | 193 |

第二次快照中，综合植被筛选排除了约 194.6 万面，占该快照植被筛选前候选量约 10.94%。地形显示简化另外减少 913109 面，两者合计为日志的 `displayExcludedQuads=2859299`。后续所有权和方向筛选后为 `afterVoxyCandidates=15790858`。

这里的 `vegetationExcludedQuads` 定义是 `terrainCandidates - displayCandidates`。它合计水草、原有植被 LOD 及新增小植物范围选择的效果，不能把 1946190 面全部记作“新增小草优化收益”。当前日志和 JFR 没有逐瓦片的小植物档位、分类排除数，未取得新的 GPU 选择缓冲快照；因此，本轮确认了正确安装包和综合植被减面，尚未单独量化新增小植物 0～4 档在该场景的占比。

源码中的新增规则仍为：小草、蕨类和符合条件的交叉面花朵按屏幕尺寸逐档变稀，最远档停止提交；近处、放大视野、混合归属与过渡状态采用相应细节保护。本实例配置将完整地表距离设为 512 方块，512 方块内不能按“应该已经隐去小草”判断失效。树叶、竹子、甘蔗、农作物及特殊模型不属于新增的完全移除对象。完整源几何继续保留，减面不等于等比例减少内存或显存。

**实际缓存恢复流程**

1. `PredictionTileManager.restoreCached()` 扫描有更高缓存精度的候选，将 `cacheOnly` 工作交给独立恢复池。普通地形、地表任务也会在常规生成池中读缓存，并非所有命中都经过独立恢复池。
2. 任务检查期望范围、版本、内存预约及资源身份状态，获取 terrain lease。已有驻留数据的部分升级会直接复用内存，刻意跳过地形记录读取。
3. `PredictionRegionStorage.read()` 在 `.vpr` 区域文件内定位记录，读取一个压缩 `byte[]`；旧单文件格式仍可回退读取。区域句柄、索引及路径有缓存。
4. `PredictionDiskCache.read()` 校验外层 magic、schema、fingerprint、坐标、detail 及必要的模板身份，经过 zlib 和 32 KiB 解压侧缓冲解析。terrain 记录恢复方块 / biome 映射、列样本、体积区间和颜色。
5. 颜色身份与资源身份满足条件后，`readMeshBaseRecord()` 独立读取、解压 mesh 记录，校验网格签名和城市条件。并非“读取一次文件就得到可直接上传的所有数据”。
6. `PredictionMeshCodec.decodeRecord()` 分配四边形数组，重映射材质，恢复面范围和接缝，随后调用 `PredictionPackedMesh.scanMerged()`。
7. `scanMerged()` 再做平面合并；构造 packed mesh 时重新执行空间排列、植被分组、`PredictionDisplayGeometry.build()`、meshlet bounds 和 ownership runs 构建。持久化只保存 canonical 源范围，显示 LOD 派生几何没有直接随记录恢复。
8. 任务准备 GPU 存储格式、上传暂存及可选的内存压缩，校验版本后发布，再由渲染流程完成 GPU 交接。
9. 独立 `cacheOnly` 路径没有可用成品时退出并延后重试，不会直接转成世界生成。常规任务才会继续使用捕获、内存复用、父级聚合或地形 / 植被生成等路径；不能把所有 `diskMiss` 都等同于一次完整重新生成。

**缓存读取调用栈中的主要成本**

普通预测线程有 2540 个执行样本包含 mesh 读取路径；独立缓存恢复线程另有 12 个，共 2552 个。以下按离栈顶最近的相关子阶段互斥归类，是采样数量，不是逐记录计时。

| mesh 读取内部子阶段 | 普通预测线程样本 | 加上独立恢复线程的样本 |
| --- | ---: | ---: |
| 平面合并 / 二维扫描 | 1381 | 1389 |
| 显示 LOD 构建 | 885 | 889 |
| bounds / ownership 元数据 | 119 | 119 |
| 范围和空间分组 | 73 | 73 |
| 材质相关 | 24 | 24 |
| 解压相关 | 22 | 22 |
| region 读取相关 | 7 | 7 |
| 解析与其他 | 29 | 29 |

平面合并与显示 LOD 合计占这些 mesh 读取调用栈样本约 89.3%。这说明“成品网格缓存命中”仍包含明显的网格预处理，不能理解为直接恢复最终静态提交数据。两个阶段有算法调用关系，但本表已按栈顶相关阶段归类，不再把 inclusive 方法样本叠加。

记录到的 `jdk.FileRead` 只有 7 次，合计逻辑字节 1019977、耗时 23.9858 ms。文件事件阈值为 1 ms，低于阈值的读取没有列入；不能把这 1 MB 当作本轮总读取量或计算硬盘吞吐。结合上述 CPU 栈，目前更充分的证据指向读取后的 CPU 重建。Render thread 中没有捕获到超过 2 ms 阈值的 monitor enter / park 等等待事件，也没有缓存读取调用样本；没有发现其被缓存 I/O 锁持续阻塞的证据。

**命中、缺失和恢复增量**

取 debug.log 第 18924、19390 行，即 00:09:18.784 至 00:10:14.042 的约 55.26 秒。它们位于 JFR 内，但不等于 JFR 的精确起止。

| 累计计数器 | 窗口增量 | 含义 |
| --- | ---: | --- |
| `buildSources.diskHit` | 549 | 构建使用了磁盘地形样本，包含成功成品恢复 |
| `buildSources.diskMiss` | 678 | 构建未使用磁盘地形样本，也包含主动跳过磁盘、使用驻留数据的升级；不是独立磁盘缺失计数 |
| `finishedMeshRestores` | 328 | 提前恢复成品并发布，属于 diskHit 的子集 |
| `disk.hits / misses` | 8294 / 1899 | 各类记录读取，不能当作唯一瓦片数 |
| `disk.meshHits / meshMisses` | 590 / 488 | 两种 mesh 查询路径合计；同一瓦片可能多次查询 |
| `disk.writes / meshWrites` | 2492 / 287 | 读取同时伴随生成和写入；meshWrites 属于总 writes 的子集 |
| `deferredEncodes / deferredEncodeMs` | 2205 / 4041 ms | 串行写入池仍在编码新记录 |
| `resourceDeferrals` | 0 | 累计值虽然为 3580，这段没有新增身份等待 |
| `cacheFastMisses` | 0 | 这段没有新增独立 cacheOnly 快速路径失败 |

外层 miss 原因中 `absent` 增加 1899，`stale / identity / corrupt / raced` 均增加 0，`errors` 也增加 0。这一层主要是没有记录，没有观测到格式损坏或 fingerprint 拒绝增长。mesh decoder 的签名不匹配也可能正常返回 null，计入 meshMiss；当前没有独立原因计数，不能据此外推“所有 meshMiss 都是文件不存在”或声称查明每个 miss 的原因。

同一时间段 `stageDetailMs.native` 增加 74261 ms，`stageDetailMs.disk` 增加 1197 ms。这些是跨后台任务累计的阶段墙钟时间，有并行重叠；`disk` 在这里主要计 terrain lease / 读取 / 解析，不覆盖后面的全部 mesh 解码，不能相加为加载总时长或直接推算瓶颈占比。

**并发和资源身份并不是这段已证实的第一限制**

独立恢复池固定 2 个 worker，`CACHE_RESTORE_IN_FLIGHT=4`；它绕过常规生成 CPU 预算，仍受内存与有效性检查保护。该池只有 15 个执行样本，其中 12 个在 mesh 读取中，日志也没有显示本窗口持续占满。因此不能把“四个在途”直接当作本次慢恢复的原因。8 个常规预测线程同时有缓存解码、网格重建和生成活动；提高恢复并发前应先减少重复 CPU 工作和分配。

资源 fingerprint 在后台生成；未就绪时，cacheOnly 路径会短暂退避。相关检查已放在地形读取之前，防止每次退避都重复读取、解压。同一任务在读取后也有再次检查，处理期间发生资源重载的情况。本窗口身份 deferral 增量为 0，不能把早期累计的 3580 次当作当前仍在发生。

**分配与 GC**

分配总量使用 `jdk.ThreadAllocationStatistics` 同一线程的首尾计数差，观测跨度 59.94 秒。`ObjectAllocationSample.weight` 用于定位热点，不拿它简单求和充当这一分钟真实分配量。

| 线程组 | 有首尾计数的线程数 | 累计新分配 | 平均分配速率 |
| --- | ---: | ---: | ---: |
| 常规预测线程 | 8 | 33.73 GiB | 576.3 MiB/s |
| Render thread | 1 | 3.17 GiB | 54.1 MiB/s |
| 客户端规划 | 1 | 2.02 GiB | 34.5 MiB/s |
| 预测磁盘写入线程 | 1 | 0.35 GiB | 6.0 MiB/s |

这是累计创建对象的总量，不是同时占用量或内存泄漏证明。上述常规线程还包含生成工作，不能全记到缓存读取。主要分配热点涉及平面合并、显示 LOD、中间 `int[]` / `Arrays.copyOf`、mesh 数组解码、材质编码、植被选择集合扩容及 bounds / ownership 元数据。

JFR 有 57 个 GC pause phase，合计约 514.7 ms，最长约 28.3 ms；顶层 `GarbageCollection` 事件为 46 个，不能把 phase 数直接表述成 57 次独立 GC。临时分配与 GC 会加重部分卡顿，但不足以用此证明所有低帧均来自 GC。

**建议的实现顺序**

1. 优先减少 mesh 命中后的重复预处理。用算法 / 格式版本明确区分已合并源记录，评估恢复时跳过重复平面扫描；缓存显示 LOD、局部 bounds 和几何 ownership 元数据，使可验证的静态派生数据可以直接恢复。当前 Voxy 覆盖决定、相机选择和本次会话 morph 状态仍要重新计算。
2. 减少重建暂存分配，复用有上限的工作数组，预估集合容量，并让同一任务复用已读记录。是否存在同一 key 的反复读取，需要补充 key / lease revision 追踪后再判断；本轮未证明可以靠跨任务解码缓存获得多少收益。若增加解码缓存，必须按字节限额、校验 fingerprint / resource identity / lease revision 并支持失效，避免加剧内存占用。
3. 补细分类指标：小植物各档瓦片和排除面、terrain 缺失与主动跳读、mesh 缺失与签名拒绝、逐阶段耗时和同 key 重读次数。把读取恢复和新区域生成窗口分开测，避免用混合负载计算纯缓存速度。
4. 在上述改变后，再根据恢复池饱和度、CPU、GC 与加载耗时决定并发。保留资源重载、城市身份、材质重映射、Voxy 覆盖撤销和接缝交接的有效性检查。

本轮定位完成，尚无改动后的加载耗时或游戏 FPS 对照。无需清空已有缓存来实施后续兼容格式；应保留旧记录恢复路径，再按需写入新的可验证派生数据。

**证据位置**

原始 JFR、详细解析 JSON 与解析器保存在 NeoForge 仓库 `C:\Users\Administrator\Desktop\voxyserverside-neoforge-1.21.1` 下；报告和精简的 `evidence-summary.json` 同步到两个仓库。

- `tools/prediction/cache-read-20261016/live-cache.jfr`
- `tools/prediction/cache-read-20261016/CacheJfrReport.java`
- `tools/prediction/cache-read-20261016/cache-jfr-report.json`
- `tools/prediction/cache-read-20261016/evidence-summary.json`
- 实例 `logs/debug.log` 第 18924、19390 行及 `logs/latest.log` 的暂停 / 退出记录。
- 植被规则与上一轮离线回归：`docs/history/rendering/prediction-ground-cover-lod-20261015.md`。
- 生产入口：`PredictionTileManager.java:2120,2357,2366,2458`，`PredictionDiskCache.java:170,415,758`，`PredictionRegionStorage.java:85`，`PredictionMeshCodec.java:239,302`，`PredictionPackedMesh.java:411,427`，`PredictionRenderer.java:279,473,605`。

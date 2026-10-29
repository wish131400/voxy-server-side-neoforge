# 预测内存常驻优化实现与验证（2026-10-10～11）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

已同步 NeoForge 1.21.1 与 Forge 1.20.1，并构建 0.3.5。本轮以 `prediction-compression-memory-review-20261010.md` 的核验结果为依据，处理归属区段表、冷元数据、可回收 CPU 缓存与压缩槽繁忙后未再尝试的问题。任务与产物目录沿用开始日期 `memory-residency-20261010`，验证在 2026-10-11 零点后完成。

## 实现

### 1. 归属区段紧凑布局

新增 `PredictionOwnershipData`。将结束位置与两位归属类型合成一个 int；X/Z 保留原算法扩张后的 16 方块边界，使用相对局部基点的四个 unsigned16 偏移。足够多的重复 footprint 使用共享 long 字典，否则每条直接保存两个 int。Y 使用精确 1/16 方块单位；跨度超过 ushort 时保留原始 float bits。不能精确表示的坐标、负零与极端浮点值进入完整 float bits 保底布局。

常见热态每条为 12～16 bytes（不含摊销很小的对象头/字典），原布局约 29 bytes。宽高度或非常规坐标仍可能使用更多空间。`PredictionOwnershipRuns` 只持有新表及小型整体 bounds/flags，构建所用的 ends/boxes/kinds 数组不再常驻。热点归属判断随机读取紧凑记录，不逐帧解压，不改变面顺序、范围、覆盖证明或整棵预览的交接范围。

### 2. 冷元数据后台压缩与恢复

新增 `PredictionMetadataCache`，单后台线程、最多 16 个任务、64 MiB 工作区预约，要求额外 128 MiB 堆余量。恢复优先于压缩。编码沿用现有 LZ4/Zstd 无损机制；不足 64 KiB、超过 16 MiB 或收益不够时保留热表。

`PredictionGpuTile` 对已上传 payload 的元数据持有 pin，防止渲染中变冷。后台 freeze 还检查 data 身份、版本与 pin，处理“压缩排队后刚好上传”的竞争。新 payload 变冷时，先后台恢复，再允许替换 GPU 网格；等待期间保持旧地形可绘制。冷数据不能用于证明可以剔除预测面。覆盖撤销、网格替换、关闭与世界 reset 均有对应恢复/释放验证。

### 3. 可回收 CPU 冷缓存字节目标

新增 `PredictionCpuCachePolicy`，高水位为 `clamp(maxHeap / 12, 128 MiB, 768 MiB)`，低水位为其 75%。例如较大堆使用 768/576 MiB。每两秒检查一次；超高水位后逐步回收到低水位，每次最多回收 32 块，优先旧数据。同一次回收不会因刚回到高水位以下就停止并反复进入。

只回收已落盘、离开当前需求且至少 10 秒未使用的候选；保护近处 256 方块、望远镜范围、pending 工作和缺少 CPU 祖先回退的数据。如果正在可绘制集合中，还需要已上传的有效 GPU 祖先；具有植被/建筑的块还需要 feature 回退。压缩状态变化也会重新核算 resident reservation 的字节。

**这个水位约束可安全回收的冷缓存，不是整个 VSS 或整个游戏的堆上限。** 当前活动覆盖、唯一回退与交接保护可能继续保留较大数据，不能为了追水位删掉唯一覆盖。已落盘的数据仍可从原缓存恢复。

### 4. 压缩槽繁忙后的有界重试

新增 `PredictionCompressionRetry`。发布阶段两个压缩槽均忙时，把一次延后压缩送入单后台线程；最多 8 个任务、64 MiB 工作区预约，保留 128 MiB 堆余量。不让渲染线程等待压缩。已完成 GPU 上传的 payload 延后压缩后释放原数组及 pass 副本，不再补留首次上传数据；world epoch 防止旧世界重填上传恢复缓存。分别计数 compressed、savedBytes、busy、unprofitable 和 retry。

## 真实几何离线核验

回放之前捕获的 3,890 个网格、22,668,765 个四边形、13,476,498 条归属区段。对每条结束位置、类型、六个坐标的 float bits 与过滤后绘制范围逐项核对旧实现，结果一致。该回放使用真实数据，不读取或修改运行中的游戏。

| 归属表保留量 | Bytes | MiB | 相对原布局 |
| --- | ---: | ---: | ---: |
| 原布局 | 391,316,362 | 373.19 | 基准 |
| 新热态布局 | 173,326,688 | 165.30 | 减少 55.71% |
| 按冷却条件压缩后的混合布局 | 115,305,893 | 109.96 | 减少 70.53% |

热态节省约 207.89 MiB，冷热混合节省约 263.22 MiB。这些只比较该批网格的归属表，**不是换包后整个客户端堆或帧率的实测降幅**，也不能线性套用到上轮 55,320,062 条区段的现场快照。

单次回放的旧/新构建累计时间为 1000.931/1687.352 ms，过滤为 93.380/113.161 ms。该流程包含 JIT、固定顺序及构建编码，不能作为公平稳态速度比较；它表明紧凑解码有额外 CPU 工作，不应宣称本轮必然提高 FPS。完整逐项一致性与内存量是本轮确认的结果。

## 回归与产物验证

| 平台 | CPU | OpenGL | 构建 |
| --- | --- | --- | --- |
| NeoForge 1.21.1 | 190 项：189 通过、1 条件跳过，0 失败 | 9 项：8 通过、1 条件跳过，0 失败 | JDK 21，jar 成功 |
| Forge 1.20.1 | 188 项：187 通过、1 条件跳过，0 失败 | 9 项：8 通过、1 条件跳过，0 失败 | JDK 17，jar + reobfJar 成功 |

新增回归覆盖紧凑编码字典/独立 footprint/宽高度/float 保底、异常元数据拒绝、冷恢复与覆盖撤销、实际 GPU pin、等待新数据期间保留旧网格、释放 pin 后允许冷却、队列上限与 reset、字节预算高低水位、manager 对地形与 feature 回退的保护以及延后压缩与上传/reset 的竞争。原有回归还覆盖普通/Iris 深度模式、MDI/直绘、透明水面、分段密集森林与压缩上传。

CPU 跳过项为 `PredictionGpuEncodingTest.realCorpusRoundTripAndEncodingCost`，因未配置外部语料。GPU 跳过项为 `PredictionVoxyDepthConventionGpuTest.installedFinalBlitPreservesPhysicalWaterDepthDespitePropertyMismatch`，因该次测试命令未注入 `vss.voxyJar`；不等于该场景已验证。普通/Iris 生产 shader 的 feature 交接测试本轮已执行。

两个 JAR 的 ZIP 完整性、0.3.5 平台版本、生产新增类、字节码平台版本与五个平台 native 文件已核对；native 文件与前一轮内存包一致。NeoForge 生产修改类与本轮编译输出逐字节一致；Forge 分发 JAR 与 `reobfJar/output.jar` 的 SHA256 一致。测试、回放与旧算法参考类没有进入生产 JAR。

- NeoForge：`lib/memory-residency-20261010/vss-0.3.5-neoforge-1.21.1.jar`
- Forge：`lib/memory-residency-20261010/vss-0.3.5-forge-1.20.1.jar`

各产物目录有 `SHA256SUMS.txt` 与 `build-verification.json`。机器可读证据同步在同名 `.json` 中。构建日志与任务前后生产 diff 保存于 `C:/Users/Administrator/.codex/tmp/vss-memory-residency-20261010`。

## 实际内存解释与验证边界

无损压缩以前主要覆盖几何正文；归属表、接缝摘要、样本、工作区、GPU/native 数据与其他模组不会自动一起压缩。本轮进一步压缩归属元数据并限制可回收冷缓存，但活动覆盖所需数据仍有成本。JVM 已提交堆也可能在对象释放后维持高位；应分清 heap used、GC 后存活量、committed 和进程工作集。

本轮没有替换实例、重启客户端、推送 GitHub、强制 GC、heap dump 或新的客户端采样。用户自行换包后，还需在相同世界、配置和位置测量持续运行与跑图时的 heap used/GC 后存活量、cpuCache/metadataCache/meshCompression 计数及帧时间，才能确认整机效果。

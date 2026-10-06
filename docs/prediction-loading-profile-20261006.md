# 2026-10-06 当前客户端预测加载与预生成放行诊断

本轮为只读采样和源码核对，没有修改游戏设置、运行代码或安装包。所有时间为 Asia/Hong_Kong。

## 运行环境与证据

- 实际游戏为 `DeceasedCraft_Beta`，Minecraft Forge 1.20.1，PID 53104；不是此前测试的 NeoForge 1.21.1 客户端。
- 安装的 VSS JAR SHA-256：`e5819878eb588f55ba0a761dc5cfe1472f30c363b21c58d52988e7d1b5f54e84`。
- CPU：Ryzen 7 7700，8 核 16 线程。
- 独立证据目录：`C:/Users/Administrator/.codex/tmp/vss-loading-20261006/`。
- `loading.jfr`：60 秒 JFR，约 03:19:15–03:20:15。期间包含菜单暂停和恢复，因此不据此比较 FPS；用于后台加载调用栈、CPU 与分配。
- `loading-counters.jfr`：另一个 45 秒 JFR；`sample-00.txt` 至 `sample-12.txt` 为 36.174 秒、13 个主线程诊断快照。
- 阶段增量主结论采用 sample 00–09，即 03:21:26.164–03:21:53.319 的 27.155 秒，避开后续本地分析进程的额外负载。原始计数器读数不是事务快照，跨字段可以存在少量时点差异。
- `jfr-analysis.txt`、`jfr-details.txt`、`allocation-window.txt`、`counter-clean-window.json`、`invalid-colors.json` 保存分析结果。使用有内存上限的 Java RecordingFile 流式分析；不以巨大 JSON 导出作为分析输入。

## 首要问题：原生地形后端没有启用

当前客户端日志明确记录：

```text
03:13:26.532 VSS native terrain snapshot rejected for minecraft:overworld;
prediction keeps the Java sampler:
java.lang.IllegalArgumentException: invalid biome color grass_color
03:13:26.537 TerraBlender positional-region backend ... terrain=java
03:13:26.710 VSS prediction dimension ready ... rust=false
```

只读导出当前已接受的注册表快照后，发现以下三个颜色：

| 生物群系 | 字段 | Java int | 32 位十六进制 | 低 24 位 RGB |
|---|---|---:|---|---|
| zombie_extreme:scorched_earth | grass_color | -13158105 | FF373927 | 373927 |
| 同上 | foliage_color | -13158105 | FF373927 | 373927 |
| 同上 | water_color | -12100265 | FF475D57 | 475D57 |

`tools/rust/vss-native-core/src/biome.rs:45` 的解析器只接受 `as_u64()` 且不大于 `0xFFFFFF`。因此，这组 Java 有符号颜色值会导致整个原生地形快照失败，而不仅是该生物群系的颜色回退。`RustTerrainSampler.java:110` 捕获拒绝并保留 Java sampler。

建议首先修正 Java/Rust 边界的颜色兼容语义：按实际 Java RGB 消费方式处理合法 32 位颜色，验证低 24 位结果一致，保留对类型错误及超出契约范围值的拒绝。不得直接忽略颜色错误或猜默认颜色。验证应包含当前真实快照及三个值；修掉此拒绝点后仍须走完原生导入，确认是否还有后续不兼容项，不能提前保证一定得到 `rust=true`。

## 加载耗时集中在 Java 采样，不在构网/打包

27.155 秒快照窗口内的阶段计数器增量：

| 阶段 | 多个工作线程累计耗时 |
|---|---:|
| sample 总窗口 | 155.645 秒 |
| surface / 装饰窗口 | 8.468 秒 |
| mesh | 2.880 秒 |
| pack | 0.870 秒 |
| disk 读取子阶段 | 0.132 秒 |
| commit 子阶段 | 0.019 秒 |

这些是多线程阶段计时的求和，不是单帧时间，也不是互斥 CPU 占比。处于窗口边界的长任务会一次性提交累计时间。

尤其注意两个现有指标名称容易误导：

- `native=13.093 秒` 包围 `sampleGridFast`，Java 后端同样会进入，不能用它证明 JNI 已工作。
- `color=144.084 秒` 不仅是着色，还包含缺失列的 `sampleAt` 和 `PredictionExteriorColumns.enrich`。真正记录的 `tintMs` 只增加 1.074 秒，`resolveMs` 增加 14.234 秒。不能因此把“颜色混合”判为 144 秒的热点。

实际 JFR 调用链包含：

```text
PredictionTileManager.enqueue task
  PredictionExteriorColumns.enrich
    ClientTerrainSampler.exteriorFootprints
      PredictionJavaExterior.ordered
        NoiseBasedChunkGenerator.iterateNoiseColumn
          NoiseChunk construction / density graph mapping / noise evaluation
```

`PredictionJavaExterior.java:92` 的 ordered 路径为每个 footprint 内的柱重新调用原版 iterator，从世界顶部向目标下界遍历；`step=4` 在未能提前结束时最多涉及 16 根柱。`PredictionExteriorColumns.java:56` 的补充工作针对 step <= 4 的悬崖，并会向已确认悬挑的邻居传播。它处于发布网格前的同步任务内，因此会拖慢可见结果。

首段 JFR 的 20,736 个预测线程执行/原生方法样本中，7,605 个能直接归到 exterior 调用链。默认栈深导致不少样本截断，所以这是可直接识别的 36.7%，不是对完整 exterior CPU 占比的精确测量。另有明显的 surface 高度/密度采样热点。

JFR ThreadCPULoad 中 8 条预测线程平均合计约 0.391 的 16 逻辑核归一化占用，即约 6.26 CPU 秒/秒。系统单点负载观测为 77–100%；预测是 JVM 的主要工作负载，但不能把系统全部占用算给它。

## 大量临时分配与重复建图

按 Java thread ID 分别对 ThreadAllocationStatistics 做首尾差分，59.9005 秒内：

- 8 条预测线程合计分配 101,700,813,608 bytes，即 94.716 GiB，约 1.581 GiB/s。
- 占有首尾统计的全部 Java 线程分配增量约 90.1%。这是累计临时分配量，不是同时驻留内存或泄漏量。
- 分配调用栈集中在 `NoiseChunk` 构造、密度函数映射、`CubicSpline.Multipoint` 的图转换、Stream/List 与密度节点对象。
- 计算热点还包括结构化密度对象的 `hashCode`、fastutil map 查找及噪声计算。
- 60 秒内 45 次 GC pause，总暂停 559.7 ms，最长 29.4 ms。GC 停顿不是全部耗时；分配、建图、计算本身仍消耗 CPU 和内存带宽。

ObjectAllocationSample 仅用于定位类型/调用路径，并丢弃每线程首个可能带录制前权重的样本；没有把 weighted 总量当作真实分配总量。

建议 Java 保底路径按 noise cell/区域批处理、复用线程内可安全复用的工作上下文，避免每柱重建同一密度图。必须验证 aquifer、插值、模组有状态密度函数和查询顺序语义。不得为了提速强制关闭 ordered 或假定所有节点纯函数。

还应将昂贵 exterior 精化和首轮覆盖分为可独立计量/调度的工作：先发布仍保持封闭的基础地表，随后近处按预算补充真实悬挑，使用现有 epoch/版本检查原子替换，避免再次引入裂缝。不能直接删除 exterior 探测来换速度。

## 不是只有两个预测线程

运行快照：`pool=8`、`workerLimit=8`、`refinementLimit=6`、`backgroundLimit=2`。`predictionBackgroundWorkers=2` 只限制判定为后台的工作，不限制整个 executor。

当前 medium 档在 16 逻辑核上建立 8 线程池、自动精化上限 6。帧率熔断仅在 medium 档平均帧率低于 30 时减载，不是 CPU 满载检测。本轮快照 `throttle=NONE`，因此 CPU 高占用仍会持续分配任务。

建议统一预算：覆盖、细化、exterior、装饰和本地预生成共享可用 CPU/帧时间余量。降低线程数只能暂时改善竞争，通常会延长完成时间，不能修复重复计算的单位成本。

## 为什么预生成会在预测仍精化时启动

`PredictionTileManager.java:1606` 的 `refreshLoadingProgress` 定义的是基础覆盖：

1. medium frontier 的 tile 达到 cellAxis >= 32。
2. 近处 leaf 达到 `min(64, ordinaryTarget)`。

它不要求所有 desired tile 完成最终目标精度，不等待全部 exterior、植被/装饰、空闲细化，也不代表 GPU 已完成当前画面的交接。

真实快照 00 中同时存在：

```text
coverageReady/coverageTotal = 234/234
nearReady/nearTotal = 192/192
terrainRemaining = 988
terrainPreview = 681
desired = 1661
```

后续 13 个快照的上述两个进度比例始终为 100%，通常仍有约 950–990 个 tile 需要达到目标地形状态。期间移动/计划变化也可短时增加目标集合。ready resident 数、desired 数、remaining 数口径不同，不能直接互相相减计算进度。

`PredictionGenerationPriority.java:19` 的现行放行规则：

- 两项基础进度都达到 95% 并持续 1 秒，进入 ramp。
- 或 score 45 秒没有超过历史最佳，或本轮 hold 满 120 秒，即使不足 95% 也进入 ramp。
- ramp 从 1/4 配额开始，每秒加 1/4，约 3 秒后达到全额。
- 正常放行后，基础进度低于 85% 持续 2 秒可重新暂停；超时放行进入 STALLED 后不再按这个回退门槛暂停，直到基础进度达标恢复 NORMAL。
- 它只控制新增 generation 请求；已发送请求、已有数据同步、dirty refresh 和原版实际视距区块加载不受这个门控完全暂停。

所以当前“预测优先”并不等于“预测绝大部分最终细节完成后才预生成”。基础覆盖进度过早完成，以及超时后全额恢复，均与用户期望存在差距。

当前会话日志进一步显示：

```text
03:13:17.324 generation=enabled, revision=1
03:16:53.874 generation=disabled, revision=2
```

本轮 13 个快照全部 `generationPriority=disabled, generationLimit=0, inFlight=0`。因此本次加载 CPU 热点是在预生成关闭时仍然存在的。没有记录之前放行瞬间的 phase，不能确定此前启动究竟命中 95% 还是超时条件。

建议调整门控契约：

1. 保留基础覆盖优先，同时增加近处实际目标精度和重要可见地表的完成度/工作债务门槛；分母固定在所定义的有效范围，避免跑图时一直变化导致永久饥饿。
2. 只有在预测工作债务和 CPU/帧时间预算都允许时，渐进开放本地预生成。远程服务器预生成与本机 CPU 竞争应区别处理。
3. 超时只给低配额探测/保活额度，并设重复评估冷却；不能超时后直接恢复全部并发，也不能永远不给预生成执行机会。
4. 记录放行原因、基础/目标完成度、在途请求和当前配额，让诊断能分辨“基础覆盖达标”“精化完成”“超时保活”，而不是只有 phase 名称。

## 实施优先级与验证

1. **P0：颜色序列化兼容，恢复原生后端可用性。** 用真实注册表离线导入，检查下一个拒绝点；Java/Rust RGB 与地形输出一致性通过后，再验证客户端 `rust=true`。不承诺修掉一种拒绝就一定能支持全部模组图。
2. **P1：Java exterior 重复建图和临时分配。** 逐步做共享上下文/批处理与分阶段发布，保留有状态遍历语义和悬挑正确性；增加独立 exterior 计时。
3. **P1：预生成放行指标与超时政策。** 明确定义“差不多完成”，覆盖进度、目标精化进度和 CPU 余量分开记录；测试移动回退、失败、内存阻塞、关闭/重新开启及已有请求排空。
4. **P2：统一线程预算和重复工作。** 本轮 27 秒还有 17 次 early capture exits、约 16.1 秒累计 captureDiscard 时间；生物群系缓存加载/淘汰各约 37 万次。这些值得优化，但不是先增线程或先改 OpenGL 提交的依据。

本轮没有构建或部署修复包。实现后的验收应比较同后端、同区域、同缓存冷暖状态的每秒有效完成量、到达目标精度耗时、分配速率、帧时间和预生成并发；不能仅比较 FPS 或累计 built 数（它包含细化/重建）。

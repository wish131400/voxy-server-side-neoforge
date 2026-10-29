# Spark Xf7oi32wJg 性能热点分析（2026-10-14）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

来源：https://spark.lucko.me/Xf7oi32wJg 。本轮读取公开报告的原始 application/x-spark-sampler 数据，并通过 Spark viewer 对应的 protobuf decoder 解码，结合两仓库源码定位热点。没有修改生产代码、构建新包、采样正在运行的客户端或改动客户端设置。

## 数据范围与统计口径

- 采样时间：香港时间 2026-10-14 20:36:50.280～20:38:50.664，墙钟时长 120.384 秒。
- EXECUTION / JAVA sampler，间隔 4000 µs。只选中 Render thread，采样权重 120000 ms，对应 30000 个 4 ms 样本。
- 调用树 9281 个节点；全部访问一次、子引用合法、self 权重无负值，self 合计等于线程根权重。
- 三段时间窗口分别约 9.72 / 60 / 50.28 秒。VSS 路径占比分别 78.15% / 67.43% / 71.99%，热点不只出现在开头的短窗口。
- Java sampler 包含线程停留在 native、呈现或等待中的样本。本文百分比是渲染线程的采样权重占比，不是整机 CPU 利用率，也不是 GPU timestamp。
- inclusive 含子调用，self 为节点自身权重。模块分组按整条栈是否经过模块判断，不直接把父子或不同模块的 inclusive 数字相加。
- VSS 归类包含 dev.xantha.vss.* 和注入方法中的 $vss$；Voxy / Sodium 的栈可能同时包含 VSS。
- 平台：Minecraft 1.21.1 / NeoForge 21.1.249；VSS 0.3.5-neoforge-1.21.1、Voxy 0.2.15-beta、Sodium 0.8.12-beta.2、Iris 1.8.14-beta.1、C2ME 0.4.0-alpha.0.112、Lithostitched 1.8.0+beta6。
- 采样出现 PredictionWaterDepthHistory 的新复制路径，可确认该修复代码参与了采样；没有报告中 JAR 的 SHA256，不能由同一个 0.3.5 版本号确认整包与某一构建完全一致。
- VSS drawNormalPass 有样本，drawIris 无样本；本次为普通预测路径。安装 Iris 不代表正在使用光影专用路径。

## 总体指标

| 指标 | 报告值 | 含义与边界 |
| --- | --- | --- |
| CPU | Ryzen 7 7700，16 个逻辑处理器 | 总占用不能说明单个渲染线程是否成为瓶颈 |
| Minecraft 进程 CPU，最近 1 分钟 | 42.14% | 折算约 6.74 个逻辑处理器的平均忙碌量；不等于实际使用线程数 |
| 系统 CPU，最近 1 分钟 | 50.80% | 全系统所有线程的占用 |
| 三个采样窗口的进程 CPU | 48.07% / 44.10% / 42.14% | 报告窗口提供的 CPU 统计 |
| Java 堆 | used 6.884 GiB / max 10.625 GiB，64.79% | committed 7.875 GiB；结束快照，不是采样期峰值 |
| G1 Old Gen | used 5.756 GiB | 快照中的 collectionUsage 同值，不能单凭此值判定泄漏 |
| Java 非堆 | 0.325 GiB | 不包含全部 native 内存和显存 |
| 整机物理内存 | 28.716 / 63.115 GiB，45.50% | 剩余约 34.40 GiB |
| 报告的 swap / virtual 指标 | 42.200 / 72.115 GiB | Windows 下可能包含提交/虚拟内存；不能解释成 42.2 GiB 已换出到磁盘 |
| Spark 客户端 tick TPS | 最近 1 分钟 19.997 | 客户端统计；不能当成服务端线程分析结果，也不是 FPS |
| 客户端 MSPT，最近 1 分钟 | mean 2.596 ms、median 2.189 ms、p95 5.180 ms、max 15.000 ms | 客户端 tick 分布，不是整个渲染帧时间 |
| 磁盘容量占用 | 86.71% | 没有 I/O 延迟或吞吐证据，不能据此把磁盘列为主因 |
| FPS、GPU 利用率/时间、显存 | 未提供 | 不从采样次数或 tick 次数推算 FPS |

GC 为系统元数据的进程累计值，进程 uptime 492.809 秒（约 8 分 13 秒），platform GC 区段为空：

| GC 类型 | 累计次数 | 平均耗时 | 平均间隔 |
| --- | ---: | ---: | ---: |
| G1 Young | 553 | 8.864 ms | 882 ms |
| G1 Concurrent | 328 | 2.058 ms | 1500 ms |
| G1 Old | 1 | 430 ms | 492379 ms |

553 次年轻代回收累计约 4.902 秒，但不能称作“本次 120 秒的 GC 总暂停”。Concurrent 指标也不能直接全部算 STW。430 ms 老年代回收可以产生明显单次卡顿，报告没有该事件时间戳，无法定位到此次窗口。没有 allocation profiler 或堆对象分布，不能推导当前分配率、VSS 独占堆大小或泄漏。

## VSS 是本次渲染线程的主要热点

整条调用栈经过 VSS 的样本为 84252 ms，70.21%；glfwSwapBuffers 为 17596 ms，14.66%；其余为 15.13%。这三项互不重叠，可以相加。

Voxy inclusive 55.60%、Sodium inclusive 69.53% 与上述 VSS 重叠：Voxy/VSS 重叠为 52.80%，Sodium/VSS 重叠为 65.44%。去除所有 VSS 子路径后，Voxy 仍有约 2.80% 样本；这只是调用线程的统计，不是 Voxy GPU 成本。

主要 VSS 入口如下，彼此为不同首次进入 VSS 的路径：

| 入口 | 渲染线程样本占比 |
| --- | ---: |
| Voxy postOpaquePreTranslucent 中 VSS predictionBeforeWater | 50.20% |
| VSS onRenderLevel 回调（主要为 deferred water） | 12.88% |
| VSS onClientTick | 4.03% |
| Voxy 真实 LOD 裙边 / 预测边界绑定（不透明 + 透明） | 2.22% |
| 其余 VSS 入口 | 0.88% |

Voxy 调用 VSS 再返回，所以页面显示 Voxy pipeline 很重时，实际大部分样本位于预测绘制阶段。不能把 VSS 与 Voxy 两个百分比当成独立成本。

## 第一优先级：渲染准备与重复可见性选择

Scene.prepare inclusive 13.893%，PreparedPass.prepare inclusive 12.980%。在本报告中这两条方法的样本没有相互重叠，合计 26.87%。Scene.prepare 含 1.193% 的 geometry upload 路径，不能再把全部网格上传相加。

### 视锥测试

Frustum.cubeInFrustum self 共 7.777%，几乎全部来自 VSS：

| 最近的 VSS 调用方 | 渲染线程 self 占比 |
| --- | ---: |
| PredictionVisiblePlan.updateStable | 3.373% |
| PredictionRenderer.tileWithinHorizon | 3.333% |
| PredictionSeamIndex.intersect | 0.547% |
| PredictionSpatialIndex.intersectFrustum | 0.360% |
| SeamBatch.requiresTerrain | 0.153% |

tileWithinHorizon 参与待上传判断、上传选择等路径；空间 broad phase 后，可见列表还做逐成员检查。报告证明多条路径在支付视锥判断成本，但不能只据方法占比数出同一瓦片被检查的次数。

建议将本帧/视角 revision 的粗可见结果供上传、驻留和绘制选择共享；保留网格/bounds revision 校验以及接缝自身 bounds，避免将 terrain 的可见结果直接套到接缝。

### 归属版本查询

PredictionSubmissionCoverage.selectionVersion inclusive 3.803%；其中 WeakHashMap.getTable self 3.503%。源码在查询每个 packed mesh 的局部版本时调用弱引用缓存；WeakHashMap 访问涉及引用队列维护，但本报告没有单独的 expungeStaleEntries 热点，不能认定全部时间都花在清理已回收条目。

应把局部选择 stamp 与准备条目或 mesh 的生命周期绑定，避免热循环每次走全局弱引用表；如替换为强引用容器，必须设置容量和显式失效/回收，不能恢复此前多余 mesh 引用的问题。Voxy 覆盖撤销仍需及时恢复预测绘制。

### 距离、方向与统计

- PredictionAquaticLod.distance / StrictMath.hypot self 2.353%，全部在 PreparedPass.prepare 之下。它同时为地形/植被显示档位服务，不是只计算水面距离。
- PreparedPass.prepareDirections self 1.170%。
- PredictionVegetationRanges.retainedHeapBytes self 0.977%，来自通道近期条目预算、批次 plan 预算和 CPU 缓存维护等多个调用方。该统计每次遍历已选 ranges，应按版本缓存估算值，不能把统计本身当成植被实际绘制成本。
- PreparedPass 本体 self 1.837%，含条目和缓存组织；单看现有缓存类存在，并不能认为移动/细化时全部命中。

距离比较可以复用同帧结果，或以平方阈值替代适用的 hypot；屏幕误差与进入/退出滞回仍应保留。方向和显示档位的扫描应以实际相关 revision、相机位置/投影变化和 morph 到期为触发条件。

## 第二优先级：提交命令和缓冲更新，水面也是显著路径

drawNormalPass inclusive 23.770%。PredictionIndirectBatch.add inclusive 13.430% 含内部触发的 stage / upload / draw，不能将它当成 13.43% 的纯 Java 组装。

| 调用 | 占渲染线程样本 | 口径 |
| --- | ---: | --- |
| nglMultiDrawElementsIndirect | 8.077% | native self；本报告均在 VSS normal draw 之下 |
| VSS BatchSlot 下的 nglBufferData | 3.283% | native self |
| BatchSlot.upload 全路径 | 4.067% | inclusive，含上述提交和 CPU 处理 |
| PredictionIndirectBatch.add 本体 | 1.860% | self |
| PredictionIndirectBatch.encodeItem | 1.070% | self |

native 调用时间可能包含驱动验证、缓冲分配、提交及等待，不能据此说 GPU 光栅化就是 8.08% 或 buffer 上传就是某个精确毫秒。

renderDeferredWater inclusive 为 12.627%。其中 MDI native self 4.720%、glBufferData self 2.500%、encodeItem self 0.667%。即使水面实际光栅工作不大，这条 CPU/驱动提交路径也有明显成本。此前另一份固定视角报告的水面 GPU 0.05 ms 不能替代本轮数据。

源码已经复用 BatchSlot 对比数组；这次的 glBufferData 样本说明部分批次内容仍在发生变更。报告没有批次数、上传字节和命中计数，不能断言每帧全量重建，也不能计算本轮临时分配量。后续应重点记录：

- opaque / water 各自的 plan hit/miss、region rebuild、encodedItems、buffer upload 字节与调用次数。
- 水面因位置变化进行透明排序后，如何保留静态几何与命令字段，仅更新顺序。
- 采用稳定区域/页面组，局部更新受影响记录，合并过碎的提交。
- 实际 GPU 时间与等待位置，避免以更大一份 CPU 组装替代现有方案。

## 第三优先级：细化优先级的 GPU 反馈并非没有主线程成本

PredictionRefinementOcclusion.sample inclusive 3.917%，poll inclusive 2.457%；其中 glGetNamedBufferSubData native self 2.037%。

源码每 250 ms 提交至多 1024 个候选，使用三个 slots，并在 poll 里先以 timeout=0 检查 fence 完成，再读回。因此已有“避免显式等待未完成 fence”的机制；不能把当前实现描述成每帧无条件同步等待 GPU。

尽管如此，buffer 读回仍在本次样本里明显出现，candidate foreground 筛选和另建 Hi-Z 也在 sample 路径里。该反馈仅影响细化优先级，不直接删绘制几何。适合试验降低频率、只在实际细化候选需要时采集、复用已有安全深度层级，以及减少/压缩读回数据。需要用收益对照验证，不能先认定降低到某个固定频率必然更快。

## 次级成本与当前未成为主要证据的项

- VSS client tick：4.03%，仍有 requestPlan / requestScheduledWork / servicePreparedWork 与 ConcurrentHashMap 遍历等工作。值得继续局部减负，但低于本轮准备与提交路径。
- 全部 geometry upload：1.84%，其中 1.193% 已在 Scene.prepare 内。暂不把几何上传列为第一主因。
- 原有 PredictionRenderTarget.writeDepth：0.177%；新增 PredictionWaterDepthHistory：0.033%。新复制方法确实出现在本轮，但 Java sampler 的调用停留很短不能证明 GPU 带宽成本为零。尚无证据将前述 deferred water 的 12.63% 归因于新深度复制。
- glFinish：0.087%；glGetError：0.003%。不是该报告的主要热点。
- Xaero inclusive 4.383%，有小地图绘制等成本，其栈也与渲染包装层重叠；暂不先优化它。
- C2ME 在渲染线程仅 0.01%。报告没有 C2ME、VSS 生成和集成服务器工作线程的调用树，不能据此说它们没有后台 CPU 成本。
- GLFW swap buffers 14.66%，可能涉及 GPU、VSync/呈现或驱动等待。没有 FPS、VSync 状态和 GPU 时间，不能进一步归因。当前没有 limitDisplayFPS 样本，也不足以排除所有驱动限帧机制。

## 后续处理顺序

1. 复用视锥、上传/驻留可见性选择结果；缩减 PreparedPass 的逐成员 stamp、距离、方向和统计重复工作。
2. 稳定 opaque / water 命令记录和区域，优先拆分水面排序与静态提交；测量内容变化与上传原因。
3. 对细化 GPU 反馈做频率/读回/共用层级的收益对照。
4. 继续减少 tick 中剩余调度扫描；单独用全线程或 allocation/JFR 记录评估后台生成与 GC。
5. 同步保留 GPU timestamp 与逐帧分位数。只有获得对照，才决定是否改用不同 GPU 选择或合并表示，并回撤没有净收益的实现。

本报告能确定 VSS 仍是本次渲染线程的主要热点，CPU 准备和提交组织尚有明显成本；同时存在 native/呈现等待。它不能承诺某项修改可以恢复固定的 20 FPS，也不能将 70.21% 直接转为禁用 VSS 后的 FPS 增幅。

## 证据位置与重现

两仓库各自 tools/prediction/spark-Xf7oi32wJg-20261014/metrics.json 包含经过筛选的元数据、树合法性、方法 inclusive/self、每窗口占比、调用方拆分和 VSS 入口统计，不包含完整启动参数或用户元数据。

原始下载和完整解码、分析脚本位于 C:/Users/Administrator/.codex/tmp/vss-spark-Xf7oi32wJg-20261014。原始文件 Xf7oi32wJg.sparkprofile 大小 1,198,743 bytes，SHA256 fccd0c238d5013962f0151a6473c5037ba100c4c8d2d6de3fd09b9ac6701640e。

读取链接指定的原始数据后，解码器使用项目已归档的 Spark viewer 模块：
tools/prediction/zoom-return-experiments/profile/decode.js，以及同目录 sparkchunks。
再次分析时先运行 decode.js 生成 decoded.json，再运行临时目录的 analyze.py。报告中的热点占比都以唯一树叶 self 权重沿祖先路径累加，并验证 self 总量为 120000 ms；不会因重复祖先方法或跨模块父子层级重复累计。

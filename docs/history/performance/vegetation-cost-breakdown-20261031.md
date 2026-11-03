# 地表装饰开销拆解（2026-10-31）

主要瓶颈是地表装饰放置规则要求的精确地形查询，以及兼容路径里的群系计算。树模型构建、方块传输和最终体素编码都不是本次样本中的主要热点。粗 LOD 减少了地形显示采样点，但装饰仍按完整来源区块运行规则，成本没有随地形间隔同步下降。

## 测量范围

沿用 `prediction-vs-full-generation-20261031.md` 的种子 42、两轮预热、五片独立 16×16 区块区域和实际服务器快照。只补测 4 格地形间隔、地形＋植被＋受支持结构路径，在完整 NeoForge 运行时调用生产算法，未启动渲染。

本次使用当前 `20261031-density-specialization` Windows Rust DLL，SHA256 为 `84b7868277de47a44fc47176ce5cc8a0e90ad3e9990770cb80befc404bed349f`；它与上一轮 DLL 不同。两轮 Java 地形采样器、植被生成器和 Voxy 数据编码器的源码哈希一致。本次数据独立保存，不能把两轮之间的时间差当成本轮工具改动的性能收益。

分阶段用单调时钟计时；正式五轮同时开启 JFR，Java 与原生方法采样周期均设为 5 ms。只统计包含测试调用入口的服务器线程栈，共 1,788 个样本。调用栈比例用于定位热点，不是校准后的 CPU 独占占比；原生栈不能继续拆分 DLL 内部函数。各项包含关系不可相加。

## 分阶段结果

五轮每区域平均：

| 阶段 | 时间 | 占整个数据生产循环 |
| --- | ---: | ---: |
| 地形显示采样 | 273.82 ms | 5.45% |
| 装饰来源生成、合并与整理 | 4,741.67 ms | 94.37% |
| Voxy 数据编码 | 9.22 ms | 0.18% |
| 总计 | 5,024.72 ms | 100% |

进程 CPU 时间平均为 5,909.38 ms，包含 Java、Rust、JIT、GC 等线程，不等于上表墙钟阶段之和。这里的“编码”没有包含 Voxy 构网、GPU 上传和绘制。

装饰内部现有计数器记录到每区域 Java 放置调用平均 3,718.2 ms、原生放置调用平均 676.6 ms。Java 放置计时包含它调用的原生地形查询，不能把它全部解释成 Java 运算。上传加读取计时平均约 29.4 ms；读取后的 Java 校验和应用不在该读取计时中。

## 调用栈与代码依据

| 热点 | 样本数 / 全部样本 | 含义 |
| --- | ---: | --- |
| `RustWorldgenBackend.surfacePoints` | 822 / 1,788，46.0% | 装饰高度、方块与环境检查触发精确地表计算 |
| `PredictionBiomeCache.sample` | 234 / 1,788，13.1% | Java 气候噪声和群系查找，包含 Perlin 与 Climate RTree |
| `RustWorldgenBackend.placedFeature` | 216 / 1,788，12.1% | 原生装饰执行，含它内部的查询 |
| `PredictionTreeModels.place` 路径 | 101 / 1,788，5.6% | 包括树放置规则、查询与模型复用；并非独占树几何成本 |
| `PredictionVegetation.surfaceBlocks` | 19 / 1,788，1.1% | 输出表面筛选 |
| `PredictionLeafStates.settle` | 19 / 1,788，1.1% | 叶状态整理 |

这些项不是互斥分类，尤其树放置与群系查询会重叠。

1. `PredictionVegetation.generationSources` 为 16×16 目标额外准备一圈来源，即 18×18＝324 个来源区块。`spacing` 不会缩小来源数量。正式五轮共生成 1,620 个来源区块，尝试 75,665 次原生放置和 100,915 次 Java 放置，相当于每来源区块约 109 项启用装饰。
2. `PredictionVegetation.generate` 遍历全局启用候选；带 `BiomeFilter` 的条目保留自身过滤流程。`PredictionPlacementExecutor` 保持原版修饰器顺序，部分高度和环境扫描先于群系过滤执行。精确查询样本的调用者包括 `EnvironmentScanPlacement`、`DiskFeature`、`LargeDripstoneFeature`、`GeodeFeature`、海草和积雪。所谓“植被”阶段还承担石盘、地下局部修饰、湖泊和表层修改。
3. 正式五轮 Java 放置中 97,741 次没有写方块，占 96.85%。没有写入可能来自群系不匹配、概率、环境不合适或碰撞等正常结果，不能直接等同于可删除工作；但这些尝试在放弃前已经可能补算地形、气候和高度。
4. `PredictionDecorationLevel.nativeColumn` 的精确分支调用 `RustTerrainSampler.surfaceRecord`；`point` 再以单点调用 `surfacePoints`。粗地形显示结果与精确装饰环境语义不同，所以已有粗网格并不能直接覆盖这些需求。
5. `PredictionDecorationLevel.getNoiseBiome` 缓存未命中时调用兼容上下文的 `noiseBiome`，进入 Java 的 `PredictionBiomeCache`。虽然 Rust 已有批量群系索引接口，这条装饰查询仍走 Java；栈样本确认噪声和群系树搜索确实运行。需要保留外部模组语义，不能直接将所有兼容查询替换为近似群系。
6. 树形模型已有复用。含预热的七轮累计构建 79 个模型，本轮构建计时仅 42 ms。`PredictionVegetationDisplayCache.merge` 发布完整方块给 Voxy 做后续几何归并，4 格地形间隔不会把装饰的来源和写入数量同步降为 1/16。

五轮 GC 暂停合计 27.37 ms，最长 14.65 ms，不能解释每区域约 4.7 秒的持续装饰成本。

## 优化优先级

1. 根据局部群系及特性影响范围准备候选集合，尽早排除确定不可能在来源范围放置的装饰；保留原版全局索引、随机种子和修饰器顺序。不能用来源中心单一群系过滤掉跨边界树木或模组特性，也不能简单把 `BiomeFilter` 挪到前面改变随机消耗。
2. 将精确放置环境按邻域和批次共享，减少 Java 逐点调用，并让原生支持的群系查询真正接入装饰环境。保持精确、显示近似两种缓存语义，针对模组保留兼容回退。
3. 逐特性审查远景需要的地表修改，减少只产生不可见地下结果的完整尝试。不能按整个生成阶段统一丢弃，否则会影响露天地形、结构切口和生态边界。

当前证据不支持优先继续优化树模型构建，也没有证明扩大缓存容量或继续降低显示地形采样密度就能解决主要问题。具体收益需优化后用相同区域与输出检查重新测量。

## 证据与复现

原始数据：`build/generation-comparison-20261031/vegetation-profile/` 下的 `prediction.csv`、`sampler-before-*.txt`、`sampler-after-*.txt`、`features-step4-round*.txt`、`vegetation.jfr`、`jfr-samples.json` 与 `summary.json`。输入哈希记录在上一级的 `vegetation-profile-input-manifest.json`。本次运行前后校验通过，运行时资源 DLL 与记录的 DLL 一致。

在已有对照输入目录中启用 `-PvssComparisonPhase=prediction -PvssComparisonProfile=true` 运行同一 `benchmark.gradle`。补测输出目录为独立的 `vegetation-profile`，已存在时拒绝覆盖；原始 `prediction.csv` 不会被修改。使用 JDK 21 的源码启动器执行 `JfrSamples.java`，再运行 `summarize_profile.py` 生成汇总。

本轮新增的是独立计时和采样工具，没有修改生产生成算法或交付新包。

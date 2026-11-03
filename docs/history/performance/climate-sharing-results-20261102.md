# 水平气候共享：完整路径收益实测（2026-11-02）

已在隔离副本实现 Java 水平输入缓存、Rust 世界级水平输入缓存，以及 Rust → Java 批量读取已存在输入三种原型。最终完整生成耗时相对生产 DLL 减少 **9.98%**，单线程吞吐提高 **11.08%**。逐值校验和最终输出摘要均一致。主要收益来自 Java 跨高度复用；跨语言共享在两边各自缓存的基础上，额外减少约 1.90% 的耗时。

本轮只新增实验工具与历史记录，未修改生产源码、原生库或现有 JAR。

## 条件与完整路径

- CPU：AMD Ryzen 7 7700，8 核 16 线程；Windows x86_64，Java 21.0.6，Minecraft 1.21.1 / NeoForge 21.1.234。
- 原版主世界，种子 42，非 legacy 随机，`minecraft:multi_noise`；单个生成线程，显示采样间距 4。
- 每片目标区域 16×16 区块，装饰源区域 18×18 区块。正式五片使用相同固定坐标，原有外部 shuffle 熵固定。
- 真实运行时路径：显示地形 → 原有结构和装饰 → 植被整理与提取 → `PredictionVoxySource.build` 的地形列及方块包围盒。
- 计时包含缓存查询、缺失后的原路径计算、跨语言调用、批次构造、数据复制、完整生成与 Voxy CPU 输入整理。上下文初始化、输出哈希和逐点重放不计入性能计时。逐值校验组单独统计，不混入性能平均值。
- 不新增候选跳过、不替换群系答案、不修改原有随机数或 Feature 执行顺序、不降低装饰密度。
- 不含渲染、GPU、网络、磁盘缓存读取和调度排队；不是 FPS 测试。没有独立测 Forge、模组群系或多线程吞吐。

## 原型做了什么

Java 在原有共享 `DensityMemo` 之后切出可证明纯水平的最大子表达式。五个水平气候根按 X/Z 缓存；`depth` 只缓存水平 offset，原来的高度渐变与加法顺序保留，避免从一个高度的 depth 反减渐变而引入舍入差异。其余群系选择继续由原来的 Java 参数树完成。

Rust 从实际生效的密度图中选择同样的水平表达式，只共享 Raw 求值，其他查询模式继续原流程。缓存由当前世界的图拥有，精确核对坐标；实验容量固定为 16384 行，碰撞时替换，缺失时照常计算。

跨语言组在 Java 缺失时读取一个 4×4 quart 页，最多 16 个 X/Z，每点传回有效位与六个原始 double。Rust 接口只读取已存在缓存，不为未来坐标提前算值。Java 没取到的分量继续使用原 Java 实现。缓存已读页，避免每个分量各调用一次接口。

原型限定本次已验证的六个水平切点，布局不符合预期时实验直接失败。Java 存储和统计也限定单生成线程。本原型不是已通过各类模组验证的通用适配层；生产实现还需要线程生命周期、图身份、模组上下文及查询模式的兼容处理。

## 完整路径结果

执行两轮，各模式先预热两片，再测五片；第二轮反转四个候选方案的顺序。每轮均有开头和末尾基线。候选每项共 10 次正式区域生成，覆盖五个不同区域的两次重复。

另执行一轮未加入任何共享实验钩子的生产 DLL 基线：开头、末尾各五片，共 10 次正式区域生成。其均值为 3710.845 ms；带关闭状态实验钩子的 20 次基线均值为 3715.897 ms，相差 0.136%。没有观察到足以解释收益的基线退化。

| 方案 | 每片完整耗时 | 相对生产 DLL 耗时减少 | 相对生产 DLL 吞吐提升 |
|---|---:|---:|---:|
| 当前生产路径 | 3710.845 ms | — | — |
| 只加 Java 水平缓存 | 3456.798 ms | 6.85% | 7.35% |
| 只加 Rust 水平缓存 | 3606.088 ms | 2.82% | 2.91% |
| 两端各自缓存 | 3405.115 ms | 8.24% | 8.98% |
| 两端缓存并共享 Rust 已算值 | **3340.576 ms** | **9.98%** | **11.08%** |

相对每轮开头/末尾配对基线，共享组第一轮减少 10.05%，反向顺序轮减少 10.15%；10 个配对区域均提速，单片减少 7.57%–11.73%。这些是固定机器、固定输入的重复结果，不是对任意整合包的统计保证。

共享组每秒目标区块数由 68.99 提高到 76.63。此数值仅描述当前预测路径的区域吞吐，包含相邻装饰源开销，不代表完整真实区块生成或游戏实时加载队列吞吐。

## 时间实际省在哪里

以下均是整个生成窗口中已经包含的子阶段，不能与总耗时再次相加。

| 嵌套阶段 | 生产 DLL | 共享组 | 变化 |
|---|---:|---:|---:|
| Java 群系查询 | 384.827 ms | 161.755 ms | 减少约 58.0% |
| 装饰精确地表/环境查询 | 1732.367 ms | 1693.909 ms | 减少约 2.22% |
| 装饰显示地形查询 | 201.298 ms | 188.979 ms | 减少约 6.12% |

Java 单独缓存时群系查询已降至 171.176 ms；共享后再降到 161.755 ms。将两端各自缓存改为跨语言共享，完整耗时从 3405.115 降至 3340.576 ms，额外约 1.90%。因此跨语言协议的收益没有 Java 水平复用本身那么大。

生成线程 CPU 时间从 3689.063 降至 3334.375 ms，减少 9.61%。Java 当前线程分配量从每片 520.273 MiB 降至 517.429 MiB，减少约 0.55%；这项方案主要减少重复计算，没有解决大量临时分配。

## 真实入口、缓存命中与一致性

- 每片 Java 六个切点平均进入 751575.6 次；只加 Java 缓存时命中 718172.4 次，仍需计算 33403.2 个分量。
- 共享组 Java 原路径只需计算 8913.6 个分量，比 Java 单独缓存再少 73.3%。平均执行 1008.2 个原生读取批次；传输与复制已计入总耗时。
- 每片平均复制 77492.4 个原生分量，包含预取和重复页，不能把此数当作节省的独立计算数。两轮同坐标的入口/命中计数一致。
- Rust 每片平均缓存命中 491191 次，缺失 43060.8 次；缓存有固定容量，并非此前诊断的无界坐标集合。上一轮 99.54% 的理论水平输入覆盖率不是本轮的缓存命中率，更不是总耗时收益。
- Java 缓存数组与传输缓冲有效负载为 984064 B，约 0.9385 MiB；Rust 缓存与节点索引为 1048804 B，约 1.0002 MiB。合计约 1.94 MiB，不含对象头与同步对象，且只描述本次单线程单上下文原型。
- 额外执行五片正式逐值校验，比较 **3757878 个原始 double 位**，全部一致。该组因为同时重新调用原路径而较慢，其耗时不纳入性能组。
- 两轮 65 次正式区域生成（含五次逐值校验）以及生产 DLL 的 10 次正式生成，全部输出哈希一致，且与上轮生产输出五个已归档哈希一致。哈希覆盖装饰位置/BlockState、地形列群系/区间/BlockState、Voxy 方块包围盒；不声称覆盖像素图或完整存档。
- 两轮各自 536 个生产 Java、资源和 Rust 源文件的内容哈希核对均无变化。

## 产物与复现

持久汇总包含原始计时行、入口计数、逐区域收益、输出哈希、生产/实验 DLL 哈希，以及各 Feature 明细文件的校验值：

- `tools/performance/climate-sharing-bench/results-20261102.json`
- `tools/performance/climate-sharing-bench/HorizontalSharing.java`
- `tools/performance/climate-sharing-bench/horizontal.rs`
- `tools/performance/climate-sharing-bench/prepare.py`
- `tools/performance/climate-sharing-bench/run.ps1`
- `tools/performance/climate-sharing-bench/summarize.py`

实际运行目录为 `build/climate-sharing-bench-20261102-a/`、`build/climate-sharing-bench-20261102-b/`、`build/climate-sharing-bench-20261102-shipping/`。首次小范围验证另存于 `build/climate-sharing-bench-20261102-smoke/`，不参与最终均值。

生产 DLL SHA-256：`2ad535c6ff24b469ca9cb127a6bab73882bb32a1e49fedac3a9e6d89897527d1`。

实验 DLL SHA-256：`fcf8b38d2530d730f6cf8823c4485664b207897909905c015403c1ba5595beee`。

Windows 使用 Java 21、已缓存的 Gradle/Rust 依赖和有效世界生成输入。运行脚本只写新隔离目录，不发布 JAR：

```powershell
& tools/performance/climate-sharing-bench/run.ps1 -Output build/climate-sharing-repeat-a -PythonExe python -JavaHome 'C:/Program Files/Java/jdk-21' -Verify
& tools/performance/climate-sharing-bench/run.ps1 -Output build/climate-sharing-repeat-b -PythonExe python -JavaHome 'C:/Program Files/Java/jdk-21' -Reverse
python tools/performance/climate-sharing-bench/summarize.py build/climate-sharing-repeat-a build/climate-sharing-repeat-b --output build/climate-sharing-repeat-summary.json
```

生产 DLL 追加基线使用 `decoration-hotspots/prepare.py` 的未加共享钩子副本，标准实验 Gradle 入口、`baseline,baseline_tail` 模式，各五片、两次预热。汇总时增加 `--shipping-directory` 指向该目录。所有有效运行必须有内容为 `success` 的 `prediction-complete.txt`，且不存在 `server-failure.txt`；不能只检查 Gradle 的退出码。

## 实施判断

水平输入复用值得实施，优先级以 Java 缓存最高。Rust 世界级共享和批量跨语言读取还需在实际多工作线程与模组环境验证同步开销及生命周期。

完整收益应按约 10% 看待。剩余 1.69 秒精确地表/环境查询占共享组总耗时约 50.7%，这一项仅下降约 2.2%。要继续取得大幅提速，需要减少这部分实际地形/表面依赖的成本，不能把气候输入的高复用率解释成整个装饰阶段都可省掉。

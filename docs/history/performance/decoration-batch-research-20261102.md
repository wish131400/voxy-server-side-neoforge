# 批量噪声、地表与装饰执行方向研究（2026-11-02）

## 结论与边界

继续保留 Rust 数值核心和 Java 模组规则入口是合理方向。下一步优先审查的是群系单点调用的跨语言成本、原生查询工作区借还，以及地表规则内部的纯噪声重复计算。换成 C++、扩大批量上限或再加一层全局答案缓存，都没有直接对应当前主要缺口。

本轮完成生产源码只读审查、上游固定版本源码核对，以及已有真实注册表与诊断计数的静态分析。新增一个可复用的静态分析工具，没有修改生产源码、原生库或发布包，也没有获得新的性能提升百分比。另一轮正在实施 26.3 装饰候选群系范围排除，本研究避开其文件和测试负载；其尚在验证的结果不计入本轮结论。

下文的计数来自已归档的同机生成实验，包含预热和正式区域，不能当作本轮改动后新包的运行数据。静态规则引用数不是动态执行次数。

## 已有能力，避免重复实施

已确认以下机制存在：

- Java 装饰代理已有方块、heightmap、block-biome 和 quart-biome 查询缓存；实际写入覆盖、版本失效和事务回滚仍需要保留。
- 精确地表使用 4×4 页，精确与显示 Job 已各自按区块复用。不能再描述成“每查一个邻居便重新构造整块世界”。
- Java `sampleBiomeIndices` 已支持最多 256 点的单次 JNI 输入。
- Rust 群系查询已有有界分片答案缓存和 Raw 工作区池，密度图有水平依赖、角点及算术执行计划。
- Java 有 `DensityGraphCompiler` / `DensityCompilation`，并非从未应用密度函数编译。未知节点保留原实现。
- Fabric 共用原生核心已经按已登记群系折叠材质规则，并按潜在依赖计算温度和 secondary noise；动态条件仍由规则树执行。
- Java `PredictionPlacementExecutor` 已按放置器类型编译操作，并保留被模组改写或未知放置器的原调用。
- Rust 原生装饰已和 Java 装饰在同一有序事务流中交替，不能把后续装饰依赖的写入任意延后。

## 发现一：批量接口已存在，但 Fabric 实际几乎都在单点调用

读取 `external/performance/20261102-version-loading/evidence/*/diagnostics-4.txt` 的累计计数：

| 目标 | 原生群系批次数 B | 原生查询点数 P | 每批平均点数 | 单点批次比例下界 |
| --- | ---: | ---: | ---: | ---: |
| NeoForge 1.21.1 | 567 | 30492 | 53.78 | 本组总计数不能推出有效下界 |
| Fabric 1.21.1 | 927530 | 957455 | 1.0323 | 至少 96.77% |
| Fabric 26.3 | 957518 | 987443 | 1.0313 | 至少 96.87% |

Java 每次实际提交的 batch 至少有一个点，因此非单点批次每批至少多消耗一个点。单点批次数至少为 `max(0, 2B - P)`。这提供严格下界，不是猜测出来的批量直方图；三组计数的 Java fallback 都是零。

NeoForge 的装饰 `getNoiseBiome` 走 `context.noiseBiome`；Fabric 走 `terrain.noiseBiome`，Rust sampler 的覆盖再调用 `sampleBiomeIndices(query, 1, result)`。两者路由及统计覆盖范围不同，不能用 NeoForge 的原生批次少直接宣称其全部群系工作少了同样倍数。

Fabric 入口逐次执行 Java 缓冲区写入、JNI 直接缓冲区校验、原生世界句柄查找和结果映射。原生 `jni_worldgen::biomeIndices` 内部又逐点调用 `World::noise_biome_index`：

1. 校验任务状态，锁定答案缓存分片查询。
2. 未命中且不是固定群系源时，锁定共享工作区池取出一个 Raw `Scratch`。
3. 计算群系，将工作区归还共享池，再写入答案缓存。

所以工作区已复用，但它的租借粒度仍是一个冷查询点。批量 JNI 内部也没有一次借出并服务整批点。已有缓存命中时不会借还 Raw 工作区；没有新探针，不能把所有原生点数都当冷查询，更不能把这些锁操作宣称为已证实的主要耗时。

### 可实施的切入顺序

先用独立归因记录单点 JNI 入口时间、缓存命中/未命中、Raw 租借次数与池访问时间。计数版与正式计时版分开，避免逐点读时钟改变结论。

第一个隔离候选是增加接受外部工作区的内部查询函数，使一次真实批次仅借出一个 Raw 工作区，首个冷点才借出。每个点的群系树上次叶节点偏好仍按现有接口重置，不跨点保留。该候选简单，但因实际批次几乎都是单点，预计覆盖面有限；不能只验证这个候选便宣称解决了 Fabric 的调用问题。

若单点入口和池访问确实占比较高，再验证**装饰任务拥有的有界查询上下文**：任务开始时惰性获取 Raw 工作区，任务结束或取消时归还。上下文必须属于具体世界和任务，不与 exact/display 密度 Job 混用；处理嵌套调用和并发租借，世界关闭后不再复用。比较一次性任务建立/释放的成本，不能为每个单点另加两个建立/释放 JNI 调用。

批量收集只用于已经按原流程产生、输入不可变、可以立即确定的一组环境查询。不能越过终端 feature 执行去预取所有后续候选：终端可能消耗同一随机流，改变后续坐标；方块与 heightmap 也可能在终端写入后改变。纯群系查询也要保留现有等距选择约定。自定义放置器和回调沿原路径。

**单纯将 256 上限改到 1024 或更大不解决单点主导的实际流量。**

## 发现二：地表规则内的纯噪声重复尚未被密度缓存覆盖

Fabric `surface::Condition::matches` 的 `Noise` 分支直接调用 `Graph::noise`；`Graph::noise` 再调用对应噪声对象，不经过密度节点 `Scratch` 的缓存。相同 noise ID、相同坐标的多个阈值条件会分别求值。`depth`、条带偏移等也独立采样。

用实际导出的 NF 1.21.1 和 Fabric 26.3 世界生成文档，展开命名材质引用并按现有 `Rule::for_biome` 的语义折叠后：

| 目标/群系 | 相同噪声的静态引用 | 可复用范围 |
| --- | ---: | --- |
| 两版本 `wooded_badlands` | `surface` 二维噪声 6 处 | 同一列 x/z |
| 两版本 `windswept_gravelly_hills` | `surface` 二维噪声 6 处 | 同一列 x/z |
| 两版本 `stony_peaks` | `calcite` 二维噪声 2 处 | 同一列 x/z |
| 26.3 `sulfur_caves` | `sulfur_cave_gradient` 三维噪声 9 处 | 同一 x/y/z |

条件会短路，六处/九处不代表每次都执行六次/九次。同列还会处理表面、地下保留层，或三种摘要材质位置；二维输入可能跨这些求值再次出现。平原、森林的折叠规则没有额外 `noise_threshold`，因此不能将特殊群系的引用密度外推到所有区域。

建议先验证**列内纯噪声槽位**，再考虑全面线性化：

- 初始化规则时给实际使用的 noise ID 分配紧凑槽位。
- 二维噪声按列代数/epoch 保留值；三维噪声至少匹配 Y，列切换清除逻辑身份。
- 只复用完全相同的噪声、坐标、精度和执行模式。26.3 `Single` 与体积输入可能有不同浮点舍入，不能共用一个结果。
- 首次真正访问才计算，不提前求值短路之后的分支。相同噪声的不同阈值比较继续独立执行。
- 不缓存顺序随机流、方块读取、矿脉结果、模组回调和事务写入。空间定位随机也先保留原逻辑。
- 用固定小数组/已编译槽位，避免每次求值分配 HashMap，避免扩大跨任务常驻缓存。

这项可与另一轮候选群系范围排除互补：前者减少不应进入的装饰，列内复用减少已进入地表材质查询的重复工作。是否有足够完整收益仍需实验。

## 发现三：应补齐规则计划，不宜重新造一个大编译器

Fabric 已进行群系部分求值，剩余 `Rule::Sequence` / `Condition` 仍递归执行。可以将其编为有跳转位置的连续指令，保留原先的首个成功规则和动态条件顺序；噪声槽位也可以接入这一计划。

但结构折叠不等于性能收益。真实文档展开后 NF 是 428 个带类型节点，26.3 是 469 个；仅按群系折叠后平原分别为 44 / 47 个节点。这些是静态体积，不是每个位置必遍历的节点数。Fabric 已拿到了群系折叠带来的结构缩减，不能再次把 469→47 当作新优化成果。

当前 NeoForge/Forge `surface.rs` 未包含 Fabric 这版 `biome_rules` 专门化；可以在各自旧版规则语义下复用这个设计，但要独立做完整路径对照。不能把现代格式、FP32 语义或整个 Fabric 文件直接覆盖到旧版。

建议拆分三个候选：A 仅纯噪声槽位；B 仅保序线性规则；C 二者组合。否则无法确定收益来自少算噪声还是少走解释器，回退时也难定位。仅用于原生已支持的规则；Java 原生模组回调仍作为未知操作保留。

## 上游源码实际借鉴点

通过 GitHub API 固定 commit，并下载 blob 校验 Git 对象哈希；上游快照留在本机忽略目录，未复制进生产。

| 项目 | 固定提交 | 已审机制 | 对本项目的意义 |
| --- | --- | --- | --- |
| C2ME `dev/26.3.0` | `3ce6fa760819b145e37cabee2c88ab790cc02ee2` | JVM single/multi 代码生成、临时数组池、范围分支短路、原生 shifted-noise 批量 | 保留小批/单点路径；编译纯操作；批量不强行计算所有分支 |
| FastNoise2 | `721400f9560831be2fc9790be9b72c44bee7d33f` | 整个节点链在 SIMD 内计算、坐标数组接口 | 学习跨节点的数据布局与融合；不能换成其噪声算法破坏 Minecraft 种子、精度和模组语义 |
| Noisium 1.20–1.20.1 | `c640041c8c932b36753c0ccf43902ac8b0bd252d` | 真实区块 palette storage 直接写入与顺序 sampler 微优化 | 当前热点是代理环境与规则求值，不能把真实区块写入捷径直接搬来解释本项目的主要差距 |

C2ME 的 `RangeChoiceF32NodeBytecodeEmitter` 批量先算判定输入，再对各点调用选中的分支，未无条件计算两侧；其 `MixinNoiseDensityFunctionXzShiftedSampler` 对一个点直接 scalar，达到条件才用批量。它采用的批量阈值取决于自己的接口和算法，不能照抄为本项目的最佳数值。

`DfcObjectCache` 的数组复用和 `BytecodeGen` 的 single/multi 分工有参考价值。本项目已有密度编译与多种临时缓存，新增工作应放在材质规则和查询生命周期，而不是为了“使用 DFC”重新覆盖现有密度编译器。

源码链接：

- [C2ME 批量范围分支](https://github.com/RelativityMC/C2ME-fabric/blob/3ce6fa760819b145e37cabee2c88ab790cc02ee2/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/gen/jvm/emitters/misc/RangeChoiceF32NodeBytecodeEmitter.java)
- [C2ME 临时对象与数组](https://github.com/RelativityMC/C2ME-fabric/blob/3ce6fa760819b145e37cabee2c88ab790cc02ee2/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/gen/jvm/util/DfcObjectCache.java)
- [C2ME shifted-noise 批量入口](https://github.com/RelativityMC/C2ME-fabric/blob/3ce6fa760819b145e37cabee2c88ab790cc02ee2/c2me-opts-natives-math/src/main/java/com/ishland/c2me/opts/natives_math/mixin/MixinNoiseDensityFunctionXzShiftedSampler.java)
- [FastNoise2 SIMD 和坐标数组入口](https://github.com/Auburn/FastNoise2/blob/721400f9560831be2fc9790be9b72c44bee7d33f/include/FastNoise/Generators/Generator.inl)
- [Noisium 区块写入优化](https://github.com/Steveplays28/noisium/blob/c640041c8c932b36753c0ccf43902ac8b0bd252d/common/src/main/java/io/github/steveplays28/noisium/mixin/NoiseChunkGeneratorMixin.java)

## 其他可审查但优先级较低的开销

原生 `placedFeature` 每次构造 `{"placed":...,"after":"..."}` JSON 并返回 Java 字符串，而生产 `RustVegetationStage.place` 不消费此返回值。可保留旧诊断 ABI，新增返回状态码的生产入口；不改变内部随机推进和事务提交。已有调用约十万次/七片，值得在同一归因中量化，但字符串开销只是整个 feature 时间的一部分，不能宣称全部可省。

连续原生 feature 合并同步理论上可减少 Java/Rust 往返，但当前每个 feature 后立即向 Java 发布写入，保证结构、树模板和兼容 feature 能看到前序结果。没有完整失败/回滚与可见性证明前，不把它当简单删同步的优化。

GPU 优先级仍低于上述查询问题。已有完整路径对照没有证明此场景 Vulkan 净提速。先把相同操作和数据组织成稳定大批，再比较 CPU+GPU 的端到端成本，保留当前按实际收益选择的机制。GPU 忙碌率本身不能判断计算是否值得卸载。

## 已否定方向，不重做成建议

参见 [现代生成修复记录](modern-generation-repair-20261102.md)：精确 4×4 页改单柱约慢 3.7%；整片精确群系预取约慢 13.9%；额外无坡度证明约慢 12%–15%；Java 精确群系答案缓存约 6% 命中、完整路径慢约 2.3%。这些是不同候选的历史实验，不是本轮重新测量。

参见 [装饰输出实验](lod-decoration-results-20261102.md)：仅减少输出盒子、span 或末端裁剪未减少主要环境查询，完整时间没有稳定收益。

## 复查工具与下一轮验收

工具为 `tools/performance/decoration-plan-audit/analyze.py`，只使用 Python 标准库，不启动游戏或编译器。接受命名世界生成文档和累计诊断文件，输出来源 SHA-256、命名引用展开与群系折叠后的静态计数、单点批次比例下界。不会输出本机绝对路径，也不把静态计数标为性能结果。

本轮命令：

```powershell
python tools/performance/decoration-plan-audit/analyze.py --document nf121 external/performance/20261102-version-loading/evidence/nf-121/native-document.json --document fabric263 external/performance/20261102-version-loading/evidence/fabric-263-cpu/native-document.json --diagnostics nf121 external/performance/20261102-version-loading/evidence/nf-121/diagnostics-4.txt --diagnostics fabric121 external/performance/20261102-version-loading/evidence/fabric-121/diagnostics-4.txt --diagnostics fabric263 external/performance/20261102-version-loading/evidence/fabric-263-cpu/diagnostics-4.txt --output tmp/decoration-batch-research-20261102/inspection.json
```

本轮对两个真实注册表及三组诊断运行成功，节点、引用和下界计数与人工源码核对一致。原始注册表和诊断位于本机忽略目录，不随文档提交。没有运行新的 Gradle/Cargo 回归或游戏性能实验。

下一轮固定候选范围排除完成后的源码/原生库基线；依次隔离工作区生命周期、纯噪声槽位、规则线性化。每个候选用完整 16×16 目标区域、相同种子和资源做交替对照，哈希在计时外比较，统计总耗时和分配/保留内存。加入含噪声重复的山地、恶地、硫磺群系，避免只测平原；检查取消、任务重入、远坐标、模组未知规则和顺序敏感上下文。完整路径没有稳定收益的候选不合入。不能仅凭单点微基准或少了多少 JNI 调用声称加载提速。

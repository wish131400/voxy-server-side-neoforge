# 通用装饰生成优化调研（2026-10-31）

本轮目标是选择能覆盖多种原版、数据包和模组装饰的优化方法，避免以逐树种枚举和完整移植为主线。结论是优先优化共享查询环境、放置规则执行和纯计算依赖；保留原生成器的随机、读写与放置语义，树形模板作为已有的补充加速。

本轮联网读取了下列项目的源码和论文官方网站，并与当前 NeoForge 1.21.1 实现核对。没有修改生产生成算法，没有运行新的性能对照，也没有打包。方法可用于规划 Forge/Fabric 移植，但本轮没有验证这些平台的实现等价性。

## 本项目的实测依据

沿用 [地表装饰开销拆解](vegetation-cost-breakdown-20261031.md) 的独立测试：种子 42，五片新区域，每片 16×16 区块，4 格显示地形采样间隔，完整 NeoForge 运行时，未启动渲染。

- 每区域装饰来源生成、合并与整理平均 4,741.67 ms，占数据生产循环 94.37%。这不是渲染帧时间。
- 1,788 个采样栈中，822 个包含 `surfacePoints`，234 个包含 Java 群系查询，101 个包含 `PredictionTreeModels.place`。这些是调用路径样本，包含关系会重叠，不是互斥 CPU 独占比例，也不能当作优化收益上限。
- 树模型已有复用；含预热的七轮累计构建 79 个模型，构建合计 42 ms。继续枚举树形不能直接消除放置前的地形、群系和环境查询。
- 正式五轮 Java 放置有 96.85% 没有写入方块。概率、群系、碰撞等正常结果都会导致无写入，不能直接跳过这些调用，更不能把整个有随机行为的 Feature 当作无副作用的失败缓存。

因此，应先减少多种装饰共同触发的重复和过量查询，再判断是否需要扩展特定树形模型。

## 模组源码证据

### ByePregen：通用执行器与有限范围的依赖缓存

审查的是 [MoePus/Bye-Pregen](https://github.com/MoePus/Bye-Pregen)，固定提交 `df3ff89c22b0b01e52a0165235cc611cc43edb5c`。该提交 `gradle.properties` 标明 Minecraft 1.21.1、模组 1.1.3.4。

1. [FastPlacementContext](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/FastPlacementContext.java) 用可复用上下文和坐标参数执行修饰器链，减少 Stream、中间位置对象等分配。[FastPlacementModifier](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/FastPlacementModifier.java) 的默认实现仍调用原 `getPositions`。它优化的是公共执行流程，而不是列举每种树的几何。
2. [EnvironmentScanPlacementMixin](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/mixin/feature/placement/EnvironmentScanPlacementMixin.java) 保留环境条件检查和搜索过程，以可变坐标和直接循环执行。这可以减少框架开销，但不会自动消除每次地形查询的计算成本。
3. [FeaturePlanCompiler](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/FeaturePlanCompiler.java) 当前只为满足条件的 `Feature.DISK` 构建专用计划。[BlockPredicateDependencies](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/BlockPredicateDependencies.java) 收集可识别方块谓词的坐标偏移；未知依赖不能进入该结果缓存。
4. [KnownFalseDiskPredicateCache](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/KnownFalseDiskPredicateCache.java) 缓存确定为假的目标谓词。坐标 `q` 被写入时，只清除 `p = q - dependency` 对应的缓存位。[PredicateMemoizedDiskPlacement](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/PredicateMemoizedDiskPlacement.java) 将缓存限制在相应放置上下文及嵌套随机片区中。它不是任意 Feature 的整次失败缓存。

这套实现说明依赖缓存可以小范围落地，无须引入全世界逐方块依赖图。但不能将它描述成已经通用编译所有植被的成熟方案：该提交的公共放置优化总开关默认关闭，专用计划有明确覆盖范围，Mixin 也排除了部分冲突模组。

本项目使用虚拟装饰世界，不能直接照搬其 [FastBlockPredicateOptimizer](https://github.com/MoePus/Bye-Pregen/blob/df3ff89c22b0b01e52a0165235cc611cc43edb5c/src/main/java/com/moepus/byepregen/worldgen/feature/FastBlockPredicateOptimizer.java) 对真实区块 section 的直接读取。预测地形并不全部存放在这些 section 中；绕开虚拟世界查询可能把地面读成空气，使植被无法放置。

### C2ME：编译公共计算图，未知节点局部调用原实现

审查 [C2ME 的 1.21.1 分支](https://github.com/RelativityMC/C2ME-fabric/tree/d06faf730725ac2e5874d694ccb28412eb655328)，固定提交 `d06faf730725ac2e5874d694ccb28412eb655328`。

- [McToAst](https://github.com/RelativityMC/C2ME-fabric/blob/d06faf730725ac2e5874d694ccb28412eb655328/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/ast/McToAst.java) 将已知密度函数转成 AST；默认分支生成 `DelegateNode`。
- [DelegateNode](https://github.com/RelativityMC/C2ME-fabric/blob/d06faf730725ac2e5874d694ccb28412eb655328/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/ast/misc/DelegateNode.java) 保留原密度函数的计算入口；[BytecodeGen](https://github.com/RelativityMC/C2ME-fabric/blob/d06faf730725ac2e5874d694ccb28412eb655328/c2me-opts-dfc/src/main/java/com/ishland/c2me/opts/dfc/common/gen/BytecodeGen.java) 编译图并复用编译产物。

可借鉴的是“公共运算编译、未知局部委托”的边界：一个组件无法识别，不必关闭整个任务的其他优化。这里证明的是密度函数编译方法，不代表 C2ME 能把任意 Java 植被生成器编译成 Rust，也不代表开启多线程会降低总 CPU 工作量。

### Tungsten：密度函数中间表示与依赖调度

审查 [Tungsten-dev/tungsten-wg](https://github.com/Tungsten-dev/tungsten-wg/tree/fce7d9aa332a86ce9d134525740235f0fbf48463)，固定提交 `fce7d9aa332a86ce9d134525740235f0fbf48463`。

[README](https://github.com/Tungsten-dev/tungsten-wg/blob/fce7d9aa332a86ce9d134525740235f0fbf48463/README.md) 描述密度函数到 SPMT 中间表示，再生成 Rust/CUDA 的流程；解析 Minecraft 输入属于独立前端。[orchestrate/transform.rs](https://github.com/Tungsten-dev/tungsten-wg/blob/fce7d9aa332a86ce9d134525740235f0fbf48463/src/orchestrate/transform.rs) 将计算节点转换并缓存为 shader 依赖，`arrange_waves` / `arrange_waves_for` 按依赖分批安排执行，后者只遍历目标所需的依赖。

适合借鉴纯地形、气候等计算的公共依赖复用和批量执行。不能据此推断任意带随机数及世界写入的 Feature 都能自动转换，也不能推断 FP64 改成 FP32 能保持世界生成结果一致。本项目已有密度计划优化，后续重点应是让装饰查询使用合适的已有计算能力，避免新建一套重复的图系统。

### Noisium：优化公共数据路径与访问局部性

审查 [Noisium 1.20–1.20.1](https://github.com/Steveplays28/noisium/tree/c640041c8c932b36753c0ccf43902ac8b0bd252d)，固定提交 `c640041c8c932b36753c0ccf43902ac8b0bd252d`。

源码包括噪声生成时直接写调色板并维护相关计数、调整群系填充循环的轴顺序，以及用普通循环执行 `ChainedBlockSource`。这些优化服务公共数据路径，而非每一种地物。其真实区块调色板写入不是本项目 `surfacePoints` 热点的直接替换方案；可借鉴批量处理和连续数据访问，不能照搬整块构造。

## 相关文献

### Halide，PLDI 2013

Jonathan Ragan-Kelley 等，*Halide: A Language and Compiler for Optimizing Parallelism, Locality, and Recomputation in Image Processing Pipelines*。来源：[Halide 官方网站及论文入口](https://halide-lang.org/)。

核心启发是分开描述“计算结果是什么”和“何时、按多大块计算、哪些结果复用”。对本项目而言，精确高度、方块和群系语义不变，查询可以按邻域共享、批量提交并按需求补齐字段。批次大小应依据实际访问密度选择；给稀疏查询无条件预填整片区域也可能更慢。

该论文研究图像流水线，不是 Minecraft 性能实验。Feature 的随机与读写依赖限制了重排，不能直接套用纯图像计算的任意调度，也不能引用其加速倍数作为本项目预期。

### Adapton，PLDI 2014；Incremental computation with names，OOPSLA 2015

来源：[Adapton 官方网站](https://adapton.org/)、[2014 论文 DOI](https://doi.org/10.1145/2594291.2594324)、[2015 论文 DOI](https://doi.org/10.1145/2814270.2814305)。

核心启发是记录结果依赖的输入，输入变化后只使受影响结果失效，并在再次需要时重算。对应本项目的实践可以是生成上下文内的不可变地形缓存、列或页版本，以及小范围纯谓词的读依赖。

不建议直接引入通用细粒度增量计算框架：为每次方块查询分配依赖节点可能产生更大开销。冷区域首次必需计算仍要发生，增量复用不能消除这些成本。

## 本项目已具备和应补齐的部分

| 层次 | 当前代码已具备 | 仍值得做的工作 |
| --- | --- | --- |
| 放置规则 | `PredictionPlacementExecutor` 编译部分公共修饰器为执行计划，保持顺序；未知组件调用原实现 | 按热点补齐公共执行路径；`EnvironmentScan`、`CountOnEveryLayer` 当前仍走原路径，后者有先扫描后放置的语义，不能简单改成即时写入 |
| 地形与方块查询 | `PredictionDecorationLevel` 有局部方块、列、高度缓存及局部写入失效；显示路径有 4×4 页 | 精确路径缓存未命中仍可能逐点调用完整 `surfacePoints`；按所需字段和真实访问密度组织批次，避免只问高度却准备完整显示记录 |
| 跨任务数据 | `RustTerrainSampler` 已有点缓存、批量接口；装饰来源有内存/磁盘缓存与任务去重 | 优先复用稳定地形与群系输入；评估跨相邻来源的命中和驱逐，避免只扩大容量；可变装饰写入保持任务内可见 |
| 群系 | Rust 已有批量 `sampleBiomeIndices` 及调色板映射 | `PredictionDecorationLevel.getNoiseBiome` 缓存未命中仍调用 `context.noiseBiome`；确认相同语义后让受支持上下文接入原生能力，外部自定义行为保留原调用 |
| 条件结果 | 已缓存查询值，并不是每次从零读取 | 可借鉴纯谓词结果和小范围依赖缓存；不把整次随机 Feature 的无写入结果直接记忆化 |
| 树模型 | 已构建并复用模型 | 保留有效路径，完整覆盖每种树形不作为主要目标 |

核对位置：

- `src/main/java/dev/xantha/vss/client/prediction/PredictionPlacementExecutor.java`：`OPAQUE`、`compile`、`operation`。
- `src/main/java/dev/xantha/vss/client/prediction/PredictionDecorationLevel.java`：`nativeColumn`、`getBlockState`、`getHeight`、`getNoiseBiome`。
- `src/main/java/dev/xantha/vss/client/prediction/RustTerrainSampler.java`：`sampleBiomeIndices`、`point`、`rememberPoint`。

上述代码已做部分优化，因此本轮结论不是要求另起一套执行器或缓存，而是沿现有接口补齐共享查询和按需计算。

## 建议实施次序与正确性边界

1. **先优化共享查询提供方。** 分开稳定地形/群系和当前装饰写入；在现有上下文内按需复用、提交精确查询。高度、材料、颜色、群系身份不必在每条查询中无条件一起计算。批处理收益和额外预取成本必须一并测量。
2. **接通装饰的原生群系路径。** 只在与当前群系源、注册表和查询坐标语义一致时使用已有原生接口；未知自定义行为沿用原调用。原版 `BiomeManager` 的坐标扰动等行为不能被显示用近似群系替代。
3. **扩展现有公共放置执行器和纯条件缓存。** 优先采样中的环境扫描及重复谓词；已知纯条件可分析依赖，未知条件只保持原调用，不阻塞整个生成任务。写入及事务回滚都要维护缓存有效性。
4. **继续复用现有密度计划。** 将能共享的纯计算安排到合适的批次中；不为整个有副作用的 Feature 建立任意并行执行图。

缓存以现有生成上下文为生命周期，必须区分种子、维度、生成配置/数据包版本以及精确/显示模式；这些身份可由上下文统一管理，无须每次热查询拼接大键。稳定底层输入可共享，装饰写入必须即时覆盖底层结果。跨 Feature、跨来源的可变结果不能因坐标相同就共享。

不能简单把 `BiomeFilter` 前移或用来源区块中心群系排除所有其他候选：跨边界放置、影响范围、全局 Feature 索引、随机消耗和前序写入都可能影响结果。保持现有生成语义的优化，应以逐区域方块结果、群系身份、随机调用和完成状态的差分检查作为依据。

预期验证包括同一构建、相同种子和新区域下的冷生成时间、CPU 时间、精确查询次数、原生调用批量、群系回退、缓存命中和峰值内存；同时检查模组树、群系边界、跨区块树、失败放置和回滚。要证明实际游玩更流畅，还需在游戏中独立比较帧时间分位数；离线生成更快不能自动证明渲染无干扰。

这种优化覆盖沿用公共放置规则的原版及数据包装饰，也能帮助通过标准世界接口查询的自定义 Java Feature。自行维护另一套地形、绕过查询接口或依赖特殊世界状态的模组仍需适配，但无需为了普通自定义树先列举其全部形状。

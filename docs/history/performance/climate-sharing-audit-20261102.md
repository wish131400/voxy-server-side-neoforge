# Rust 地形与 Java 群系的精确共享边界（2026-11-02）

已追踪真实 NeoForge 运行时的完整预测路径，并对 Java 实际使用的六项气候输入逐点做 Rust 重放。主要机会是共享水平气候输入和深度的水平子表达式，保留 Java 原有群系选择；只共享最终群系答案或扩大三维答案缓存，都不能充分利用已有地形计算。

本轮增加独立诊断工具和报告，没有修改生产 Java/Rust、原生库或安装包。本文中的比例是输入覆盖率、数据使用率，不是已兑现的整体提速。

## 实验范围与证据

- Windows x86_64、Java 21.0.6、Minecraft 1.21.1、NeoForge 21.1.234；原版主世界，种子 42，非 legacy 随机源，`minecraft:multi_noise`。
- 单生成线程，每片 16×16 目标区块、18×18 装饰来源区块、显示间距 4；一片预热、五片不同正式区域。
- 完整路径：显示地形 → 结构与装饰 → 植被整理 → Voxy 输入列和包围盒。保留原有预测实现及其兼容边界；固定已有实验的外部 shuffle 熵。不跳过失败候选，不使用预知答案。
- 包装 Java 六个已编译根的出口，记录原始 double 位与实际坐标；Rust 在真实 `climate::targets::sample` 调用及其缓存命中处记录坐标、量化输入和原始位。在 Java 首次读取坐标时查询 Rust 探针中已经存在的记录，区分先后顺序。
- 完成整个路径后，使用独立 Rust Raw 工作区重放 Java 坐标；重放数据不计入自然重合统计，不提前为生成路径预热。
- Java 记录本地环境页读取及底层记录读取；Rust 记录代理方块、代理高度、显式物化的读取以及装饰页请求。跨语言取坐标并集，不将只在单个 Feature 内未使用的列当成整条路径都不需要。
- 正式五片输出摘要全部与此前使用生产 DLL 的同区域结果一致。摘要覆盖装饰位置/BlockState、地形列群系/区间/BlockState、Voxy 包围盒。诊断未修改颜色，因此本轮没有另做延迟颜色实现的等价验收。
- 不含渲染、GPU、网络、缓存磁盘读取、调度排队和世界上下文初始化；不是完整整合包或 Forge 的独立性能测试。

工具位于 `tools/performance/climate-sharing/`，持久结果为该目录的 `results-20261102.json`。最终运行副本为 `build/climate-sharing-20261102-trace3/`。`prediction-complete.txt` 必须为 `success`，不能只依据 Gradle 的退出状态。

准备期间第一次在仅含实验代码的副本中遇到补丁匹配不唯一，未执行生成，随后收紧匹配。首轮成功探针验证六个区域；第二版补充执行顺序和消费记录，最后补齐原生高度与导出读取后再次完整执行。失败准备目录不参与统计，带探针时间不作为优化基准。

## 输入和中间值可以共享到哪里

### 世界级不可变输入

以下内容需要具有相同来源和生命周期，才能确认跨语言值对应：世界种子、维度/生成器、实际生效的 noise router 和密度图、噪声参数、legacy 随机算法、噪声实例的种子派生和 octave 初始化、插值单元尺寸、有效注册表/数据包版本，以及模组运行上下文。

已有 `RustWorldgenDocument` 负责传递生成器、密度函数、噪声和群系等注册表快照。Java 使用 `RandomState.sampler()` 的六根，Rust 通过地形图的 `temperature/vegetation/continents/erosion/depth/ridges` 六根采样。原版可以逐项对应。名称相同不能代替实际图和上下文相同的检查。

`BiomeManager` 的混淆种子只用于方块坐标选择 quart 单元；`getNoiseBiome(qx,qy,qz)` 已经拿到 quart 坐标，不应再做一次缩放、混淆或高度截断。

### 实际气候输入

Minecraft 的顺序为：quart 坐标各乘 4 → 六根以 Raw 语义计算 double → 转 float → float 乘 10000 → 截断为 long → 群系参数树查找。两个后端必须保留这个顺序，不能先截断 double 或改成四舍五入。

| 共享对象 | 依赖 | 与地形的关系 | 精确共享要求 |
| --- | --- | --- | --- |
| `shift_x/shift_z` | 已证明的 `(x,z)` 与同一噪声实例 | 地形、温度、湿度、大陆性等共同使用 | Raw/地形 marker 行为、输入坐标一致；保存原始 FP64 |
| 温度、湿度 | 本次图只依赖 `(x,z)` | 纯密度地形不一定计算这两项；Rust 地表群系采样已经计算 | 分析实际图的水平性，不能按根名称硬编码 |
| 大陆性、侵蚀度、奇异度 | 本次图只依赖 `(x,z)` | 与地形密度及 spline 共享 | 同一子图、模式和上下文 |
| `ridges_folded`、offset spline | 本次图只依赖 `(x,z)` | 地形和深度的公共中间量 | 保留原有 spline 浮点操作顺序 |
| 深度 `depth` | `(x,y,z)` | 地形与 Java 气候都使用 | 缓存原始 offset，重新执行原 gradient + offset；不能把整个 depth 当二维值 |
| 六项量化 target | 同一个精确三维 Raw 坐标 | 后续 Java 参数树的直接输入 | 六根和量化方式全部一致；保留 Java 路由、树、上次命中与模组逻辑 |
| 最终群系/渲染 RGB | 还依赖路由状态或资源/位置 | 不是纯地形密度中间值 | 不在两个后端之间无条件替换 |

当前实际主世界图的深度定义为：

```text
depth(x,y,z) = y_clamped_gradient(y, -64, 320, 1.5, -1.5) + offset(x,z)
```

offset 本身包含大陆性、侵蚀度、折叠奇异度与嵌套 spline，通常比梯度和最后一次加法贵。应共享 offset 的原始值。用旧高度的 `depth - gradient` 反推出 offset 可能引入舍入误差，不能作为精确方案。

静态展开实际发送的文档确认：大陆性、侵蚀度、奇异度、depth、offset、`ridges_folded`、`shift_x/shift_z` 都在气候根与 `final_density`/`initial_density_without_jaggedness` 的依赖交集中。温度和湿度的独立噪声不在密度依赖中，但其偏移和后续地表群系计算有交集。具体依赖列表与文档摘要保存在结果 JSON 的 `graph` 中。

### 实际重合与逐位一致性

正式五片共记录 **626,313 个 Java 三维坐标、3,757,878 个根结果**。重放时六根的原始 double 位及量化 long 全部相同；没有不完整记录，没有同一坐标根值变化。自然重合处的原始位和量化值也全部一致。

每片均值如下：

| 指标 | 数量 | 含义 |
| --- | ---: | --- |
| Java 实际三维坐标 | 125,262.6 | 已经过现有本地及共享答案缓存，不能据此称所有原始调用都未命中 |
| Java 水平位置 | 5,444.0 | 每个位置平均涉及约 23 个高度 |
| Rust 实际三维坐标 | 25,682.6 | 包含初始地形及装饰环境，不含事后重放 |
| 自然三维坐标交集 | 16,724.6（约 13.35%） | 忽略先后顺序时，整个区域最终都访问过 |
| Java 首次查询前 Rust 已有同一三维点 | 15,273.8（12.19%） | 直接复用完整六项输入的时序机会 |
| Java 首次查询前 Rust 已有同一水平位置 | 124,682.0（99.54%） | 该水平位置的某个高度已有六项根记录；不代表生产缓存已经发布这些值 |

同一水平位置不同高度的温度、湿度、大陆性、侵蚀度、奇异度没有任何原始位变化。深度在大多数水平位置存在变化；上下边界截断或只访问单高度的位置可能没有观察到变化。

**99.54% 不是缓存命中率，也不是可消除的时间占比。** 它证明值曾在 Rust 路径中存在。诊断使用无淘汰的线程局部记录；生产实现仍需有界页、世界所有权、并发发布和失效。Java/Rust 内部已有 memo 命中也不能再重复计为新增节省。

## 为什么当前结构没有共享这些值

1. `ClientTerrainSampler` 的地形密度根和 `PredictionClimateSampler` 的气候根分别建立 `DensityMemo`/`DensityCompilation`。Rust 再维护独立的 Graph/Scratch。它们共享输入定义，没有共享运行中算出的数值。
2. Java `PredictionBiomeCache` 保存最终三维群系答案；`DensityMemo` 每个节点保留一个最近坐标。它能共享一次查询内的 DAG 子表达式与连续同列访问，不能持续保留整个区域数千个水平位置。
3. Rust `climate::targets` 是每个 Scratch 256 项的三维 target 缓存；水平节点的 `HorizontalCache` 为每节点四项，并带 Mode。工作区池保留部分状态，但没有面向 Java 的区域水平输入页。
4. 原有 JNI 返回地表十项记录及群系索引，没有暴露气候根或公共子图结果。Java 得到地形高度，并不自动得到原来算高度时用的 offset 和大陆性。

因此，优先实现的边界是：**世界所有的、有界的水平表达式页 → Rust 原计算路径发布精确值 → Java 原气候图消费已验证节点 → 原 Java BiomeSource 完成路由。** 值不存在时才补算。按实际依赖识别可共享节点；对不同高度，仅补垂直依赖。避免每根每点单独跨 JNI，也避免一次性生成整段世界的三维群系表。

## 哪些环境数据不用为当前查询生成

| 查询或阶段 | 必需 | 可不生成或延后 |
| --- | --- | --- |
| 本次 MultiNoise 来源、已知 quart 坐标的群系查询 | 六项气候输入、原群系路由需要的上下文 | 最终地形密度、地表高度、邻居列、表层材质、RGB、完整区块 |
| 固定群系来源 | 固定 Holder/索引 | 六维气候、参数树与地形环境；原生 fixed 分支已经直接返回 |
| 末地等专用群系来源 | 专用来源实际读取的种子、噪声和上下文 | 不能统一要求先生成主世界六项气候；未执行的分支无需计算 |
| 候选坐标与放置随机选择 | 原 seed、feature seed、modifier 顺序和 RNG | 尚未被任何 modifier 读取的环境；不能少消费随机数 |
| 未编辑的精确代理方块/高度 | floor、fluidY、fluid 类型、结冰/空列标记、三层材质和高度类型谓词 | 十项记录的 RGB 三字段、渲染侧群系索引；已知记录上也不必物化整列方块 |
| 精确代理颜色 | 精确位置群系、资源版本、染色规则 | 可以在实际着色时生成；装饰无需预先保存三种 RGB |
| 普通地表邻居坡度 | 邻居边界/高度 | 不符合特殊几何或规则条件时，不必构造邻居完整列；当前 exterior 路径已做 |
| 地表预测环境 | 实际访问的外壳、稀疏编辑 | 普通路径无需 `80×384×80` 密集方块数组；当前代理已是稀疏/懒加载 |
| 传播光照、实体和 tick | 当前放置契约的亮度/可见天空/方块发光回答 | 无需构造完整动态光照引擎、方块实体 ticking 或实体模拟；当前环境已有专用回答 |

不能将上表理解为更改未知模组的查询契约。未知 Feature、自定义方块钩子、内部三维路径仍需原有委托。尤其不能为了减少表面数据，把真实洞穴密度、含水层、特殊冻海/恶地地表规则或结构影响全部去掉：它们可能改变最终代理的高度、流体或材质。

### RGB 可以晚算，但不是主瓶颈

`RustTerrainSampler.proxyBlock` 和 `proxyHeight` 只消费记录下标 0–6，Rust `ProxyBase.value` 同样如此。`sampleRecord` 创建的 Java ColumnSample 颜色填零。RGB 7–9 在渲染相关的 `rememberColors` 中才进入 tint cache。

带探针归因中，装饰阶段每片约 162,063 次经过统一 record builder，RGB 计算约 **10.3 ms**；温度判断约 4.4 ms，整个记录尾部约 26.1 ms。这些有嵌套计时和探针开销，不能作为延迟颜色原型的净提速。记录尾部不含之前的密度、地表规则和群系选择。

只供精确装饰代理的记录可以分离颜色。显示记录的 RGB 还用于插值与显示缓存判断，不能全部置零；精确记录稍后若升级并进入 `rememberColors`，也不能把占位值写入精确 tint cache。资源包重载应只失效颜色层，避免与几何层合并失效，但需独立实现和验证。

### 缩小预取不能直接省掉大部分精确地表

最终完整路径的跨语言坐标并集见结果 JSON 的 `columns`。此前只观察单个 Feature 或只看 Java 会高估未使用量。即便被某个 Feature 的群系过滤拒绝，其他特性、提取或原生代理仍可能读取同一列。

约 85,917 个精确请求位置中，84,213 个最终被 Java 或 Rust 代理读取，剩下 1,704 个未观察到代理消费者，占 **1.98%**。后续 Voxy 输入构造使用已准备好的显示列及装饰结果，原生第三阶段没有新增地表记录生产。这仍只是代理读取边界：邻居坡度和地表规则存在内部依赖，不能把该小部分自动当成安全删除清单；从批量页改为逐点也可能损失摊销。

此前高度拆分完整路径试验只减少了约 0.089% 的最终完整精确请求，反而增加一轮高度生产。当前证据继续支持：不应把“高度快速回答命中很多”当成“昂贵材质生产消失很多”。

## 兼容与精度边界

- 本次原版六根逐位一致，不能外推为全部种子、全部维度、全部模组已验收。
- TerraBlender 的区域路由、Blueprint 等嵌套来源、自定义 BiomeSource 仍保留原选择流程。Rust 参数树和 Java 参数树可能因上次叶节点、等距离优先级或排序细节给出不同结果；共享 target 可以让 Java 保持自己的判断，直接替换 biome ID 不行。
- FreeTerraForged 的 effective router 与气候 sampler 还存在 preset、surface context、地下分带和运行时计划；Fabric/Forgified Fabric 末地来源带种子上下文。现有 `copyClimateContext` 明确保留了这些数据，新的共享层也必须保留。
- 自定义密度图可以把温度/湿度变成三维函数，或者查询线程上下文。使用现有 `PredictionRawDensity`/Traits 证明纯度与水平依赖，未证明节点保留三维/原调用。不能靠名字、`flat_cache` 或 `cache_2d` 标签推断一定独立于 Y。
- 气候 Raw 模式绕过 NoiseChunk marker 行为。地形插值结果、FlatCache 在对齐点的值，以及显示近似不能直接喂给任意 Raw 坐标。模式、原始节点、坐标、上下文必须对应；现有 Rust 水平 memo 也有 Mode 键。
- Forge 配套项目相应 Java 路径在语义上相同，所查 Rust 文件一致；本次未运行 Forge 运行时测量，不宣称已经取得双版本性能收益。

## 优先级及性能预期

1. 先实现可验证的公共水平子图输入页：大陆性、侵蚀度、奇异度、offset、shift，以及 Rust 地表已经产生的温湿度。最大化复用已有值，保持 Java 原路由。以完整路径输出和逐位输入两级对照验收。
2. 深度保留原表达式的最后垂直操作；从实际图提取水平与垂直边界，未知图继续委托。不要按整个世界生成三维气候大表。
3. 只为必要字段生产环境；颜色拆层作为次要项。预取范围调整要证明整个区域最终工作减少，不能只报告首个 Feature 的局部下降。

此前生产 DLL 的完整路径归因中，Java 群系约占 10.38%，精确地表查询约占 47.12%。即使把 Java 群系全部变成零成本，按该数据计算的总时间也只能降约 10.38%，速度约 1.12 倍；真实共享还需查表、垂直计算和参数树，收益会更小。公共子图若同时减少 Rust 地表内部重复工作，才可能进一步影响更大的部分，需要另测，不能把 99.54% 的输入机会说成接近两倍或数倍提速。

## 复现与生产核对

```powershell
$py = 'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $py tools/performance/climate-sharing/prepare.py build/climate-sharing-reproduction
Push-Location build/climate-sharing-reproduction/native
cargo build --release --offline --locked --target-dir ../native-target
Pop-Location
Copy-Item build/climate-sharing-reproduction/native-target/release/vss_native_core.dll build/climate-sharing-reproduction/main-resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
& .\gradlew.bat '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21' --offline --project-cache-dir build/climate-sharing-reproduction/project-cache -I tools/performance/decoration-experiment/experiment.gradle -PvssExperimentRoot=build/climate-sharing-reproduction -PvssExperimentRounds=5 -PvssExperimentWarmups=1 -PvssExperimentSide=16 '-PvssExperimentModes=baseline' --console=plain --max-workers=2 runServer
& $py tools/performance/climate-sharing/summarize.py build/climate-sharing-reproduction --output build/climate-sharing-reproduction/summary.json
```

结果记录了逐区域输入一致数、时间先后、字段计时、整路径摘要、实际图依赖，以及二进制证据 SHA256。生产侧 490 个 Java/资源文件与 161 个 Rust crate 输入均与实验前清单一致。诊断 DLL 单独加载，生产 DLL 仍为 `2ad535c6ff24b469ca9cb127a6bab73882bb32a1e49fedac3a9e6d89897527d1`。本次没有性能发布，也没有打包。

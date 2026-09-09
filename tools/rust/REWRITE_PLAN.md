# VSS Rust 重写计划（Minecraft 1.21.1 / NeoForge，版本固定 0.3）

本计划的交付目标是：按 Minecraft 1.21.1 原版代码流程将地形噪声、密度、地表高度、液体柱、地表材质规则、生物群系着色和原版植被放置计算移植到 Rust。Java 提供当前世界的注册表、数据包、方块状态、资源包数据与兼容入口；GPU 继续负责绘制。

**实现依据是对应版本的原版函数、调用顺序和配置。世界采集数据只作异常复现与补充回归，不是算法来源，也不是开工前提。** 每个模块先确认原版实现和依赖，再编写 Rust，直接调用本地 Minecraft Java 类做离线对照；不要求用户先跑遍所有生物群系，不从有限样本拟合算法。完整调用链和函数映射见 [VANILLA_PIPELINE.md](VANILLA_PIPELINE.md)。

当前生产后端为本项目 Rust JNI ABI 2；不可用时使用 Java 兼容路径。原版 Java 函数输出是验收基准。

## 当前实际状态（2026-09-09）

这次已实现并接入独立的 JNI ABI 2 Rust 后端。具体模块、直接原版对照与
生产边界以 [README.md](README.md) 和 [VANILLA_PIPELINE.md](VANILLA_PIPELINE.md)
为准。ABI 1 仅保留独立的噪声验证探针。

| 阶段 | 当前状态 | 验收边界 |
| --- | --- | --- |
| 原版算法与直接 oracle | 已建立 | 实际原版方法生成期望值，不从用户样本拟合 |
| 随机与全部所需噪声 | 已实现并对照 | Gaussian 有明确 ULP 界；不宣称所有平台逐位一致 |
| 密度、整柱、高度与液体 | 已实现并对照 | 基础柱、插值与 aquifer 语义；完整 structure/carver 状态另计 |
| 原版地表规则 | 已实现并对照 | 42 surface 案例及 400 稀疏/完整列比较 |
| 生物群系与草叶水颜色 | 已实现并接入 | 实际三维坐标、资源颜色图与重载；固定叶色仍由材质层识别 |
| 所需树、草、花、竹子算法 | 已实现并接入 | 261 configured、171 placed、9 蜂巢案例；含黑森林巨型蘑菇链、原版全局排序、树苗生存、边缘更新与事务 |
| JNI 与生产调用 | 五个原生目标已打包；Windows x86_64 已实测 | Windows、Linux x64/ARM64、macOS Intel/ARM64；批量 state transfer、混合编辑、取消与缓存 |
| 完整世界 chunk statuses | 未全部实现 | 结构起点/jigsaw、carvers、其他装饰阶段和未知模组钩子保持明确边界 |
| 跨平台与游戏帧时间 | 尚待额外验证 | 不以离线吞吐或 Windows 通过宣称所有 GPU/平台及游戏 FPS 已验收 |

需要继续扩大的覆盖是实际多生物群系边界、最终原版区块、完整结构地形
上下文、HashMap tree-bin 极端碰撞顺序、模组自定义方块生存钩子与其他平台。
这些不阻止已经对照和接通的原版计算使用 Rust，但不能省略兼容路径或
把未覆盖情形包装成已经完全一致。

7 份存档的历史评估保留在 [REWRITE_PROGRESS_20260908.md](REWRITE_PROGRESS_20260908.md)。
它们是补充复現数据，不是这次移植的算法来源。

## 已落地的数据采集

离线命令（JDK 21，Gradle 8.8）：

```powershell
gradle --offline -I tools/rust/reference.gradle captureRustReference
python tools/rust/validate-reference.py <输出的 capture 目录>
```

也可以用本项目已安装的 Gradle 完整路径执行。`-PvssCaptureOutput=<目录>` 指定输出根目录，每次建立独立目录，避免覆盖既有基线。

当前离线集合覆盖 forest（两个种子）、plains、flower_forest、bamboo_jungle、badlands、snowy_plains、swamp、nether_wastes、the_end。它们是明确标注的原版固定生物群系夹具，直接使用原版 Java 配置和函数；不是用户世界的采样。间距为 1/16/256/4096 格，包含负坐标和跨区块位置。

游戏中显式运行：

```text
/vssclient prediction capture
```

命令在后台独立计算，以当前维度和玩家附近为起点导出 4 组 4×4 地表柱、原版噪声/密度/基础柱对照，以及 2×2 植被区块。一次仅允许一个采集任务。平时不自动采集，不在每帧刷日志，不修改世界或预测缓存。单人存档位于 `.vss/prediction/rust-reference`，服务器位于客户端相应 `.vss/servers/<标识>/prediction/rust-reference`。任务完成后在聊天栏显示路径。

每组数据包含：

- `generator.json` / `registries.json`：原始 Java 世界生成配置，保留地表规则、噪声、密度、生物群系、feature、结构等输入。
- `columns.jsonl.gz`：Java 地表记录、方块名称、生物群系和颜色，以及独立的原版基础高度。
- `density.jsonl.gz` / `noise.jsonl.gz`：Java 原版函数输出，用十六进制浮点字符串无损表示。
- `base-columns.jsonl.gz`：`getBaseColumn` 的全高度 BlockState 连续段，用于检查插值与液体结果；包含开始/结束高度。
- `vegetation.jsonl.gz` / `vegetation-feature-order.json` / `random.jsonl.gz`：当前预测地面上的原版 feature 输出、全局 feature 顺序和装饰随机序列。
- `block-states.jsonl.gz` / `block-tags.json` / 两张 colormap：方块属性与地表/着色/植被所需基础输入。
- `manifest.json`：版本、来源、种子、维度、范围、Java 来源、各文件哈希和成功完成标记。失败目录没有成功标记。
- 游戏采集额外包含完整 `worldgen-profile.bin`，可还原当时收到的服务端快照。

## 数据解释与仍需补齐的覆盖

1. 已采集的原版基础高度与旧库高度确实存在差异。验证脚本报告平均/最大误差，不把这项测量包装成原版一致性通过。密度节点直接求值与实际生成时细胞插值也要分别比较。
2. 当前植被输出是在 VSS 有界预测地面上调用原版 placed feature 得到的。它可作为当前实现的回归基线；完整原版 chunk 装饰还需验证结构先后、地表生成、光照、邻区读写、其他装饰阶段与数据包。最终植被验收必须加入真实生成世界中的区块对照。
3. 还需补充多噪声生物群系交界、完整种子矩阵、海洋/含水层、红树林/樱花/大型树、modded 世界生成、资源包颜色覆盖、BiomeManager/混色边界、修改后的世界脏列。
4. 新导出使用 `vss-rust-reference-2`，不需要原生库；旧格式文件仍可由验证脚本读取，但不再提供旧二进制重放。
5. 样本只能验证实现，不能由有限输入输出推断整套未知算法。原版算法需从对应 1.21.1 代码/配置逐项移植，遵循相应许可证。

## ABI 与缓存约定

- 生产库使用 JNI ABI 2，提供版本与逐 feature 能力查询，版本/能力不匹配时显式回退 Java。
- 采用批量调用和有上限的直接缓冲区。地形和编辑走批量接口，三维颜色查询有 Java/native 缓存；记录 count/stride/容量/字节序和返回值语义。
- Rust 内部错误转为状态码；panic 不得跨 JNI；拒绝无效句柄、已关闭句柄、长度溢出、只读或不足容量的缓冲区。噪声原型关闭时移除句柄，在途调用通过 Arc 租约保留状态直到结束；新调用拒绝，不提前释放内存。生产 ABI 2 已在 sampler 退役时发出取消，原生按列边界停止；结果句柄保留所有者租约。
- 磁盘键包含算法/协议版本、种子、维度、世界生成指纹；材质与资源包颜色数据应独立失效。保留现有存档 `.vss` 和服务器隔离路径以及脏列刷新。
- 资源包纹理、模型烘焙和 OpenGL 对象由 Java/渲染线程管理；Rust 输出完整材质/状态标识与数值，不访问 JVM 对象内部布局。

## 每阶段的交付物

对应 Rust 源码、JNI 契约及 Java 调用、不可变输入夹具、对照报告、失败/回退测试、受支持平台构建产物、性能前后对比。模组版本继续为 `0.3-neoforge-1.21.1`，内部算法/缓存版本独立递增。

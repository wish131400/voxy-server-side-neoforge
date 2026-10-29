# vss design benefit tests 20261012

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 对照 Voxy / Sodium 设计的收益测试 — 2026-10-12**

本轮完成四个候选的实现与组件 A/B，保留两项：让直绘路径也能在提交前删除可证明背向相机的整段命令；水面在相机只改变 Y 时复用完整提交计划。带精确连接环的 2×2 局部地形 LOD 实现因实际成本上升被淘汰；2 方块占用树冠代理虽降低 GPU 时间，但填平树冠层间空隙、丢失少数材质，因此被淘汰。两项保留改动已同步到 NeoForge 1.21.1 与 Forge 1.20.1，两版 JAR 均完成构建。

这些结果是独立 OpenGL 组件测试，尚未进行同存档、同位置、同画质的客户端 FPS 对照。重复提交合成几何是为了测出组件成本，表中的百分比不能直接换算成游戏 FPS。

**比较基线与实施范围**

基线是本轮开始时两个仓库的完整预测源码工作树快照，包含此前已保留但尚未提交的优化。快照位于两仓库的 `tools/prediction/design-benefit-experiments/baseline/{neoforge,forge}/`。核对生产预测源码，相对各自快照只修改了 `PredictionRenderer.java`、`PredictionMeshletBounds.java`、`PredictionPackedMesh.java`，新增 `PredictionCommandDirections.java`。未将工作树恢复至 Git HEAD；已有显示 LOD、植物整株范围选择、精确面合并、静态命令重放、Voxy 归属、接缝和驻留机制继续使用。

完整差异归档为 `tools/prediction/design-benefit-experiments/results/{neoforge,forge}/production.patch`。`PredictionDisplayGeometry.java` 和 `PredictionVegetation.java` 均与本轮快照逐字节相同，被拒绝的局部地形和树冠表示没有进入生产类或 JAR。

测试基线 `BenefitBaselinePass` 提取本轮开始时的 PreparedDraw / PreparedPass，使用相同当前运行环境。比较没有借用更早版本的 PreparedPass 冒充当前基线，也没有用 Git 旧版本删除之前的优化。

**测试方法与边界**

显卡为 NVIDIA GeForce RTX 4070 Ti SUPER，驱动 572.70，OpenGL 4.6。两版本依次运行独立 GPU 测试，没有同时启动相互争用显卡的对照。隐藏窗口为 256×256，使用生产打包格式、地形 / 水面着色器、几何页、间接绘制与完整静态计划重放。

GPU A/B 每组预热 24 轮、测量 81 轮，基线与候选交替先后。CPU 区间包含实际准备和提交，排除测量前的 `glFinish` 及测量后的同步查询读取。`GL_TIME_ELAPSED` 包围准备 / 提交区间，所以 GPU 数值包含该区间中可能产生的等待，不应解读成纯 shader 执行时间。CPU 与 GPU 时间会重叠，不能相加后换算帧率。

方向测试将一份 16,384 面合成瓦片重复提交 64 次；地形 / 树冠测试重复 128 次；水面测试使用 1,024 条绘制记录。方向和水面比较均执行实际生产 PreparedPass，随后尝试完整计划重放，未只比较已经生成的两个命令数组。两侧准备方法通过可比较的反射调用测试入口执行，绝对 CPU 数值也包含测试入口开销。

地形构建测试预热 5 轮、测量 21 轮；纯准备测试预热 400 轮、测量 301 轮。以下主表使用中位数，逐轮数据、P95、最小值、最大值及哈希保存在 JSON 与 CSV。这里的 P95 是组件样本分布，未测整帧 P95 / P99。

**保留：直绘模式的保守方向范围选择**

沿用网格后台构建时已有的 256 面 meshlet 方向掩码，先在 PreparedPass 选择静态命令范围，使自适应策略进入直绘模式后仍可减少顶点提交。没有每帧重新解码顶点，也没有把通用几何改成强制六方向新格式。整个 meshlet 能被证明背向相机时才删除；混合方向、斜面、未知和双面模型继续提交。

方向判断同时考虑瓦片 bounds 和上传几何的实际范围，避免采样地形高度漏掉高树或模型。接缝、morph、非有限相机 / bounds 保守保留。4 方块保护带用于抵御世界边界附近的浮点误差。相机在方向阈值之间移动时复用区域缓存；跨阈值时更新方向选择，不重新做 Voxy 归属过滤。归属删除量和方向删除量分开统计，新增 `directionExcludedQuads` 便于实机验收。

| 合成场景 | 面数：基线 → 保留实现 | 命令数 | NeoForge GPU | Forge GPU |
|---|---:|---:|---:|---:|
| 全部为背面 | 1,048,576 → 0 | 64 → 0 | 0.433152 → 0.001024 ms | 0.433152 → 0.001024 ms |
| 全部为正面 | 1,048,576 → 1,048,576 | 64 → 64 | 1.471488 → 1.468416 ms | 1.470464 → 1.470464 ms |
| 一半背面且范围连续 | 1,048,576 → 524,288 | 64 → 128 | 0.873472 → 0.741376 ms | 0.869376 → 0.739328 ms |
| 未知 / 双面 | 不变 | 不变 | 1.472512 → 1.470464 ms | 1.467392 → 1.474560 ms |
| 正在 morph | 不变 | 不变 | 1.473536 → 1.471488 ms | 1.470464 → 1.472512 ms |
| 背面棋盘碎片 | 保守保持原范围 | 64 → 64 | 0.754688 → 0.751616 ms | 0.753664 → 0.752640 ms |

连续半背面测试中，GPU 时间分别减少 15.1% 和 15.0%；NeoForge 准备 / 提交 CPU 为 0.033600 → 0.035500 ms，Forge 为 0.028700 → 0.029899 ms。额外 CPU 成本明显小于本场景省下的 GPU 区间。正面、未知、morph 等对照仅有微小波动，没有把这些波动算成正收益。

早期没有碎片保护的棋盘实现虽然删去一半面，却使命令从 64 增至 1,024，GPU 没有明确收益，约为 0.754688 → 0.755712 ms。最终实现若删除不足 12.5% 面，或保留范围超过 `max(4, 原范围数量×2)`，就保持原有粗命令。该阈值来自本次有限样本，不是适合所有 GPU / 整合包的理论最优值。

六种最终场景的颜色、深度均与基线一致；正面和混合场景额外确认有真实可见像素。回归还覆盖相机跨方向边界、世界极限坐标、高模型超出采样 bounds、局部归属范围、unknown / 斜面保留，以及 morph 后立即恢复原范围。

**保留：水面纯 Y 移动复用提交计划**

现有水面距离比较只使用 X/Z，过去相机 Y 改变也触发排序并推进 pass 版本，导致完整提交计划失效。现在快速复用路径只在 X/Z 改变时重排。相机 Y 仍用于覆盖与渲染，网格、GPU 驻留、归属和范围变化仍走原有版本检查；没有采用近似排序或按显存页改变透明顺序。

| 1,024 条水面记录 | NeoForge CPU | Forge CPU | NeoForge GPU | Forge GPU |
|---|---:|---:|---:|---:|
| 相机只上下移动 | 0.232600 → 0.046600 ms | 0.235500 → 0.041199 ms | 0.100352 → 0.099328 ms | 0.100352 → 0.100352 ms |
| 相机水平移动 | 0.105200 → 0.100999 ms | 0.081700 → 0.083999 ms | 0.087040 → 0.087040 ms | 0.086016 → 0.087040 ms |

纯 Y 移动时，完整准备 / 提交 CPU 中位数减少约 80.0% / 82.5%，包括预热在内的完整计划命中从 0 增至 104。水平移动的计划命中仍为 0，继续按原顺序重排；微小耗时变化不作为收益结论。两条路径的颜色和深度一致，并确认水面画面非空。这个收益适用于纯 Y 移动且无其他版本失效的情况，不能推广为所有跑图场景都少 80% CPU。

**淘汰：2×2 地形子区域 LOD 的本次实现**

实现了四个地形 patch 独立误差、bounds 和绘制范围，并保留外缘及内部精确连接环，测试原有环坐标和 scoped 模式恢复全部 16,384 个源面。对照为 128×128 合成地形：均匀微小起伏，以及一角有较大起伏。

| 地形样本 | 实际提交面数 | 命令数 | NeoForge GPU | Forge GPU |
|---|---:|---:|---:|---:|
| 均匀微小起伏 | 137,216 → 262,144 | 128 → 512 | 0.329728 → 0.525312 ms | 0.330752 → 0.527360 ms |
| 一角较大起伏 | 216,448 → 335,104 | 128 → 512 | 0.434176 → 0.617472 ms | 0.434176 → 0.618496 ms |

存储分别从 230,700 → 261,552 words、241,476 → 265,980 words。NeoForge 构建中位数为 6.243400 → 10.571100 ms 和 4.002800 → 4.960900 ms；Forge 为 4.591001 → 5.713399 ms 和 4.893300 → 7.076500 ms。局部误差未充分降低，额外连接环与命令成本超过收益，因此退出生产方案。

选择测试使用假设相机与投影尺度，随后用真实生产 shader / MDI 比较所选几何，并确认两侧非空。它不是当前客户端完整 clip-W / 透视选择器的实机重放。本次结论仅否定这个实现，不能证明所有局部 LOD 都无效；今后需要能在减少连接成本的同时证明几何误差的方案。

**淘汰：2 方块占用树冠代理**

保留树干原坐标，对叶片按 2 方块占用体素和多数材质生成外表面。原始和粗表示均使用已有生产精确面合并，避免把原实现已经具备的合并收益再算一次。测试包含简单树冠、上下分层树冠、稀疏混合材质树冠，并比较距离 180、1,800、4,096 方块。

| 树冠样本，距离 1,800 | 提交面数 | NeoForge GPU | 质量检查 |
|---|---:|---:|---|
| 简单 | 886,784 → 425,984 | 0.345088 → 0.176128 ms | 新增 11,264 个原本空的占用单元 |
| 上下分层 | 2,296,832 → 622,592 | 0.868352 → 0.249856 ms | 填平 5,120 处层间空隙 |
| 稀疏混合材质 | 18,863,104 → 1,138,688 | 7.605248 → 0.448512 ms | 填平 5,120 处空隙，1,728 个材质不符 |

Forge GPU 对照同样有明显减时，但质量要求不满足。粗树冠额外原始表示占约 528,384～983,040 字节 / 样本；若和细表示共存，还要计入额外构建与驻留成本。树干精确一致不足以证明树冠正确；在 256×256 远景中只覆盖几个像素的图像也不足以确认资源包、树冠层次和剪影安全。没有把加速作为唯一验收条件，该代理仅留作独立实验材料。树冠构建的单次耗时只用于诊断，没有当作正式多轮构建 A/B。

**回归、同步与打包**

| 检查 | NeoForge 1.21.1 | Forge 1.20.1 |
|---|---:|---:|
| 正确性回归 | 202 / 202 通过 | 201 / 201 通过 |
| 生产 GPU 测试 | 10 / 10 通过 | 10 / 10 通过 |
| 候选 CPU 测试 | 3 / 3 通过 | 3 / 3 通过 |
| 最近保留的候选 GPU XML | 水面单独重测 1 / 1 通过 | 完整候选 3 / 3 通过 |
| JAR 构建及 ZIP 完整性 | 通过 | 通过 |

NeoForge 前一次方向 / 几何表示候选 GPU 测试已成功并保存 CSV；最后仅重测水面，Gradle 覆盖了该组 XML，所以不把最近 XML 的 1 项伪称为完整 3 项证据。Forge 最后一轮保留完整三项候选 XML。原始 CSV 与最新 XML 已在两个仓库的 `tools/prediction/design-benefit-experiments/results/{neoforge,forge}/` 归档。

生产回归包含 Voxy 覆盖撤销、混合 / 缺失覆盖、网格和 feature 回退、接缝、morph、原始 / 压缩解码、显示 LOD、植物选择、页组及独立缓冲重放、透明顺序、Hi-Z / 直绘策略、GL 状态恢复和实际 Voxy JAR 的深度约定。两个共享方向辅助源码、测试实现和 init script 已同步；各加载器原有接口差异保留。

实验代码仅由 `tools/prediction/design-benefit-tests.gradle` 创建的显式实验 source set 引入。普通 main source set 和成品 JAR 不含 Benefit、Completion 测试类或 JUnit。两版包中的 Windows / Linux / macOS Rust 本地库均与上一份植物 LOD 包逐字节相同，本轮修改限于 Java 渲染准备。

| 成品 | 大小 | SHA256 |
|---|---:|---|
| [NeoForge 1.21.1](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/design-benefit-20261012/vss-0.3.5-neoforge-1.21.1.jar) | 5,952,885 bytes | `3101aba82ae5c7bd87984753efe924e19b7e44b09f5bd700ff9c5ba488495acd` |
| [Forge 1.20.1](C:/Users/Administrator/Desktop/voxyserverside/lib/design-benefit-20261012/vss-0.3.5-forge-1.20.1.jar) | 6,625,203 bytes | `d56b0ff85d221012d1c00f731c1baad4e2bd1b6f6df6ef498964549dbf1390ca` |

本轮没有提交或推送 GitHub，也没有替换已安装的客户端模组。条件候选“上传结果估计、在线显存页整理、竹子模板缓存”尚缺对应热点证据，本轮未加入生产路径。下一次实机对照应观察新增方向删除量、实际 MDI 面 / 命令、各通道 GPU、准备 / 提交 CPU 和整帧 P95 / P99，才能判断当前世界的整体帧率变化。

**复现与证据**

机器可读报告：[vss-design-benefit-tests-20261012.json](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-design-benefit-tests-20261012.json)。报告脚本只整理已经完成的测试，不会启动 Minecraft 或 GPU 测试：

```powershell
python tools/prediction/design-benefit-experiments/summarize.py --neoforge-root C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1 --forge-root C:/Users/Administrator/Desktop/voxyserverside
```

显式重做测试需在相应仓库指定对应 JDK，避免和客户端同时进行 GPU 测量。NeoForge 使用 JDK 21；Forge 使用 JDK 17，并补充 `-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17`。CPU / 回归与 GPU 可分开执行：

```powershell
./gradlew.bat -I tools/prediction/design-benefit-tests.gradle designBenefitCpuTest designBenefitRegressionTest --console=plain
./gradlew.bat -I tools/prediction/design-benefit-tests.gradle designBenefitGpuTest designBenefitProductionGpuTest jar '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' --console=plain
```

`vssVoxyJar` 在此用于实际深度约定验证，路径需按本机安装情况调整。上述命令的工作区输出放入独立的 `build-design-benefit-20261012/`，成品输出放入 `lib/design-benefit-20261012/`。

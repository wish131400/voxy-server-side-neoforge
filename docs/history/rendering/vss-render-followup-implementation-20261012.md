# vss render followup implementation 20261012

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 渲染架构后续实施与 A/B 验收 — 2026-10-12**

依据 [lNvcOAWHJs Spark](https://spark.lucko.me/lNvcOAWHJs) 和[上一轮调查](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-render-followup-20261011.md)，本轮保留两项改动：接缝候选空间索引、按当前接缝依赖管理 GPU 驻留。两个持久映射读回实验和提前背面判断实验均未达到保留标准，已从生产源码撤回。

两个仓库已同步本轮修改、回归、实验资料和新 JAR。对照基准是本轮开始时的工作树源码，包含此前已经实施的优化；没有用 Git HEAD 替换既有未提交工作。本轮没有游戏 FPS A/B，下面的毫秒数均有明确的组件范围。

**保留：让接缝候选按空间局部查询**

原路径在地形完成空间筛选后，仍把两个接缝批次的全部 key 加回候选列表。新 [PredictionSeamIndex.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionSeamIndex.java) 给接缝建立动态四叉树，每个节点聚合自身和子节点的真实 patch 包围盒。patch 增删、替换只更新对应路径，场景准备先查询相关子树，再执行原有精确可见选择。

包围盒保留接缝实际高度和水平延伸，不能用名义地形高度替代。可见成员仍由原选择器决定，原有稳定顺序和后续水面排序继续执行。随机回归覆盖负坐标、多 LOD、伸出地块的接缝、替换、删除、清空与转身；确认轮进一步要求优化前后的可见列表及顺序完全相等。

组件 A/B 使用 census 中的 6,306 个接缝地块坐标，重放转动、小幅移动和每帧一项局部更新；每轮 A/B 交替，预热 80 轮、测量 121 轮。高度重建为 [-64,512]，没有捕获完整实际 patch，不能将其称为真实存档逐帧重放。

| 平台与轮次 | 原候选处理中位 | 索引候选处理中位 | 下降 | 加上该帧局部索引更新后的中位 |
|---|---:|---:|---:|---:|
| NeoForge 首轮 | 0.5776 ms | 0.4221 ms | 26.9% | 0.4244 ms，下降 26.5% |
| NeoForge 确认轮 | 0.6666 ms | 0.5086 ms | 23.7% | 0.5101 ms，下降 23.5% |
| Forge 首轮 | 0.5931 ms | 0.4626 ms | 22.0% | 0.4638 ms，下降 21.8% |
| Forge 确认轮 | 0.6898 ms | 0.5054 ms | 26.7% | 0.5070 ms，下降 26.5% |

这里的候选处理包含新树查询、候选集合构造和原可见列表处理。更新单独计时，表中最后一列按每轮 query+update 的和重新取中位，没有直接相加两个独立中位数。单次更新中位约 0.0015–0.0017 ms。

两轮测试的下游精确条目检查累计均从 1,267,506 次降到 348,043 次，约减少 72.5%；累计包含预热。这不是全部视锥判断次数的减少比例，树本身也执行包围盒测试，其成本已包含在候选处理计时中。四组 P95 也均下降，完整数据见 CSV 和 JSON。

**保留：收窄接缝对 GPU 地形的保护，并加强安全父级回退**

[PredictionRenderer.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderer.java:763) 的上传准入和驻留观察同时改用接缝真实 bounds 判断；存在 CPU 接缝记录不会直接把整块地形永久标为 required/protected。当前可能可见的接缝仍采用 64 方块边缘余量保护，未知视锥时保守保护。

[PredictionRenderResidency.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRenderResidency.java:106) 新增回收专用父级检查：父级必须已在 GPU 驻留表内、属于普通覆盖、拥有非空几何；细地块已有完整地表、植被或水面时，父级还必须完成地表生成。望远镜专用父级不能作为普通回收替代。每个退休对象执行前再次校验，防止同一批次先回收了它的父级。

冷地块入队时使用当前 CPU tile 重验父级，不能根据已经移出驻留表的子级判断；单纯后台 revision 更新不会立即恢复冷 GPU 副本。近处 256 方块、望远镜范围、可见接缝、缺少安全父级的地块和粗级覆盖保持保护。

沿用原有节奏：10 秒视野外等待期、0.5 秒扫描、每次至多 8 块／约 32 MiB 的退休选择，以及原有每帧上传和释放预算。没有通过立即清空视野外几何制造测试收益。

实际 OpenGL 回归分别在两个平台验证以下链路：

- 旧条件下，8 块地形因为存在 CPU 接缝记录无法退休；新条件下，在等待期满且存在完整父级时可以退休。
- 测试中的实际 live payload 共 134,144 字节，释放后为零；独占的 64 MiB 测试页完全变空后，缓冲对象被真正删除。
- 细节退休时，普通父级始终提供地形和植被覆盖；冷状态下 CPU 版本变化不会马上触发重新上传。
- 转回视野时可恢复 GPU 数据，上传内容逐字一致。一次恢复 8 个小测试网格的 CPU 提交约 0.53 ms；这是链路验证中的单次事件，不是大网格恢复延迟上限。
- 未完成植被／水面父级、望远镜专用父级不能触发不安全退休；高空可见连接面仍保护对应地形。

64 MiB 是小网格 fixture 使用的页容量，实际 live payload 只有约 0.128 MiB；它证明整页释放机制，不代表存档将固定节省 64 MiB。上一轮发现的约 2.53 GiB 视野外接缝保护数据仍只是待复核规模，本轮没有宣称已经回收它们，也没有测得新存档的显存下降、直绘减少或转身最差帧改善。

**撤回：两种持久映射读回**

对相同 1,024 个 probes 使用生产深度金字塔和过滤路径，每轮交替 A/B，预热 32 轮、测量 81 轮；每次隐藏反馈结果一致。零超时 fence 的语义、版本、epoch 和过期检查保持一致。测试只在 CPU 计时外用 glFinish 确保 poll 已完成，GPU 计时覆盖完整提交。

| 实验 | 原 CPU 提交+poll 中位 | 候选 CPU 提交+poll 中位 | 原 GPU 中位 | 候选 GPU 中位 | 决定 |
|---|---:|---:|---:|---:|---|
| 普通输出复制到持久映射 staging | 0.6990 ms | 0.7363 ms | 0.044032 ms | 0.103424 ms | CPU、GPU 均变差，撤回 |
| GPU 直接写持久映射输出 | 0.5298 ms | 0.4891 ms | 0.044032 ms | 0.207872 ms | CPU 只改善约 0.041 ms，GPU 成本明显上升，撤回 |

CPU 总中位来自每轮提交和 poll 之和，不是两列独立中位的相加。CPU 与 GPU 会重叠，不能再把两者相加当作帧时间。两种方案的运行环境和基线分别记录，不能横向拼接两次的 CPU 数值。本轮 GPU 为 RTX 4070 Ti SUPER、驱动 572.70；不能据此断言持久映射在其他 GPU 或 Voxy 的不同工作负载中没有价值。

新 helper 和读回实验测试已从生产／测试 sourceSet 删除；[原 PredictionRefinementOcclusion.java](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionRefinementOcclusion.java) 与本轮开始时逐字节一致。实验源码以 .java.txt 保存于 tools 下，不参与编译或打包。

**撤回：材质处理之前的保守背面判断**

候选仅对非双面、常量平面的几何提前返回，保留坡面和 morph 语义。在相同生产 MDI 记录与独立缓冲回退下，对上方、下方、斜向三个视角分别测量，每种模式 81 轮；每次提交约 270 万面，为重复 fixture，不是实际世界面数。

| 视角 | 原 GPU 中位 | 候选 GPU 中位 | 变化 |
|---|---:|---:|---:|
| 上方 | 1.984512 ms | 1.995776 ms | 变慢 0.57% |
| 下方 | 1.979392 ms | 1.974272 ms | 下降 0.26% |
| 斜向 | 2.007040 ms | 1.982464 ms | 下降 1.22% |

不透明、水面、全部方向／单方向的颜色和深度一致，但未出现稳定、明显收益，已撤回。[生产顶点程序](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/src/main/java/dev/xantha/vss/client/prediction/PredictionTerrainProgram.java) 与本轮基线逐字节一致。未引入六方向 meshlet 命令、普通父级屏幕选择或自适应 Hi-Z 重启规则的新实现；PreparedPass 的进一步局部更新也没有作为本轮已完成收益报告。

**最终验证与产物**

| 平台 | CPU 回归 | OpenGL 回归 | 确认轮 A/B | 失败／跳过 |
|---|---:|---:|---:|---:|
| NeoForge 1.21.1，JDK 21 | 125 | 9 | 1 | 0／0 |
| Forge 1.20.1，JDK 17 | 123 | 9 | 1 | 0／0 |

数量取自 JUnit XML；两个平台既有测试集有差异，共享 122 项 CPU 回归，另有 NeoForge 3 项、Forge 1 项。OpenGL 回归包括真实 Voxy 深度、水面合成、普通/Iris 特征交接、显示层级、通用／紧凑格式、当前帧 Hi-Z、静态提交重放、细化可见性反馈和 GPU 冷恢复。深度回归从已安装的 NeoForge Voxy 0.2.15-beta JAR 读取实际 shader 文本；Forge 测试没有加载这个 NeoForge 模组，不能将其称为 Forge 整合包启动验收。

两个 JAR 已通过 ZIP 完整性、版本元数据、Java 字节码级别和本地库哈希检查；新索引类存在，实验 helper 与测试类不存在。Forge 已执行 reobfJar 并核验实际 SRG 方法引用。五份 native 文件与两个仓库的资源文件哈希一致，本轮没有修改 Rust 二进制。

- [NeoForge 新 JAR](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/lib/render-followup-20261012/vss-0.3.5-neoforge-1.21.1.jar)：5,937,669 字节；SHA256 `0bc185c59ff115ac9decce0b7b2494d0bb626653ca59155a855d1beedf5b52fb`。
- [Forge 新 JAR](C:/Users/Administrator/Desktop/voxyserverside/lib/render-followup-20261012/vss-0.3.5-forge-1.20.1.jar)：6,610,008 字节；SHA256 `03269d70f3e735482f80a6cf13a97c8106accfc6300d41352898a749878960f3`。
- [机器可读结果](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/vss-render-followup-implementation-20261012.json)、[本轮生产补丁](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/tools/prediction/render-followup-experiments/production-change.patch)、[实验和结果目录](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/tools/prediction/render-followup-experiments)。两个仓库均保存相同资料。

**复现方式**

NeoForge 在仓库根目录执行；GPU 测试使用隐藏独立窗口，不启动 Minecraft：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
.\gradlew.bat --offline -I tools/prediction/render-followup-tests.gradle followupRegressionTest followupCpuBenchmark followupGpuTest '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' jar --console=plain
```

Forge 在对应仓库根目录执行，将 JDK 与最终打包任务改为：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
.\gradlew.bat --offline '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/render-followup-tests.gradle followupRegressionTest followupCpuBenchmark followupGpuTest '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' reobfJar --console=plain
```

GPU 测试应顺序运行，性能 A/B 也应避免与其他重负载竞争。`tools/prediction/render-followup-experiments/summarize.py` 使用标准 Python 重新汇总保存的两轮 CSV、JUnit XML 和产物哈希。fixture SHA256 为 `1be0c4dc7fbcc7f68c74d4be0782aa04560d174f38ca93df83e2eabf1b2cb08d`。拒绝实验的完整源码、CSV、日志位于 `rejected/`，不会进入交付 JAR。

后续实际游戏验收应对齐相同位置、视角、画质和加载阶段，比较准备耗时、GPU 驻留／直绘量、上传量、转身恢复与帧时间分布；本轮组件的 22%–27% 不能直接换算成游戏 FPS 增幅。

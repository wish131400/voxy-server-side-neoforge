# 0.3.5 预测加载优化与验证

日期：2026-10-06，Asia/Hong_Kong。基于 [客户端加载调查](prediction-loading-profile-20261006.md) 的四项根因，已同步实现于 Forge 1.20.1 和 NeoForge 1.21.1。按用户要求以 0.3.5 版本重新打包，保留已完成的优化。当前用户自行替换 JAR 和启动测试，本轮没有部署到游戏实例。

## 颜色兼容和原生后端

`tools/rust/vss-native-core/src/biome.rs` 现在接收完整 Java 有符号 int32 颜色，并按 Java 逐通道掩码的含义取低 24 位 RGB。现场 `zombie_extreme:scorched_earth` 的 `FF373927` 草/叶色与 `FF475D57` 水色不再使整个文档拒绝导入。可选字段、必填字段、错误类型和 int32 越界检查保留。

新增 `biome_colors` 回归、Rust 严格快照导入 CLI 和 Java JNI 导入对照工具。Windows x86_64 DLL 已重建、两端一致，ABI 仍为 6，SHA256 为：

```text
03447C3F2AD8EF552A1449BB81C1D69C1E04DE8E682FE9D91360AFAA607B97D5
```

实际 DLL 对照使用现场捕获的 137 个 biome 和 feature 注册表，搭配明确标注的 vanilla 地形、状态表及色图夹具。旧 DLL 重现 `invalid biome color grass_color`；新 DLL 成功导入，6 个 surface record、6 个 tint、状态表与 feature schedule 均和只做 RGB 归一化的同一文档一致。

完整现场快照仍缺运行时 `block_definitions`、canonical `input_states` 和最终 `vss_terrablender` 位置路由。真实 density/biome 数据的部分导入已越过原颜色拒绝点，随后停在缺 block definitions；这不是完整整合包导入成功的证据。尚未在客户端确认 `rust=true`。其他操作系统的预编译库本轮没有重建，颜色修复已同步到 Rust 源码，Windows 产物包含新 DLL。详细材料见 [原生颜色验证](prediction-native-color-compatibility-20261006.md)。

## Java 实体柱补采样

`PredictionJavaExterior` 按不可变 sampler 快照为每个工作线程保留最多 8 个 NoiseChunk 工作区。相邻查询共享已映射密度图，减少重复建图与临时对象。已识别的原版有状态缓存在再次使用前恢复到实际构造结束时的标量和数组状态，包含 FlatCache 构造期间对嵌套缓存产生的初始状态。

有顺序要求的图仍从顶部向下按原版求值。未知模组节点、未知 generator 子类、无法证明兼容的反射结构均走原遍历路径。异常或取消会丢弃受影响上下文，防止污染后续采样。查询继续使用 Minecraft 的 aquifer、方块状态和实体区间语义，没有通过跳过未知密度节点降低精度。

`PredictionColumnVolume` 的相邻补采样采用有界批次，并保留逐标量等价性。`PredictionTileManager` 将实体柱/悬挑补采样单列为 `stagesMs.exterior`；它和采样总窗口有包含关系，不能与父阶段相加。原先的 `color` 标签不再适合被直接解释为纯颜色计算成本。

最终加载回归中的复杂 vanilla Overworld 有顺序图对照，分别采样同一片 20 次，均先验证结果一致：

| JVM / Minecraft | 原遍历耗时 | 工作区复用耗时 | 原分配字节 | 复用分配字节 |
| --- | ---: | ---: | ---: | ---: |
| Java 21 / NeoForge 1.21.1 | 378.541 ms | 213.617 ms | 313,873,136 | 33,372,240 |
| Java 17 / Forge 1.20.1 | 392.866 ms | 216.626 ms | 323,389,120 | 33,097,232 |

这个特定阶段的耗时降低约 44%～45%，临时分配降低约 89%～90%。不是整世界加载速度或 FPS 的测量。小型合成密度图可能因 JVM 逃逸分析而没有耗时收益；该用例验证相邻 16 柱只构建一个映射上下文，实际 Overworld 用例另验证分配减少。

## 预生成放行

`PredictionLoadingProgress` 分开发布基础覆盖、近处预览、近处实际普通目标精度、重要请求地表的进度。`PredictionTileManager.refreshLoadingProgress()` 使用普通目标，排除可选 idle 精化；dirty 或 scope-only resident 不算目标完成，Voxy 已拥有的地表不形成额外地表债务。尚未建立计划和已建立但为空的计划分别表示。

`PredictionGenerationPriority` 要求四项各达到 95%，并稳定 1 秒后才渐进恢复。正常状态下任何一项持续 2 秒跌至 85% 以下，会重新让预测优先。CPU/帧预算为 0 会立即撤销新请求，已经发出的任务允许完成。

45 秒没有新的最佳进度或等待满 120 秒，只开放 5 秒、总在途并发上限 1 的保活窗口；随后冷却 15 秒。超时不能绕过 CPU/帧预算，也不会自动进入满并发。完成细节后仍可在冷却期间转入正常恢复。

## 共享 CPU 和帧预算

新增进程级 `PredictionCpuBudget.SHARED`，每 500 ms 采样系统/进程 CPU 和滚动 FPS。CPU 达 85% 或 FPS 低于 45 时预算减半；CPU 达 95% 或 FPS 低于 30 时减至四分之一，最少保留 1 个预测名额。恢复要求 CPU 低于 80%、FPS 至少 50 的健康状态持续 3 秒，并按档逐级恢复。

每个真实 worker 在昂贵工作开始前取得非阻塞 lease，完成、取消、异常、内存拒绝等路径均在 finally 释放。不同维度/管理器、地形和地表阶段竞争同一个预算。拒绝只延后重试，不会把瓦片记为永久失败。

本地 VSS 预生成按实际在途请求数占用同一准入预算。扫描前原子预留，扫描结束的 finally 发布真实数量并释放未用额度；断线重置或超过 2 秒未更新的观察不会残留债务。请求数是保守准入上限，不等于原版集成服务器真实线程数。远程服务端生成不占本机 CPU 名额。

在严重压力或 LOW 档的 PAUSE 状态，当前实际目标和重要地表可同时执行最多 1 项细节任务；两次启动至少隔 2 秒。间隔从启动计算，长任务结束后不会再无故等待完整 2 秒。排队后 worker 会再次检查限流并争用实际 lease，计划阶段的提示不能让两个管理器同时执行。30～45 FPS 和低于 30 FPS 都有实际推进测试；可选 idle 升级仍须健康帧率。缺失覆盖/dirty 刷新保留优先准入，既有本地生成排空时仍可维持一个恢复通道。

## 最终回归

| 检查 | 通过 | 跳过 | 失败 / 错误 |
| --- | ---: | ---: | ---: |
| NeoForge `loadingRegressionTest` | 187 | 1 | 0 |
| Forge `loadingRegressionTest` | 187 | 1 | 0 |
| Rust release：biome_colors + surface + worldgen | 17 | 0 | 0 |

每个 Java 加载套件总计 188 项，其中包含 25 项 Java 外露地形/实体柱验证：真实 Overworld、aquifer、负坐标与 noise cell 边界、悬空多层、嵌套构造缓存、未知模组逐调用 trace、取消后重用、保存的山体和不同 LOD 接缝。

同一套件还包含实际任务队列准入、40/25 FPS 进度、跨管理器并发、成功/异常/取消/close/内存拒绝/执行器拒绝释放、真实目标及地表进度、超时保活和恢复退化测试。唯一跳过的是需要 `VSS_SURFACE_RETRY_BENCH_OUTPUT` 的可选长耗时成对基准；对应功能测试已经通过。本轮没有渲染代码或 shader 修改，不另将以前的 GPU 结果作为新版本游戏验收。

两个仓库本轮共享类、Rust 源码、测试、导入工具和 Windows DLL 已核对；TileManager 与网络接入保留 loader/API 差异和 Forge 现有移动限流。对涉及文件的 `git diff --check` 通过。

复现加载回归：

```powershell
.\gradlew.bat --no-daemon --console=plain `
  -I tools/prediction/loading-regression-tests.gradle `
  '-PvssTestNativeLibrary=<仓库绝对路径>/src/main/resources/META-INF/vss-natives/windows-x86_64/vss_native_core.dll' `
  loadingRegressionTest
```

Forge 在 Gradle 需要指定 Java 17 时增加 `'-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17'`，NeoForge 使用配置中的 Java 21。

生产构建使用 `build -x test`，因为已先执行独立加载回归；Forge 还完成 `reobfJar`。交付文件：

- Forge：`lib/vss-0.3.5-forge-1.20.1.jar`
- NeoForge：`lib/vss-0.3.5-neoforge-1.21.1.jar`

打包校验和保存于两个仓库的 `docs/prediction-loading-artifacts-20261006.json`，包括 JAR SHA256、大小、元数据版本、生产类的字节码版本、内嵌 Windows DLL 的 SHA256 和测试计数。日志与原始证据位于 `C:/Users/Administrator/.codex/tmp/vss-loading-fixes-20261006/`。

需要用户替换后验证的结果是实际 DeceasedCraft 启动时是否进入 `rust=true`、加载过程是否继续收敛、预生成的 `reason/limit/budget`，以及游戏内帧时间和闪烁。当前未重新采样游戏，不能给出修复后的实际 FPS、最差帧或整世界加载时间。

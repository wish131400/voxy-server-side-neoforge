# 逐步加载后的地形显示精度与 Spark 热点优化（2026-10-14）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮沿 Spark `Xf7oi32wJg` 的渲染准备热点，处理“刚进游戏流畅，加载更多区域后逐渐变精细并掉帧”的源码路径。修改同步到 NeoForge 1.21.1 与 Forge 1.20.1，版本保持 0.3.5。验证使用历史真实网格、组件 CPU 对照和生产 OpenGL 渲染路径；本轮没有取得长时间运行的客户端 FPS、逐帧分位数或 JFR，不能把组件收益解释成整合包的 FPS 增幅。

## 为什么加载完成后会重新变重

源数据精度、显示精度、地形归属和装饰归属是不同状态。原实现至少有两条路径把这些状态连在一起：

1. `PredictionDisplayGeometry` 的完整表示准入同时计算地形与装饰面。完整表示超过 131072 面，或重复保留大量模型后达不到减面/驻留门槛，就不生成粗表示。后续树木和建筑完成时，原本能简化的地形也可能失去粗表示。
2. `PreparedPass.prepareAquatic` 用整块 `completeOwnership` 强制原始显示精度。地形已经完整、只有植被或建筑仍在父子交接时，同样退回原始地形。细化区域增多意味着更多瓦片可能经过这条交接路径。

这些是源码与回放确认的条件，不是现场逐帧归因。单纯“磁盘数据无损压缩”不会减少 GPU 的四边形数，也不会消除归属切换引起的 CPU 准备工作。

另一个会随后台加载反复发生的成本是全局 mesh/GPU epoch 变化。原准备路径即使当前画面成员没有改变，也会重新检查距离档位、方向和提交范围。

## 生产修改

### 地形显示表示与原始装饰共享

- 已存在完整粗表示的网格，保留原表示的几何内容；工作线程额外提取其中的地形范围，配合源数据中植被/建筑的原始范围。装饰交接时复用已上传的粗地形，不新增对应 GPU 几何。
- 完整表示无法准入时，仅为非 FEATURE 地形尝试粗表示。所有植被、模型和建筑仍引用原始位置；不把整块装饰重复追加到每个显示档位。
- 分开记录 `completeTerrainOwnership` 与 `completeOwnership`。地形完整而装饰混合时可选择共享的粗地形；真正混合的地形归属仍用原始面，避免粗化穿过父子边界。
- 原有屏幕误差、近景保护、望远镜投影、有效 morph 过渡和高度保护继续约束显示档位。尚无安全粗表示时保守保持原始几何。
- 水和模型的三维保护范围来自完整源网格；不能为了取得代理而忽略地表附近的水、房屋和模型。
- 精确地形与精确 FEATURE 排成连续组，减少共享范围的碎片。未排序旧数据超过 128 个提取 runs 时拒绝新路径，防止命令数量失控。
- 代理新增面数沿用地形面数一半的驻留上限；显示计划只保范围、误差与选择元数据，不再次保留完整坐标数组。压缩上传与冷恢复均有回归。

### Spark 对应的渲染准备成本

- 用两条各 8192 项的 ID 失效日志记录 mesh/GPU 更新。当前画面的成员未受影响时，在组装前更新 cursor 并复用计划。日志只存 ID，不保留已淘汰网格；日志溢出或 cursor 无法验证时回到原精确检查。
- 在有预算的 `PreparedDraw` 内缓存归属查询结果，校验 owner、覆盖 revision、mesh revision、坐标和通道，避免相同状态反复查询 `WeakHashMap`。覆盖撤销、相机跨格、clear 和 manager 替换继续失效。
- 在 Minecraft 有界坐标范围内以平方距离与 `sqrt` 替代 `hypot`，并复用地形/植被选择的水平距离平方。
- `PredictionVegetationRanges.retainedHeapBytes` 改为范围创建时增量统计，查询 O(1)。未发布代理调整地址时清除原偏移的缓存，防止范围地址错误。
- `tileWithinHorizon` 先拒绝水平距离界外的瓦片，再创建 AABB 和测试视锥。此次没有实现所有上传、驻留、接缝路径共用的完整可见性缓存。

生产入口：`PredictionDisplayGeometry`、`PredictionTerrainDisplayRanges`、`PredictionVegetationRanges`、`PredictionPackedMesh`、`PredictionRenderer`、`PredictionSubmissionCoverage`、`PredictionSubmissionChanges`、`PredictionGpuTile`、`PredictionAquaticLod`。

## 实测收益与边界

### CPU 组件对照

使用修复前 `PreparedPass` 实现作为 baseline，比较同一输入与当前路径。NeoForge 单独运行的中位数如下；完整 CPU 操作次数见测试和证据，下面各行不是同一帧的分阶段时间，不能相加。

| 组件场景 | 修改前 | 修改后 |
| --- | ---: | ---: |
| 1024 绘制条目，后台更新不相关成员，单次 prepare | 0.1893 ms | 0.0162 ms |
| 上述单次操作的线程分配计数 | 200 bytes | 32 bytes |
| 已建植被范围的内存估算，1000 次查询 | 0.3502 ms | 0.0036 ms |
| 已稳定的归属版本查询，10000 次 | 0.0708 ms | 0.0347 ms |
| 10 轮 × 4096 项世界距离计算 | 0.1813 ms | 0.0307 ms |

Forge 另一轮构建中的相应对照同方向：0.3513→0.0309 ms、0.3684→0.0047 ms、0.0903→0.0409 ms、0.2368→0.1120 ms。该轮与其他构建并行，绝对值不能直接与 NeoForge 独立结果比较。最终独立项目缓存构建又得到 0.2075→0.0178 ms、0.3456→0.0046 ms、0.2491→0.2015 ms、0.2056→0.1013 ms；这一轮 CPU 也与另一平台构建并行，只用于验证方向。

### 2019 个真实历史网格回放

历史采集的源几何先经过当前空间排序和合并，再比较修复前/后的显示选择。固定测试像素比例为 1475、近景距离 512、地形误差 2；4096 与 8192 是回放统一输入距离，不是客户端当时所有网格的实际距离分布。

- 原始候选面：21,344,411，其中地形 16,772,833、FEATURE 4,571,578。
- 1320 个网格可用共享范围，其中 1252 个复用原有粗表示，68 个原来不准入、本轮新获得地形粗表示。
- 其余网格保持原准入与安全回退。

| 回放场景 | 修改前候选面 | 修改后候选面 | 下降 |
| --- | ---: | ---: | ---: |
| 完整归属，统一距离 4096 | 14,577,206 | 14,396,427 | 1.24% |
| 完整归属，统一距离 8192 | 10,806,177 | 10,546,082 | 2.41% |
| 假设所有瓦片地形完整、装饰在交接，统一距离 4096 | 21,344,411 | 17,367,528 | 18.63% |
| 同一假设，统一距离 8192 | 21,344,411 | 15,456,277 | 27.59% |

后两行是交接条件的组件上界场景。没有测量当前世界有多少瓦片满足该条件，不能宣称全场减面 18.63% 或 27.59%。真正混合的地形仍需原始表示。

raw canonical 存储由 1,453,159,968 增至 1,460,760,384 bytes，增加 7,600,416 bytes（约 7.25 MiB，0.52%）。这是回放几何数组口径，未包含全部选择元数据，也不是压缩堆占用或实机显存。已有粗表示的共享不新增对应几何，新增占用主要来自 68 个新准入代理。

工作线程 build 顺序是 old/new，且没有严格独立 warmup；两轮耗时不同，本轮不据此宣称构建加速。

### 生产 GPU 路径对照

6 个真实历史网格，每个重复 32 份，256×256 目标，使用生产着色器、上传、MDI 与 OpenGL 异步时间戳。场景专门验证地形完整、装饰交接时的共享路径，结果不是游戏 FPS。

| 历史 mesh ID | 修改前→后四边形 | 修改前→后 GPU 时间 |
| --- | ---: | ---: |
| 13 | 921152→569376 | 0.575488→0.327680 ms |
| 1074 | 1877536→1150048 | 1.071104→0.646144 ms |
| 2241 | 777024→113568 | 0.534528→0.087040 ms |
| 1567 | 5271968→2766848 | 2.458624→1.270784 ms |
| 1874 | 630848→343488 | 0.377856→0.178176 ms |
| 2070 | 750208→434400 | 0.442368→0.217088 ms |

部分场景命令数由 32 增至 64/192/320，所测 GPU 净收益仍为正。测试验证不同装饰归属掩码下源 FEATURE 的颜色和深度；新选中范围的 direct 与 MDI 结果一致。粗地形本身受误差约束，不宣称它与高精度地形逐像素完全相同。

表格取单独运行的 `evidence/isolated-gpu-benefit.csv`，不使用两平台并行构建时的 GPU 绝对值。

## 回归与打包

| 平台 | CPU 执行项 | 不重复 GPU 执行项 | 失败/跳过 |
| --- | ---: | ---: | --- |
| NeoForge 1.21.1 / Java 21 | 354 | 14 | 0 / 0 |
| Forge 1.20.1 / Java 17 | 352 | 14 | 0 / 0 |

CPU 为本轮 14 项、既有主线程/显示回归 299/297 项、村庄与水面回归 41 项之和。GPU 包括本轮 1 项（内部 6 个真实网格）、既有 12 项、村庄水面 3 项；其中 2 项重复执行，只计一次。参数化方法可以有相同显示名称，统计保留各次执行。此处只描述所选回归，不等同于仓库全部测试。

回归覆盖装饰完成与超限、所有面组/植被/水生档位、混合装饰和混合地形归属、近景/望远镜/morph、无坐标重复引用、冷恢复、局部失效日志溢出、覆盖撤销、距离语义和视锥短路。既有村庄、海洋结构和水深交接修复保留。

测试环境修正：水面 resize 之后，删除的 GL texture name 可能被新目标立即复用。测试改为检查 history 自身的句柄和 available 状态；close 后仍检查纹理释放。没有修改本轮的生产水面实现。

期间发现同一仓库有另一个使用不同 buildDir 的 Gradle 构建，默认项目缓存中的旧输出清理造成 classpath 文件消失。后续两个平台使用独立项目缓存重新构建、验证，GPU 回归按平台串行执行。最终源码又经过一次同步更新，因此按最终源码补跑所选回归；打包期间校验源文件哈希保持稳定。各验证只认最终成功结果，未把 `NoClassDefFoundError` 当作通过。

产物在各仓库 `lib/progressive-detail-20261014/`：NeoForge `vss-0.3.5-neoforge-1.21.1.jar`，Forge `vss-0.3.5-forge-1.20.1.jar`。Forge 已执行 reobfJar。随包 `SHA256SUMS.txt` 与 `validation.json` 记录校验值、版本、类版本、原生库、源码哈希、所选回归和回放统计。测试采集文件不进入发布 JAR。

## 还需要实机评估的部分

本轮消除了两条显示精度回退条件，并减少无关后台更新引起的当前画面重算，但没有给整个画面设置总四边形硬预算。

- 普通驻留归属仍优先选择最细的源数据；这是数据与覆盖选择，并未改为主动丢弃已生成的高精度瓦片。
- 真正混合的地形归属、缺少安全代理、近景与建筑保护仍可能保留高面数。地形完整时共享粗表示，不能替代所有边界条件。
- 植被/建筑的源几何仍可能驻留；显示降精度与压缩、CPU 冷缓存、GPU 回收是不同措施。本轮没有解决所有长期内存增长问题。
- Spark 的水面排序与命令变更、native 提交/等待、细化反馈读回、视锥测试的完整共享仍是后续待测方向，本轮未修改这些路径。

下一次客户端验证应在相同位置、朝向、FOV、渲染设置下比较加载早期/后期，记录 source/terrain/display/selected 面数、`mixedOwnershipTiles`、`noTerrainVariantTiles`、有效 morph 数、后台无关更新时的 plan 命中、上传字节、CPU prepare 时间、GPU 时间与 p95/p99 帧时间。若已启用粗显示而最终候选面仍持续增加，应继续处理距离/屏幕误差预算与真正混合的地形边界，而不能认定这次修复已完全根治长时间掉帧。

## 重现与证据

在对应仓库运行，使用独立项目缓存。GPU 收益测量应串行，避免另一个构建或游戏抢占 GPU。

```powershell
# NeoForge / Java 21
.\gradlew.bat --offline --no-daemon --console=plain --project-cache-dir .gradle-progressive-detail-20261014 -I tools/prediction/progressive-detail-tests.gradle progressiveDetailCpuTest turnMainRegressionTest villageWaterCpuTest progressiveDetailGpuTest turnMainGpuTest villageWaterGpuTest jar

# Forge / Java 17
.\gradlew.bat --offline --no-daemon --console=plain --project-cache-dir .gradle-progressive-detail-20261014 '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' '-Dnet.minecraftforge.gradle.check.certs=false' -I tools/prediction/progressive-detail-tests.gradle progressiveDetailCpuTest turnMainRegressionTest villageWaterCpuTest progressiveDetailGpuTest turnMainGpuTest villageWaterGpuTest jar reobfJar

# 在各自仓库核验产物与所选回归
py -3 tools/prediction/progressive-detail-20261014/audit-artifacts.py
```

证据目录 `tools/prediction/progressive-detail-20261014/evidence/` 包含全量历史回放 CSV、单独 GPU 对照、单独 NeoForge CPU 指标、Forge 最终 CPU 指标及各平台最终 CPU 原始表。历史回放的 290 MB 输入只保留在本机临时目录，不复制到仓库或 JAR。基础 Spark 报告为 `docs/history/performance/spark-client-hotspots-Xf7oi32wJg-20261014.md`。

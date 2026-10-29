# prediction cache restore optimization 20261016

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

**VSS 成品网格缓存恢复优化（2026-10-16）**

已针对《prediction-vegetation-cache-read-20261016.md》确认的“缓存命中后重复预处理”完成修复，源代码和回归用例同步到 NeoForge 1.21.1 与 Forge 1.20.1。两个平台均已打包。

这次减少的是缓存恢复时的 CPU 工作与临时分配。网格上传、全新区域生成和持续绘制的 GPU 成本仍需分别测量；本轮没有替换客户端或执行新包的游戏内 FPS 对照。

**实现**

- 记录基础面是否已经执行扫描合并。生产打包产生的合并结果可直接恢复，避免再次运行 PredictionScanMerge.merge。
- 新记录保存完整四边形数据，明确区分精确源前缀、原始水面和追加显示 LOD。cacheWords() 仍只返回精确源前缀，重复读写不会叠加远景层。
- 持久化水草保留前缀、源植被分组、显示 LOD 范围和误差、植被档位、地形代理与精确建筑/装饰的共享范围。恢复时跳过空间排序、植被排序和 PredictionDisplayGeometry.build。
- 保留相邻档位共享的植被计划及地形/装饰共享关系。只保存静态 first/count 等元数据，选择缓存、当前 Voxy 覆盖、相机、morph 和上传时间均在本次会话重新建立。
- 校验材质分类和渲染算法开关。材质行重排后分类仍相同即可复用；共享材质发生分类变化或材质表满额回退时，从精确源前缀重建派生计划。未知材质继续保留精确几何。
- 成功发布旧记录恢复结果后，通过现有有界保存队列逐步写回新格式，保留资源、城市身份和 surface completion。临时沿用旧装饰的交接网格不会持久化。
- 额外保存的材质分类快照约 272 字节/网格，已计入保留内存。未引入跨瓦片的解码对象缓存。
- 单条记录仍有 16 MiB 上限；追加派生数据导致记录超限时尝试保存规范源格式。

**兼容与有效性**

| 成品记录版本 | 读取行为 |
| --- | --- |
| 8 | 原兼容路径，恢复后仍不声称地表来源已完整收集 |
| 9 / 10 | 原规范源网格，重新执行必要预处理；10 包含城市 footer |
| 11 / 12 | 已合并的规范源网格，跳过重复精确扫描合并；12 包含城市 footer |
| 13 / 14 | 完整静态显示计划；条件匹配时直接恢复；14 包含城市 footer |

网格内容身份 VERSION 保持 13，记录格式单独升级，所以既有身份正确的缓存仍可命中。无需清空缓存。旧记录首次读取仍可能进行一次转换，写回被接受后，后续恢复使用新格式。

解码继续校验身份、轴尺寸、材质、源/水面范围、接缝与地表完成标记，并新增派生范围边界、单调植被前缀、误差、共享关系、别名和材质分类校验。坏数据和截断记录会被拒绝。算法或开关不匹配时，不使用旧的显示选择计划。

本轮仍从恢复后的坐标构建 meshlet bounds、几何 ownership runs 和几何统计。当前 Voxy 归属判定继续使用实时覆盖数据。本轮优先消除了调查里占比最大的扫描合并、显示 LOD 和排序重复工作。

**离线验证**

| 平台 | CPU 回归 | GPU 回归 | 失败 / 跳过 |
| --- | ---: | ---: | --- |
| NeoForge 1.21.1 / Java 21 | 92 | 6 | 0 / 0 |
| Forge 1.20.1 / Java 17 | 93 | 6 | 0 / 0 |

用例覆盖新格式恢复、旧 8/9/10/11/12 格式、旧缓存自动升级、城市 footer、完成状态、变更身份、轴尺寸、坏范围、截断数据、材质重排与满额回退、水面偏移，以及重复保存不会追加重复显示层。

正确性测试用静态拦截确认新格式命中时不调用扫描合并、空间排序、植被排序或显示网格构建。每个测试类使用独立 JVM，内存与性能测试不受静态拦截残留影响。

GPU 回归在 NVIDIA GeForce RTX 4070 Ti SUPER 上执行。显示 LOD 用例先编码新格式，再解码并上传 GPU，检查近/远档位、直绘/MDI、完整/缺失/混合覆盖的逐像素一致性；几何压缩、植被、地表小植物与交接测试也通过。

**离线基准**

每个场景先预热 20 次，再测量 48 次完整 decode 调用。基准为构造的固定几何：普通场景含 1,024 个规则面，装饰场景额外包含 8,192 个精确模型面，均包含水面。它不是整段森林的实测加载时间。

| 平台 / 场景 | 旧格式 ms/次 | 新格式 ms/次 | 旧分配 KiB/次 | 新分配 KiB/次 |
| --- | ---: | ---: | ---: | ---: |
| NeoForge / 普通 | 3.000 | 0.721 | 815.2 | 434.0 |
| NeoForge / 8,192 装饰面 | 22.710 | 0.855 | 4,560.4 | 872.1 |
| Forge / 普通 | 3.243 | 1.007 | 815.5 | 433.5 |
| Forge / 8,192 装饰面 | 7.119 | 0.886 | 4,565.2 | 871.4 |

分配量使用 ThreadMXBean 的线程累计分配差值。两种平台的普通样例分配下降约 47%，装饰样例下降约 81%。耗时是墙钟均值，运行期间存在其他构建与任务，数值会受调度、JIT 和 GC 影响；不能据此承诺固定的游戏 FPS 或存档加载倍数。基准不包含磁盘文件读取和外层解压。

两个样例的未压缩 mesh 记录分别从 278,391 增至 281,838 字节（约 +1.24%），从 671,607 增至 696,273 字节（约 +3.67%）。实际磁盘增量还取决于压缩与瓦片内容。恢复后的 GPU 几何与原路径逐字一致，没有额外复制一套常驻 GPU 网格。

**构建与证据**

构建期间工作区有其他会话并行修改，因此最后的编译、测试和打包使用每个仓库下的固定快照 .gradle/cache-restore-snapshot-20261016。两处 Minecraft API 写法与当时已修正的工作区保持一致。快照的 source-manifest.json 记录全部 main/test 输入的 SHA256，本轮五个缓存核心源码与快照一致。

使用 tools/prediction/cache-restore-tests.gradle 隔离可选实验测试源码，并使用其正常的基线辅助源码完成验证。既有实验文件均保留。

NeoForge 构建命令（PowerShell）：

```powershell
.\gradlew.bat -I tools/prediction/cache-restore-tests.gradle '-PvssCacheRestoreSnapshot=.gradle/cache-restore-snapshot-20261016' cacheRestoreTest cacheRestoreGpuTest jar --console=plain --max-workers=2
```

Forge 使用同样任务，构建 JVM 固定为 Java 17，并完成 reobfJar：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-17' -I tools/prediction/cache-restore-tests.gradle '-PvssCacheRestoreSnapshot=.gradle/cache-restore-snapshot-20261016' cacheRestoreTest cacheRestoreGpuTest jar --console=plain --max-workers=2
```

验证文件：

- docs/prediction-cache-restore-20261016.validation.json：两仓库测试统计、基准原始行、产物 SHA256、核心源文件校验值。
- build-cache-restore-20261016/test-results/cache-restore：CPU JUnit XML。
- build-cache-restore-20261016/test-results/cache-restore-gpu：GPU JUnit XML。
- .gradle/cache-restore-snapshot-20261016/source-manifest.json：固定构建源输入清单。

产物已验证 ZIP 完整、新缓存类存在、Java 字节码版本正确、原生库存在、测试与基线类未混入：

| 平台 | 文件 | 字节 | SHA256 |
| --- | --- | ---: | --- |
| NeoForge 1.21.1 | lib/cache-restore-20261016/vss-0.3.5-neoforge-1.21.1.jar | 6172201 | 20a15ef4483ec3dd28c5ff64b3c1b94ffb668eb86969c7f3f9404620fe9bed0f |
| Forge 1.20.1 | lib/cache-restore-20261016/vss-0.3.5-forge-1.20.1.jar | 6842586 | dfce6079d8acd5a60afc7f68e7cd325a0e38ae7d9e75ddff7b787e7cfc145b16 |

本轮未提交或推送 GitHub，未启动/关闭游戏，未替换客户端 JAR，未清理缓存。新包的完整存档加载时间、实际 FPS 和长帧仍需游戏内对照。

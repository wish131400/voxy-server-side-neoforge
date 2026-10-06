# 2026-10-06 Lost Cities 缓存并发崩溃修复

## 结论与现场证据

错误报告：`C:/Users/Administrator/Desktop/错误报告-2026-10-6_14.12.49.zip`。

致命异常位于 `crash-2026-10-06_14.12.37-server.txt`，描述为 `Exception ticking world`，服务器线程抛出：

```text
java.util.ConcurrentModificationException
  at java.util.HashMap$HashIterator.nextNode
  at mcjty.lostcities.varia.TimedCache.cleanup(TimedCache.java:97)
  at mcjty.lostcities.varia.TimedCache.maybeCleanup
  at mcjty.lostcities.varia.TimedCache.get
  at mcjty.lostcities.worldgen.lost.BiomeInfo.getBiomeInfo
  at WorldStyle.getCityChanceMultiplier
  at City.getCityFactor
  at BuildingInfo.getBuildingInfo
  at LostCitiesImp$LostCityInformation.getChunkInfo
  at mcjty.incontrol.compat.LostCitySupport.isCity
  ...
  at NaturalSpawner
  at ServerLevel tick
```

现场版本为 Minecraft 1.20.1、Forge 47.4.0、Lost Cities 1.20-7.4.11、InControl 1.20-9.4.5。旧版 `TimedCache` 使用普通 `HashMap`，读写、TTL 清理与清空没有共同的同步保护；`get` 本身也可能触发清理，不能作为无副作用的读取。

VSS 后台城市规划查询也会访问 Lost Cities 缓存，增加同时访问的机会。报告中的致命路径由 InControl 刷怪检查进入，因此仅在 VSS 内部加一个私有锁不能保护所有调用者。单份崩溃栈不能锁定究竟是哪条线程完成了冲突写入，本次同时保护 VSS 的规划读取与旧版缓存本身。

日志中的 ModernFix 配置观察线程也曾出现并发修改异常，它属于启动阶段的独立警告；本次修复针对造成世界 tick 停止的 Lost Cities 缓存异常。

## 同步修改

两个仓库都包含以下修改：

- `LostCityPlannerAccess.withLegacyMonitor`：旧版规划器的区块、邻居、城市样式、道路与基础设施查询使用 `BuildingInfo.class` 监视器，与旧版同步规划入口共用锁。新版带独立维度锁的规划器保留分阶段访问方式，避免持锁等待其他构建任务。
- `LostCityPlanningReader`：先在正确的规划锁下读取城市和模板状态，再使用已有快照构建模型。VSS 自身的模板、公共地表与基础设施缓存继续使用各自的短临界区。
- `TimedCacheConcurrencyMixin` 与 `LostCitiesMixinPlugin`：仅识别具有旧版 `Map`、`nextCleanupAt` 和完整旧方法签名的缓存，将 `clear`、`get`、`put`、`maybeCleanup` 与 `cleanup` 设为实例同步方法。
- 重写旧版 `computeIfAbsent` 为受保护的读取、锁外工厂回调及受保护的写入。缓存锁不会跨越城市规划回调，保留空值不缓存、异常向调用方传播与 TTL 行为。多个线程同时未命中时仍可计算多个候选值，不把长时间规划串行化在缓存锁上。
- 新增可选 `vss.lostcities.mixins.json`，并在两端 Manifest 与资源白名单中注册；Forge 的开发运行 Mixin 配置也一并注册。没有安装 Lost Cities，或安装新版并发缓存时，不应用旧版改写。

版本继续为 `0.3.5-forge-1.20.1` 与 `0.3.5-neoforge-1.21.1`，包内协议分别保持 49、50。此前的预测 GPU 优化包含在本次成品中。

## 回归与验证范围

专项任务使用实际 Lost Cities 发布 JAR，原始旧缓存通过受控清理/写入竞态确定性复现 CME，再加载改写后的字节码验证相同行为。

| 平台 | 测试总数 | 通过 | 条件跳过 | 失败 | 错误 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Forge 1.20.1 | 58 | 57 | 1 | 0 | 0 |
| NeoForge 1.21.1 | 58 | 57 | 1 | 0 | 0 |

跳过项为 `LostCityHintServiceApiTest.queriesActualLostCitiesApiWithBoundedNegativeRegion`，需要额外的运行时 Lost Cities 实例；版本矩阵、旧缓存竞态和锁顺序测试均已执行。

矩阵覆盖：

| Lost Cities 发布版本 | 缓存处理与验证 |
| --- | --- |
| 1.20-7.3.6、1.21-8.2.6 | 没有该缓存类，跳过缓存改写，规划兼容矩阵仍执行 |
| 1.20-7.4.11、1.20-7.4.13、1.21-8.3.8 | 原缓存复现 CME；改写后的清理、读写、清空、TTL、锁外工厂与并发测试通过 |
| 1.20-7.5.7、1.21-8.4.6 | 新版并发缓存不改写，验证字节码保持原样 |

另已验证旧版规划锁等待、异常后释放锁、新版分阶段操作不会错误持有维度锁，以及道路、公园、建筑模板和基础设施读取。

新增资源回归检查直接从构建类路径读取 Mixin 配置并与源文件比对。本轮检查发现并补齐了两端资源白名单的遗漏，最终 JAR 再次逐项核对配置和 Manifest。

仓库全量 `test` 曾被已有的其他测试源码/API 编译错误阻断，例如 `PredictionCityGeometryTest`、`PredictionRegionStorageTest`；本次使用独立专项源集，并排除全量测试源码的编译。生产源码和最终打包均成功。本轮没有重新运行此前已通过的 GPU 专项，也没有启动整合包进行实机验收，不能将专项结果表述为完整整合包已通过。

## 复现命令

在对应仓库执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-21'
./gradlew.bat -I tools/prediction/city-cache-tests.gradle cityCacheTest '-PvssLostCitiesMatrix=C:/Users/Administrator/.codex/tmp/vss-city-compat-20261006' -x compileTestJava --console=plain

# Forge
./gradlew.bat jar reobfJar --console=plain

# NeoForge
./gradlew.bat jar --console=plain
```

最终日志：`C:/Users/Administrator/.codex/tmp/vss-city-cache-crash-20261006/forge-final.log` 与 `neoforge-final.log`。JUnit XML 位于各仓库 `build/test-results/city-cache`，HTML 位于 `build/reports/tests/city-cache`。实际发布包来源和校验值见 `docs/lostcities-release-matrix-20261006.json`。

## 最终产物

以下大小和 SHA256 记录本轮缓存修复完成时的成品快照。同名 `lib` JAR 后续叠加了植被三档与望远镜加载开关；最新产物与校验值见 [植被与望远镜设置记录](prediction-vegetation-settings-20261006.md)。本节保留当时的验收数据。

| 平台 | 文件 | 大小（字节） | 协议 | class 版本 |
| --- | --- | ---: | ---: | ---: |
| Forge 1.20.1 | `lib/vss-0.3.5-forge-1.20.1.jar` | 6316472 | 49 | 61 / Java 17 |
| NeoForge 1.21.1 | `lib/vss-0.3.5-neoforge-1.21.1.jar` | 5642388 | 50 | 65 / Java 21 |

SHA256：

```text
Forge:    6CF8C3BAEB01E09B647B8E97F80254AB6DDDDF8251976BF3933209AD041D0D90
NeoForge: 505CB939C7FDDABA3ACCC6BE85363780E6C9E784BC48CA95BCA826CD734369C9
```

全部 ZIP 条目均可读取且长度正确，没有重复条目或测试类。两包均包含新增 Mixin 配置、插件和目标 Mixin，Manifest 注册完整，五个平台的原生库和处理后的资源逐项一致。NeoForge 的 766 个类与本次编译输出逐项一致；Forge 的 777 个类已打包并核对重混淆的 Minecraft 调用。此前的收益选择、显示 LOD、空间排序与遮挡类均存在。

产物检查脚本和结果：`C:/Users/Administrator/.codex/tmp/vss-city-cache-crash-20261006/verify-packages.py`、`packaged-artifacts.json`。

修改前 GPU 优化成品保存在同一证据目录的 `before-forge`、`before-neoforge` 子目录，SHA256 分别为 `C1C9AE51D54D742200AF0897ABA22D84E7727EEE988488B5B44D60C9C321C6F2` 与 `217366DC8A56FA95B195225CFA090DBB303B5CCB901834DAB563C6D54CDB693F`。

本轮未替换游戏实例中的 JAR，也未启动或关闭游戏。DeceasedCraft_Beta 当前使用 Forge 1.20.1，应自行替换对应 Forge 成品后，进入原世界测试城市加载、移动和刷怪期间是否再次发生相同异常。无需为本修复删除世界或预测缓存。

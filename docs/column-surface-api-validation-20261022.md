# 每列高度 API 2 验证记录（2026-10-22）

本轮完成 PYF 后续请求中的 `ColumnInput.topSolidY[256]`、元数据可用性／单点读取、公开 API 版本／能力查询，以及不冻结注册的状态查询。两平台共用同一实现和回归。完整契约、接入示例及来源协调限制见 [API 2 说明](column-surface-api-20261022.md)。

## 生产变化与兼容范围

- `ColumnInput` 新增 `int[] topSolidY` record 分量，数组传入／传出复制，提供 `hasTopSolidY()` 与 `topSolidY(localX, localZ)`。旧六参数构造器保留，未知高度不伪装成已知空气。
- `ColumnFilterPipeline` 从既有脱离世界的 section 一次遍历计算段内和每列最高非空气 Y；实时、独立快照、ChunkAccess、磁盘 NBT 共用。不信任来源 Heightmaps，无 provider 时绕过，缓存命中不再扫描。
- `ColumnFilterApi.version()==2`，能力集合不可修改；版本／能力查询不加载世界或冻结注册。`ColumnFilters.isRegistrationFrozen()` 只读状态，实际注册继续原子校验。
- JAR 新增 `VSS-Column-Filter-API: 2`，保留 `VSS-Protocol: 53` 和指定构建 ID。网络、缓存文件格式和策略身份算法不变；外部规则改用新高度时必须升级自己的注册版本。
- `UPDATE`／`CLEAR`／`UNTOUCHED`、光照、局部列完整性和来源语义保持原契约。本轮不引入 Voxy 来源优先级，不默认开启裁剪。

## 验证命令与结果

两仓以 JDK 21 运行 Gradle；NeoForge 字节码目标 Java 21，Forge 目标仍为 Java 17。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules jar -PvssBuildId=column-surface-api2-20261022-p53 --no-daemon --console=plain
```

两边 `BUILD SUCCESSFUL`，Forge 已完成 `reobfJar`。本轮生成的 JUnit XML 汇总：

| 任务 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | ---: | ---: |
| architectureTest | 11 | 11 |
| moduleRegressionTest | 276 | 276 |
| columnFilterTest | 107 | 109 |
| 合计 | **394** | **396** |
| 失败／错误／跳过 | **0 / 0 / 0** | **0 / 0 / 0** |

每平台新增 22 项回归，并增强旧用例：

- `ColumnFilterSurfaceHeightTest` 新增 18 项：正负高度和传输边界、X/Z 数组索引、256 个不同高度、乱序 section、树叶／植物／流体、已知空气、独立快照、缺失／失效 NBT Heightmaps、ChunkAccess，以及高山 Y=150 邻近低地 Y=60 的高度带选择和拆空后显式更新。
- `ColumnFilterCapabilitiesTest` 新增 1 项：反射查询新能力、未知能力和 null、不支持来源优先级、集合不可变；探测后仍可注册，真正 snapshot 才冻结。
- `ColumnFilterApiCompatibilityTest` 新增 3 项，当前共 5 项：新高度数组不可变、单点越界、未知／已知空气区分、长度和范围校验；增强旧二进制夹具，独立按旧接口编译后实际链接当前 `ColumnInput` 和 `Section` 的旧构造器及访问器。
- 既有缓存测试增加高度元数据断言，继续验证内存／磁盘命中不再执行 provider 或高度扫描；无 provider、传输、协议和局部覆盖回归继续通过。

## 最终产物核验

产物在各仓 `lib/column-surface-api2-20261022/`，同时保存 `SHA256SUMS.txt`、`validation.json` 和 `api-descriptors.txt`。

| 平台 | 文件 | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `vss-0.3.5-neoforge-1.21.1.jar` | `01500f811bedd3d60fbd7173106b8cdde1a95eb6838cc656c4dff6f82e011e87` |
| Forge 1.20.1 | `vss-0.3.5-forge-1.20.1.jar` | `99186001e4c8bd5f39decca770bad7ed751785c8e0853c5031c1725e4bc9e6db` |

检查了最终 ZIP 完整性、Manifest、API 类字节码版本 65／61；`javap -s` 确认旧／新 `ColumnInput` 构造器、旧四／五参数 `Section` 构造器、数组／单点访问器及能力查询均存在。包内没有 JUnit、Mockito、测试夹具或旧调用方测试类。

两平台 12 个共享 API／生产／测试／接口文档／验证文档逐字节一致；构建脚本与平台原有内容分别保留，`git diff --check` 通过。

## 未覆盖的范围

本轮为源码行为回归和本地 JAR 打包验证，没有启动游戏、替换实例、测量 TPS／帧率／带宽，或运行真实 PYF + Voxy 联调。最高点高度带仍无法表示同一 X/Z 的多层可见表面。Tectonic 配置快照兼容和来源摄入仲裁是独立工作，不能据本记录声称已完成。产物为本地验证包，未作为 GitHub Release 发布。

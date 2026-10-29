# 列过滤高度 API 扩展与验证（2026-10-21）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

在 Forge 1.20.1 和 NeoForge 1.21.1 同步扩展现有 `ColumnInput.Section`，增加 `int maxBlockY()`。该值是 section 内最高非空气方块的绝对 Y，包含树叶、植物和流体；空段为 `Integer.MIN_VALUE`。旧四参数构造器继续存在，手动创建的非空旧输入使用该值表示高度未知，须结合 `onlyAir()` 判断。

生产修改集中于 `ColumnInput`、该 API 的包注释及既有 `ColumnFilterPipeline`。高度扫描使用管道已解码的脱离世界的 section，实时快照、ChunkAccess 和磁盘 NBT 共享入口；不访问 Heightmaps 或可变世界，不引入第二套过滤器或缓存。无 provider 时绕过解码和扫描，缓存命中复用已过滤结果。

协议仍为 **53**，模组版本仍为 0.3.5，缓存文件格式和策略身份算法沿用原实现。过滤结果仍是带 `coverage.filterKey()` 的局部更新，`completeColumn=false`。外部模组改变规则／配置时须升级注册版本；新增元数据本身不默认启用深度裁剪。

接口契约、兼容范围及最高点高度带示例见 [列过滤接口说明](../../development/column-filter-api.md)。`maxBlockY` 不表示地表高度，`UNTOUCHED` 不删除 Voxy 已有内容，示例对未知高度和全空气作显式处理，并以 long 计算截线。

## 本地验证

两个仓库分别执行（Gradle 使用 JDK 21，Forge 源码／产物仍以 Java 17 为目标）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules jar -PvssBuildId=column-height-api-20261021-p53 --no-daemon --console=plain
```

两边均 `BUILD SUCCESSFUL`，Forge 已执行 `reobfJar`。由本轮 JUnit XML 汇总：

| 任务 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | ---: | ---: |
| architectureTest | 11 | 11 |
| moduleRegressionTest | 276 | 276 |
| columnFilterTest | 85 | 87 |
| 合计 | **372** | **374** |
| 失败／错误／跳过 | **0 / 0 / 0** | **0 / 0 / 0** |

本轮每平台新增 21 项用例，并加强原缓存行为断言：

- `ColumnFilterApiCompatibilityTest`：2 项。针对旧四分量 API 独立编译调用方，再加载当前真实 API，实际调用旧构造器和旧访问方法；另验证新元数据与光照防御性复制、旧构造器未知高度语义。
- `ColumnFilterHeightTest`：18 项。覆盖正／负 section、local Y=0/15、X/Z 边角、传输 Y 极值、多方块取最高点、显式／省略空气、流体／树叶／植物、独立快照、ChunkAccess、失效 Heightmaps 的磁盘 NBT，以及外部过滤器按截线选择后清单与局部覆盖语义。
- `ColumnFilterNoProviderTest`：1 项。无注册过滤器时即使没有解析上下文也原样返回源数据，确认不进入解码或高度扫描。
- `ColumnFilterCacheTest`：原 4 项继续通过，增加回调高度值断言和内存／磁盘命中不重复调用 provider 的断言。

两仓的 8 个共享源码／测试／API 文档逐字节一致，更新日志分别保留平台原有差异。

## 构建产物

本轮 JAR 单独保存在两仓的 `lib/column-height-api-20261021/`，清单标记均为 `VSS-Build-Id: column-height-api-20261021-p53`、`VSS-Protocol: 53`。

| 平台 | JAR | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `vss-0.3.5-neoforge-1.21.1.jar` | `d5e865166c2c417712adfb7dfd30619e008689b6e8ed4a260f3ad38399ad2c9a` |
| Forge 1.20.1 | `vss-0.3.5-forge-1.20.1.jar` | `ec078d9bf62b43e1e21a550d42e5e23b8aab72961b1bb11b8d4a169274e0a5bd` |

对最终打包 JAR 执行 `javap -s`，确认保留 `(IZ[B[B)V` 构造器，同时存在新增 `(IZI[B[B)V` 构造器及 `maxBlockY()I`。字节码版本分别为 65／61；检查包内未包含 JUnit、Mockito、测试工具类或旧接口编译夹具。

本轮是服务端元数据接口的 CPU 回归与打包验证，没有启动游戏或替换实例 JAR，没有测量帧率、传输节省或扫描开销，也未进行真实 PYF／Voxy 模组组合联调。

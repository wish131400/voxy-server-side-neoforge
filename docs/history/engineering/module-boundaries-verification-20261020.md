# 2026-10-20 模块边界重构与验证

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮在 Forge 1.20.1 和 NeoForge 1.21.1 同步进行跨模块重构，覆盖生产包的职责说明并建立可执行的开发检查。原有工作区中的其他未提交功能继续保留，因此本地 JAR 包含整个当前工作区，不能理解为只含本轮重构的最小补丁。

## 交付范围

- 服务端把已完成列的校验、分片与发送准入提取到 ColumnDeliveryService，把既有玩家注册表查询提取到 PlayerSessionLookup。九个业务服务通过构造参数取得 lifecycle、delivery、session 等共享依赖。VSSServerNetworking 负责装配与平台事件，保留公开门面供已登记的旧适配器使用。
- 客户端以 ClientLodSession 暴露唯一网络会话，ClientLodSessionAdapter 无状态转发。预测与兼容层不能依赖 networking.client 实现包。稳定预测缓存身份也走契约，未知身份返回 null；连接身份、握手和覆盖均没有新建状态副本。
- StrictLodFrontier 及其纯算法测试迁到 common，调用方提供数据和渲染就绪谓词；原 networking.client 实现已经移除。
- JsonConfigStore 接受目录，集中 JSON、help 与校验回写；JsonConfig 保留模型和加载器适配。日志底层的配置开关改由启动入口注入，消除 common 对 config 的反向依赖。
- 每个生产包都有职责、依赖、线程、生命周期四项说明；关键服务补充类级契约。开发约定、模块说明、PR 模板和 GitHub Actions 检查同步写入两仓。NeoForge 构建属性移除本机 JDK 绝对路径，改用 JAVA_HOME。

## 实际执行结果

| 平台 | 已注释生产包 | 架构检查 | 固定范围 CPU 回归 | Voxy / 交接回归 | 迁移调用方回归 | JAR |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| NeoForge | 34 | 10 | 206 | 54 | 39 | 成功 |
| Forge | 37 | 10 | 205 | 54 | 39 | 成功 |

表中四组测试均为零失败、零错误、零跳过。不同集合存在交集，不能把各列相加当作独立用例总数。

固定范围回归包含状态/运行时、列交付准入、配置保存与坏文件保留、消费者异常隔离、断线默认行为、配置替换和会话重连、缓存身份协商、完整方环以及 Voxy 节点撤销等。架构检查导入实际生产字节码，并用正反 fixture 验证依赖识别，不能用空扫描冒充通过。

迁移调用方另运行 LodRequestManagerStrictOrderTest（27 项）和 PredictionCacheStorageTest（12 项），验证方环算法迁包后的请求顺序，以及缓存身份通过会话接口查询后的行为。该列是筛选执行的普通 test 结果，不代表完整普通 test 已通过。

Voxy 兼容回归提供了真实新版与旧版 JAR。新版使用 `voxy超远视野-0.4.11-1.21.1-neoforge-alpha.jar`；NeoForge 旧版输入为 `neo-voxy-0.3.1.jar`，Forge 旧版输入为 `voxy-0.2.14-alpha-51ea1a841fff1717b261d2fbd3b3f6dafd470663.jar`。其中真实字节码/Mixin 用例不启动这些模组；新版 NeoForge 包在 Forge 测试里也是字节码 fixture，不能据此宣称支持跨加载器直接安装。

## 命令与记录

Gradle 使用 JDK 21，Forge 编译目标仍为 Java 17。两仓分别执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat verifyModules --no-daemon
.\gradlew.bat -I tools/compat/ingest-retained-tests.gradle ingestRetainedTest `
    "-PvssVoxyJar=C:\path\to\current-voxy.jar" `
    "-PvssLegacyVoxyJar=C:\path\to\legacy-voxy.jar" --no-daemon
.\gradlew.bat test --tests '*LodRequestManagerStrictOrderTest' `
    --tests '*PredictionCacheStorageTest' --no-daemon
.\gradlew.bat -I tmp/module-boundaries-20261020/package-local.gradle jar --no-daemon
```

本轮本地打包 init script 只重定向 jar 输出目录。Forge 的 reobfJar 已成功完成。历史兼容回归 init script 会改变输出目录，因此只用于测试。

本地日志和结构化统计保存在各仓 `tmp/module-boundaries-20261020/`：`verify-modules-complete.log`、`ingest-retained-complete.log`、`migration-consumers.log`、`package-complete.log`、`verification-results.json`。初次缺少 Voxy 路径的失败已通过提供真实输入重跑验证；表中只记录最终完整运行。

## 本地构建产物

| 平台 | 路径 | SHA256 |
| --- | --- | --- |
| NeoForge | `lib/module-boundaries-20261020/vss-0.3.5-neoforge-1.21.1.jar` | `d19fb0d3dedeb7c80130dae77dab1b3858f3cf0e92ea120221ceb92e536c9b0b` |
| Forge | `lib/module-boundaries-20261020/vss-0.3.5-forge-1.20.1.jar` | `e1b29af52d6a2a018fa6bd18da1308333eb45ebeced33f753e9c9dad7e16923e` |

已检查平台 metadata、主类版本（NeoForge 65 / Java 21，Forge 61 / Java 17）及新增边界类。Forge 的 refmap、JarJar 元数据与 LZ4 内嵌包存在。主 JAR 没有测试类、JUnit、Mockito、ArchUnit 或历史实验实现，也不再包含旧 networking.client.StrictLodFrontier。

## 实际边界与未验证范围

这次按 Java package 建立模块边界，没有转成多个 Gradle 子项目。client.prediction 仍有大量 package-private 协作类；PredictionTileManager、PredictionRenderer、LodRequestManager 的内部职责继续按[架构说明](../../development/architecture.md)中登记的边界拆分，不能宣称所有大型类已经完成分解。

仅四个已登记的命令/广播适配器仍可调用服务端门面；新增业务禁止扩大例外。运行时反射和字符串类名不受一般字节码依赖检查完整覆盖。

未执行完整普通 test/check、GPU/真实游戏/专用服务器启动或 FPS 对照。六个依赖历史 baseline 或已退休接口的测试源仍从日常编译中精确排除，登记见架构说明；它们不是通过或跳过的测试。新 GitHub Actions 文件已在本地配置，未在 GitHub 实际运行。没有提交、推送或替换游戏实例 JAR。

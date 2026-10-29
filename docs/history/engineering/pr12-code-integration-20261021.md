# PR #12 合并与累计代码整理（2026-10-21）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮将两个平台自 10 月 9 日起积累的开发改动整理为分组提交，补齐 GitHub 正式合并、独立提交编译、当前行为回归和本地打包核验。运行时修改按依赖关系集成，不将历史调查建议或已撤回实验写成新实现。

## PR 与平台同步

[NeoForge PR #12](https://github.com/wish131400/voxy-server-side-neoforge/pull/12) 已正式合并，GitHub 状态为 `MERGED`。

- 原作者：Campione01；原提交：`36b2087906dc43b9452e3e487082dbeac8b7c192`。
- GitHub 合并提交：`1dc2880cfef7e0bf854f649a576f370a9331e528`。
- 合并时间：2026-10-21 07:58:58（香港时间）。
- Forge 对应移植：`41fd9e2`，保留原 PR 来源和共同作者署名。

该 PR 将磁盘网格记录的 16 MiB 限制与运行时成品网格的 64 MiB 限制分离，将构建内存预约调为 192 MiB，避免复杂网格错误套用磁盘记录上限。[10 月 18 日报告](pr12-issue13-ingest-retained-fix-20261018.md) 记录的是当时仅在本地集成的状态；本报告记录后续正式合并，不改写历史记录。

## 提交分组

| 内容 | NeoForge | Forge |
| --- | --- | --- |
| PR #12 正式合并／对应移植 | `1dc2880` | `41fd9e2` |
| 本机构建缓存、采样和源快照忽略规则 | `7cba014` | `fc0e97e` |
| 公共数据、配置、整列策略与会话接口契约 | `94ba246` | `8569cf6` |
| Voxy 实际摄取任务追踪与流体队列兼容 | `ae38978` | `67929e8` |
| 常驻场景、版本化规划及粗细交接等运行时消费者 | `8858885` | `f408f7a` |
| 开发约定、CI 与专用回归工具 | `f29654c` | `9d0e1c3` |

API 契约与 Voxy 兼容两个提交分别从暂存快照导出主源码，在隔离输出目录编译成功。后续预测、网络、服务端装配及对应测试有跨模块依赖，按一个可编译的运行时集成提交保留，避免形成调用方尚未迁移的中间提交。累计玩家行为变化见 [更新日志](../../../CHANGELOG.md)。

运行时集成包括稳定 GPU 场景与提交计划、统一后台规划通道、带版本的粗细网格与边界交接、粗格内归属裁切、同源植被体素层级、驻留和缓存生命周期、状态效果雾及水面处理，以及地形／模板能力和整列过滤接口的实际接入。它汇集此前开发，不表示这些功能均在本轮重新开发。

## 当前回归结果

以下数量来自各仓 `build/test-results/` 的 JUnit XML。各组可能包含相同用例，不能相加为独立测试总数。

| 验证入口 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | ---: | ---: |
| `verifyModules` 合计 | 351 通过，0 失败，0 跳过 | 353 通过，0 失败，0 跳过 |
| 其中 `architectureTest` | 11 | 11 |
| 其中 `moduleRegressionTest` | 276 | 276 |
| 其中 `columnFilterTest` | 64 | 66 |
| `planningConsumerTest` | 32 通过，0 失败，0 跳过 | 32 通过，0 失败，0 跳过 |
| `coarseHandoffTest` | 120 通过，0 失败，0 跳过 | 119 通过，0 失败，0 跳过 |
| `ingestRetainedTest` | 54 通过，0 失败，0 跳过 | 54 通过，0 失败，0 跳过 |
| `coarseHandoffGpuTest` | 14 通过，0 失败，0 跳过 | 14 通过，0 失败，0 跳过 |
| `jar` | 成功 | 成功，包含 `reobfJar` |

GPU 回归在 RTX 4070 Ti SUPER 的隐藏 OpenGL 上下文依次执行，覆盖生产批次、粗细交接、边界事务、格内裁切、水面、植被深度与体素层级。它们检验固定输入下的颜色、深度、上传与资源行为，不是实际存档帧率测试。

Voxy 兼容用例使用用户提供的 `0.4.11-1.21.1-neoforge-alpha` 分支 JAR 与旧 `0.2.11-alpha` JAR，实际运行 Sponge Mixin 应用、ASM 校验及原始摄取队列路径。没有取得问题报告中的精确 0.4.14 二进制，不把相邻版本测试当成对该文件的验证。输入 SHA256 见 [结构化验证记录](../../pr12-code-integration-20261021.validation.json)。

独立效果雾与双遍光栅包围盒工具已在两平台通过 `compileEffectFogJava compileRasterBoxesJava`。这次只核对其当前源码可编译，没有重新执行完整森林实验，也没有据此新增性能结论。

## 整理中修正的问题

1. 日常测试编译原先依赖实验目录的 `AlgorithmSupport`。现只保留 `src/test/java` 中的共享实现，专用脚本复用同一位置；重复历史 bootstrap 移出活动源码。
2. 内存生命周期用例原先只等待 pending 清零，可能早于工作线程释放预算租约。改为同时等待 `pendingCount == 0` 和 `activeBuildCount == 0`，保留原有全部轮数、连续稳定窗口、覆盖与释放断言。
3. 独立双遍光栅实验仍调用已移除的归属距离 setter。删除该过期调用，保持无真实覆盖的测试输入，并完成两平台独立编译。
4. Forge 本机旧编译输出残留七个已删除的顶层类。清理主源码输出后重新打包并重混淆，再用干净的主类和测试类重跑当前回归；最终 JAR 不再包含这些旧类型。
5. 两平台 README 的当前协议号与源码统一为 53。模组版本暂保持 0.3.5；客户端和服务端须使用同一次对应平台构建。

源码、专用回归夹具和必要历史几何输入纳入版本管理。本机 Gradle 缓存、JFR、原始捕获和复制的生产源快照留在本机并忽略；不将这些输入误作主 JAR 内容。原始 `upstream-pr12.patch` 保留字节不变，空白检查排除该补丁的合法 diff 上下文；其余新增源码和文档通过 `git diff --check`。

## 本地构建产物

构建标记为 `pr12-organized-20261021-p53`，两平台 Manifest 均声明 `VSS-Protocol: 53`。

| 平台 | 保存位置 | SHA256 |
| --- | --- | --- |
| NeoForge | `lib/pr12-organized-20261021/vss-0.3.5-neoforge-1.21.1.jar` | `a71cf26c2f0bab18f9685f835ff4c7cbdf81bd0fa58ebe3d930875970863a0e4` |
| Forge | `lib/pr12-organized-20261021/vss-0.3.5-forge-1.20.1.jar` | `859c03c8cf21cc41c618ef12a015cda5081c78a36016a7b84b2b9ce9c9c986c2` |

包检查包含关键运行时和兼容类、Mixin 插件与配置、两种流体队列字段名、原生资源与源码资源一致性、Java 目标版本，以及旧类和测试／实验类型未混入主 JAR。NeoForge 类版本为 65（Java 21），Forge 为 61（Java 17）。保存目录同时提供 `BUILD-INFO.json` 和 `SHA256SUMS.txt`。

## 复核命令与范围

日常门禁与普通打包：

```powershell
.\gradlew.bat verifyModules planningConsumerTest --no-daemon
.\gradlew.bat jar --no-daemon '-PvssBuildId=pr12-organized-20261021-p53'
```

删除／迁移类型后的主源码清理与打包：

```powershell
.\gradlew.bat cleanCompileJava cleanJar jar --no-daemon '-PvssBuildId=pr12-organized-20261021-p53'
```

粗细交接 CPU 与 GPU 回归（同一机器不要同时启动两个平台的 GPU 进程）：

```powershell
.\gradlew.bat -I tools/prediction/coarse-handoff-tests.gradle `
    coarseHandoffTest coarseHandoffGpuTest '-PcoarseHandoffBuildDir=build' --no-daemon
```

摄取兼容回归需要两份外部 JAR：

```powershell
.\gradlew.bat -I tools/compat/ingest-retained-tests.gradle ingestRetainedTest `
    '-PvssVoxyJar=C:\path\to\current-voxy.jar' `
    '-PvssLegacyVoxyJar=C:\path\to\legacy-voxy.jar' --no-daemon
```

专用回归脚本可能修改 JAR 输出位置，发行打包须另用普通 `jar` 命令。CI 自动运行模块与规划调用方 CPU 回归及打包，保存报告和产物；GPU 和外部模组用例仍需对应输入。本轮没有执行完整普通 `test`、专服启动或真实存档复测，也没有重新采样 FPS。详细流程见 [开发约定](../../../CONTRIBUTING.md) 与 [模块架构](../../development/architecture.md)。

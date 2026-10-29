# PR #12 合入与 Issue #13 导入完成追踪修复

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

日期：2026-10-18。修改同步到 NeoForge 1.21.1 与 Forge 1.20.1 两个本地仓库；未提交、推送或远程合并 PR，未替换游戏实例文件。

## 上游范围

- PR #12：[Separate retained mesh admission from disk codec limits](https://github.com/wish131400/voxy-server-side-neoforge/pull/12)，作者 Campione01，head `36b2087906dc43b9452e3e487082dbeac8b7c192`。
- Issue #13：[Strict ingest completion gaps with the Voxy 0.4.14 NeoForge fork](https://github.com/wish131400/voxy-server-side-neoforge/issues/13)。

PR #12 的原始差异保存在 NeoForge 仓库 `tools/compat/ingest-retained-20261018/upstream-pr12.patch`。两个仓库均通过反向 `git apply --reverse --check`，确认该 PR 的各项源码和测试变更已经存在。

## PR #12：网格运行时限制与磁盘限制分离

- 磁盘 mesh codec 记录上限保持 16 MiB，不修改缓存格式。
- 分段构建的运行时网格保留上限独立为 64 MiB。
- 构建工作空间预约从 128 MiB 调整到 192 MiB，既有入场与回收逻辑使用同一常量。
- 合并网格在分配数组之前检查最终保留内存，包括紧凑法线与平滑法线混合后引起的整条数据流展开。
- 预计大小采用 checked-long arithmetic，非法尺寸或溢出按超过限制处理。

192 MiB 是调度预约量，不是所有复杂场景的实际峰值内存保证；剩余堆内存较少时，预约增大会减少同时允许的构建数。64 MiB 仍为有限上限。没有提升 GPU／上传暂存上限。

## Issue #13：按实际 Voxy 任务一次性结算

原来的登记只关联 `LevelChunkSection`，并假设 raw ingest 返回 true 必定存在待执行任务。实际 fork 达到 40000 个队列节点时，可以在没有 world reference、pending task 或 queue node 的情况下直接返回 true。原登记因此永远等不到 worker 完成回调。

新版 fork 从队列取出的是 `IngestNode`，随后通过 `pending.remove` 获取真正的 `IngestSection`。同一坐标发生替换时，节点携带的 section 身份不一定对应实际执行任务。

修复集中在 `VoxyIngestTracker`、`StrictVoxyIngestPlugin`、`StrictVoxyIngestMixin` 与 `VoxyCompat`：

1. 每次 VSS raw ingest 建立独立 ticket；嵌套调用通过 ThreadLocal scope 恢复父调用。
2. 在真实 task 发布前绑定 ticket，允许 worker 在 raw 调用返回之前完成。
3. raw 返回 false、抛异常，或返回 true 却没有发布任务时，在 finally 内结束自己的 ticket。
4. pending-map 队列依据 `pending.remove` 返回的实际 task 绑定 worker；直接队列的旧版本依据取出的 task 绑定。
5. `pending.put` 返回的被替换任务、入队过程中被淘汰的任务各自结束一次，重复回调不会扣减另一个提交。
6. 保留正常 RETURN 回调；在原 worker 的全部 native handlers/finally 外添加异常清理，清除 worker scope 并重新抛出原异常。清理失败作为 suppressed exception 附加到原异常。
7. 世界重置递增 generation，迟到的旧 ticket 不能消费新世界登记。
8. 完成追踪只在队列结构验证并成功注入后启用。未知布局不登记无法结算的任务，记录警告并回到 mesh／GPU readiness 判定；optional-port 的 `require = 0` 保留。

旧的 section-only 计数、列映射和基于出队节点反射 section 的完成路径已移除。保留 Voxy 原始返回值、队列调度以及 acquire/release reference 指令与顺序。

发布前的 `DUP / DUP / GETFIELD` 注入经栈分析确认合法：它保留 native 调用的 receiver、可选 key 和 task，只增加供 `submitted(task, section)` 消费的两个栈值。未引入临时 local，也不需要重新计算原方法的 stack-map frames。

## 流体模型队列

保留并核验既有的新版 `blockBakeQueue` 优先、旧版 `bakeQueue` 回退。没有对所有 Voxy 变体做无条件字段重命名。测试直接执行生产 getter，确认两种字段名和新版优先级；真实 Sponge 也成功将流体回调应用到本次使用的 ModelFactory。

## 验证

两个仓库各通过 54 项 focused JUnit 测试，0 失败、0 跳过。测试入口为 `tools/compat/ingest-retained-tests.gradle`，结果位于各仓库 `build/test-results/ingest-retained/`。

覆盖：

- PR #12 的 16／64 MiB 边界、法线展开、合并精度、内存预约与释放。
- false／异常／true-without-job raw 路径、同 section 多次提交、嵌套 raw、即时 worker 完成、世界 reset。
- 实际 fork `enqueueJob` 的原始 40000 节点上限和任务替换路径；fixture 的队列淘汰、空队列和异常 worker。
- 原生 queue/reference 调用与字段访问保持原顺序。
- 对两个 Voxy 版本执行真实 Sponge Mixin 应用，确认 RETURN 回调保留、wrapper 与 readiness marker 存在。
- 对变换后方法执行 ASM `Analyzer<BasicValue>`／`BasicVerifier` 栈验证；执行变换后 fixture，触发 JVM 验证。
- 流体 getter、真实 ModelFactory 的 Sponge 注入与栈验证。
- 既有 Voxy ownership／GPU coverage 交接回归。

生产构建：NeoForge `compileJava jar` 成功；Forge `compileJava jar reobfJar` 成功。Gradle 由 JDK 21 启动，生产目标分别为 Java 21 与 Java 17。成品与校验记录位于各仓库 `lib/ingest-retained-fix-20261018/`。

## 外部二进制与验证边界

| 输入 | SHA-256 |
| --- | --- |
| 用户提供的 Voxy 0.4.11 NeoForge fork | `13555A2EE7D03BC00C5A90BDB7B4097EA1BFF34CC3DF5FA8B7F3C005739C9235` |
| 旧版 Voxy 0.2.11 | 见同目录 `BUILD-INFO.json` |

本次可用的 0.4.11 JAR 具有 Issue #13 描述的 pending-map／IngestNode 队列与 true-without-job 节点上限行为，已用原始入队字节码验证。Issue 作者报告的 0.4.14 JAR（SHA-256 `EA118067CFEBD9B479CA267ABB5DCD9F67BAAD1AE1389FF678BC264589A0BAA5`）未在本次工作区取得，不能声称已验证该精确二进制。

本次未启动完整游戏或进行世界内画面测试。真实 Sponge 注入、原始入队代码执行和 ASM／JVM 验证不能替代完整客户端的进档、维度切换与视觉验证；本修复也不宣称解决 Issue 中另外提到的所有水面／覆盖画面问题或历史 Voxy 原生引用错误。

## 复现命令

NeoForge（在该仓库执行）：

```powershell
.\gradlew.bat -I tools/compat/ingest-retained-tests.gradle ingestRetainedTest compileJava jar --no-daemon `
  "-PvssVoxyJar=D:\缓存\qq\接受\voxy超远视野-0.4.11-1.21.1-neoforge-alpha.jar" `
  "-PvssLegacyVoxyJar=C:\Users\Administrator\Desktop\voxyserverside-neoforge-1.21.1\tmp\external-mods\voxy-0.2.11-alpha.jar"
```

Forge（在该仓库执行，使用 JDK 21 启动 Gradle）：

```powershell
& 'C:\Program Files\Java\jdk-21\bin\java.exe' `
  '-Dorg.gradle.appname=gradlew' '-Dorg.gradle.java.home=C:\Program Files\Java\jdk-21' `
  -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  -I tools/compat/ingest-retained-tests.gradle ingestRetainedTest compileJava jar --no-daemon `
  "-PvssVoxyJar=D:\缓存\qq\接受\voxy超远视野-0.4.11-1.21.1-neoforge-alpha.jar" `
  "-PvssLegacyVoxyJar=C:\Users\Administrator\Desktop\voxyserverside-neoforge-1.21.1\tmp\external-mods\voxy-0.2.11-alpha.jar"
```

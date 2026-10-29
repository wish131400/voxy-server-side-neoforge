# 预测与本地生成仅按 CPU 负载减载（2026-10-10）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

## 问题与行为

玩家使用帧生成并锁定基础帧率为 30 FPS。原来的共享预算会在实际渲染帧率低于 30 FPS 时进入 `pressure=2`，暂停 VSS 发出的本地真实区块生成请求；恢复还要求达到 50 FPS。锁帧使这个恢复条件无法满足，即使 CPU 已经空闲也会一直保持零配额。

现场 `debug (1).log` 的最后一组记录是 `generationEnabled=true`、预测关闭、`generationLimit=0`、`generationInFlight=0`、`pressure=2`、`fps=30.0`、`cpu=0.011`。缓存命中为零，磁盘未命中累计 18,608 次；服务端 VSS 生成启动和完成计数都为零。

这次修改同步到 NeoForge 1.21.1 和 Forge 1.20.1。预测与本地预生成的自动减载、恢复、普通精化和空闲升级只使用 CPU 负载，FPS 保留为诊断数据。三个性能档位继续设置预测线程池与精化上限。

## 调度规则

CPU 信号取系统与 Java 进程有效负载的较大值，每 500 ms 在独立诊断线程采样。Windows 管理接口继续位于工作线程和渲染线程的预算锁之外。

| CPU 条件 | 调度行为 |
| --- | --- |
| 达到 85% | `REDUCE`，共享并发预算减半，普通精化降低并发 |
| 达到 95% | `PAUSE`，共享并发预算降至四分之一，暂停新本地生成请求；必要预测覆盖与当前目标保留有界进度 |
| 低于 80% 并持续满足约 3 秒 | 每次恢复一个压力等级，恢复与 FPS 无关 |
| 无压力且低于 80% | 可以安排空闲升级，仍需满足静止、可见目标完成和内存条件 |
| CPU 指标不可用 | 保持有界线程／内存准入，不用 FPS 代替 CPU 负载 |

内存保护、可见工作优先级、取消与预约归还、上传积压限制、预生成的预测完成门槛继续生效。本地真实区块生成仍通过 Minecraft 的区块请求路径执行；改动不会修改 C2ME 内部线程池。

`PredictionCpuBudget` 已移除 FPS supplier 与恢复门槛，直接提供 CPU 压力等级。`PredictionTileManager` 的规划与工作线程最终准入使用同一预算；独立的性能档位 FPS 保险丝和空闲升级的 60 FPS 条件已删除。`PredictionFramePace` 只统计日志，字节码中不再提供调度方法。中英文选项说明同步更新。

单人游戏真正暂停时主动降低渲染帧率的 `predictionPausedFps` 配置仍属于显示设置，它不会再进入生成预算。

## 验证

| 版本 | 回归通过 | 失败 | 跳过 |
| --- | ---: | ---: | ---: |
| NeoForge 1.21.1 | 111 | 0 | 0 |
| Forge 1.20.1 | 112 | 0 | 0 |

用例覆盖 15／30／40 FPS 下的预测和本地生成准入；低、中、高三档在 30 FPS 下执行真实瓦片构建与空闲升级；CPU 饱和、减载、恢复；跨管理器共享 lease、取消与排队；可见地表完成后预生成放行；缓存恢复和内存生命周期。新增测试通过实际 `PredictionTileManager` 队列调用采样器并发布网格，不仅检查状态返回值。

构建使用 Java 21 启动 Gradle；Forge 目标字节码为 Java 17，NeoForge 为 Java 21，Forge 已执行 `reobfJar`。

```powershell
.\gradlew.bat '-Dorg.gradle.java.home=C:/Program Files/Java/jdk-21' --project-cache-dir .gradle-cpu-only-20261010 -I tools/prediction/cpu-only-tests.gradle cpuOnlyTest jar --console=plain
```

两个项目使用同一测试脚本，各自输出到 `build-cpu-only-20261010` 与 `lib/cpu-only-20261010`。最终包通过 ZIP 完整性、模组版本、语言资源、原生库一致性和关键字节码检查。NeoForge 的 850 个生产 class 与构建输出逐字节一致。最终预算 class 没有 `PredictionFramePace` 引用；瓦片管理器对帧率监控的引用仅用于日志。

这些结果是自动化调度与网格回归，没有启动玩家整合包复测帧生成。手动验证应保持帧生成和 30 FPS 锁帧，观察 CPU 有余量时 `generationLimit` 是否恢复、服务端 `started/completed` 是否增长，以及新的 LOD 是否进入 Voxy。CPU 实际饱和时零生成配额仍可能是正常减载。

## 产物

版本保留 0.3.5，独立交付目录为两个仓库各自的 `lib/cpu-only-20261010`。

| JAR | 大小（字节） | SHA256 |
| --- | ---: | --- |
| `vss-0.3.5-neoforge-1.21.1.jar` | 5,866,184 | `eeff9eb90172bcd158952e44f7bcf07542018fa71755bbcce49a27a2d2e47b1f` |
| `vss-0.3.5-forge-1.20.1.jar` | 6,538,323 | `2081701ddc22631a025d50b5eab039836f501344d4204ace6e3b3b987b462d83` |

每个目录保存 `build-verification.json`、JUnit XML 副本和关键字节码。日志中的 Voxy 配置写入失败与液体模型字段兼容异常属于另外的问题，此次仅处理 CPU 调度与帧率误判。

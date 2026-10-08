# Spark 阴影通道覆盖缓存热点修复与验证

日期：2026-10-08。Forge 1.20.1 与 NeoForge 1.21.1 已同步实现并完成构建，继续使用各自的 0.3.5 版本号。

## 问题与最终行为

原始 Spark 分析见 [客户端热点报告](spark-client-hotspots-20261008.md)。阴影等辅助渲染调用进入 `StrictLodVisibility.beginFrame` 时可能传入 null 或尺寸无效的 viewport。旧实现会将共享渲染窗口清空，随后主画面恢复窗口，再次触发全局覆盖失效。

采样中 `StrictVoxyCoverageChanges.invalidateAll → Arrays.fill` 的合并权重为 2,500 ms，占 60,000 ms 渲染线程样本的 4.17%。这是采样权重，不能直接换算为新版 FPS 提升。

本次修改了以下行为：

1. `beginFrame` 在操作主视图状态之前检查 viewport。null、零尺寸或负尺寸的辅助调用直接返回，保留主视图窗口、覆盖缓存和待处理的排序重启。有效主视图继续执行世界、节点管理器和渲染范围检查。
2. `invalidateAll` 继续推进 `resetRevision` 与全局 revision，并丢弃旧变化历史；删除两套 `long[6][65536]` 版本表的清零循环，省去每次约 6 MiB 的数组写入。
3. 诊断字符串增加 `handoffResetRevision`，便于后续采样确认辅助／主视图交替是否仍导致全局重置。

版本失效仍然有效：列与区域版本查询以 `resetRevision` 为基线，再取对应桶值的最大值。新基线大于所有旧桶值，因此旧 stamp 无需物理清零；后续局部更新使用更大的版本，仍能使受影响的查询失效。相关读写继续保持同步。

登录、断线及真实世界重置仍调用显式 reset。节点撤销、渲染范围变化和新世界建立仍刷新覆盖；辅助 viewport 的返回不消费主帧需要处理的重启标志。

## 复现与回归

先加入复现测试，确认旧实现会在辅助／主窗口交替时改变覆盖版本并使缓存失效。应用修复后，两端均通过相关回归。

新增测试覆盖：

- 64 轮辅助／主窗口交替，每轮检查 null、宽为零、高为零及两种负尺寸 viewport；覆盖 revision、resetRevision 与暖缓存 miss 数保持稳定。
- 辅助视图不消费待处理的排序重启。
- 节点实际移除后立即撤销覆盖。
- 显式世界 reset 后旧肯定缓存不再生效，新节点上传可恢复覆盖。
- 全局版本基线压过旧桶值，随后局部更新、第二次 reset 和变化历史继续正确。

主／辅助窗口复现采用测试夹具：辅助侧调用生产 `beginFrame`，主窗口侧调用生产 `updateRenderWindow`。这验证覆盖状态往返的根因；本轮没有启动完整 Minecraft 或实际加载 Iris／Oculus 整合包。

| 验证 | NeoForge 1.21.1 | Forge 1.20.1 |
| --- | --- | --- |
| Strict、覆盖缓存、归属及提交过滤回归 | 116 项：114 通过，2 条件跳过，0 失败／错误 | 116 项：114 通过，2 条件跳过，0 失败／错误 |
| OpenGL 边界遮挡与原始状态深度交接 | 2 通过，0 跳过 | 2 通过，0 跳过 |
| `assemble` | 成功，Java 21 | 成功，Java 17，`reobfJar` 成功 |
| 成品字节码 major | 65 | 61 |
| 成品覆盖类 `Arrays.fill` 调用 | 已移除 | 已移除 |
| 成品 viewport 检查顺序 | 位于主视图操作之前 | 位于主视图操作之前 |

两项条件跳过分别为 `StrictVoxyContractTest.uploadIngestAndBothDrawHooksMatchTheActualVoxyJar` 和 `StrictVoxyShaderFixtureGpuTest.realVoxyProgramsLinkWithoutStrictDistanceClipping`，它们需要额外提供实际 Voxy JAR 等外部输入。其余本次选择的相关回归均通过。独立 OpenGL 测试使用 NVIDIA GeForce RTX 4070 Ti SUPER 上的隐藏 GLFW 上下文，检查边界墙剔除和深度交接，不启动游戏。

执行命令（各仓库使用其对应的 Java）：

```powershell
.\gradlew.bat test -I tools/prediction/regression-tests.gradle --tests '*Strict*Test' --tests '*PredictionCoverage*Test' --tests '*PredictionSubmissionCoverageTest' --tests '*PredictionOwnership*Test' --offline --no-daemon --console=plain
.\gradlew.bat test -I tools/prediction/regression-tests.gradle -I tools/prediction/gpu-tests.gradle --tests '*PredictionVoxyBoundaryGpuTest' --tests '*PredictionRawStateHandoffGpuTest' --offline --no-daemon --console=plain
.\gradlew.bat assemble --offline --no-daemon --console=plain
```

各仓库的回归 XML、构建日志与字节码核验保存在 `build/reports/tests/spark-shadow-fix-20261008/`；OpenGL XML 保存在 `build/reports/tests/spark-shadow-fix-gpu-20261008/`。

## 成品与同步核验

| 平台 | 相对本仓库的 JAR 路径 | 大小（字节） | SHA256 |
| --- | --- | ---: | --- |
| NeoForge 1.21.1 | `lib/vss-0.3.5-neoforge-1.21.1.jar` | 5,743,756 | `2227680a0967ec3c78fc715e24b7ec7709cbdc16d7bad7d125250def899c07e1` |
| Forge 1.20.1 | `lib/vss-0.3.5-forge-1.20.1.jar` | 6,416,010 | `74c2b88f7179566d1a564926558c77fc1aced261a69ef947b87b9fb3ec7b15b2` |

两个仓库中 `StrictVoxyCoverageChanges`、新增测试以及 `beginFrame` 方法的实现已核对一致。`StrictLodVisibility` 其余平台说明与已有诊断差异保留。工作区已有其他修改未撤销。

成品核验包括 ZIP 完整性、模组与 manifest 版本、两项修复的实际字节码、三个 Mixin 配置，以及全部五个平台原生库资源与旧包字节一致。详细核验结果见各仓库 `build/reports/tests/spark-shadow-fix-20261008/jar-audit.json`。

旧同名 JAR 已备份到各自仓库的 `lib/backups/spark-shadow-fix-20261008/`。本轮只在仓库打包，客户端实例由用户自行替换并启动测试。

## 实测范围

本轮确认修复了复现中的反复失效，并确认成品不再包含该覆盖类的数组清零调用。没有对新版进行实际游戏 FPS、帧时间或 GPU 时间复采，尚不能给出帧率提升幅度。

后续在同一光影与视角采样时，可检查 `handoffResetRevision` 是否仅在真实失效时推进，以及 Spark 中 `invalidateAll → Arrays.fill` 热点是否消失，再比较缓存命中率与帧时间。

## 2026-10-08 提交整理补测

整理近期代码和文档时，对齐了 Forge 与 NeoForge 的缓存维护空闲复用及保留天数变更处理，修正 Forge 的旧 Xaero 回放测试夹具，并将缓存缺失回归同步到两端。

两端各完成 108 项针对性回归，全部通过、无跳过；各完成 1 项 `PredictionRenderTargetGpuTest`，其中包含水面深度、移动／FOV、普通与 Iris 着色器契约、覆盖交接和状态恢复等多种场景。Forge 的缓存维护对齐后另复测 10 项文件缓存回归通过；这 10 项包含在上述 108 项中，不重复计数。

最终源码重新执行 `assemble`，Forge 完成 `reobfJar`。上表大小和 SHA256 已更新为本次整理后的成品。补测 XML、构建日志和最新成品核验分别位于 `build/reports/tests/github-sync-20261008/` 与 `build/reports/tests/github-sync-gpu-20261008/`。本次整理前的 JAR 另备份在各仓库 `lib/backups/github-sync-20261008/`。

# 植被闪现与粗地形缺面修复（2026-10-17）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

本轮针对“植被不断出现、消失，同时能透过粗地形看到侧面”的反馈。上一轮 `scene-handoff-20261017` 修复不充分：它检查了真实区块上传，却仍把上传／显存驻留当成当前画面已接管预测区域的证明；此外，粗坡面的两个三角形存在不一致的可见性判断。

## 使用版本与调查边界

- 已核对用户 NeoForge 实例中的 VSS SHA256：`dbfe46c18967f2688a87b8bd621bc69eaa75fa6398bc261122af4f06ba395aa0`，与上一轮仓库交付包一致。本轮不归因于用户加载旧包。
- 本轮检查时游戏已退出，没有进行新的游戏内 JFR、无菜单抓帧或 FPS 对照。以下是生产代码复现、真实 OpenGL 回归和打包审计结果，不能代替用户存档现场验收。
- 测试 GPU：NVIDIA GeForce RTX 4070 Ti SUPER / 驱动 572.70。
- 没有替换实例 JAR、启动客户端或操作 GitHub。

## 1. 上传不是当帧可见，不能据此删除预测地形和植被

`StrictLodVisibility.predictionCoverageBox` 和 Voxy 节点索引描述的是 GPU 驻留。节点已上传，并不保证它被本帧遍历选中、具有可用表面，或确实覆盖预测地形所在的像素。粗祖先、空节点和未选中节点都能满足驻留查询。

上一轮的 `PredictionAuthoritativeCoverageView` 将这项信息用于三个不同位置：scene 成员过滤、提交 ranges 过滤，以及预测 GPU 副本退役。节点状态变化时，同一片预测植被会反复进入／离开绘制范围；预测地形也可能在真实画面尚未接管之前消失。

本轮删除这条 CPU 驻留剔除路径：

- scene 和 prepared pass 保留正常选中的预测几何，交给已有的当帧深度、真实地表校验及原版遮罩处理。
- Voxy 上传／索引继续用于生成需求调度，避免重复生成；它不再单独决定已有预测绘制的删除。
- `PredictionRenderResidency` 不再因 `voxyOwned` 和上传范围而绕过尚未上传的预测父层。正常的预测父子交接、维度和视距退役、显存预算保护仍按原有规则工作。
- 上传就绪只依赖真正的几何／暂存数据，不再等待被删除的归属元数据。几何处于冷恢复状态时，继续保留旧的 GPU 地形，等待新数据可用后再替换。

新增生产回归覆盖：真实 `StrictVoxyNodeIndex` 驻留节点不能删除地形、水面、完整植被或预览植被；连续 120 帧切换驻留节点不能改变同一植被的 ranges，也不能重建静态 ranges；旧 `voxyOwned` 快照不能替代尚未上传的预测父层。3 项均通过。

## 2. 粗坡面的顶点判断会误删半边三角形

`PredictionTerrainProgram` 原先使用轴法线与每个顶点相对相机位置的点积计算 `vSurfaceVisible`。对于同一水平面分组中的斜坡，四个顶点高度不同；当相机位于这些高度之间，顶点会得到不同结论。这个值以 flat varying 传入片元阶段，两个三角形采用不同的 provoking vertex，因而可能只留下半边 quad，暴露后面的断壁或天空。

修复后，只有确定处于同一轴向平面的面才使用该快速判断。非平面／morph 坡面保守保留，再交由正常深度处理，避免一个面的两个三角形被不同顶点误判。CPU 和 meshlet 的保守方向选择保持原有行为。

独立参考着色器逐像素验证了普通直绘、间接批次、Iris 普通深度及反向 0–1 深度，组合不同相机高度和朝向，共 48 个场景：

| 指标 | 修改前 | 最终生产源码 |
| --- | --- | --- |
| 与参考不一致的场景 | 36 / 48 | 0 / 48 |
| 保留下来的目标像素 | 20,176 / 27,020 | 27,020 / 27,020 |

这是合成坡面的正确性复现，不是游戏 FPS 或完整现场画面比较。

## 3. 从生产删除失效的重复数据路径

删除 `PredictionAuthoritativeCoverageView`、`PredictionOwnershipRuns`、`PredictionOwnershipData`、`PredictionMetadataCache`，包括相应筛选、bounds/runs 元数据、压缩恢复队列和 GPU pin／unpin 等待。现有少数调用只需要知道网格是否含植被／建筑，改为 `PredictionPackedMesh.hasFeatures` 一个布尔值。

冻结的旧实现只放在 `src/test/java` 的 `Legacy*` 对照夹具中，用于复现历史行为／保留旧比较测试，不进入发布 JAR。这些夹具自己的压缩、归属测试通过，不代表生产渲染修复验证。

## 验证结果与复现命令

两个平台均完成以下验证：

- 新增可见性 CPU 回归：3 项通过。
- 新增 GPU 回归：坡面 48 场景逐像素一致；冷几何恢复保持旧 GPU 地形、上传不等待废弃元数据。
- 既有粗模交接 GPU 回归：288 个内部场景全部通过。
- 既有实现回归：214 项 CPU、10 项 GPU 通过。
- scene 逻辑测试：40 项通过。该组及上述 CPU 组包含冻结算法对照项，不能把总数当成全部验证生产绘制的独立场景。
- 水面、植被深度、真实侧壁、显示档位、OpenGL 状态、雾效和实际 Voxy 着色器／深度格式回归通过。
- NeoForge 使用 Java 21 构建；Forge 使用 Java 17 构建并执行 `reobfJar`。

基础命令（PowerShell，在各自仓库执行，分别设置 Java 21／17）：

```powershell
./gradlew.bat `
  -I tools/prediction/meridian-implementation-tests.gradle `
  -I tools/prediction/scene-handoff-tests.gradle `
  -I tools/prediction/visibility-stability-tests.gradle `
  '-PvssVoxyJar=F:/NovaEngineering-World整合包/.minecraft/versions/1.21.1-NeoForge_21.1.249/mods/voxy-0.2.15-beta.jar' `
  visibilityStabilityTest visibilityStabilityGpuTest `
  meridianImplementationTest meridianImplementationGpuTest `
  sceneHandoffLogicTest sceneHandoffReproGpuTest sceneHandoffRegressionGpuTest jar `
  --continue --console=plain
```

NeoForge 首次完整回归遗漏了实际 Voxy JAR 参数：雾效测试在检查该输入时失败，另外两项实际着色器测试跳过。补入上述参数后，这三项全部通过；并未修改断言来跳过验证。Forge 初次测试编译遇到旧测试清理接口残留，清理后完整命令通过。

证据保存在 `tools/prediction/visibility-stability-20261017/`：`before.log`、`neo-final.log`、`neo-voxy-verified.log`、`forge-verified.log`（Forge 仓库）、`artifact-audit.json`，以及各仓库 `build-visibility-stability-20261017/test-results/`。NeoForge 对实际 Voxy 三项的定向重跑覆盖了同名报告目录，完整第一次回归的其他结果以 `neo-final.log` 为准。

## 平台同步与产物

采用本轮修改前后的差异同步到 Forge，验证了两平台原有的事件接口、Java API 和采样／世界数据差异未被整类覆盖。

相对上一轮 JAR，两个新包均只修改预期的生产类及构建清单，删除 10 个旧类文件（4 个顶层类及其内部类），没有加入测试／Legacy 类。VSS 类字节码版本分别为 65／61。

| 平台 | 产物 | SHA256 |
| --- | --- | --- |
| NeoForge 1.21.1 | `lib/visibility-stability-20261017/vss-0.3.5-neoforge-1.21.1.jar` | `755d5db95d06cff1269928727bc338f92bd10b542da79e33c0f76d49483881a7` |
| Forge 1.20.1 | `lib/visibility-stability-20261017/vss-0.3.5-forge-1.20.1.jar` | `5e1ba43d2f107c71b17207a66a6101a858f89399fed164539160bfc8fda610ed` |

本次修正优先确保当前画面仍有可用覆盖，可能保留比上一轮更多的预测回退几何。由于没有本轮游戏内复测，不能承诺所有闪烁现场已经消失，也不能报告 FPS、显存或持续 GPU 时间收益。需要用户使用新包复核传送初期粗模、原地观察植被、转向和 Voxy 接管过程。

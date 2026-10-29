# 地形精细化期间短暂空白：代码审查与 GPU 复现（2026-10-20）

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

后续修复已完成，见[修复与验证记录](C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1/docs/prediction-refinement-blank-fix-20261020.md)。以下保留修复前的审查、失败用例和证据。

审查对象为两个仓库当前未提交工作区，包括 10 月 20 日的粗格局部交接实现。没有修改生产源码、构建交付包、替换游戏实例或提交 GitHub。新增内容仅为独立诊断用例、执行入口和本报告。

## 已确认的 P1 问题：新 GPU 资源遗漏完整边界掩码

触发条件：同一瓦片重新发布网格，生产渲染器为仍在绘制的旧网格保留独立 GPU 对象，为新网格创建新对象；与此同时，网格采样、接缝摘要、地形/植被归属和覆盖拓扑未发生变化。装饰、材质或其他不改变接缝的重建可以进入这个分支；不是每次改变采样间距都会触发。

调用链：

1. `PredictionRenderer.java:938`：旧 GPU 对象仍被已发布场景引用时，新网格使用新 GPU 对象，以免污染等待中的旧画面。这项保护本身正确。
2. `PredictionLodSeams.java:112`：`sameInputs` 成立时只重新绑定网格，复用旧接缝与边界掩码。
3. `PredictionSeamPreparation.java:104`、`:124`：掩码按逻辑内容做增量比较；相同掩码不进入 `maskChanges`。完整正确值仍存在于 `Result.masks()`。
4. `PredictionRenderer.java:1593`：`commitBoundaries` 先对 staged 瓦片调用 `updateCoverage`，随后仅上传 `result.maskChanges()`。它没有检查新 GPU 对象是否曾收到完整掩码。
5. `PredictionGpuTile.java:238`：固定网格的初始 allowed=true 写为 255，而完整掩码还包含独立地形/植被归属和四个侧墙替换位。255 的低四位全为 1。
6. `PredictionTerrainProgram.java:1189`：在普通地形启用侧墙替换时，低四位使源地形侧墙被丢弃，即使对应边界根本没有替代接缝。

核心错误是把“CPU 掩码内容没变”当成“新 GPU 资源已初始化”。逻辑增量不等于资源初始化状态。

## 生产 GPU 路径的复现结果

用例使用生产 `PredictionRenderer.Scene`、异步接缝事务、`PredictionGpuTile` 上传和 `PredictionTerrainProgram` 着色器。测试创建隐藏 OpenGL 窗口，不启动 Minecraft 游戏客户端。正面观察同一块仍应存在的地形侧墙，读取实际颜色缓冲中心像素 alpha。

| 检查 | NeoForge 1.21.1 / Java 21 | Forge 1.20.1 / Java 17 |
| --- | --- | --- |
| 旧 GPU 掩码 | 128 | 128 |
| 新 GPU 的预期完整掩码 | 128 | 128 |
| 新 GPU 实际掩码 | 255 | 255 |
| 事务中的 maskChanges 条目 | 0 | 0 |
| 替换前侧墙像素 alpha | 255 | 255 |
| 替换后侧墙像素 alpha | 0 | 0 |
| 保持不变再走 20 次准备后的 alpha | 0 | 0 |
| 后续真实接缝输入变化后的 alpha | 255 | 255 |

128 在此用例表示仅拥有地形、不替换侧墙。alpha=0 是测试目标的清屏背景，证明该面没有输出像素。测试不加载真实世界，不能用该像素数推算游戏中的空白面积。

这条路径解释了“加载时先出现一段空白，随后又恢复”：后续真正的边界变化重新发布完整掩码，恢复了被误删的墙。稳定画面并不保证下一帧自动修复；用例中保持 20 次准备仍为空白。无法据此给出现场空白持续的毫秒数。

## 对照：常规采样精细化交接

同一 64×64 方块瓦片，从 8 格采样切换到 1 格采样，同时改变高度。人为延迟接缝任务，连续调用场景准备 20 次：

- 旧 Draw 和旧 GPU 资源始终保留。
- 接缝事务完成后才切换新网格。
- 新网格 4096 个归属格的完整掩码均为 128。

该对照在两个平台通过。因此本轮证据不支持把常规异步等待或上传预算直接定为本次漏洞的原因；当前已经有旧网格保留机制。

另外，`Scene.finishBoundaries` 会拒绝过期接缝事务，事务等待期间暂停更多可选上传。这可能延长精细化等待，但本轮没有证明它单独造成空白，不列为已确认的第二个漏面问题。

## 修复建议

在 `Scene.commitBoundaries` 中，让每个本次发布的 staged Draw 都确保自己的 GPU 对象拥有 `result.masks().get(key)` 对应的完整掩码，再发布绘制记录。未 staged、但因邻居变化而受影响的瓦片仍处理 `maskChanges`。利用 `PredictionGpuTile.updateBoundaryCoverage` 已有的相等判断跳过重复上传，保持局部更新。

不能只把 255 改成 128：植被独立归属和合法侧墙替换位仍必须完整初始化。也无需为了初始化新资源而清空所有接缝缓存或延长旧网格生命周期。

修复后的验收应要求本次失败用例通过，并覆盖直绘、MDI、细分覆盖树、水面/植被独立掩码、新资源和旧资源复用；现有异步交接与粗格局部交接用例也应继续通过。

## 复现入口与验证边界

两个仓库都保存：

- `tools/prediction/refinement-blank-audit-20261020/audit.gradle`
- `tools/prediction/refinement-blank-audit-20261020/src/dev/xantha/vss/client/prediction/PredictionRefinementBlankAuditTest.java`
- 同目录下的平台执行日志。
- `docs/prediction-refinement-blank-audit-20261020.validation.json`：两端 JUnit 统计、生产源码 SHA256 和审查基线 HEAD。

执行：

```powershell
.\gradlew.bat -I tools/prediction/refinement-blank-audit-20261020/audit.gradle refinementBlankAudit --console=plain --max-workers=2
```

Forge 指定 Java 17；NeoForge 指定 Java 21。GPU 测试按平台顺序执行。每端结果为 2 项执行，1 项通过、1 项失败、0 跳过；失败断言正是当前生产代码误删像素和未初始化完整掩码的复现证据，**不是修复后的通过记录**。诊断源码仅在显式应用该初始化脚本时加入测试源集。

两仓的 `PredictionGpuTile`、`PredictionSeamPreparation`、`PredictionLodSeams` 和 `PredictionRenderResidency` 内容相同；Renderer 仅有加载器事件 API 的三处差异。本次没有运行新包在原存档中的现场对照，没有核验当前客户端实际加载的 JAR，也不能将用户看到的所有整片顶面空白都归因于已复现的侧墙漏洞。

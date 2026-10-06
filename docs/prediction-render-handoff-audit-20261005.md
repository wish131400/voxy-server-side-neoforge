# Voxy / 预测 LOD 渲染交接检查（2026-10-05）

本次检查针对开始工作时已有的未提交改动。视频为 `2026-10-05 15-55-53.mkv`，60 FPS、约 29.18 秒；抽帧可见远景缺块、海面断面和黑色区域。视频只能证明现象；下面的失败回归证明当前源码中的错误路径，不把录像里的每一个异常像素都归为同一原因。

## 结论与修复

### 上传就绪不等于本帧可见

原渲染链路用 `StrictVoxyNodeIndex.coversBox` 的上传节点覆盖结果关闭预测瓦片的 CPU `allowed` 掩码。Voxy 的粗层父节点可能仍在显存，但 GPU traversal 会依据视锥、HiZ、距离和子节点决定是否提交它；`EMPTY_MESH = 0xFFFFFE` 也算上传就绪，却根本不生成像素。整列预测一旦在 CPU 被禁止，后续深度测试就无法填补这些缺口。

新增 `PredictionVisibleHandoffTest` 在修改前两项均失败：上传一个 LOD4 祖先就会让一个 64×64 的预测瓦片从 4096 个单元变为 0；Voxy 状态重置也会让预测自身的所有权缓存失效。

同时，旧 `Scene.invalidateVoxyCoverage` 会移除相交瓦片的完整抑制缓存。预算分帧重算未完成时，`ownershipCache` 恢复完整预测；证明完成后又隐藏它。节点持续替换或窗口移动时，即使最终覆盖答案不变，也存在“隐藏—恢复—隐藏”的状态路径。仅排除排队中的 CPU mesh 工作不能消除这个问题。

修复后 CPU 掩码只选择已经上传到 GPU 的预测父子 LOD，Voxy 交接由当前帧实际深度完成。移除了渲染中的 Voxy 节点抑制队列及其全局/局部缓存失效依赖；节点与请求就绪跟踪仍用于请求顺序。普通帧计划可以复用几何，但每帧仍重新播种深度并执行绘制。已有 shader 对真实地表、同高度表面、水面及前后遮挡的判定继续生效。

### 离屏深度不能凭空制造遮挡物

`PredictionRenderTarget` 原本无条件把借来的 Voxy 离屏深度写入预测深度。Voxy 最终合成会丢弃 alpha 为零的像素；环境雾覆盖整个视图时也可能跳过合成。离屏存在深度，不代表主画面存在可替代预测的表面。

新增 GPU 回归在 NVIDIA GeForce RTX 4070 Ti SUPER 上于修改前失败：主画面的空白像素预期反向深度为 0，实际却写入 `4.882794E-4`，相当于一个约 2048 方块处的不可见遮挡物。该测试每帧移动已经合成的半屏，连续检查 120 帧。

修复后仅在主深度非清空像素上使用 Voxy 原始深度细化距离。同时将深度捕获从 `NormalRenderPipeline.finish` 的无条件 RETURN 移到 `transformBlitDepth` 调用之后，避免借用没有执行合成的视图。

## 全链路检查范围

| 环节 | 检查与处理 |
| --- | --- |
| 区块摄入、mesh 排队与完成 | 核对 `StrictVoxyPipeline` 的序列、旧工作完成与新工作隔离；没有再用待处理任务决定预测显隐。 |
| Voxy GPU 上传与结果复用 | 检查节点差量及完成通知在 SyncResults 返回工作线程之前读取；保留已有修复并运行相应回归。 |
| 上传节点与 GPU traversal | 对照上游源码以及当前实例 JAR 内 `traversal_dev.comp`、`node.glsl`；节点驻留、空 mesh、父子选择与实际绘制是不同阶段。 |
| 预测准备与父子 LOD | 保留 GPU residency、父级缺失子块回退、局部 mesh 更新、视锥剔除、接缝和批处理；取消 Voxy 元数据直接抹除预测。 |
| 普通管线深度 | 核对反向深度播种、原版远平面 clamp、原始 Voxy 深度反投影和最终深度导出；修复未合成像素被遮挡的问题。 |
| 水面与 Iris | 使用生产 shader 回归覆盖水面/熔岩/冰、同面深度误差、普通与反向 Z、两种 clip 范围、高空与望远镜，以及 MRT/state 恢复。 |
| 边界墙与最终画面 | 检查边界墙 shader patch、预测覆盖与深度关系、后续实体绘制、抗锯齿以及实际 Voxy GLSL 编译链接。 |

本地上游副本来自 `https://github.com/MCRcortex/voxy`，提交 `ee6e4bad73f1ed06765641db9a37cf8ee6399bb1`。当前运行实例使用 `voxy-0.2.15-beta.jar` 的 `NormalRenderPipeline + MDICSectionRenderer`。针对版本差异，另用 `javap` 核实已安装 JAR 的最终合成调用及描述符，并直接提取它的 traversal/node/final-blit shader 作为证据。

只读现场采样确认桥接没有失效、预测与 Voxy 节点均已驻留；这排除了该次采样中“预测完全没生成”或“桥接异常后整个功能关闭”的解释。该采样来自正在运行的旧包，不能作为本次修复后实机画面的证明。

## 验证与复现

全量 `build` 成功（1 分 30 秒）：292 个测试类、1364 项测试，1290 通过、74 跳过、0 失败、0 错误。跳过项包含需要单独启用的 GPU 测试、性能实验及需要额外输入的兼容测试，不能将跳过项算作通过。独立启用的 GPU 套件另行运行成功（4 分 24 秒）：8 个测试入口全部通过、0 跳过，其中包括生产渲染链路的多个内部场景和已安装 Voxy JAR 的真实 GLSL 编译链接。

复现命令（仓库根目录，PowerShell）：

```powershell
.\gradlew.bat build -I tools/prediction/regression-tests.gradle `
  '-PvssVoxyJar=F:\NovaEngineering-World整合包\.minecraft\versions\1.21.1-NeoForge_21.1.249\mods\voxy-0.2.15-beta.jar' `
  --console plain

.\gradlew.bat test -I tools/prediction/gpu-tests.gradle -I tools/prediction/regression-tests.gradle `
  '-PvssVoxyJar=F:\NovaEngineering-World整合包\.minecraft\versions\1.21.1-NeoForge_21.1.249\mods\voxy-0.2.15-beta.jar' `
  --tests '*PredictionDepthSeedGpuTest' --tests '*PredictionRenderTargetGpuTest' `
  --tests '*PredictionRawStateHandoffGpuTest' --tests '*PredictionVoxyBoundaryGpuTest' `
  --tests '*StrictVoxyShaderFixtureGpuTest' --tests '*PredictionProductionBatchGpuTest' `
  --tests '*PredictionFrameCaptureGpuTest' --tests '*PredictionEdgeFilterGpuTest' --console plain
```

本轮实际先运行 GPU 套件并保存 XML，再运行全量构建；两份结果分别保存在证据目录的 `gpu-results/` 和 `full-results/`，计数见 `test-summary.json`。

交付产物：`lib/vss-0.3.5-neoforge-1.21.1.jar`，5,482,153 字节。SHA-256：`9904f007642f95897a875de432d9bbc337a727c214e91287d3f39c1a58c4fe12`。已验证本次 4 个生产源文件对应的 23 个类与编译输出逐字节一致，未打入新增回归测试或 JUnit 类；详情见 `package-check.json`。本次相关文件的 `git diff --check` 通过。

GPU 回归将 Voxy 非空 mesh、EMPTY_MESH 和撤回交替 120 帧，验证生产 `Scene` 保留相同的预测绘制列表；另外检查 120 帧主画面像素显隐时深度能即时恢复。原有父子 LOD、真正可见的 Voxy 像素优先、天空回退、远处竖墙、同面水面与 GL 原始/缓存状态不一致的测试继续运行。

证据目录为 `build/render-chain-20261005/`：修改前文件、两类失败回归日志、GPU 日志、已安装 Voxy 字节码与 shader、只读现场诊断、最终测试汇总和打包信息。`tmp/flicker-contact.png` 为本次视频抽帧。

修复增加了在 Voxy 覆盖区域仍可提交的预测回退几何，实际遮挡交由 GPU 深度处理；对应减少了 CPU 节点覆盖查询和抑制遮罩更新。独立 GPU 测试验证正确性，整合包实际 FPS 和原场景的视觉收敛需要重启加载新包后测量。本轮交付仓库构建产物，不把运行中的旧客户端描述成已加载新代码。

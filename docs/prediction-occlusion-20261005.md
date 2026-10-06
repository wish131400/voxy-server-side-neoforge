# 预测 LOD 当前帧遮挡剔除与 Forge 同步

日期：2026-10-05。此文承接 `prediction-render-visibility-20261005.md` 的链路检查；该检查报告中的“尚未实现遮挡系统”是当时状态，本轮已经实现。

## 实现

普通管线的不透明预测 MDI 绘制现在经过 `PredictionOcclusionCuller`。它借鉴 Voxy 的 HiZ 与 GPU 间接命令筛选思路，适配现有预测瓦片、arena page 和距离桶，不移植 Voxy 的完整时间复用/层级遍历系统。

1. 预测目标仍由当前帧主深度播种，使用现有 reversed-depth 投影。
2. 每个不透明距离桶开始前，先提交前一个近处桶，再从目标深度构建 HiZ。这样原版/Voxy 的已有深度以及近处预测地形都可以遮住后面的预测瓦片。
3. HiZ 的第一级保守合并 4×4 像素，后续逐级合并。反向深度取最小值；天空、孔洞、无效值及扩展区域不能构成遮挡证据。
4. compute shader 投影瓦片包围盒，检查覆盖范围内所有相关 HiZ texel。仅完全被更近深度覆盖时，将对应间接命令的 count 置零，使其跳过实际网格的顶点和图元处理。
5. 相机位于盒内、近平面相交、未知结果、非标准 clip mode 都保守保留。包围盒考虑 morph 高度；快速转头使用当前矩阵与深度，不复用上一帧的可见性。
6. 原始间接命令保持不变，过滤结果写入独立 GPU 缓冲。统计每 30 帧抽样，用零等待 fence 检查后读取，不等待可见性来决定绘制。

同页批次上限从 512 个 tile / 2560 条命令提高到 2048 个 tile / 16384 条命令，减少容量触发的拆批；水面的既有顺序仍保留。

范围：新增 HiZ 只接入普通不透明 MDI 路径。水面、Iris 路径和不能使用 arena 的单独绘制仍走原有逻辑。它减少遮挡网格的绘制工作，不自动取消后台生成；整瓦片 AABB 跨度很大时，可能无法证明完全遮挡。

## 此前修复一起同步

目标为 `C:/Users/Administrator/Desktop/voxyserverside`（Forge / Minecraft 1.20.1 / Java 17），源为 `voxyserverside-neoforge-1.21.1`（NeoForge / Minecraft 1.21.1 / Java 21）。保留 Forge 的事件 API、快速移动上传/调度保护、内部 cellSetKey 与缓存接口。

- 稳定 VSS 客户端索引控制区域归属，未知/缺失区域保留预测，完整已覆盖瓦片退出生成/驻留。索引等待与原有 PRESENT 定义的限制仍见区域归属报告。
- Voxy 上传节点、祖先替换和临时遍历不再控制预测瓦片显隐。
- Normal Voxy 源深度按实际 GL raster 约定解码，目标按各自 Minecraft 普通投影映射；不修改 Voxy 全局 HiZ/投影。
- 混合瓦片的水面保留真实深度回退，避免索引已知但边缘缺实际水面像素时露出黑色海底。固体区域归属和完整内部瓦片退休保留。
- Forge 回归同步了新的归属行为，适配 Java 17 和内部坐标键；没有用旧上传节点断言重新引入闪烁逻辑。

同步清单、已有文件备份和平台差异在源项目 `build/prediction-occlusion-20261005/`。此前几份审计文档保留 NeoForge 当时的采样、旧包哈希和历史结论，不能当作 Forge 实机测试记录；本轮产物以 `verification.json` 为准。

## 验证与性能证据

两端分别执行完整 `build`（测试堆 2 GiB）和真实显卡 GPU 套件。完整计数、产物 SHA-256、共享源文件比较及 JAR 内容检查见 `build/prediction-occlusion-20261005/verification.json`。全量中的可选 GPU/性能/外部模组测试可能跳过，不把跳过算通过。

最终结果：NeoForge 全量 1373 项，1294 通过、79 跳过；Forge 全量 1378 项，1301 通过、77 跳过；两端各自的专项 GPU 套件均为 7 项通过、0 跳过。所有套件均无失败或错误。NeoForge 新增剔除类与 JAR 逐字节一致；Forge 重混淆会重排常量池并改变 ldc 指令宽度，按规范化后的指令、控制流、签名和常量核验一致。两端产物均未混入测试类或 JUnit。

GPU 回归覆盖遮挡命令实际执行、天空/单像素孔洞保留、同深度保留、近平面、相机转向、尺寸改变、GL 状态恢复，以及生产深度播种、交接、水面回退和实际 Voxy shader 契约。Forge shader 契约测试使用安装版 NeoForge Voxy 作为共享 GLSL fixture，并不等于 Forge 安装版 Voxy 游戏兼容验证。

全量构建可用 `.\gradlew.bat build -I tools/prediction/regression-tests.gradle --console plain`；运行 Forge 前将该终端的 `JAVA_HOME` 设为 Java 17。单独复现新增 GPU 筛选及生产网格基准：

```powershell
.\gradlew.bat test -I tools/prediction/regression-tests.gradle -I tools/prediction/gpu-tests.gradle --tests '*PredictionOcclusionGpuTest' --tests '*PredictionProductionBatchGpuTest' --console plain
```

完整七类 GPU 套件还包含 `PredictionRenderTargetGpuTest`、`PredictionVoxyDepthConventionGpuTest`、`PredictionDepthSeedGpuTest`、`PredictionRawStateHandoffGpuTest`、`PredictionVoxyBoundaryGpuTest`；其中真实 Voxy 契约测试需提供 `-PvssVoxyJar=<实际 fixture JAR 路径>`。本轮精确筛选保存在证据目录的 `gpu-suite.gradle`。

RTX 4070 Ti SUPER 上本轮已记录的重复生产网格基准：

| 基准 | 遮挡前后 GPU 时间 | 被过滤命令 / 四边形 |
| --- | --- | --- |
| NeoForge | 1.9466 → 0.8356 ms | 4096 / 5,406,720 |
| Forge（最终 Java 17 套件） | 1.6609 → 0.7260 ms | 4096 / 5,406,720 |

该基准把同一份生产网格重复 4096 次并完全遮挡，包含 8 个桶的 HiZ 成本；它验证几何阶段确实减负，**不是游戏 FPS**。两端环境、JIT 等不同，不能横向比较版本快慢。

HiZ 有固定成本：NeoForge 的廉价合成网格隐藏时 0.0481 → 0.0870 ms，无遮挡时 0.0850 → 0.1454 ms。因此不能承诺所有视角都更快，更不能据此宣称用户的 FPS 会翻倍。新包尚未重启进入原录像场景完成同位置开关对照。

## 诊断与回退

预测提交诊断新增 `occlusion`、`hizBuilds`、`sampleAge`、`testedCommands`、`hiddenCommands`、`testedQuads`、`hiddenQuads`。这些是抽样的 MDI 命令统计；原有 queuedQuads 是筛选前候选，不能用它断言被遮挡几何仍实际执行。

JVM 参数 `-Dvss.disablePredictionOcclusion=true` 只关闭新增遮挡筛选，保留此前交接/深度修复，可用于同场景 A/B。实机应在无遮挡远景、地面近山遮挡、背向远景、快速转头和高空水面接缝分别对照 FPS/帧时间与隐藏命令比例，并观察完整性。交付位置是两端 `lib/` 的 0.3.5 JAR；本轮没有替换整合包 mods 中的文件。

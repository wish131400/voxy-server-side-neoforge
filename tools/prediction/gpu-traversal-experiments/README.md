**Voxy GPU selection experiment, 2026-10-14**

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

This directory contains an isolated experimental implementation and its raw measurements. It does not change the shipped renderer. The complete Chinese report is `docs/history/performance/prediction-gpu-traversal-benefit-20261014.md`.

Run from either repository with the platform JDK:

```powershell
# NeoForge: JDK 21, configured in gradle.properties.
.\gradlew.bat --offline --no-daemon -I tools/prediction/gpu-traversal-tests.gradle gpuTraversalExperimentTest

# Forge: JDK 17.
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
.\gradlew.bat --offline --no-daemon -I tools/prediction/gpu-traversal-tests.gradle gpuTraversalExperimentTest
```

The test creates an invisible GLFW OpenGL 4.6 context. Minecraft is not launched. No mod instance or installed JAR is modified. Experiments require a GPU supporting OpenGL 4.5 and indirect-count; this run used an RTX 4070 Ti SUPER with NVIDIA 572.70.

Mode 0 calls the current platform's production `PredictionVisiblePlan` and `PredictionIndirectBatch`. Modes 1–3 use stable resident input and the isolated batch:

- 1: GPU frustum/direction/current-depth selection, compact commands, indirect-count drawing.
- 2: One coarse box for each 64 leaf commands before the same selection.
- 3: Coarse-node traversal writes a queue and indirect dispatch parameters; only queued leaf groups execute.

The candidate reuses production terrain storage, encoding, ownership ranges, shaders, and prepared passes. It does not implement Voxy's complete hierarchy or GPU selection of display LOD tiers. All renderer glue changes were reverted after the comparison.

`production-gpu-traversal.csv` records preparation/submission wall time and the GPU query interval from 41 alternating isolated frames after 20 warmups. Each frame is synchronized before its measurement. `production-gpu-traversal-throughput.csv` records 21 alternating blocks after 8 warmup blocks, each with 32 frames and synchronization only at block boundaries. These are render-submission workload measurements, not Minecraft FPS.

Fixtures are synthetic. 128/1024 terrain tiles contain 131072/1048576 opaque quads. The 1025-entry fixture contains 342 terrain tiles and 683 seam patches, totaling 393920 quads. Camera position stays fixed; stationary and continuous-turn scenarios are compared with Hi-Z disabled or a current-frame depth proof. Images and depth are checked for 81 pairs per platform. Real vegetation textures, Iris, ownership revocation, loading, and production camera-switch integration are outside this experiment.

The local decision is to keep the experiment and evidence, with all production source edits restored. The queue adds cost in these fixtures; simple selection helps some larger turning cases but adds cost to stationary/small cases. A broad renderer replacement is not supported by these results.

After both platform tests finish, `python tools/prediction/analyze-gpu-traversal.py` verifies production restoration, checks the previous delivery hashes, and writes identical Markdown/JSON reports to both repositories. The result audit also checks the frozen baseline hashes in this directory.

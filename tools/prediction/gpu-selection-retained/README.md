This directory keeps the pre-change submission baseline, retained-path benchmarks,

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

GPU counter/state checks, rollback evidence and archived results out of main.

The production selector is flat. It has no new coarse-node stage or queue traversal.
It requires a large current visible workload, fixed-position rotation, valid current
Hi-Z, no seams and valid shared arena slices. The ordinary path remains available.

Run the corresponding repository with JDK 21 (NeoForge) or JDK 17 (Forge):

```
gradlew.bat --offline --no-daemon -I tools/prediction/gpu-selection-retained-tests.gradle turnMainRegressionTest turnMainGpuTest gpuSelectionBenefitTest gpuSelectionFallbackTest jar
```

The benefit test contains 8 input sets and 3 scenarios, alternating baseline and
candidate. It updates the real display projection for terrain input, verifies
72 color/depth pairs, records both isolated and continuous submission intervals,
and asserts that the candidate only starts GPU selection in retained cases.
The separate fallback test rejects an otherwise allowed selection when Hi-Z
initialization encounters an unsupported clip mode.

`prepare-comparison.py` regenerates the comparison source from the archived traversal
test and the test/build script. `sync-forge.py` applies only this implementation to
Forge, retaining its existing event hooks and unrelated edits. `report.py` validates
both repositories' test reports and JARs, then writes the shared report and checksums.
The synchronization and report scripts are coordinated from the NeoForge repository.

Build output: `build-gpu-selection-retained-20261014`.
JAR output: `lib/gpu-selection-retained-20261014`.
Report: `docs/prediction-gpu-selection-retained-20261014.md`.

The initial, threshold and projected input result archives explain why broader
conditions were rolled back. Final results are under `results/neoforge` and
`results/forge`. Performance intervals are synthetic submission costs, not live
Minecraft FPS. GPU visibility does not enter the CPU via a synchronous readback.

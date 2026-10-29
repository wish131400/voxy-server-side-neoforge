# VSS resource optimization experiments, 2026-10-11

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

The decision report is `docs/history/performance/vss-resource-optimization-20261011.md`. Measurements
are local A/B benchmarks or policy replays, not Minecraft FPS comparisons.
Production contains the accepted changes only.

`results/` preserves the initial isolated NeoForge experiment. The four
`iris-submission-layout*.csv` files supersede the exploratory
`iris-submission.csv`, `iris-submission-pooled.csv` and
`iris-submission-mixed.csv` files. `mode=0` is the original path and `mode=1`
is the candidate. CPU and GPU values are nanoseconds. Cold-residency upload
counts are cumulative through the named round; memory values are bytes.

`LegacyPassFixture` and `LegacyGpuResidency` under `src/test/java` preserve the
actual source at the start of this task, including earlier uncommitted work.
Each platform uses its own original class bodies for accepted-change A/B checks.
The rejected larger biome cache, its baseline fixture and benchmark have been
removed from the current test sources.

Run accepted-code checks from either repository with its matching JDK:

```powershell
# NeoForge: JDK 21; Forge: JDK 17.
.\gradlew.bat --offline --console=plain -I tools/prediction/resource-optimization-tests.gradle resourceCpuTest resourceRegressionTest
.\gradlew.bat --offline --console=plain "-PvssVoxyJar=C:/path/to/voxy.jar" -I tools/prediction/resource-optimization-tests.gradle resourceRegressionGpuTest
.\gradlew.bat --offline --console=plain -I tools/prediction/resource-optimization-tests.gradle jar
```

Results are written to `build-resource-optimization-20261011/`; the JAR goes to
`lib/resource-optimization-20261011/`. The GPU task creates hidden GLFW contexts
and needs OpenGL 4.6. The final-depth test reads the supplied Voxy shader JAR.
Without that argument its skip is explicitly recorded by JUnit.

`accepted.patch` describes only this task's production changes against the
starting working tree, rather than Git HEAD. It is evidence, not a replacement
for the repository's previous modifications.

## Rejected candidates removed

The Iris MDI prototype regressed the dense fixture's GPU interval by 6–7%.
The 8,192-entry biome cache did not reduce expensive noise loads and improved
streaming by only about 0.63% in the initial test. Both production experiments
were reverted before packaging. Their experiment source, patches and build
script have now also been removed from both repositories.

Historical CSV files and JUnit XML retain the measurements behind the rejection.
They are evidence only; the rejected candidates are absent from all current
source sets and production JARs. The original GPU measurements used a synthetic
Voxy emission adapter with rasterizer discard during timing, so they are not
world FPS comparisons. The initial 32 color/depth comparisons and binding
conflict fallback check are preserved in the historical test output.

`initial-junit/` and `final-junit/` retain the measurements before experiment
cleanup. `cleanup-junit/` records the current accepted-only CPU checks: ten
tests per platform, with no failures, errors or skips. Production source and
JAR hashes remained unchanged by that cleanup.

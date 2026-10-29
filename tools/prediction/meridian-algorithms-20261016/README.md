# Historical Meridian algorithm measurements, 2026-10-16

> 历史实验说明：接口、命令与性能结论以记录时的输入为限。当前实现见 [模块架构](../../../docs/development/architecture.md)；工具目录见 [实验索引](../../../docs/development/prediction-experiments.md)。

This directory retains the **historical inputs and evidence**, not an active
candidate implementation. The rejected/superseded experiment classes, old Gradle
entry point, and old result collector were removed on 2026-10-16. Frozen
`production-src/` remains necessary for the seam baseline and is not a release
source set. Its original manifests and test reports describe the historical run,
not the cleaned current source tree.

Retained files:

- `inputs/`: six captured forest samples, real atlas/sprites, native fixtures.
- `results/`, CSV, PNG, XML and summaries: evidence for the original decisions.
- Shared `AlgorithmBootstrap` and `AlgorithmSupport` now live only in
  `src/test/java/dev/xantha/vss/client/prediction/`. Current regression scripts
  reuse those fixtures; the duplicate bootstrap and helper copies were removed.
- `production-src/`: explicitly selected old seam baseline only.
- `rejected-pair-dictionary/attribute-encoding.csv`: rejected early encoding data;
  its unused source copy was removed.

Use `tools/prediction/meridian-implementation-tests.gradle` for retained live
production regressions and `tools/prediction/raster-boxes-tests.gradle` for the
current full-shader two-pass occlusion comparison. The old procedural occlusion,
binary canopy, encoding, demand, JNI, and duplicate mesher harnesses were retired;
their historical results remain available in the reports.

See `docs/history/performance/meridian-018-algorithm-tests-20261016.md` for the original findings and
`tools/prediction/raster-boxes-20261016/cleanup.json` for removed source hashes.

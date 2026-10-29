# Prediction Code Cleanup (2026-09-26)

> 历史记录：保留当时的实现、输入与验证结果，不能据此判断当前版本的渲染架构、配置或兼容范围。 当前说明见 [文档目录](../../README.md)，相关旧报告见 [历史索引](../README.md)。

Scope: prediction production code and its tests in the NeoForge 1.21.1 and
Forge 1.20.1 repositories. Existing working-tree changes are retained.

## Removed Paths

- `PredictionChunkLocks`: no production caller. Delete the isolated stripe
  distribution test; keep the vegetation publication regression in
  `PredictionVegetationPublicationTest`.
- `GlProgram.linkCompute()` and `id()`: unused remnants in NeoForge.
- `PredictionTileManager.refineRelief()`, its private `highRelief()` helper,
  and `enqueueRing()`: no callers. The current planner uses
  `PredictionRelief` and its normal work plan.
- `PredictionMeshBuilder.colorForWall()` and `addHorizontal()`: no callers.
- `PredictionIndirectBatch.commandCacheEnabled`: only the optional GPU
  benchmark disabled this. Production now always reuses unchanged commands.
  Pixel comparison against individual-buffer submission, movement invalidation,
  and the legacy-versus-cached benchmark remain.

The historical Hi-Z/GPU AABB experiment was already removed. Its measured
cost exceeded the small reduction in submitted geometry; no implementation or
runtime switch remains to delete in this cleanup.

## Ownership

- `PredictionTileResidencyPolicy` owns pure horizon, cold-storage, parent
  coverage and retirement decisions. It owns no executor, GL object, disk
  cache or mutable tile map.
- `PredictionTileManager` owns planning, admission, publication, persistence
  and resource release. It calls the policy rather than defining these
  decisions internally.
- `PredictionOpaqueBatches` owns reusable near-to-far distance buckets and
  grouping by arena page. Shrinking scenes and render resets clear references
  to obsolete draws. Transparent order is unchanged.
- `PredictionRenderer` owns render passes, state binding and submission.
- `PredictionIndirectBatch` owns command upload/reuse and MDI submission.

The residency API no longer accepts unused dimension or ready-parent
arguments for cold-storage decisions, or desired ancestors as coverage.
A desired parent still waiting in a queue cannot release its child.
The render residency layer retains its separate GPU upload hand-off guard.

## Retained Compatibility

Java terrain sampling, older-native-library fallback, non-arena submission,
Iris state restoration, corrupt-cache rebuilding and missing-coverage parents
are active compatibility or correctness paths. Their slower execution alone
is not evidence that deleting them improves the project.

Density, compact GPU/seam storage and sequential seam-mask switches still
serve active result-parity and performance benchmarks. Test-only instance
experiments remain under `src/test` and do not enter release JARs.

This change primarily reduces obsolete code and separates responsibilities.
It does not change mesh precision, terrain algorithms or water submission
order, and is not evidence of a particular in-game FPS gain.

## Validation

- NeoForge: 979 passed, 45 skipped, 0 failures in the final full test run.
- Forge: 984 passed, 43 skipped, 0 failures in the final full test run.
- Each repository: 15 incremental-render and GPU tests passed separately.
  The GPU test compares page-grouped opaque rendering and mixed arena/individual
  buffers against reference color/depth pixels; transparent order, command
  reuse, camera updates and stale bucket references are covered.
- Both versioned JARs were rebuilt in `lib`; Forge reobfuscation completed.
  Archive inspection confirms the new modules are present and removed
  classes and test experiments are absent.

The first full run exposed Windows temporary-file cleanup racing the
asynchronous cache close. Tests now await the disposer and commit queues
before temporary-directory removal; production shutdown remains asynchronous.
The optional-refinement completion test also timed out in early runs and
passed in both final runs. Its scheduler was not modified here. The attempted
old-JAR comparison was invalid because Gradle had already rebuilt that JAR;
it does not establish whether the timing issue predates this change.

Evidence: `build/cleanup-validation-final.log` in both repositories,
`build/cleanup-gpu-validation-selected.log` in NeoForge and
`build/cleanup-gpu-validation.log` in Forge.

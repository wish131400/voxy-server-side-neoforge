# Approximate coarse terrain, exact refinement

Mod version remains 0.3, Minecraft 1.21.1 NeoForge. No commits or pushes.

## Runtime behavior

- Rust terrain grids below the layout's full cell axis (normally 8/16/32,
  versus full 64) use the separate `previewPoints` JNI method.
- Search raw final density from the ceiling with 16-block vertical steps,
  narrowing the first sampled solid/air crossing to at most 4 blocks.
- Skip interpolation-cell arrays, neighbouring full columns, aquifer search,
  ore generation and structure blending. Water/lava use the configured sea
  level and default fluid. Empty columns stay empty.
- Apply the world's parsed surface rules using approximate depth and biome
  context. No replacement hand-written biome palette. Cliff steepness,
  badlands pillars, icebergs and thin floating spans await exact refinement.
- Full grids use the existing accurate sampling path. Vegetation also keeps
  its existing accurate terrain context and scheduling policy.
- Flag bit 27 marks approximate samples, including serialized disk entries.
  Exact grid requests discard approximate retained samples, except captured
  authoritative columns. Approximate records never enter exact native or Java
  point/chunk caches. Existing accurate disk entries stay reusable.
- The Java fallback is unchanged. This optimization applies to supported
  native noise generators, not every custom mod backend.
- JNI ABI is now 3, checked at load time. Initialization is synchronized and
  idempotent: loading another copy can otherwise split JNI method resolution
  between independent world-handle tables.

## Measurements

Cold 64-point batches, same seed, world document, coordinates and spacing.
The comparison is against the previous accurate VSS surface path.
Each of three origins uses a fresh World and has five spacings. World creation,
JNI, mesh building, upload and scheduling are outside the timed region.
Raw results are in `build/reports/aggressive-preview-20260910/*-*.jsonl`.

For spacing 64 blocks:

| World | Accurate sampling | Approximate sampling | Per-origin ratio |
| --- | --- | --- | --- |
| Vanilla | 45.63-69.15 ms | 3.37-4.26 ms | 10.7-20.5x |
| Captured Tectonic world | 190.36-372.68 ms | 6.40-7.72 ms | 29.8-48.3x |

These are initial single-run per-origin kernel timings, not an in-game loading
or FPS benchmark. Some preview measurements overlapped native cross compilation;
repeated isolated measurements are required for stable throughput claims.

Height differences across 960 sampled points per world:

| World | Mean absolute error | Median | 95th percentile | Maximum |
| --- | --- | --- | --- | --- |
| Vanilla | 2.49 blocks | 3 | 5 | 37 |
| Captured Tectonic world | 3.09 blocks | 3 | 6 | 74 |

Coarse approximation intentionally can miss thin or suspended geometry. These
errors do not define exact refinement accuracy and must not be reused there.

## Validation

- Rust suite: 36 tests passed, including approximation isolation, valid bounds,
  material rule use, void behavior, and existing exact surface regressions.
- Java/compatibility/GPU suite: 604 tests, 603 passed, one optional live test
  skipped. New coverage exercises disk reload followed by exact resampling,
  authoritative capture precedence, and invalid JNI input atomicity.
- JNI ABI 3 oracle: 1,515 density values, 21,120 base blocks, 29 vegetation cases.
- Production adapter: 1,064 grid/material/tint samples and seven mixed vegetation
  jobs passed with the packaged Windows DLL.
- All five Windows/Linux/macOS libraries built and passed package/architecture/
  dependency checks, with 31 JNI exports each. Linux/macOS were not executed on
  target hardware. No interactive game visual or FPS check was performed.

Initial full Java testing exposed duplicate native loading with invalid world
handles. The initialization fix was applied before the successful full rerun.

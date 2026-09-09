# Terrain Mod Native Migration

Status: partial implementation, not completion of the requested all-Rust migration.
Minecraft 1.21.1, NeoForge, VSS version `0.3-neoforge-1.21.1`.

## Implemented And Wired

- Epic Terrain 0.1.4 and Compatible 1.0.3: native density/NoiseChunk sampling,
  including Y-dependent Cache2D, eager quart FlatCache, ordered interpolation
  slices, CacheOnce batch counters and CacheAllInCell. Stateful evaluation is
  enabled only for graphs which need it; ordinary vanilla retains its path.
- Tectonic 3.0.26: effective runtime configuration export remains Java data
  transfer; supported density graphs can select the native backend.
- Lithostitched 1.8.0+beta6: axis, ceil, floor, sin, cos, signed sqrt, mix,
  select, coordinate shifts and FastNoise Lite Perlin/OpenSimplex2S/cellular.
  Custom FastNoise registry references, salts and configuration are transferred.
- FreeTerraForged 0.0.6005: complete prediction tile filtering in Rust (erosion,
  smoothing, steepness, beach detection, quart beach correction). Java transfers
  tile fields and commits results. Only prediction-owned WorldFilters are bound.
  Buffer memory is explicitly freed and native work commits only on success.
- FreeTerraForged density noise nodes can read native Perlin/Perlin2,
  Simplex/Simplex2, white, constant, seed shift, frequency, add/multiply/min/max,
  abs/invert/power, clamp/map/alpha/threshold, including registry references.
  Density clamp_to_nearest_unit and linear_spline are native too.
- Native sampler close now also releases its owned Java context once, including
  the FTF tile cache. Unreferenced custom density entries do not disable unrelated
  dimensions. Unknown codecs still fail explicitly into compatibility handling.
- Native algorithm identity is `vanilla-rust-abi2-r2`; old prediction data uses a
  different cache identity. JNI remains ABI 2 with an additional filter export.

## Still Required

1. Port remaining FTF noise modules and domain warps, preserving floating-point
   bounds, seed transformations and codec semantics. Cache2D currently remains
   unsupported rather than being incorrectly treated as a transparent wrapper.
2. Port FTF continent selection and hydrology, river graph generation and erosion
   inputs, terrain regions/populators, biome/climate evaluation and water levels.
3. Replace FTF CellSampler dependencies with native-owned tiles, eliminating Java
   heightmap/continent/river/climate sampling and per-cell object transfer.
4. Port or explicitly account for custom surface/feature/structure postprocessing.
   Existing vanilla native vegetation support is not equivalent to implementing
   every mod's custom placements or all Minecraft structure generation.
5. Validate complete effective Tectonic graphs and generated FTF columns/surfaces
   against real worldgen across multiple presets/seeds, then compare wall time,
   allocation, cancellation latency and actual in-game frame times.

The full FTF graph still contains `reterraforged:cell` and unsupported modules,
so its base terrain generation remains Java plus the native filter hook. The
presence of native noise functions does not mean that full generator is native.
Java remains necessary for Minecraft registries, resource/configuration export,
block-state identities, textures/models, loader hooks and GPU resource ownership.

## Validation

- Epic: two release data packs, three seeds, 54 full columns per seed/pack:
  324 columns and 176,256 block comparisons. Includes the negative-Y cached
  density case which previously incorrectly produced lava instead of stone.
- Lithostitched FastNoise: 35 configurations, five seeds, 72 points = 12,600
  bit-exact values. Additional operator tests include overlapping selections,
  truncating coordinate shifts and signed sqrt; trig allows one double ULP.
- FTF noise: 50 configurations/compositions, four seeds, 512 positions = 102,400
  exact values; six density expressions add 12,288 values. Coordinates include
  both signs and world-border scale. Extreme integer seeds and resolution cases
  are included. Numeric native queries use the actual JNI backend.
- FTF filters: 24 combinations of size 32/64/96/256, three seeds and optional
  filters on/off. 466,944 cells compare height, sediment, erosion, gradient and
  terrain category against release classes. Real Tile.getCellRaw flattened-index
  semantics are used, including tiles with borders. Heap/read-only/short/invalid
  input buffers are rejected without partial writes.
- The optional Mixin method descriptor is checked against release bytecode.
  This is not a complete Mixin transformation or an in-game activation test.
- Native sampler ownership has an idempotent-close regression test.

Test sources: `EpicTerrainCompatTest`, `LithostitchedNativeTest`,
`FreeTerraForgedNativeNoiseTest`, `FreeTerraForgedNativeFiltersTest` and
`PredictionWorldgenCapabilitiesTest`. Development runs can use
`-PvssTestNativeLibrary=<absolute DLL path>` with `compat-tests.gradle`; packaged
verification should omit that override and load the JAR resources.

Cross compilation and native-container audits do not replace Linux/macOS runtime
tests. Voxy's rendering requirements are unchanged by moving CPU work to Rust.

## Build Result And Performance

The packaged-resource regression completed 551 Java/compatibility/GPU tests with
zero failures, errors or skips; the Rust release suite completed 25 tests.
Logs: `build/native-mods-final-tests.log`, `build/native-mods-rust-tests.log`.
The five cross-compiled binaries pass `build/reports/native-mod-migration.json`:
30 JNI exports each, matching packaged/source build hashes, Linux glibc 2.28,
macOS 11.0 and Apple Silicon ad-hoc signature validation.

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 4,133,995 bytes.
SHA256: `420c6d9da18a264783327b48b1551d47f09803adf8d30a939196a90edb1a0093`.

Five alternating baseline/current process pairs used the existing production
document with 26,684 states. Baseline was the previous performance-review
`after/worldgen_bench.exe`; current was built from this Rust source. These are
ordinary vanilla worldgen checks, not FTF throughput or in-game frame rates.

| Run | Baseline 5x5 ms | Current 5x5 ms | Baseline 64 sparse ms | Current 64 sparse ms |
| --- | ---: | ---: | ---: | ---: |
| 0 | 1044.77 | 983.72 | 74.04 | 70.75 |
| 1 | 1079.89 | 1024.83 | 79.10 | 70.03 |
| 2 | 992.21 | 1031.52 | 82.91 | 70.96 |
| 3 | 1048.83 | 1006.30 | 79.77 | 69.08 |
| 4 | 1098.37 | 1054.51 | 74.56 | 75.41 |
| Median | 1048.83 | 1024.83 | 79.10 | 70.75 |

Every pair kept proxy checksum `372423c89bb031c6` and sparse checksum
`6277bf0bbebe6d52`. No large vanilla regression was observed in this workload;
the small proxy difference is not a general speedup claim.

FTF filter timing in the full test run was Java 143.17 ms versus Rust including
packing/JNI/unpacking 177.71 ms for the entire 24-case workload. This test mixes
JIT warmup, object construction and algorithm work and is not a stable benchmark.
It does **not** establish that native FTF is faster. Native tile generation/storage
is still needed to eliminate the transfer layer; profile the stages separately
before attributing the measured difference or claiming an FPS improvement.

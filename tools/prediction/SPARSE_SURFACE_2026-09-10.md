# Sparse surface sampling optimization

The new-world profile showed that the coarse-to-medium phase spent almost all
prediction-worker time in native terrain sampling before vegetation started.
This change reduces work per surface point without changing grid locations,
LOD resolution, scheduling priority, material rules, or the JNI ABI.

## Implementation

- Sparse stateful jobs precompute the Z interpolation slices required by the
  requested center and steepness-neighbour columns. They retain the existing
  full-height slice evaluation and noise-cell internal traversal order.
- Center columns stop below the first solid surface after retaining the seven
  blocks read by the LOD record plus the depth required by surface predicates.
  Other columns in the cell continue until their own requirements are satisfied.
- Eroded badlands and frozen oceans retain complete center columns. Graphs with
  coordinate-transform dependencies retain the original preparation path.
- A surface rule that removes/replaces the retained material can invalidate the
  depth bound. The output check then regenerates a fresh full-depth job, retaining
  structure adjustments and leaving the remaining batched columns intact.
- Air scanning above terrain and full noise-cell output arrays are still used.
  No approximate height bound, horizontal decimation, or texture simplification
  is introduced. Further empty-cell skipping requires a proven density/fluid bound.
- No additional runtime logging or profiling instrumentation is packaged.

## Before/after benchmark

Sources before the change were copied to an isolated build directory. The
baseline and final optimized executables process identical cold batches with
the actual exported worldgen documents. Each batch has 64 points; three origins
and five strides produce 960 points per world. New/old execution order alternates
between runs. These are offline terrain sampling timings, not game frame times.

| Actual world profile | Baseline total | Optimized total | Reduction |
| --- | ---: | ---: | ---: |
| New world, run 1 | 4,462.0 ms | 3,067.3 ms | 31.3% |
| New world, run 2 | 4,457.9 ms | 3,067.3 ms | 31.2% |
| New world, run 3 | 4,418.9 ms | 3,131.5 ms | 29.1% |
| Previous world | 4,074.9 ms | 2,785.2 ms | 31.7% |

All ten fields of all 1,920 distinct surface records matched the baseline.
Across repeated runs this covers 3,840 record comparisons. Raw measurements,
the baseline sources and replay script are in
`build/reports/sparse-optimization-20260910/`.

The existing sparse/full-chunk tests cover surface materials, fluid levels,
colours, frozen oceans, badlands, the Nether, the End, cross-chunk order and
duplicates. A new stateful-density regression covers snow, ice, neighbour
columns and a surface-removal rule that requires the full-depth fallback.
All 34 Rust release tests passed.

The full Java/compatibility/GPU suite passed 602 of 603 tests, with only the
optional live-snapshot test skipped. Tectonic, Lithostitched, FreeTerraForged
and Epic Terrain fixture checks ran with their dependency JARs supplied.
JNI ABI 2 validation passed 1,515 density values, 21,120 base blocks and 29
vegetation cases. The production Java adapter passed 1,064 grid/material/tint
samples and seven mixed Java/native vegetation jobs, including staged
refinement, state transfer, bounds and cancellation with the packaged DLL.
The integration helper was updated to call the current vegetation-stage
constructor and selectStep API; it is not included in the game artifact.

## Artifact

`build/libs/vss-0.3-neoforge-1.21.1.jar`, 4,250,871 bytes.

SHA256: `088de398fc63b78de5ba8bfdb145b87eafacd57ef3e4acd0ab955bdb1802b4b1`

All five bundled targets were rebuilt from the same source: Windows x64,
Linux x64/ARM64 and macOS Intel/Apple Silicon. The native package audit verified
embedded hashes, architectures and 30 JNI exports per target. Linux targets
require glibc 2.28; macOS targets require macOS 11.0. Linux/macOS libraries were
cross-compiled and inspected, not executed on native target hardware.

No commit or push. The earlier uncommitted refinement-budget changes are retained.

Installed into the active NeoForge instance's mods directory; installed and
built JAR SHA256 values match. The old game JAR is backed up as
`build/reports/sparse-optimization-20260910/vss-before-sparse-optimization.jar`.
The game was not running at installation. Existing prediction caches remain
valid; no cache deletion or shader/mask change was performed. No post-install
live game loading-speed or frame-time measurement has been taken yet.

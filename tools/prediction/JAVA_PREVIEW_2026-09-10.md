# Java fallback sampling stages

Minecraft 1.21.1 NeoForge; version remains 0.3. No commits or pushes.

## Changes

- ClientTerrainSampler now has an explicit preview entry point. Noise-backed
  coarse/medium grids search raw final density every 16 vertical blocks and
  narrow a detected crossing to four blocks. Empty columns remain empty;
  approximate fluid uses the configured sea level/default fluid.
- Preview samples carry FLAG_APPROXIMATE. The shared manager rejects them when
  retaining columns for a full grid; disk serialization preserves the flag.
  Captured columns still take precedence. Preview sampling bypasses the exact
  Java sample cache, NoiseChunk column cache and decoration context.
- Minecraft surface rules still select materials. Preview workers are separate
  from exact workers; they use flat steepness and one deterministic 16x16-cell
  height anchor instead of exact neighbours/preliminary scans. Height and biome
  caches are bounded and separate from accurate sampling. Biome context and
  material rules are still approximate until refinement.
- MinecraftColumnTerrainSampler now invokes Minecraft's original column
  iterator with a state predicate and no output array. The predicate observes
  surface fluid and stops at the first solid. This preserves interpolation,
  aquifers and traversal order without allocating/evaluating the entire lower
  column. Reflection resolves the protected iterator once by signature.
- FreeTerraForged's Java path selects its point estimator for preview and full
  filtered terrain for refinement regardless of sample spacing. Color queries
  stay in the matching stage. Its old unmarked approximate full-grid caches
  receive a new identity; old directories are retained, not deleted.
- Fixed unsigned midpoint calculations in Java's precise height search: a
  negative interval must not jump to a large positive coordinate.
- Opaque custom generators keep their old sampling/deferral hooks. Shared
  medium-before-fine/vegetation scheduling, half-processor refinement admission,
  telescope refinement and adaptive memory budget already apply to Java.

## Validation

609 Java/compatibility/GPU tests: 608 passed, one optional live test skipped.
This includes the released Tectonic, Epic Terrain, Lithostitched, FreeTerraForged
and Voxy fixtures. New Java cases cover exact column heights and fluids against
full vanilla columns in all three dimensions; material rule evaluation without
populating exact column caches; disk flags; preview-to-exact grid convergence;
negative height/void/cancellation; and biome/color isolation between stages.

Initial full-suite failures exposed custom-generator hook bypass, which was
corrected. Other missing anonymous test classes required a clean test compile;
the subsequent complete suite passed. No workaround disables failed tests.

Cold VSS caches, warm JVM/world graph, 32 points spaced 64 blocks, seed 42:

| Round after warmup | Full vanilla column + exterior-height extraction | Exact early-stop height | Approximate height + materials |
| --- | --- | --- | --- |
| 1 | 46.510 ms | 45.766 ms | 9.993 ms |
| 2 | 46.939 ms | 36.723 ms | 10.401 ms |
| 3 | 41.794 ms | 38.792 ms | 10.726 ms |

These operations have different accuracy and output work; this is a sampling
stage comparison, not a claim of equal-output speedup or in-game loading/FPS.
Only full-column and early-stop height outputs are asserted equal. Before
simplifying preview surface neighbours, the approximate operation took 27-32 ms
in the same fixture. Concurrent desktop work and JVM warmup limit timing precision.

Final logs and the five-platform native package audit are in
`build/reports/java-preview-20260910/`. Native sources/binaries did not change in
this batch; the package retains the previously built ABI 3 libraries. No live
game run with Rust deliberately disabled was performed in this assessment.

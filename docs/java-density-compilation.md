# Java Density Graph Compilation (0.3.5)

The Java raw-density sampling path now specializes supported arithmetic into
JVM bytecode. HotSpot still supplies the machine-code JIT; this does not compile
all mods or replace Minecraft's JVM. Rust remains the preferred terrain backend.

Java biome routing also wraps the six climate roots through one independent
memo and deferred compilation owner. This covers Java decoration compatibility
even when Rust handles terrain. Biome sources and their mod hooks still receive
the same climate values and spawn targets; the live RandomState is not mutated.
The existing dependency analysis prevents horizontal reuse of vertical or
unknown roots, and stateful nodes retain their original evaluator.
`climateCompiler` reports this owner's state independently of `densityCompiler`.
Both owners are cancelled and closed when their sampler retires.

TerraBlender's deterministic X/Z region layers now keep 1,024 exact-coordinate
entries per layer per sampling thread. Each entry validates both full integer
coordinates, and collisions only trigger recomputation. For a region size of
three, the eight layers retain about 104 KiB of primitive arrays per thread.
They retain no biome, world, chunk or sampler references in thread-local values.
Native diagnostics additionally separate deliberate tree-model reuse, feature
order mismatch, unsupported definitions, transaction fallback, unavailable
proxies and unregistered features. Java calls with no writes and Java placement
elapsed time are reported separately from native feature calls.

## Runtime Behavior

- A decoded Java sampler creates a deferred compilation owner. Its first density
  query requests compilation on one low-priority daemon thread; the query uses
  the original memoized graph immediately. Unused Java contexts alongside Rust
  do not request compilation.
- The executor admits four waiting jobs. Retired samplers cancel their jobs and
  remove them from the queue. Busy samplers can retry after one second.
- All router roots switch through one volatile compiled evaluator. No partial
  graph is published. Unsupported layouts or linkage failures keep the original
  evaluator; failures do not repeatedly compile the graph.
- Constants, gradients, raw markers, holders, mapped operations, clamping,
  binary arithmetic and range branches are specialized. Noise, splines, unknown
  mod nodes and already-compiled opaque graphs retain their original calls.
  Stateful descendants keep their whole parent evaluator. Unproven custom
  min/max bounds are read at evaluation time rather than folded into the code.
- Generated and opaque paths share the existing per-thread memo arrays and
  query context. Exact height-cache eligibility is determined before wrapping
  the roots. Arithmetic order, signed zero, NaN payloads and lazy branches are
  preserved; neither sampling resolution nor surface rules change.
- The process-wide LRU stores up to eight code templates and 1 MiB of serialized
  bytecode. It retains constructors and structural keys, not worlds, noise
  instances, node arrays or memo slots. Machine-code/metaspace usage is separate
  from this bytecode budget. Code is rebound to each sampler's own objects.
- Generated calls target typed VSS bridge methods. Forge reobfuscates their
  Minecraft calls to SRG names; generated strings never hardcode Minecraft
  method names. Record access also resolves the exact backing field from record
  metadata when Forge renames an interface accessor independently; compilation
  and dependency analysis use the same reader. ASM is supplied by both loaders.

This is an in-process code cache. Generated machine code is not written to the
prediction disk cache, and it does not persist across JVM restarts. Existing
prediction caches and network protocols are unchanged. The optional JVM flag
`-Dvss.javaDensityCompiler=off` restores the previous Java density evaluator for
diagnostic comparisons. `densityCompiler` in prediction diagnostics reports
deferred, queued, compiling, ready, opaque, fallback or retired state.

## Production-Path A/B Results

Measured 2026-10-02 on a Ryzen 7 7700, Windows x86-64. Baseline and candidate use
independent samplers in the same JVM. The baseline disables compilation; the
candidate requests it through the real lazy sampler path. There are 44
alternating rounds per stage, with the first 24 discarded. Every round uses
disjoint coordinates, and every output is compared. Test JVMs use a 1 GiB heap.

| Scene / stage | NeoForge 1.21.1 / Java 21.0.6 | Reduction | Forge 1.20.1 / Java 17.0.12 | Reduction |
| --- | ---: | ---: | ---: | ---: |
| Overworld height | 102.669 -> 87.051 ms | 15.21% | 102.794 -> 85.220 ms | 17.10% |
| Overworld surface | 42.522 -> 36.327 ms | 14.57% | 34.713 -> 29.856 ms | 13.99% |
| End surface | 32.149 -> 29.697 ms | 7.63% | 42.169 -> 38.858 ms | 7.85% |
| Mountain height | 102.759 -> 84.454 ms | 17.81% | 113.476 -> 94.312 ms | 16.89% |
| Mountain surface | 26.121 -> 22.477 ms | 13.95% | 27.714 -> 25.258 ms | 8.86% |
| Server height | 201.288 -> 144.540 ms | 28.19% | 223.404 -> 152.818 ms | 31.60% |
| Server surface | 56.103 -> 43.168 ms | 23.06% | 72.616 -> 48.600 ms | 33.07% |

Height batches have 4,096 columns; surface batches have 1,024 complete
`sampleSurface` records. These are compute-stage results, not total LOD loading
times or live-game FPS. The mountain/server replays retain captured density and
noise but use vanilla material rules and a fixed plains biome. The server replay
includes 499 reachable density definitions and a test-only Tectonic reciprocal
node that stays opaque. This is not a full modpack compatibility test. Nether's
raw-density microbenchmark is not a measurement of its production volume route.

First graph generation/loading took 26.508 ms on NeoForge and 23.486 ms on Forge,
off the render thread. Template reuse does not eliminate per-sampler graph
analysis, and JVM machine-code warmup continues after publication. Measured
overworld surface allocations increased about 3.5%; the other listed surface
fixtures had unchanged allocation counts. Do not infer whole-game memory
reductions from this optimization.

Raw measurements live under `build/jit-production-20261002` in each repository.
The opt-in runner is `tools/prediction/java-density-compilation.gradle`;
`VSS_JIT_BENCH_OUTPUT` enables it. The earlier duplicate test compiler has been
removed: benchmarks and correctness tests now use the production compiler.

## Verification

`DensityGraphCompilerTest` covers raw-bit equality, vertical raw markers, lazy
branches, unknown/stateful nodes, dynamic bounds, cross-thread memo isolation,
template limits, deferred compilation, retirement and the real surface sampler.
`PackagedDensityCompilerTest` loads a release JAR in a separate class loader;
Forge additionally uses a real SRG Minecraft binary to verify generated code,
memo bindings and asynchronous publication after reobfuscation.

`tools/prediction/regression-tests.gradle` gives the complete registry/native
palette test fixtures a 2 GiB test heap. This changes test JVMs only. Warm-cache
tests explicitly flush queued commits before simulating a completed persistent
restart, and queued-capture tests bind the latest epoch when work starts.

The complete regression run passed 1,020 tests on NeoForge (48 conditional
skips) and 1,025 on Forge (47 conditional skips), with no failures. After the
final SRG record-access repair, each loader passed 25 relevant compiler,
dependency-analysis, memo-compatibility and snapshot cases, with two optional
mod-binary cases skipped. Each final release JAR also passed its isolated
packaged test: identical density bits, memo binding and asynchronous ready
publication. Forge used the real Minecraft 1.20.1 joined SRG binary on Java 17.
No live-game FPS validation with the new JARs was performed.

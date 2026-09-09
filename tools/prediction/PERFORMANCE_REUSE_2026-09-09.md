# Prediction sampling and generation-lock improvements

The Java/native boundary now uses cached sparse columns and complete chunk columns before scheduling new samples, including near grids. Near cache misses are counted by chunk within the current batch: dense groups of at least 16 columns use the existing complete-chunk path, while thin borders use the existing native sparse path. The Rust surface implementation retains its biome-specific fallback to complete chunks.

Previously, spacing below four always loaded every touched chunk in full. The regression case at (-1, -1), spacing one, 8 by 8 samples, now issues one complete chunk plus 15 sparse columns instead of four complete chunks. Height, fluid, flags and material IDs match complete-chunk results. Repeating the grid makes no further sampling submissions. Cached requests still honor sampler cancellation.

Generation-lock stripes now mix both packed coordinates. The previous Long.hashCode calculation reduced to x XOR z, so every x=z chunk contended for one stripe. Cached vegetation is read before taking a generation lock; a concurrency test holds that lock and verifies that an already cached result returns without waiting.

Existing debug diagnostics now include gridCacheHits, gridSubmittedPoints and fullChunkLoads. These count Java cache hits and native submissions, not internal native evaluations; native caches and biome fallback can change the actual work done.

## Validation

- Full Java/JNI/mod compatibility/GPU test invocation: 561 tests, zero failures, zero skipped.
- Log: perf-optimizations-full.log.
- Cold vanilla 8 by 8 border benchmark: one warm-up and five measured runs, fresh world handles each run, world construction excluded. Complete-chunk median 139.4211 ms; mixed-path median 44.4391 ms, about 68% less time. This is a local border microbenchmark, not a full-world or Tectonic loading-speed claim.
- Earlier focused run: 139.9747 ms versus 45.7661 ms.
- JAR: build/libs/vss-0.3-neoforge-1.21.1.jar, 4,132,537 bytes.
- SHA-256: a014842593df8eb5b3bef0b418320d7819a626a5cc668f274546dffc3267bb4b.

No native binary, worldgen algorithm, world data or game installation was changed in this iteration. First-time density/surface evaluation remains expensive and is not eliminated by cache reuse. Terrain-mod whole-route throughput still requires an in-game measurement.

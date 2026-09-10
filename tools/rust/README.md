# VSS source-built Rust worldgen core

Minecraft 1.21.1 / NeoForge, mod version `0.3-neoforge-1.21.1`.

The implementation follows the mapped Minecraft classes and effective runtime
registries. Offline oracles call the actual Minecraft methods. Saved-world
captures are supplementary regression data; they do not define the algorithm.

## Production integration

`ClientWorldgenProfileDecoder` selects `RustTerrainSampler` for supported noise
generators when the independently named `vss_native_core` library is available.
The worldgen interface is JNI ABI **3** (`RustWorldgenBackend`); the noise-only
probe keeps ABI 1 for validation. The release packages the same core for
**Windows x86_64, Linux x86_64/aarch64, macOS x86_64/aarch64**.
Unknown targets use the Java sampler. The only production native backend is this core.

ABI 3 adds `previewPoints`: grids below the full terrain axis use raw density
at 16-block vertical intervals and refine the detected crossing to 4 blocks.
Preview material selection runs the world surface rules with approximate depth
and no neighbour geometry. Aquifers, ores, structure blending and vegetation
are deferred to exact refinement. Flag bit 27 survives disk serialization;
exact grids reject these retained columns and resample them. Preview results
never enter exact native or Java point/chunk caches. Existing exact terrain
cache identity remains valid. The Java fallback uses the same explicit preview
stage and persisted approximation flag. It retains Java density/surface rules,
uses one stable preliminary-height anchor per surface cell, and omits preview
steepness scans. NoiseChunk-backed exact columns stop at the exterior through
Minecraft's original iterator. FreeTerraForged selects erosion by stage rather
than spacing and isolates approximate biome/material caches from exact work.

Cross compilation from Windows uses Rust target standard libraries and pinned
Zig 0.13.0 / cargo-zigbuild 0.23.4, through `build-cross-platform.ps1 -Package`.
Install the tools with `python -m pip install --target build/cross-tools ziglang==0.13.0 cargo-zigbuild==0.23.4`.
Install the four non-host targets with `rustup target add x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu x86_64-apple-darwin aarch64-apple-darwin`.
Linux targets require glibc 2.28 or newer (not musl); macOS targets require 11.0 or newer.
Zig provides system-library link stubs; cross compilation does not require a copied Xcode SDK.
macOS linking uses the Rust toolchain's `rust-lld` through `ld64-windows.cmd` /
`ld64-windows.py` (Python 3 required). The wrapper adapts Windows response-file
paths, preserves rustc's JNI-only export list, and keeps the macOS 11.0 deployment target.
The Apple Silicon artifact has a verified ad-hoc code signature; it is not notarized.
Only Windows has local JNI/game hardware verification. Cross-platform container,
architecture, JNI symbol and dependency checks do not establish game compatibility;
in particular the Voxy OpenGL 4.6 path remains unavailable on standard macOS drivers.

Audit a built JAR with `audit-native-package.py <jar> --output <report.json>`
(requires `lief==1.0.0`). It checks all five embedded/build-file hashes, machine
types, the source-declared JNI exports, dynamic dependencies, minimum system
versions and Apple Silicon signature page hashes.
The optional `-PvssNativeLive=true` on `verifyNativeWorldgenIntegration` runs a
60-second live tick/real JNI progression probe with a 2 GiB JVM and four build slots.
This headless probe does not measure game FPS.

The source-built sampler performs noise, density interpolation, base columns,
aquifers, surface rules, biome lookup and grass/foliage/water tint calculations
in Rust. Java transfers registry/BlockState/colormap data and owns Minecraft
models, textures and GPU resources. Near sampling uses compact complete surface
chunks; sparse distant queries preserve the surface pass's neighbour/order
semantics. Predecessor rules which can remove the top to air retain the full
chunk path; solid-to-water rules preserve WORLD_SURFACE_WG height and do not
force distant samples to generate complete chunks. LOD spacing does not select a different material algorithm.

`RustVegetationStage` runs supported placed features in native block storage,
using the original global feature order, seed derivation and modifiers. A
5x5-chunk read neighbourhood has a 3x3-chunk write region, matching the existing
prediction context. Previously placed Java structure edits are uploaded before
vegetation. Native edits are validated and drained after each feature, so Java
compatibility features see the same ordered mutations. Unsupported codecs retain
the Java compatibility path. A failed native feature rolls back blocks and both
random streams before a compatibility retry.

The runtime diagnostics include `backend=vanilla-rust-abi2-r3`, `nativeFeatures`
and `compatibilityFeatures` in `/vssclient stats`. Ordinary native
initialization/fallback detail is logged only when `debugLogging` is enabled.

## Implemented modules

| Module | Calculation |
| --- | --- |
| `random.rs`, `noise.rs`, `blended_noise.rs` | Legacy/Xoroshiro/WorldgenRandom, positional and decoration seeds, Gaussian, Improved/Simplex/Perlin/Normal/BlendedNoise |
| `density.rs`, `climate.rs` | Density graph, spline, interpolation/cache contexts, Climate RTree, biome source |
| `lithostitched.rs` | Lithostitched FastNoise Lite configuration; additional density operators are in `density.rs` |
| `freeterraforged_filters.rs` | Prediction-owned erosion, smoothing, steepness, beach detection and correction |
| `freeterraforged_noise.rs` | FTF Perlin/Perlin2, Simplex/Simplex2, white and basic composition modules; incomplete FTF generator port |
| `terrain.rs`, `beard.rs` | Base columns, aquifers, ore veinifier, structure terrain adjustment from explicit pieces/junctions |
| `surface.rs` | SurfaceRules, SurfaceSystem, badlands bands/erosion and frozen-ocean geometry |
| `biome.rs` | Grass/leaf/water colors, overrides, swamp/dark forest, temperature, BiomeManager zoom and blend primitive |
| `providers.rs`, `vegetation.rs`, `vegetation/` | State/int providers, placed modifiers, ordinary and large trees, mangroves/cherry, grass/flowers/bamboo, giant mushrooms, vines/cocoa/ground/attached-leaf/bee-nest decorators |
| `decoration.rs`, `backend.rs`, `jni_worldgen.rs` | FeatureSorter, ordered transactions, surface caches, batch buffers, leased handles, cancellation |

The production state export enumerates each block's possible states, including
leaf distance/waterlogging, bamboo age/leaves/stage and double-plant halves.
CherryFoliagePlacer's vanilla codec encodes one field with the wrong getter;
`VanillaFeatureSnapshot` preserves the actual runtime corner-hole probability.
Bee nests use an explicit independent shuffle entropy stream, as vanilla's
`Collections.shuffle` does not use the terrain seed. Prediction volumes do not
create live bee block entities.

Epic Terrain's Y-dependent `cache_2d` now uses native NoiseChunk slice/cell
evaluation with batch CacheOnce counters and eager FlatCache initialization.
Tectonic/Lithostitched installation alone no longer forces Java. Unknown native
codecs still select the existing compatibility path. FreeTerraForged's full
continent/river/climate generator is **not yet native**; see
`MOD_MIGRATION_2026-09-09.md` for exact scope and validation.

Tree placement also runs vanilla sapling survival and the ordered
`StructureTemplate.updateShapeAtEdge` pass. Runtime state data includes face
support masks; Rust updates vines, carpets, propagules, snowy ground, cocoa and
double plants. Unknown neighbour-update hooks request Java compatibility and
roll back the native transaction rather than leaving partially updated trees.

## Validation

The reproducible reports are `build/rust-worldgen-final-validation.log` and
`build/rust-worldgen-delivery-validation.log`:

- 97,440 exact random/noise checks, plus 56,014 noise dependency checks.
- 22,725 density values and 21,120 base-column blocks match the vanilla oracle.
- 3,030 climate/zoom and 15,360 biome-color/temperature comparisons.
- 42 surface cases (3,604,480 blocks) and 400 sparse/full-chunk comparisons.
- 261 configured vegetation cases, 171 placed-feature cases, four global orders,
  and nine bee-nest cases with controlled external entropy.
- 33,235 structure terrain adjustment values match bit for bit.
- Actual JNI buffer/handle/ownership checks and the production sampler bridge,
  including 560 grid/material/tint samples and seven mixed Java/Rust feature jobs
  (including actual savanna/taiga trees and dark-forest vegetation), cancellation,
  colormap replacement and a DLL loaded from packaged resources.

Gaussian uses a separately reported tolerance of four ULP for platform log/sqrt;
the observed Windows maximum is one ULP. The numerical checks do not establish
cross-platform bit identity or prove every fully generated world chunk matches.
The vegetation voxel count includes background cells; case counts are the useful
coverage metric.

The final Java/GPU build completed 516 tests with no failures, errors or skips;
the GPU regression ran on an NVIDIA GeForce RTX 4070 Ti SUPER. The existing
`PredictionVanillaMask` bytecode matches the pre-rewrite baseline.

The 2026-09-09 performance review uses all 26,684 runtime states and five
alternating before/after process pairs on the same production document. Reusing
interpolation cell corners and indexing immutable block metadata by state ID
reduced the cold 5x5 surface proxy median from 3392.70 to 1299.83 ms (61.7%).
Cold full base/surface chunks took 46.17–58.52 ms and 64 cold sparse points
took 100.83 ms after the change. Cached proxy rebuilds did not improve
(5.43 to 6.18 ms). All output checksums match. See
`PERFORMANCE_REVIEW_2026-09-09.md` and
`build/rust-performance-review/paired/results.json` for methods and limitations.
These are offline generation measurements, not in-game FPS. Shared state tables
and compact column caches avoid retaining full chunk volumes per job.

## Build and reproduce

Use JDK 21 and the project's Gradle 8.8 environment:

```text
cargo test --locked --release --manifest-path tools/rust/vss-native-core/Cargo.toml -- --nocapture
powershell -File tools/rust/build-vss-native.ps1 -Package
gradle --offline -I tools/rust/reference.gradle verifyNativeNoise verifyNativeWorldgen verifyNativeWorldgenIntegration
gradle --offline -I tools/prediction/gpu-tests.gradle build
```

Oracle regeneration tasks in `reference.gradle`: `captureVanillaKernels`,
`referenceVanillaNoiseDependencies`, `referenceVanillaWorldgen`,
`referenceVanillaSurface`, `referenceVanillaVegetation`, `referenceVanillaBeard`
and `referenceVanillaBeehive`. These invoke Minecraft classes directly.

For throughput, run `cargo run --locked --release --manifest-path
tools/rust/vss-native-core/Cargo.toml --example worldgen_bench`. This measures
native generation/cache access, not Minecraft frame time or GPU performance.

## Remaining integration boundaries

This backend is a **surface prediction** system, not a complete replacement for
all Minecraft chunk statuses. Complete structure starts/references/jigsaw,
carvers, every decoration stage and arbitrary mod hooks are not all ported.
Beardifier accepts explicit structure contexts, but the production sampler does
not yet construct those contexts from vanilla structure starts. Existing Java
village/path/farmland/air-cut/water edits remain active.

Tree correctness depends on the supplied terrain/neighbourhood context. Exact
feature placement in controlled native volumes does not imply complete agreement
with a final vanilla chunk after carvers and every other feature. Exceptional
HashMap tree-bin collision order and wider real-world matrices remain additional
coverage work. Unsupported native features report compatibility requirements;
they are not silently replaced with guessed trees.
Dark-forest and mushroom-island chains include native huge-mushroom generation;
they are tested as complete placed features, together with regular and flower
forests, so a supported tree does not silently fall back because of its mushroom
selector branch.

Native work uses shared immutable state tables, compiled feature caching, chunk
work deduplication, bounded result volumes and compact caches. Eight concurrent
vegetation volumes bound transient native memory; this does not change the
existing overall worker policy. Surface/vegetation disk caching and dirty-column
invalidation retain the save-local `.vss` / per-server layout, with an independent
algorithm key to keep results from different terrain implementations separate.

See [VANILLA_PIPELINE.md](VANILLA_PIPELINE.md) for semantic mapping and
[REWRITE_PLAN.md](REWRITE_PLAN.md) for acceptance boundaries.

The reference export now uses format `vss-rust-reference-2` and invokes Minecraft Java directly. Historical format-1 exports remain readable by the validation tools; binary replay of the retired backend is removed.

Tectonic 3.0.26 changes the positional RNG name of `tectonic:parameter/*` to
`minecraft:*` in `NoisesMixin`, while keeping the original octave parameters.
The server now exports these as `noise_seed_aliases` in each generator snapshot;
Rust consumes the aliases only during noise construction. A standalone datapack
without the Tectonic mod does not receive this remapping. See
`../prediction/TECTONIC_NOISE_SEEDS_2026-09-09.md` for live-server validation.

# FreeTerraForged prediction adapter — 2026-09-09

## Target

The user explicitly selected https://github.com/ETcodehome/FreeTerraForged.
The original racoonman2 repository and a different 1.21.1 port were read only
as preliminary references before the user supplied this target.

Supported/tested API: `reterraforged-0.0.6005-neoforge-1.21.1.jar` from
https://github.com/ETcodehome/FreeTerraForged/releases/tag/1.21.1_V0.0.6005.
Source checked out to tag commit `afbfbc439f07e2118c9f03f01abf1210bbd56deb`.
The development branch differs from the release in its standalone cell cache;
the compatibility tests use the actual release JAR, not assumptions from HEAD.

## Implementation

1. The server explicitly requests `reterraforged:worldgen/preset` and
   `reterraforged:worldgen/noise` when an active `reterraforged:preset` exists.
   These runtime dependencies are not fully discoverable from density holders.
   The existing custom registry closure serializes them with their own codecs.
2. Profiles carry `vss_freeterraforged` and force the Java backend. The client
   scopes `RTFWorldGenContext.IS_VANILLA_OVERWORLD` while constructing and
   initializing RandomState. It invokes `RTFRandomState.initialize` with the
   reconstructed registries, then restores the prior thread context even if
   initialization fails. Missing codecs/APIs fail explicitly.
3. Standalone `CellSampler.compute` in the release rounds coordinates to
   4-block cells. Full chunk generation reads the filtered tile at exact block
   positions. Prediction maps its density router's cell nodes to the latter
   field reads, retaining the mod's noise functions, seed and erosion output.
   Per-worker density caches hold numeric column values, not borrowed pooled
   tile references. Climate lookup retains the mod's climate implementation.
4. For coverage samples with spacing >=32 blocks, the adapter uses the mod's
   own point estimate and avoids forcing erosion tiles for every far sample.
   Finer samples use filtered tiles; existing distance, telescope, disk sample
   caching and worker priorities continue to select the work.
5. Surface-rule evaluation provides/restores the synthetic ActiveChunk expected
   by the mod's NoiseChunk mixin. River/lake/wetland water height uses
   `ContinentalHydrology.getComplexWaterHeight` and `Levels.scale`, converting
   inclusive water-block Y into the upper face used by VSS column metadata.
6. After VSS sampling workers terminate, disposal cancels the prediction tile
   cache's polling, clears its map and removes only that cache from the mod's
   global cache list. Upstream `Cache.close()` alone only stops polling. The
   adapter does not call the global CacheManager.clear or shut down mod pools.

The FreeTerraForged JAR remains an external mod dependency; it is not embedded
inside VSS. The existing five native libraries remain unchanged. This is a Java
mod adapter, not a Rust port of FreeTerraForged's erosion/river calculations.

## Validation

536 tests passed, zero failures, errors or skips, with GPU tests enabled and
actual FreeTerraForged, Lithostitched, Tectonic and Voxy release fixtures.

FreeTerraForged tests:

- Restore dimension and coarse-sampling thread contexts on success/failure.
- Decode/re-encode the actual default preset through runtime-only registry sync.
- Bootstrap the release's complete default noise registry using its own codecs
  and compare every decoded noise entry across multiple coordinates and seed.
- Execute the release MixinRandomState constructor redirect and initializer.
  A test proxy supplies its Mixin interface outside the game. Compare sampling
  from source and reconstructed registries using independent generator contexts.
- Generate actual filtered terrain tiles and compare exact cell heights with
  the prediction density adapter, including non-quart-aligned coordinates.
  The test initially detected the release's standalone 4-block rounding error;
  the final exact-cell path matches the real filtered tiles.
- Check dry cells, inclusive water height conversion and uplift river levels.
- Check release of a prediction cache removes exactly that cache from the
  global registry while preserving other registered caches.

The test invocation uses `tools/prediction/compat-tests.gradle` and the
`vssFreeTerraForgedJar`, `vssLithostitchedJar`, `vssTectonicJar`, `vssVoxyJar`
Gradle properties, plus `tools/prediction/gpu-tests.gradle`.

## Limits

The user's installed mods directory did not contain FreeTerraForged at the
start of this task. No user mods/configs/saves were modified and no complete
game session with this new artifact was run. Tests execute release generation
code, but do not validate the full live Mixin transformation, every preset,
resource pack or shader pack. Existing GPU regressions use the controlled
shader adapter rather than fully preprocessed Complementary.

VSS still estimates the outer solid surface from density and applies its
existing prediction decoration model. The full mod surface pass also performs
waterfall flowing-water placement, riverbank/gasket blocks and neighbor-column
edits; those are not fully replayed here. Precise replication of all final
block states is not claimed. Existing TerraBlender rejection remains in place
for unsupported positional biome regions. Combining Tectonic and
FreeTerraForged in one world is not established by testing each adapter.

FreeTerraForged's internal tile-generation tasks and other mod caches remain
owned by that mod; the VSS worker limit does not establish a global CPU/heap
limit for those internal pools.

## Artifact

`build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,890,511 bytes.

SHA256: `68fbe854c937a385e54edbcbcaf19e0060cafe280214090a202d3ec7fb94c7c5`.

All five Windows/Linux/macOS native entries were verified byte-for-byte against
the existing resource files. Version remains `0.3-neoforge-1.21.1`.

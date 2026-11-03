# FreeTerraForged Compatibility

[文档目录](../README.md)

The adapter targets https://github.com/ETcodehome/FreeTerraForged.

## Verified Releases

- `freeterraforged-1.0.0-neoforge-1.21.1.jar`, tag `1.21.1_v1.0.0`,
  source commit `dfa4368`.
- `reterraforged-0.0.6005-neoforge-1.21.1.jar`, tag `1.21.1_V0.0.6005`.
- `ftf-unstable-1.0.0015+mc1.21.1-neoforge.jar` through
  `ftf-unstable-1.0.0023+mc1.21.1-neoforge.jar`; all nine builds are checked individually.

The upstream releases page groups the stable `1.0.0` release and rolling
`1.21.1_unstable-latest` release separately. The latter retains multiple numbered
JARs as assets; these are different builds within one release entry. Compatibility
checks use the actual numbered NeoForge JARs rather than assuming that the rolling
tag's source matches every asset.

These are tested in isolated JVMs against the actual release JARs. Other releases,
ReTerraForged forks and combinations of terrain mods are not covered by this
version claim. The shared adapter is also synchronized into VSS Forge 1.20.1;
these upstream NeoForge 1.21.1 artifacts cannot run on Forge 1.20.1.

## Runtime Path

The server detects the active preset in the matching `freeterraforged` or
`reterraforged` namespace. It includes that namespace's preset and noise
registries in the worldgen profile. Tile-based releases retain their dimension
ThreadLocal and registry-based RandomState initialization. Epoch-based releases
reconstruct the released TerraForgedChunkGenerator, compile its worldgen plan and
bind RandomState before prediction begins. The snapshot includes the server's
resource/tag revisions and, in 1.0.0016–1.0.0023, biome-replacement configuration
content. 1.0.0015 uses its earlier epoch
API without that configuration record.
Client-local replacement files do not replace the server's snapshot.
No extra initialization handshake is introduced.

Detailed density sampling reads filtered cells at exact block coordinates.
Coverage previews retain the upstream point estimate and avoid generating
filtered tiles for every distant sample. Surface rules retain the upstream
ActiveChunk context. River, lake and wetland heights use the released hydrology
function and convert inclusive water blocks to VSS upper-face coordinates.
Optimization of the climate sampler preserves 1.0.0's underground biome preset,
full world seed, terrain context and spawn-search center.

The nine unstable builds read their own FilteredChunkCache through the released
padded-cell lookup and finite field reader, including negative coordinates and
chunk/region boundaries. They retain upstream region filtering and cache reuse.
Their TerrainFilters algorithm stays in Java; the older WorldFilters Rust hook
is not applied to it. Optimized climate samplers retain the worldgen plan, query
policy, full seed, surface context and spawn-search state, with independent query
caches. Closing prediction closes only its owned generator/context.

Rust receives only tiles owned by the prediction context. Live server generators
remain unregistered. The filter bridge preserves the version-specific pipeline:

| Stage | 0.0.6005 | 1.0.0 |
| --- | --- | --- |
| Erosion and smoothing | Optional | Optional |
| Steepness and beach detection | Required | Required |
| Quart beach/noise correction | With optional filters | Absent |
| Tall mountain ceiling compression | Absent | Matches the preset's filter |
| Height normalization | Legacy Levels | `terrainScaleFactor`, independent of world height |
| Seed conversion | Legacy integer root | Released `Seed.toInt(long)` |

The native noise/density operators accept both namespaces and resolve references
from the corresponding noise registry. Unknown operators, including `cell`,
still require the Java generator context. This is not a complete Rust port of
the continent, river-network or climate generator.

Disconnect cleanup closes and removes only prediction-owned tile caches. It
does not clear the upstream mod's global caches or shut down its worker pools.

## Validation

`FreeTerraForgedCompatTest` replays the actual RandomState constructor redirect
and initializer, round-trips the real preset/noise registries, checks filtered
cell heights, verifies hydrology and cache ownership, and checks climate context
transfer against the released interface.

`FreeTerraForgedNativeNoiseTest` checks the two tile-based releases and compares released noise and density operators
against JNI at 512 coordinates, including negative coordinates and full-width
world seeds. `FreeTerraForgedNativeFiltersTest` compares cell fields and terrain
types without tolerance across tile sizes, seeds and optional-filter modes.
The 1.0.0 filter fixtures use world heights 128, 384 and 512, including terrain
above the compression band.

For each unstable JAR, `FreeTerraForgedRuntimeTest` is called by the compatibility
test. It runs the released epoch/compiler and RandomState terrain initializer,
round-trips the actual preset/noise graph, compares filtered-cell density reads,
checks that previews do not allocate filtered regions, verifies water elevation,
checks climate-context transfer and verifies cache release. Where available, it
also compiles a server-provided plains-to-desert replacement. The offline fixture
supplies the Mixin interface binding outside a running game.

An additional `FreeTerraForgedClimateMixinTest` applies the actual released Sponge
climate Mixin from 1.0.0020–1.0.0023 before invoking VSS's context bridge. It covers
non-null setter contracts, missing source state, preserving existing destination
state, the full seed, climate policy and independently owned caches. Missing
spawn-search state restores FTF's own `WORLD_ORIGIN` / `BlockPos.ZERO` default;
an existing source search is copied unchanged. This fixes prediction initialization
failing with `NullPointerException: spawnSearch`. See the
[repair record](freeterraforged-spawn-context-20261102.md).

Server snapshot tests cover both namespaces, absent presets and missing runtime
dependencies. Java sampler/compiler regressions run on both VSS loaders. The
five bundled Windows, Linux and macOS native libraries retain their previously
verified build; the spawn-search repair changes only the Java bridge.

These tests do not replace a full client session with every Mixin, preset,
resource pack or shader. Waterfall flow, bank blocks and neighbor-column edits
in the mod's full surface postprocessing are not reproduced block for block.
Existing Voxy columns continue to take precedence over predicted terrain.
Explicit in-memory biome source plans without a serializable source and mismatched
client/server provider contributions are outside this adapter's snapshot contract.

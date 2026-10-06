# FreeTerraForged Compatibility

The adapter targets https://github.com/ETcodehome/FreeTerraForged.

## Verified Releases

- `freeterraforged-1.0.0-neoforge-1.21.1.jar`, tag `1.21.1_v1.0.0`,
  source commit `dfa4368`.
- `reterraforged-0.0.6005-neoforge-1.21.1.jar`, tag `1.21.1_V0.0.6005`.

Both are tested in isolated JVMs against the actual release JARs. Other releases,
ReTerraForged forks and combinations of terrain mods are not covered by this
version claim. The shared adapter is also synchronized into VSS Forge 1.20.1;
these upstream NeoForge 1.21.1 artifacts cannot run on Forge 1.20.1.

## Runtime Path

The server detects the active preset in the matching `freeterraforged` or
`reterraforged` namespace. It includes that namespace's preset and noise
registries in the worldgen profile. The client scopes the matching dimension
ThreadLocal and initializes the released RandomState API with those registries.
No extra initialization handshake or network delay is introduced.

Detailed density sampling reads filtered cells at exact block coordinates.
Coverage previews retain the upstream point estimate and avoid generating
filtered tiles for every distant sample. Surface rules retain the upstream
ActiveChunk context. River, lake and wetland heights use the released hydrology
function and convert inclusive water blocks to VSS upper-face coordinates.
Optimization of the climate sampler preserves 1.0.0's underground biome preset,
full world seed, terrain context and spawn-search center.

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

`FreeTerraForgedNativeNoiseTest` compares released noise and density operators
against JNI at 512 coordinates, including negative coordinates and full-width
world seeds. `FreeTerraForgedNativeFiltersTest` compares cell fields and terrain
types without tolerance across tile sizes, seeds and optional-filter modes.
The 1.0.0 filter fixtures use world heights 128, 384 and 512, including terrain
above the compression band.

Server snapshot tests cover both namespaces, absent presets and missing runtime
dependencies. Java sampler/compiler regressions run on both VSS loaders. The
five bundled Windows, Linux and macOS native libraries are rebuilt from source.

These tests do not replace a full client session with every Mixin, preset,
resource pack or shader. Waterfall flow, bank blocks and neighbor-column edits
in the mod's full surface postprocessing are not reproduced block for block.
Existing Voxy columns continue to take precedence over predicted terrain.

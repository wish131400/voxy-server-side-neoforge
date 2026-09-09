# Epic Terrain and terrain-mod version boundaries — 2026-09-09

## Identity and release evidence

The user identified https://www.mcmod.cn/class/15808.html, whose title is
`[ETN]史诗地形 (Epic Terrain)`. Canonical source:
https://github.com/wonderfulaichen/Epic-Terrain.

The tests load actual release archives, not repository HEAD:

- Epic Terrain, Modrinth `7qSJGSHp`, version `fR0foOLP`, displayed as
  `v0.1.4b-Beta-1.20.5~1.21.1`, version number `0.1.4+mod`.
  File `epicterrain-0.1.4.jar`, SHA1 `a71fa3df06fb56441e1d9b443a4b4245056a7dfa`.
  https://modrinth.com/mod/epicterrain/version/fR0foOLP
- Companion Compatible edition, Modrinth `sc69VpnK`, version `6WRQyW1o`,
  `v1.0.3 Release 1.20.5~1.21.3`, version number `1.0.3+mod`.
  File `epic-terrain_compatible-1.0.3.jar`, SHA1 `3c32f62b97233d06d5f1a1e242624cc459af4f8c`.
  https://modrinth.com/mod/epic-terrain_compatible/version/6WRQyW1o
  Both archives explicitly list NeoForge. The separate newer
  `1.0.3b-1.21.1` mod entry lists Forge only and is not claimed here.

The ordinary edition declares dimension minY -64 / height 576 and noise
height 512; Compatible uses noise height 576. VSS retains those values and
the ordinary edition's explicit multi-noise biome parameter list, including
its `etn:zenith_lake` biome. Custom entry names use vanilla codecs and do
not by themselves require Java fallback.

## Findings and changes

1. Compatible's `etn:overworld/r/1a` spline has locations `[-1, 0, 0, 1.3]`.
   Minecraft 1.21.1 `CubicSpline.codec` only checks nonempty/equal-length
   arrays. Its programmatic Builder enforces increasing locations, but the
   datapack codec does not. Rust incorrectly applied the Builder restriction
   and rejected the complete graph. Rust now preserves the input order and
   reproduces `CubicSpline.findIntervalStart` / `Mth.binarySearch`. Sorting
   or merging these points would change the generated terrain.
2. After resolving that rejection, the real Compatible archive exposed a
   base-column mismatch at x=20480, y=-56, z=-7168 (Java stone vs native lava).
   Raw density sampling agreed. Its `etn:overworld/factor/jungle/1` puts
   Y-dependent `old_blended_noise` under `cache_2d`, also feeding
   `etn:overworld/m/em` and the flat-cached offset. Such graphs depend on
   Minecraft NoiseChunk prefill and last-column evaluation order. VSS's
   persistent XZ cache is not an exact implementation of that stateful case.
3. Native graph construction now checks Y-dependencies in the compiled DAG
   once and rejects Y-dependent `cache_2d` with a specific reason. The
   existing decoder retains its Minecraft Java sampler when native opening
   rejects a graph. This is capability detection, not a mod-name blacklist.
   Both tested editions contain this dependency, so both use Java. Ordinary
   Epic Terrain matched the initial finite native column samples, but that
   does not establish correctness for this stateful graph in other columns.
   Neither edition is claimed to have native performance parity.
4. `MinecraftColumnTerrainSampler` obtains actual base columns through
   Minecraft `NoiseBasedChunkGenerator.getBaseColumn` / `NoiseChunk`, then
   publishes the first solid upper surface and its overlying fluid. The
   existing general Java fallback estimated the surface directly from raw
   density; simply selecting that fallback would not resolve cache-order
   semantics. The ETN adapter is selected during Java profile construction,
   including on platforms without a native library. Other graphs rejected
   for the same native cache limitation receive this adapter as well.
   At most 4096 compact floor/fluid records are retained per sampler;
   generated block arrays are not kept. Disk-cache keys already include
   sampler class, separating these results from previous estimated columns.

No sampling-worker budget, scheduling, shaders, depth masks, game configs,
user mods or saves were changed. Runtime diagnostics remain debug-only.
All five native targets must be rebuilt together for this release.

## Regression coverage

`EpicTerrainCompatTest` overlays released registry entries onto full vanilla
registry data, supplies real vanilla block tags in the headless fixture,
decodes with the production client registry/profile path and loads the actual
packaged Windows native library. Missing block tags initially prevented
the headless fixture from decoding optional registries; that was a test
setup issue, not a reason to change production registries.

For seeds 0, -918273645 and 123456789:

- Both editions: require the explicit native capability rejection, then
  compare decoded Java base columns with an independently constructed
  Minecraft generator at six coordinates, including air, fluids, extended
  height and the coordinate that exposed the native mismatch. These are
  full columns, not just a surface-height acceptance threshold.
  Also compare the adapter's actual published `surfaceY` and `groundY` with
  the first solid block of those authoritative columns.
- In a separate isolated router, compare duplicate and unordered spline
  evaluation directly to the Minecraft codec at and around knots, including
  extrapolation. The isolated graph is a test fixture, never a fallback
  terrain definition used in production.
- Rust regression tests retain the duplicate-knot discontinuity and verify
  that Y-dependent column caches require Java context.

The fixtures are local under `build/compat-fixtures` and are not embedded
in the distributable JAR. Supply them with
`-I tools/prediction/compat-tests.gradle`
`-PvssEpicTerrainJars=build/compat-fixtures/epicterrain-0.1.4.jar;build/compat-fixtures/epic-terrain_compatible-1.0.3.jar`.
Quote that Gradle property in PowerShell because it contains a semicolon.

Tests cover registry reconstruction, numerical terrain and backend choice;
they do not certify all decorated structures, resource packs, full live
Mixin transformation, or a complete shader/modpack game session.

## Other terrain-mod version boundaries

VSS's artifact is for Minecraft **1.21.1 / NeoForge** only.

- Tectonic: verified `3.0.26-neoforge-21.1` with Lithostitched
  `1.8.0+beta6-neoforge-21.1`. No continuous older-version support range
  is claimed. Upstream Modrinth releases span multiple game versions from
  1.18.2 through 26.2, with distinct downloads; this does not broaden VSS's
  own Minecraft version support.
  https://modrinth.com/mod/tectonic/versions
- FreeTerraForged: verified `0.0.6005-neoforge-1.21.1`.
  Downloaded release JARs for `0.0.6001`, `0.0.6002`, `0.0.6003R2` and
  `0.0.6004R1` lack `raccoonman.reterraforged.world.worldgen.RTFWorldGenContext`,
  required by the current adapter; their retained `RTFRandomState.initialize`
  method alone is insufficient. They are not supported by this adapter.
  https://github.com/ETcodehome/FreeTerraForged/releases

Each mod's individual adapter does not establish compatibility of multiple
overworld generator replacements installed together. Existing rejection of
unsupported TerraBlender positional regions remains unchanged.

## Final validation and artifact

- 538 Java tests passed, zero failures/errors/skips, including actual mod
  fixtures and the controlled Voxy/Iris GPU regressions.
- 24 Rust tests passed, zero failures/ignored tests.
- All five native targets were rebuilt and audited against the embedded JAR:
  Windows x86_64, Linux x86_64/aarch64, macOS x86_64/aarch64. Each exports
  all 29 current JNI functions. Linux symbol versions remain within glibc
  2.28; macOS deployment minimum remains 11.0. Cross-built Linux/macOS
  artifacts were inspected but not executed on their respective hardware.
- Package audit: `build/reports/epicterrain-native-package.json`.
- Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,897,750 bytes.
- SHA256: `8dcf302f140e26e8f9cf28fb67b6346fca3e26c6967761da013b646434560c0e`.

The native library changes do not make ETN's stateful column caches run in
Rust. The explicit Minecraft column adapter prioritizes reproducibility;
initial generation can cost more than the fully native vanilla path.
Existing nearest-first scheduling and persistent prediction caching remain
in effect. No game installation files were replaced by this task.

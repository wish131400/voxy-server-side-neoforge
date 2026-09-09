# Lithostitched registry dependency and seed synchronization

## Runtime evidence

The 12:54 session with Tectonic 3.0.26 and Lithostitched 1.8.0+beta6
successfully built and received a profile, then failed while reconstructing
`lithostitched:fast_noise` because the client had no
`lithostitched:fast_noise_config` registry. Prediction was disabled before tiles
could be built. The installed VSS artifact at that point had SHA256
`cdf9c848e119a7f2c06a0eec0a1b49152eece2952d25858d5872e0665335999b`.

While working, latest.log was replaced by a 13:09 session without
Tectonic/Lithostitched. That session built the first prediction tile at
13:09:09, enabled Complementary Unbound r5.8.1 at 13:09:47, and created Voxy's
Iris pipeline. It reports missing Iris uniforms (including endFlashIntensity)
but the excerpt does not establish that those warnings caused invisible LOD.
This second session still used the previous artifact; it is not validation of
this fix or of correct rendered output under shaders.

## Upstream implementation checked

Repository: https://github.com/Apollounknowndev/lithostitched

Inspected cloche commit: `cd5482eeb09d8bc7a1db33a3fa1df057db40160b`.

- `src/neoforge/21.1/main/java/dev/worldgen/lithostitched/LithostitchedNeoforge.java`:
  registers dynamic registries without a network codec.
- `src/shared/21.1/main/java/dev/worldgen/lithostitched/impl/worldgen/densityfunction/FastNoiseDensityFunction.java`:
  encodes the config as a registry holder; sampling reads the referenced config.
- `src/common/main/java/dev/worldgen/lithostitched/impl/LithostitchedInternalHooks.java`:
  binds every FastNoise config to the server world seed at server start.
- `src/common/main/java/dev/worldgen/lithostitched/api/worldgen/densityfunction/fastnoise/FastNoiseConfig.java`:
  `bind(long)` initializes FNL with `(int) seed + salt`.

Installed 1.8.0+beta6 classes and bundled configuration resources were also
loaded directly in the compatibility test, independently of the upstream HEAD.

## Changes

- Track custom data registries requested by worldgen codecs while encoding
  generators and registry snapshots. Include their entries and transitive
  registry dependencies using the NeoForge registered element codecs.
- Declare all received custom registry lookups before decoding their values
  or density functions, preserving forward holder references. Unknown client
  codecs fail with a specific registry name. Unreferenced modifier registries
  are not included in the snapshot.
- Bind newly decoded Lithostitched FastNoise objects to the payload's world
  seed before opening samplers. Only isolated snapshot values are changed;
  no server startup modifiers are replayed and no fallback registries mutated.
- Report profile receipt as receipt, not successful installation. Report
  sampler initialization once per decoded profile. Report each Iris callback
  pass once per pipeline after it returns, including whether its program exists.
  These events do not prove visible pixels; ongoing diagnostics remain debug.
- Include registry and entry identity in reconstruction failures.

## Verification

534 tests passed, zero failures, errors or skips. Full run includes GPU tests
and actual installed Voxy 0.2.15-beta, Tectonic 3.0.26 and Lithostitched
1.8.0+beta6 compatibility checks.

New regression tests reproduce the missing registry, restore transitive and
forward references, reject missing codecs, and exclude unreferenced registries.
The installed FastNoise dispatcher, density class and all three shipped region
configs round-trip through the new dependency snapshot. Decoded samples match
the seeded source values exactly. The test also detects the wrong unbound seed
and checks that binding a second snapshot does not change the first snapshot.

GPU coverage retains the previous shader/depth/state regression checks. It
uses a controlled fragment adapter, not the complete preprocessed Complementary
pack. The user's full game has not run with this artifact during this turn;
visual shader compatibility and full Tectonic terrain output remain unverified.

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,872,612 bytes.

SHA256: `65c30c750f6897e2d2193a1fcabc38195a944deac6c48376bd6052ebb3830fdd`.

Five native libraries retained (Windows x86_64, Linux x86_64/aarch64, macOS
x86_64/aarch64), individually verified against the existing resource files.
No user mods, shaderpacks, configuration or saves were changed.

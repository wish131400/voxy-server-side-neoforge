# Prediction snow cover — 2026-09-09

## Observations

The supplied screenshot shows white nearby snow terrain meeting green
distant ground. The available game log at 14:11:33 reports:
`VSS prediction samplers ready: dimensions=3, rust=0, javaOrCustom=3`.
The installed VSS SHA256 is
`68fbe854c937a385e54edbcbcaf19e0060cafe280214090a202d3ec7fb94c7c5`,
the earlier FreeTerraForged build, not the subsequent ETN adapter build.

Enabled files include FreeTerraForged 0.0.6005 and Lithostitched
1.8.0+beta6. Tectonic 3.0.26 has a `.disabled` extension. Epic Terrain
was not present in that mods directory. Installed mod presence alone does
not establish which generator/preset this specific save selected.

## Confirmed defect

Java surface sampling recorded `FLAG_SNOW`, but `ClientSurfaceResolver`
correctly resolved the underlying Minecraft SurfaceRules block to grass.
`PredictionMaterialPalette.surfaceBlock` then selected that grass block
without applying the separately recorded snow cover. Hence coarse terrain
and terrain without completed decoration stayed green even when the sample
already indicated snow. Captured ground with a snow layer had the same
material-selection issue.

Minecraft adds snow later in `SnowAndFreezeFeature`; it is not equivalent
to replacing the SurfaceRules ground block or the whole cliff with snow.

## Change

- Resolve a dry, existing, snow-marked terrain up face through the original
  snow-block texture, independent of feature/vegetation completion.
- Keep the original ground block and under/deep strata for side faces.
- Check snow support using Minecraft block tags and the solid upper-face
  collision shape. Water-covered ground, missing surfaces and unsupported
  blocks do not receive this up-face override.
- Recompute Java snow/ice flags from the resolved surface biome, height and
  material instead of only removing flags from an earlier biome estimate.
  As in `Biome.shouldSnow`, temperature is the weather criterion; a separate
  precipitation flag is not a substitute for that method's behavior.
- Do not clear the user's caches. Existing cached samples that already carry
  snow flags benefit when their meshes are rebuilt. This change does not
  add a new sampling pass or require waiting for distant decoration.

The surface mesh represents the snow appearance at the column top. Exact
layer thickness, canopy shelter, block lighting and mod-specific snow
erosion remain matters for the detailed feature/real-block path; this is
not a claim that all snow-layer placements match final generated chunks.

## Backend status

For terrain/noise generation (distinct from native mesh or other utilities):

| Generator/configuration | Current route |
| --- | --- |
| Vanilla supported worldgen graphs | Rust when available and not forced to Java |
| Tectonic / Lithostitched integration | Java applied graph and custom registry path |
| FreeTerraForged 0.0.6005 | Java, calling the mod's own erosion/hydrology code |
| Epic Terrain 0.1.4 and Compatible 1.0.3 | Java NoiseChunk column adapter for stateful caches |
| BetterEnd New Dawn | Java PAULEVS compatibility adapter |
| Other datapacks using supported vanilla codecs | Capability-dependent; not an unconditional mod compatibility claim |

Currently `WorldgenCodecSnapshot.requiresAppliedDensityGraph` triggers when
Tectonic **or Lithostitched is installed**, then attaches `vss_force_java`
to noise generator snapshots. Thus leaving Lithostitched enabled also sends
otherwise vanilla dimensions through Java. This explains the recorded
`rust=0`; this task does not silently change that compatibility policy.

## Validation

`PredictionSnowCoverTest` checks snow texture selection, captured snow,
unchanged ground strata, underwater/warm/void/unsupported exclusions, steps
1/2/4/8/16/32/64 before decoration, and real vanilla surface-rule evaluation
for snowy plains, plains and desert. Existing ice/cliff regressions also run.
Full game screenshot comparison with the new JAR remains unperformed.

Final full suite: 542 tests, zero failures/errors/skips. Native package
audit passed for all five existing native targets; their binaries were
not changed by the snow-material fix.

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,898,325 bytes.
SHA256: `72d3c40cba3965ae01bfb673e44e09a43dfb4221a3da12487fac51dd8a3a3a18`.
No user game JARs, configs, saves or caches were replaced or removed.

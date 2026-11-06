# Prediction exterior seams and NBT tree templates

Build: `prediction-seams-trees-compat-4`, Minecraft 1.21.1 NeoForge / Java 21.
Includes the previous Tectonic, Lithostitched and Rust surface-adapter fixes.
This batch changes Java prediction only; the five native libraries are byte-identical
to `tectonic-lithostitched-rust-compat-3`. Source changes remain local, without a new PR push.
Forge 1.20.1 is outside this build's requested and verified scope.

## Reproduced faults

A local exterior occupancy column is measured at a particular horizontal footprint.
A parent reduction can retain that column while increasing the cell spacing.
`PredictionWallEvidence` already rejects the air evidence at larger spacings,
but `PredictionVoxySource.terrain` used its volume unconditionally. A cave
measured in one narrow column could therefore be expanded across an unmeasured
coarse cell, exposing sky through the coarse terrain.

Honor that same footprint check in the voxel writer. Keep local air at the
measured spacing or finer; use the closed heightfield at coarser spacing.
Do not interpret the discarded local roof/lower spans as a floating island.
Complete interior volumes and explicit non-profiled floating terrain keep their
actual air gaps at every LOD. No blanket filling of full underground geometry.

Distant appearance captures configured features into a flat `FeatureStampLevel`.
OTTYG's NBT trees obtain templates with `WorldGenLevel.getLevel().getStructureManager()`.
The existing Mixin safely redirects that exact chain for `PredictionDecorationLevel`,
but did not recognize the flat capture level. Template attempts then fail at
`getLevel()` while structures can still use their own supported prediction level.

Add a worker-owned `PredictionTemplateStampLevel` with the same verified template
catalog. Recognize it in the existing stateless template adapter. Keep access to
actual ServerLevel/getServer unsupported, retain cancellation and write bounds,
and leave the real-world Mixin forwarding path unchanged.

A new settings revision rejects old cached decoration and finished voxels,
including empty tree results and already stretched exterior air. Raw terrain
grid caches and the voxel file schema remain unchanged. Players do not need to
manually remove their cache or world.

These faults are reproducible in code. Screenshots alone cannot establish every
cause of missing trees or cracks; the same-world graphics result still needs a game test.

Tree visibility is not exact tree-generation parity. Reusable vanilla tree models
select one of eight position-derived variants captured on flat ground; the real
world's random stream, terrain-dependent checks and later feature interactions can
change the tree shape or even whether a tree survives at a given position. The
placement stage still visits biome features, but positions may also differ when
predicted terrain, biome routing or modded placement rules diverge. Compare the
same seed, coordinates and view after prediction finishes before attributing a
specific offset to this patch. No one-to-one tree-position guarantee is claimed.

## Validation

The `predictionSeamsTreesTest` suite covers:

- Local air valid at spacings 1/2/4 is retained only within its measured footprint;
  unmeasured coarse spacings close the heightfield, preserving the cap material.
- Full interior columns, cave gaps, floating spans and fluids remain intact.
- NBT template redirection recognizes the new flat capture environment and forwards
  ordinary world calls; both released OTTYG 5.3.2 feature versions have the exact
  invocation chain targeted by the existing Mixin.
- The distant capture path generates three fixture variants containing both wood
  and leaves while accessing the verified manager; it does not expose a live server.
- Existing Voxy ownership/publication, far decoration, parent LOD and voxel-cache
  regressions remain enabled.

The released-JAR test inspects bytecode and the adapter tests invoke redirect
handlers directly; neither is a complete Minecraft/Mixin/GPU launch. The controlled
capture fixture is not an OTTYG full-tree placement oracle. Windows gameplay and
this complete modpack were not run. Exact per-suite totals are in the test bundle's
`verification/results.json`, with successful build/test logs.

With JDK 21 and the released OTTYG JAR:

```sh
./gradlew -I tools/verification/prediction-seams-trees.gradle predictionSeamsTreesTest \
  -PvssNbtTreeJar=/absolute/path/to/Oh-The-Trees-Youll-Grow-neoforge-1.21.1-5.3.2.jar
./gradlew verifyModules planningConsumerTest
./gradlew jar -PvssBuildId=prediction-seams-trees-compat-4
```

For a live check, replace VSS, keep the same world and view, let prediction
rebuild, then compare the previously cracked slopes and treeless forest. Confirm
`rust=true` in the overworld readiness log. If trees remain absent, collect a
fresh full log and performance snapshot so unsupported placements and template
capture counters can distinguish a new failure from unfinished work.

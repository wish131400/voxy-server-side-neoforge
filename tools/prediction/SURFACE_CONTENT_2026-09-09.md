# Coarse canopy and missing surface content

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,860,895 bytes.
SHA-256: `b01638904203755cead485f953e8bff284485cc8355b6e0eadb7e31a73eff444`.

## Demonstrated defects

- Tree reduction selected logs ahead of leaves regardless of how many leaves
  occupied the voxel. Birch bark consequently covered leaf-dominated canopy
  cells. Reduction now counts the original blocks at each requested size, groups
  states by block material, and uses deterministic tie-breaking. Ground edits
  retain their exact position and cannot be overwritten by a snapped canopy.
- Decoration ran only VEGETAL_DECORATION. Surface generation now selects actual
  RAW_GENERATION, LAKES, LOCAL_MODIFICATIONS, SURFACE_STRUCTURES, vegetation and
  TOP_LAYER_MODIFICATION stages, plus DISK features in UNDERGROUND_ORES and Nether
  UNDERGROUND_DECORATION. Global feature indices are preserved. Ordinary ore veins
  remain excluded. The existing Rust bridge is used wherever it supports the job;
  disabled stages do not initialize a native stage.
- Vanilla LakeFeature calls real-chunk post-processing. Its adapter uses the
  original geometry and material algorithm while omitting post-processing markers.
  Sampled fluid meshes no longer require a configured ocean sea level.
- A fortress/bastion set was rejected because every member had to be in
  SURFACE_STRUCTURES. Nether underground-decoration structures are now eligible,
  templates are not required for procedural structures, and each structure uses
  its own stage and registry index within that stage for seeding.
- End spikes are SURFACE_STRUCTURES features. Their vanilla block geometry is
  adapted without creating a server entity. End-city entity data markers likewise
  no longer abort the entire building transaction. Chest markers remain intact.
- Surface schema 3 invalidates old incomplete/empty surface results. Terrain
  schemas and native ABI are unchanged. No manual cache deletion is required.

## Verification and limits

525 Java/GPU tests passed, including coarse birch canopies with mixed leaf states,
negative coordinates, majority determinism, exact sand edits, stage ordering,
actual vanilla lava lakes, fluids without sea level, vanilla spike/cage block
comparison, dimension structure eligibility and end-city entity markers.
Production bridge passed 1,064 grid/material/tint samples and seven mixed native
vegetation jobs. Package inspection verifies all five native binaries and unchanged
vanilla mask bytecode. Game-world screenshots are not yet verified.

The 60-second real Rust/JNI scheduling probe passed with four workers, a 2 GiB
Java heap and a 65,536-block horizon. At 55 seconds it had 86 full terrain tiles
and 82 decorated tiles, with zero tile-build failures. Individual unsupported
feature transactions were still skipped (1,301 cumulative skips at that sample);
zero tile failures does not mean all features were generated. This is a progress
check under a fixed plains profile, not an in-game FPS or Nether validation.

End crystals, their animation/beams and other entities are NOT implemented in
the block mesh. Nether sampling still uses a single top-down height column and
can select the bedrock roof; it cannot represent the cavern under that roof.
Enabling fortress generation does not fix that data representation or guarantee
all Nether structures render. A separate exposed-volume/cavern representation and
entity rendering/state synchronization are needed for complete support.
Unsupported feature/structure calls still roll back their own transaction; the
expanded stage set does not imply every vanilla or modded feature is supported.

Minecraft 1.21.1 reference source inspected locally: SpikeFeature, LakeFeature,
EndCityPieces, EndBiomes, Structures, ChunkGenerator. Evidence and backups:
`build/surface-content-2026-09-09/`. No game mods directory was modified.

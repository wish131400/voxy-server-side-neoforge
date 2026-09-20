# Nether and End prediction

## Nether

The old Nether sampler kept one exterior height per X/Z column. In a ceiling
dimension that reduced most columns to the roof bedrock at Y=128, so a player
inside a cavern could receive no useful terrain below the roof.

The current sampler stores compact vertical solid runs instead. Air gaps are
implicit; each run keeps its bottom, top, block and fluid kind. Mesh building,
depth bounds, seams, morphing and disk cache all consume the same volume. This
preserves caves, layered terrain and lava without storing every air block. The
cache identity now includes `interior-columns-r3-structures`, and the volume cache uses schema
3, so old roof-only Nether entries are ignored automatically.

The initial geometry-only fixture benchmark generated 256 native columns in about 31 ms, containing
297 air gaps and 156 lava runs, with about 22 KB of retained volume data. The
Java fallback produced the same geometry for 16 columns in about 39 ms. These
are sampler-only measurements; in-game frame time still depends on tile count,
mesh upload and the active shader.

## Interior decoration follow-up (2026-09-20)

Geometry alone did not connect the old exterior decoration pipeline. Its block
proxy filled the space below the roof, its extraction removed below-roof writes,
and its mesh clipped vegetation against one exterior height. The far vegetation
classifier also recognized trees but not huge fungi.

Native ABI 6 evaluates synchronized surface rules over the complete retained column,
including every cave floor and ceiling. It does not use the exterior shortcut that
stops seven blocks below the highest solid. Java uses its reusable Minecraft rule
context when the native world/codec is unsupported. A full native surface-pass
comparison covers all five vanilla Nether biomes; the Java comparison includes
Minecraft's original SurfaceSystem and paired native/Java material columns.

Native decoration uses a sparse volume backed by lazy complete columns. The world
cache holds at most 512 columns and 2 MiB of state data. A job retains only columns
it actually reads, plus sparse edits; it never eagerly samples an 80x80 region.
Existing eight-volume admission, 48x48 write bounds, 65,536-write transaction
budget and cancellation remain active. Native and Java placements share ordered
edits, including structural changes between decoration steps.

New native codecs cover count-on-every-layer (including its eager random stream),
uniform/constant height ranges, huge fungi, Nether forest vegetation, glowstone,
weeping/twisting vines, spring features and the survival rules for roots, sprouts,
fungi and native fire patches. Unknown mod codecs, additional basalt feature
algorithms and unsupported placement providers still use the isolated Java path.
The complete crimson/warped decoration tests compare every final write with Java,
including native transactions interleaved with Java fallback.

Structures deliberately share Minecraft's actual Java layout/template processors
on both terrain backends; this is not a separate Rust rewrite of jigsaw placement.
A bounded virtual chunk routes block/fluid/height queries and block writes through
the same transaction. It supplies no real-world chunk, entity, loot or ticking
side effects. Nether fortress and bastion fixtures load actual vanilla structure
algorithms/templates and compare both backends, including air cuts.
The 1.20.1 bastion fixture also exposed the old 8 MiB NBT accounting cap.
Templates now allow 32 MiB each, with a 64 MiB aggregate accounting weight
and 256-entry LRU limit for decoded templates. Accounting is a conservative
weight, not a measured JVM heap allocation bound; active structure pieces can
also retain templates independently of this cache.

Extraction now retains below-roof solid replacements, liquids and air cuts.
The interior mesher removes overwritten terrain only at the edited footprint and
subdivides affected coarse cells plus their immediate neighbours. Geometry and
occlusion are separate so placed blocks retain their actual stair/fence state and
an empty room does not erase adjacent rock. Unedited cells retain their spacing.
Structure support still requires synchronized registries/templates and supported
WorldGenLevel APIs; it does not promise arbitrary custom city-generator support.

Fire and soul fire use two upright, double-sided cutout quads (12 vertices per
block), rather than the thin outline/collision box used for solid block shapes.
This is a cheap distant representation; it does not simulate fire ticks, particles
or the complete multipart near-field model.

The mesh removes exterior height clipping and canopy-envelope stripping for
interior decoration. It includes underside faces for hanging blocks and fungus
caps, and uses directional material faces for coated rock columns. Vines remain
cutout plants. Existing coarse vegetation budgets still apply, so this is a LOD
representation rather than a full block-for-block renderer at every spacing.

Far representatives read huge-fungus species and required ground from registered
feature configurations. They reuse sampled cave floors, reject fluids and low
ceilings, test the sampled cap footprint, and use deterministic world parcels.
They run at 4/8/16/32-block spacing and retain at most 256 forms per tile across
all cave layers. They do not trigger extra density sampling or full decoration.
The clearance check is limited by sampled spacing; it cannot prove every block
between sparse columns. Ordinary grass/tree representatives retain their path.

The Nether cache identity and surface settings key changed so old uncoated
terrain and empty decoration results cannot hide the fix. Other dimensions
retain their identities. New decoration results persist and reopen without
replaying features, covered by a cold/write/reopen test.

Tests record sampler and paired four-chunk decoration times, but these small cold
runs include JIT/init and build-machine contention. They are not an in-game FPS
measurement or a promised speed multiplier. The migration removes Java/native
round trips for supported surface/feature work; it does not eliminate density
sampling, actual structure placement or mesh/upload cost. The exposed-stage
regression also exercises warm memory/disk reuse.

Release validation follow-up (0.3.2): the two scheduling fixtures
`readyNearTilesGetTreesEvenWhenAnotherNearTileCannotFinish` and
`enablingTreesAfterTerrainFillsMemoryStillPublishesNearbyTrees` now explicitly
use the single builder assumed by their setup. The memory-pressure fixture
waits for actual budget exhaustion with a bounded deadline instead of assuming
150 planner turns fill compacted terrain storage. Both retain their publication,
budget and parent-coverage assertions. No runtime scheduling change was made
for these fixture corrections.

## End

End void is valid terrain and remains empty. The horizon and worker pool are
unchanged. Only the fine-detail band is widened to 1.5 times the configured
fine distance, capped by the horizon and 4096 blocks. For example, a 4096 block
horizon with a 1536 block fine distance uses 2304 blocks for End detail while
Overworld and Nether remain at 1536. This adds nearby island refinement without
creating geometry in the void.

The dimension-aware radius is used consistently by planning, work-view
admission, tile cell selection and render coverage. The selected tests cover
the End radius and ensure that the empty gaps are not filled.

## Verification

The focused NeoForge and Forge tests cover volume sampling, cache reopening,
mesh side walls, mixed-resolution seams, morph/depth handling and End detail
selection. A complete game-pack verification is still required to confirm
visual output with the user's shader and terrain mods.

The current follow-up adds native feature parity (11 configurations, six seeds,
every write plus random continuation), full mixed-stream comparison, real
fortress/bastion parity, upright-fire packing, and exact interior room footprints
at 1/2/4/8-block mesh spacing. Verification logs for this follow-up are
`build/nether-adaptation-final.log`, `build/nether-adaptation-final-fixes.log`,
`build/nether-native-tests.log` and `build/nether-native-cross-build.log`.
The broad run passed the existing OpenGL/Iris, surface, snow, vegetation and
village tests. Its obsolete raw-density comparison and Forge fixture definitions
were corrected; the 23-test follow-up passes on both loaders with no skips.
The Rust suite passes 114 tests, with three pre-existing captured-world benchmark
probes ignored. Windows JNI tests run locally; the other four target binaries
are cross-built, not game-tested on their target operating systems.
Standard jar names remain unchanged under
`lib`; no automatic game installation or restart is part of this change.

# Radial terrain quality bands

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,852,022 bytes.
SHA-256: `156fceb20116b48817d78690636c484d79d9942cc9cd86ed5e15574a89eac64a`.
Version remains `0.3-neoforge-1.21.1`.

## Behavior

Normal terrain targets follow horizontal distance as a fraction of the configured
prediction radius: below 60% uses 64 cells per axis, 60-90% uses 32, and 90-100%
uses eight. These are sampling densities within an LOD tile, not a promise that
every position within 60% has one-block spacing. Tile footprint still follows the
quadtree; nearby and telescope-target terrain can reach one-block spacing.
Tile centers select the band, and boundary tiles have bounded spatial subdivision.
Consequently boundaries follow tile extents rather than exact circular clipping.

New terrain initially publishes a cheap grid, then advances through 8/16/32/64
only as far as its target requires. Completed middle/outer tiles do not repeatedly
queue full-grid work. Moving closer or selecting a telescope target increases the
target. Previously resident or disk-restored finer terrain remains usable and is
not deliberately downgraded to enforce a band.

The radial skeleton has at most 640 leaves. Its added subdivisions do not consume
the original 1024 normal / 2048 scoped refinement allowance. The existing neighbor
transition pass still adds at most 512 leaves. Planning the distant skeleton does
not require generating it before nearby work. Admission and execution retain
near-first priority, bounded queues, memory reservations and half-CPU concurrency.

Vegetation retains the existing default 768-block extension beyond observed
nearby coverage. It does not expand to the terrain's 60% radius. The telescope
retains its 1024-block (64-chunk) target radius anywhere inside the prediction
horizon, forcing full terrain and enabling vegetation there. The closest 256
blocks precede telescope work, which precedes ordinary distant work.

Transition readiness now indexes each neighbor's requested spacing. A fine tile
cannot wait forever for a middle/outer neighbor to reach an unrequested full grid.
Exposed-parent upgrades respect finished band targets and already resident detail.

## Verification

- 518 Java/GPU tests passed with zero failures, errors or skips. Tests cover
  proportional boundaries, negative coordinates, arbitrary horizon distances,
  all-direction coverage, vegetation separation, telescope overrides, retained
  nearby planning capacity, target completion/re-entry, and upload handoff.
- A 60-second production Rust/JNI scheduling probe passed with a 2 GiB Java heap,
  four workers and a 65,536-block horizon. At 15 seconds four full/decorated tiles
  were present; at 55 seconds 54 full tiles and 52 decorated tiles were present,
  with no build failures. This is a liveness check, not an FPS benchmark or full
  horizon completion test. More detailed terrain increases total generation work.
- Five native binaries match the packaged files; architecture and all 29 JNI
  exports per target passed inspection. Native source and ABI are unchanged.
- Vanilla mask bytecode matches the backup. Retired classes and duplicate ZIP
  entries remain absent. No user game directory was modified.

Evidence is in `build/distance-bands-2026-09-09/`: `final-verification.log`,
`native-audit.json`, earlier iteration logs, and `before.zip`. The artifact has
not been visually verified in the user's save or run on Linux/macOS hardware.

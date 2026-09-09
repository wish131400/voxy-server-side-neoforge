# Refinement transitions and resident detail visibility

Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 3,849,241 bytes.
SHA-256: `9725948c806453a9108f17551067775fc8ceccd862c37470cae6ffdc2f717f63`.

## Causes and changes

The render owner lookup preferred the distance-requested LOD over finer resident
tiles. Moving away or closing the telescope could therefore reveal a coarse
ancestor despite completed detail still being available. It now chooses the
smallest actual sample spacing, using the smaller tile footprint to break ties.
Nominal LOD alone is insufficient: an LOD 0 preview has eight-block spacing,
while a completed LOD 1 tile has two-block spacing. The latter must remain visible
until the child becomes at least as detailed. Selection uses GPU-uploaded tiles;
worker completion alone still cannot remove existing coverage.

Cold disk-backed detail also had a 30-second retirement path inside the horizon.
Automatic retirement now requires leaving the horizon. Memory pressure can still
reclaim covered, less urgent detail; this is not an unlimited residency promise.

Near native tiles previously started directly at 64 cells per axis while coarser
tiles started at eight. Combined with unbalanced neighbor levels and preview-only
intermediate parents, this produced abrupt detail boundaries. Native terrain now
progresses through 8, 16, 32 and 64 cells per axis at every level. The planner adds
intermediate neighbor bands, with at most 512 extra leaves. Exposed parents refine
alongside their children until all four child regions have resident replacements.
Upgrades wait for neighboring spacing to come within 2:1 where the requested plan
permits it. Outside-plan tiles and unattainable resolutions cannot block upgrades.
The bounded planner can retain larger differences when its extra budget is spent.

Worker count respects both half the logical CPU count and the memory budget's
builder limit. This prevents excess threads from consuming queued jobs only to
reject them for lack of a build slot in constrained configurations. When pressure
stops builders, covered lower-priority detail can make room for terrain upgrades
as well as surface upgrades.

## Verification

- 512 Java/GPU tests passed, zero failures, errors or skips. Coverage includes
  near/telescope progression, negative coordinates, bounded transition planning,
  actual-spacing owner selection, disk retirement and GPU upload handoff.
- Production bridge passed 1,064 grid/material/tint samples and seven mixed
  vegetation jobs, including state transfer, bounds and cancellation. The real
  native sampler also completed all three refinement stages over the same tile.
- Actual Rust/JNI live scheduler: 60 seconds, 2 GiB Java heap, four workers,
  65,536-block horizon, fixed plains profile, disk cache disabled. By the
  10-second diagnostic, two full terrain tiles and one decorated tile existed;
  by 55 seconds, 82 full terrain tiles and 78 decorated tiles existed. No build
  failures. This checks progress, not complete horizon generation or game FPS.
- All five packaged native binaries match their built files and export the
  expected 29 JNI functions. The vanilla mask bytecode is unchanged from this
  turn's backup. Retired classes and duplicate ZIP entries are absent.
- Windows native execution was tested. Linux/macOS artifacts were inspected,
  not executed on their target systems. Native source and ABI are unchanged.

Evidence: `build/transition-2026-09-09/verification.log`,
`production-integration.log`, `native-audit.json`, and `before.zip`.
This build has not been visually verified in the user's world. No game mods
directory was modified. Version remains `0.3-neoforge-1.21.1`.

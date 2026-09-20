# Prediction boundary ownership and stale terrain walls

## Decision

Keep the user-configured prediction horizon. Read Voxy's current rendering distance
when binding the frame, and limit metadata-assisted real-surface ownership to the
current real renderer range. A stored column or a ready rectangle alone does not
remove prediction: a depth sample from the current render pass is still required.
Require the reconstructed real and predicted XZ positions to be nearby (32 to 64
blocks, depending on sample spacing). An unrelated distant mountain must not own
a foreground predicted surface.

This radius is an ownership eligibility limit, not a replacement for Voxy's
hierarchical visibility test. Voxy tests the closest point of section AABBs, so
its exact displayed silhouette is not a circular pixel boundary. Ordinary depth
occlusion remains authoritative. Voxy disabled/absent uses the vanilla range.

Do not add a timed handoff window, expand the prediction horizon, force a coarse
outer ring, or stretch skirts to cover missing geometry.

## Elongated-wall mechanism

A heightfield tile includes side walls generated from its sampled margin. If a
selected neighbor later uses a different sampled height, the previous stitcher
only subtracts existing walls and fills missing intervals. It cannot retract the
old, now incorrect wall. A reproducible fixture has ground at 64, an old margin at
180, and the current neighbor at 68: the old wall hides the real four-block gap.

At selected ownership boundaries, replace old terrain walls only if the entire
cell edge has selected exterior neighbors. Emit the connection from the current
mesh heights in the same render selection. If neighbors disappear, restore the
old fallback immediately. Partial coverage retains the fallback. Confirmed cave
interiors and vertical volume columns remain on their existing interval path.

Ground provenance is marked before feature emission in the worker mesh. Ground
and placed features cannot merge across that boundary. The packed payload uses
the previously unused word-9 bit 27. Trees, structures and baked model faces are
not inferred from their normal alone and are not eligible for replacement.

## Reference review

- Distant Horizons checkout: c2339b1e849e919b8a83f3a8dccac87c16e22886.
  ColumnBox.java, around lines 305-386, starts with a column's actual vertical
  interval and removes neighboring opaque intervals. It intentionally permits
  some LOD-boundary overdraw to avoid holes during resolution changes.
  This is not a universal maximum wall height or a guarantee of no full sides.
- Meridian 0.1.4 local decompilation: LodTileManager.highRelief (around line 305)
  caches relief decisions; neighboring jumps above max(8, spacing) help trigger
  refinement. LodMeshBuilder uses flat column tops, side walls, median outlier
  filtering and parent-height deltas. VSS already had corresponding relief and
  bounded morph behavior before this fix.
- Voxy local source: VoxyUniforms converts sectionRenderDistance to chunks with
  a factor of 32 (512 blocks per unit). Hierarchical traversal tests distance to
  node bounds. Its normal final depth blit can clamp far depth, so the existing
  original-depth path remains necessary.

These references inform the design; no DH/Meridian source implementation was
copied into this patch.

## Cost and scope

- No additional terrain, biome, density or cave sampling.
- Reuses the existing R8 ownership texture and packed-quad record size.
- One CPU byte per cell for the replacement mask: 4096 bytes per 64x64 tile,
  plus small map/array overhead. The GPU tile and seam cache share that array.
- Ground provenance needs a temporary int per cell during mesh construction.
  This is released with the worker triangle mesh.
- Stable selections reuse masks and seams. Local changes reuse unrelated
  regions; affected regions rebuild. Mixed-resolution midpoint identity alone
  is no longer considered enough to reuse a seam.
- Changed masks require a small texture upload. Replacement sides still have
  raster/discard cost; there is no claim of zero GPU overhead or a measured FPS
  gain. Real-world frame impact needs in-game A/B sampling.

This repairs stale boundary geometry. It does not make a sparse heightfield
reconstruct arbitrary overhangs, nor remove every legitimate tall coarse cliff.
The initial wall patch was verified with fixtures. The follow-up below records
the later live investigation of the remaining transparent terrain.

## Verification

CPU regressions exercise exact height differences, both axes and height orders,
partial parent ownership, negative-facing margins, neighbor removal/return,
partial mixed-resolution edges, feature provenance, underwater light, memory,
interior volumes and upload fallback.

The production OpenGL shader fixture exercises normal and Iris paths at 70 and
7 degree FOV: stale-wall appearance before replacement, removal after selection,
immediate restoration when the neighbor leaves, feature-face preservation and
closure of the remaining four-block cliff. The handoff fixture also shrinks and
restores the real rendering radius while cached coverage stays populated, and
checks that an unrelated distant real surface cannot remove foreground prediction.

Build outputs stay in lib with their existing 0.3.1 names. No game installation,
Git commit or GitHub push is part of this change.

## Follow-up: live Voxy depth convention and premature fog (2026-09-20)

The 20-54-58 recording shows the mountain breaking into disconnected patches,
including through the spyglass. Increasing Voxy's view restores the mountain;
reducing it brings the holes back. A read-only render-thread snapshot of the
installed Voxy 0.2.15-beta pipeline reports `isZero2One=true, isReverseZ=false`.
The loaded VSS 0.3.1 jar matched the preceding boundary-repair build.

The normal original-depth path incorrectly reconstructed every borrowed Voxy
sample with `raw * 2 - 1`. With a zero-to-one projection, the correct clip Z is
`raw`. This can make a distant Voxy surface look closer than foreground
prediction and discard the prediction. The new production-shader GPU fixture
failed before the fix for prediction at 2048 blocks and Voxy at 3000 blocks,
zero-to-one depth, forward Z, vanilla far plane 256 and FOV 70.

Capture both clip-range and reverse-Z properties with the borrowed depth frame.
Use the corresponding scale/bias in depth reconstruction. The fluid depth-bin
tolerance also follows the clip scale and direction toward the camera. No new
depth texture, framebuffer, render pass, terrain work or GPU readback is added
to gameplay. The diagnostic readbacks used for this investigation are tools.

The live shared fog uniforms independently exposed another problem:
`RealRenderDistance=8608`, `HorizonDistance=4096`, fog start/end `4608/4609`.
The padded coverage scan radius pushed the start past the prediction horizon,
collapsing the transition and completely fogging still-visible Voxy terrain.
Shared fog now ends at the larger current display range (prediction or Voxy).
Only vanilla's nearby clear range delays fog; metadata scan padding has no role.
Start is normally 55% of the display range, with a minimum 10% fade span when
the vanilla clear range is unusually large. This is a fog fade, not a coarse LOD
band. Prediction generation and its horizon clipping remain unchanged.

Regression coverage includes both clip ranges and both Z directions, ordinary
and spyglass FOV, clamped vanilla far depth, coincident terrain/water, distinct
riverbeds, missing original depth, and dynamic Voxy view changes. The fog GPU
fixture checks that terrain at 5000 retains color with Voxy at 8608 and prediction
at 4096, then follows shrinking/restoring Voxy view. The reflection test bootstraps
the loader paths so it also works in an isolated test JVM.

The corrected jar still requires restart and comparison at the recorded position.
Passing shader fixtures is not a claim that every sparse-prediction mismatch or
every Voxy fork has been reproduced. Additional frame-time cost has not been
benchmarked; the correction adds constant-size uniforms and arithmetic, not
distance-dependent scanning or sampling.

Final verification: NeoForge's focused depth/fog/GPU run passed 6 JUnit tests;
Forge's run including seam and upload regressions passed 35. Both had zero
failures, errors and skips. The GPU test contains the parameterized pixel checks
described above. Both normal jar tasks succeeded (including Forge reobfuscation).

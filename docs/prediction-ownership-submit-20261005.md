# Voxy ownership before prediction submission

The underwater comparison in `2026-10-05 23-31-21.mkv` shows an important
distinction: prediction can be invisible while it still sends geometry to the
GPU. The user's same-direction toggle reported about 60 more FPS with
prediction disabled. That is a client observation, not a measured gain from
this change.

## Evidence from the running client

The old client was inspected read-only. Its sampled scene had 3,076,266
candidate prediction quads. A conservative offline footprint count found
372,027 quads wholly inside stable Voxy columns before camera-distance and
morph checks. The previous 256-quad meshlet bounds could classify only
120,491 of those as wholly owned. The 375 completely Voxy-owned tiles in the
scene already had no resident prediction mesh, so remaining overlap was in
partially covered tiles. The 20-second live metrics window had the pause menu
open and cannot be used as an FPS comparison. The game exited before a new
scene dump could be taken.

## Submission rule

Each packed mesh now has ordered runs of quads with the same covered chunk
rectangle. The runs are built on the mesh worker and retain no per-quad
objects. A pass filters a run only when every touched chunk is settled Voxy
interior and the full 3D run, including possible morph height and camera-cell
margin, lies inside Voxy's view sphere. Chunk edges, missing or unsettled
coverage, and out-of-range geometry stay in prediction. Opaque and water use
the same filtered ranges; neither is reordered. The plan cache invalidates on
index, camera-cell, view-radius, residency, and source-list changes. Coverage
removal invalidates an old CPU snapshot immediately and restores submission
until a fresh snapshot settles. An entirely Voxy-owned retiring tile leaves
GPU residency without waiting for an unuploaded prediction ancestor.

The existing R8 mask and depth handoff remain for partial boundaries. This
change removes known Voxy interior geometry before legacy or indirect draw
submission; it does not stop all prediction work or remove the extra
composite/depth-copy cost introduced by the earlier water fix.

## Verification

Both repositories passed complete builds: NeoForge 1,301 passed / 76 skipped,
Forge 1,308 passed / 74 skipped. Both passed seven GPU suites. In the
production GPU fixture, full stable ownership reduced generated opaque
triangles from 337,920 to 0 and water triangles from 5,632 to 0 for both
legacy and MDI paths. Mixed coverage retained boundary triangles (89,728
opaque and 1,664 water); revoking coverage restored baseline submissions.
Forge GPU shader fixtures use the installed NeoForge Voxy shaders; this is
not Forge modpack runtime compatibility validation.
The GPU test also exercises the renderer's prepared-pass and page-group cache
when the ownership snapshot changes.

Evidence, logs, and package hashes are in
`build/ownership-submit-20261005/verification.json`. Both repositories have
the updated `vss-0.3.5` JAR under `lib/`. No game mods were replaced, and
these tests do not establish a new same-location FPS result. The next client
check is to load the new NeoForge JAR, repeat the underwater same-camera
on/off comparison, and inspect `voxyExcludedQuads` plus GPU pass timing. Any
remaining difference can then be separated from prediction generation,
composite/depth-copy work, and boundary draws.

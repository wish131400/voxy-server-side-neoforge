# Prediction scheduling and persistent reload

All changes are in the desktop `main` working directory, `C:/Users/Administrator/Desktop/voxyserverside-neoforge-1.21.1`.

## Scheduling changes

VSS already used a priority queue with distance bands, progressive stages and a telescope focus. Its separate limits were unnecessarily restrictive: half the logical processors overall and half of those for detail while previews were pending. The machine has 16 logical processors; the limits were 8 total / 4 detail. They are now 14 total / 13 detail while previews are pending, then up to all 14 for detail. One worker remains available to advance previews so long full-grid jobs cannot occupy every worker. Two logical processors remain available for Minecraft. Actual free-heap admission, cancellation, and bounded GPU uploads remain active. The global memory budget still limits combined builders across dimensions.

## Confirmed reasons for rebuilding

1. Terrain persistence only accepted final 64-cell grids. Initial coverage and the 8/16/32-cell distance-band targets were never saved, even though they can remain visible indefinitely.
2. The profile fingerprint hashed serialized/compressed bytes, whose object-field order depended on registry traversal. Two captured sessions from the same world have equal parsed registries but different raw bytes: `build/live-prediction-snapshot/registries.json` in the old Codex worktree hashes to `42fe9db91acb8c33...`, while the desktop `build/new-world-snapshot/registries.json` hashes to `4288c518cba93c95...`. Their generator documents are byte-identical. Three cache directories exist in that save with the same generator suffix but different first hashes; retained data was not being consistently identified as the same inputs.
3. Re-sent authoritative columns called cache invalidation even when all stored column samples were unchanged; reconnecting could delete useful nearby cached tiles again.
4. A terrain cache hit still invoked native biome tint sampling for every column. Restoring saved heights alone therefore did not bypass all terrain-related work.

## Cache implementation

- Save each completed 8/16/32/64-cell grid, with its actual sample count. Restore the best saved grid for that tile before sampling a smaller preview.
- Do not re-read a resident preview instead of refining it. After restoration, later builds can progress to the next density exactly as on a cold start.
- Store raw surface/foliage/water tints with terrain samples. The Rust sampler exposes a stable fingerprint of the loaded grass/foliage colormaps. Matching resources skip native tint calls; changed colormaps refresh tints while reusing heights. Opaque Java color providers opt out of tint reuse.
- Terrain cache schema 2 reads legacy schema 1 files when identity matches. Surface feature cache schema 3 remains unchanged.
- Identical authoritative column samples received after reconnect no longer invalidate terrain/decoration caches. Explicit dirty notifications still clear samples and invalidate caches immediately.
- Dirty-column invalidation includes the largest coarse-grid sample margin, invalidating unloaded previews and final grids through the existing lease/tombstone path.
- Encode worldgen JSON objects in sorted key order; preserve array order and all parameter values. Use the same canonical bytes for density reference IDs and profile compression, stabilizing cache identity across registry traversal order.

The corrected canonical identity may require one rebuild when first upgrading. Existing cache directories are not deleted or blindly reused across generator/algorithm changes. Future sessions with identical worldgen, backend and settings use the same identity. The earlier `vanilla-rust-abi2-r3` height correction is preserved.

Restoration still includes file decompression, lightweight mesh reconstruction and GPU upload. It does not persist GPU handles or resource-atlas indices, which are not stable between runs. No instantaneous-loading or measured in-game FPS claim is implied.

## Validation

Targeted reopen tests cover every preview stage: cold creation, closing/reopening the cache and manager, zero height calls and zero tint calls on warm restore, colormap changes without terrain resampling, continued refinement after restore, and dirty invalidation. Additional tests cover legacy terrain files, coarse sample borders and stable snapshot/density identities. Existing full-grid/vegetation cache lifecycle and interrupted-write tests remain in the suite.

Synthetic fixture timings include test polling and are not real-world terrain benchmarks; the asserted metric is that warm restores execute no height or tint sampling. Full validation and artifact details are appended after the build completes.

## Final result

- Full Java/native integration, compatibility, GPU and live-height reference suite: **581 tests, 0 failures, 0 errors, 0 skipped** (`build/scheduler-cache-full.log`).
- The five packaged native libraries pass the JNI/platform audit (`build/reports/scheduler-cache-native-audit.json`). Native source did not change in this task.
- Height parity remains 260 density values / 13 columns against the previously captured running game.
- Artifact: `build/libs/vss-0.3-neoforge-1.21.1.jar`, 4150089 bytes, SHA-256 `0573223986b541165f7f3b3033c466ee9536fccff2218403ae1c7aee2d99fb0f`.
- Game mods/configuration/saves were not overwritten. The running game still needs a full restart with this artifact.

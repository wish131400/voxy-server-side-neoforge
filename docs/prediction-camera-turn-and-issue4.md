# Camera-turn rendering and issue 4

## Runtime evidence (2026-09-20)

A 60-second JFR was collected from the running NeoForge client while the user
reported stutter when turning the camera. Recording and summary:
`build/issue4-audit/live-turn.jfr`, `build/issue4-audit/jfr-summary.txt`.

- Render-thread allocation counter delta: 7,621.985 MiB. This is cumulative
  allocation during the recording, not retained heap or mod memory usage.
- 907 of 2,735 render-thread execution/native samples included `appendSeams`;
  835 included `PredictionLodSeams.update`. Inclusive samples overlap and are
  not an exact wall-time breakdown.
- Boundary/seam assembly was also the largest sampled VSS allocation source.
- 16 GC pause events totalled 124.524 ms; the largest was 13.590 ms.

## Rendering changes

Previously, the frustum-selected draw list was also the complete set of seam
neighbours. Turning removed and restored otherwise unchanged resident tiles.
That invalidated boundary masks and seam payloads, and could require new GPU
uploads just because a neighbour crossed the edge of the screen.

Boundaries now use all uploaded, drawable terrain inside the prediction
horizon. The current frustum filters the final terrain and seam draws. Geometry
replacement, ownership changes, movement across the horizon, distance changes,
and world reset still update or invalidate the relevant data. There is no timed
handoff delay and no change to prediction distance or accuracy.

Seam draws use their own vertical bounds, including both connected heights.
An offscreen low owner must not cull a visible cliff connector that replaced
the neighbouring tile's margin wall.

The geometry cache reuses distance ordering when camera position, horizon and
resident snapshot are unchanged. Upload candidates drain as uploads finish and
refresh when the worker snapshot changes. Uniform lookup uses the cached value
without constructing a capturing callback on each cache hit.

The 81-tile, 60-turn CPU replay compares the previous frustum-driven seam path
with stable resident topology. One NeoForge run measured 54.877 ms / 68.273 MiB
versus 0.796 ms / 0.002 MiB cumulatively. Parallel build runs varied in timing
but retained the same allocation reduction. These are boundary-stage results,
not a claimed in-game FPS multiplier. Updated game-session A/B is still needed.

## Issue 4: scope and limits

Source: https://github.com/wish131400/voxy-server-side-neoforge/issues/4

The attached report identifies an RTX 2070 SUPER with NVIDIA 610.88. Its logs
end after prediction startup, but the archive contains no `hs_err_pid` report,
native stack, or dump pinpointing the failing driver call. The issue body's
third-party diagnosis does not establish that unrestricted Voxy visibility or
the fluid compatibility warning caused the native crash.

Code review found an independent unsafe upload assumption: prediction uploads
set only alignment, inheriting another renderer's pixel-unpack buffer, row/image
strides, skips and byte-order flags. With a PBO bound a CPU pointer is treated as
an offset. With nonzero strides/skips a driver may read beyond the allocated
direct buffer. Java bounds checks cannot protect that native read.

`PredictionPixelUnpack` isolates these states for the pass and every texture
upload helper, including coverage, boundary masks, the 3D vanilla mask, sprite
metadata and depth-target allocation. Nested scopes avoid repeated GL queries
inside a pass. Normal completion and exceptions restore the caller's state.

Real OpenGL tests on an RTX 4070 Ti SUPER verify actual coverage/boundary pixels
under a foreign PBO, row/image strides, skips and byte order, including resize
and exception restoration. Existing terrain, water, seam, depth-handoff and Iris
state tests remain enabled. This removes a reproducible class of unsafe upload
state assumptions; it does not establish the exact crash cause on the reporter's
GPU/driver. Issue 4 must remain open until the affected modpack is retested.

## Logging and validation

The `VSS LOD wire` INFO statement existed only in the Forge sender. It now checks
the server's `debugLogging` flag, clears disabled diagnostic accumulation and
uses `VSSLogger.debug`. NeoForge has no equivalent unconditional statement.

Focused tests cover seam neighbours, actual frustum changes, stable masks,
replacement/reset, upload ownership, renderer selection and production OpenGL.
Test output: NeoForge `build/issue4-audit/tests-final.log` and
`build/issue4-audit/geometry-final.log`; Forge `build/turn-upload-tests-final.log`.
Both production jars keep their existing names in their repositories' `lib`.

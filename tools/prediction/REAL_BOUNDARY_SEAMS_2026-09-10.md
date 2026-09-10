# Compiled ground / prediction boundary seams

## Follow-up: oversized wall guard

The later spyglass screenshot demonstrates why connecting an arbitrary real /
predicted height difference is unsafe. The original policy was insufficient:
endpoints being valid does not establish that a vertical cliff exists there.
Real-boundary connectors now reject differences above four blocks, independent
of FOV and mesh spacing. They do not clamp a tall wall to a short partial wall.
Large errors can expose the original gap again and still require terrain-data
correction; this guard does not claim prediction parity.

Missing grass under/deep materials now use the existing dirt/stone fallback
shared by regular terrain walls. Explicit sampled strata remain untouched.
Seam side bands use ground material instead of the snow-covered top material.

The current normal shader also had a same-footprint Voxy preference up to 64
blocks vertically. The existing GPU test proved this erased a distinct surface
32 blocks in front. That tolerance is now four blocks. The test remains strict.

Validation: build/boundary-wall-guard-tests.log (mesh, seam, cache and normal/Iris
GPU tests). The real seam GPU fixture now checks a three-block mismatch; unit
tests reject both large height orders and remove cached connectors when the
difference grows. Original tall natural cliff tests still pass.

## Evidence and scope

The reported FreeTerraForged screenshot contains a horizontal sky opening near
the real/predicted terrain handoff. The corresponding earlier run reported
three Rust samplers. This is not evidence of Java fallback being selected.
There is no live Minecraft process available to read the exact camera and
resident meshes from that frame, so the screenshot's entire cause is not yet
verified in game.

The existing PredictionLodSeams connects selected prediction tiles only.
Compiled vanilla geometry is absent from its surface index. A flat prediction
cell intersecting the compiled boundary has no reason to emit a wall matching
the real ground height. GPU regression fixtures reproduce an open sky interval
with this geometry and close it using the new real boundary connector.

## Implementation

- Read only edges of fully compiled client chunks, using existing heightmaps
  and at most 32 existing blocks per edge column. Never generate chunks or
  evaluate worldgen noise on the render thread.
- Reuse the existing mask refresh cadence (at most eight times per second).
  Stable edges, selected tiles and coverage reuse packed seam payloads.
- Split boundary connectors at individual block edges and connect only the
  actual real/predicted height interval, using top/under/deep materials.
- Exclude non-ground roofs and unknown/deep columns; no world-bottom curtains,
  generic structure boxes, or underground ceiling reconstruction.
- Keep mask population and distance rules unchanged. Dedicated connector
  metadata checks that its real side is compiled and its prediction side is
  not. Existing foreground depth tests still apply in normal and Iris paths.
- Remove connector geometry when compilation or selected prediction changes.
  This mesh is transient; existing terrain caches need no invalidation.

## Validation

PredictionRealBoundarySeamsTest covers all four directions, both height orders,
1/4/16-block cells, exact vertical limits, stable reuse and removed neighbors.
PredictionRenderTargetGpuTest reproduces the gap before adding the connector,
then verifies closure at 70/7-degree FOV, both height orders, normal/Iris paths,
and rejection behind actual foreground depth. PNG evidence is written under
build/reports/lod-seams/real-{before,after}-higher-*.

Full compatibility output: build/real-boundary-full-tests.log.
JUnit XML/HTML: build/reports/real-boundary/.

This specifically covers the compiled vanilla/prediction boundary. It does
not synthesize a separate heightmap from GPU-only distant Voxy meshes, and
does not claim to eliminate every FreeTerraForged prediction height error.
Final verification at the user's screenshot location remains necessary.

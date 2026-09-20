# Player model / shader interaction: bounded frame capture

Status update (2026-09-21): the user confirmed that the reported player transparency was not a VSS issue. It is no longer tracked as a known VSS defect, and no VSS fix is claimed for it. The bounded capture tool remains available for diagnosing rendering problems.

The following records the diagnostic work originally prompted by the ETF/EMF/Fresh Animations player report. The supplied video and initial reports described parts of the player showing terrain-like imagery with prediction and shaders, with changes when prediction or shaders were disabled. Those observations did not establish VSS as the cause. Static source inspection and isolated GPU tests did not identify the exact faulty draw in that player's pack; the investigation notes below preserve the diagnostic method rather than a pending repair commitment.

## Capture an affected frame

Install the diagnostic JAR for the correct loader and restart Minecraft. Keep only one VSS JAR in that instance. Enter the affected world, use third-person view, and place the abnormal part near 50% of screen width and 65% of screen height. Run:

```text
/vssclient prediction rendercapture
```

Close chat and hold the camera still. After at least three seconds, the next world frame with no menu open is captured. A local message gives the resulting ZIP path under `<game directory>/debug/vss-render/`. To target another location, use normalized screen coordinates measured from the top left:

```text
/vssclient prediction rendercapture 0.50 0.50
```

Capture the same location with prediction enabled and disabled while retaining the shader and entity mods. A shaders-disabled capture provides another baseline. A request expires after 30 seconds or if the world changes. A second request is rejected while capture/export is running.

The capture is deliberately manual. GPU readback may stall the single captured frame; do not use that frame to measure FPS. There is no recurring readback, disk polling, capture thread, or extra render target while idle. Serialization/compression runs in the background and the pending/exported raw payload is limited to 32 MiB, with at most 24 snapshots. Temporary staging uses one attachment crop at a time. The ZIP and metadata add some overhead beyond the payload limit; this is not a claim that process RSS rises by exactly 32 MiB.

## What is recorded

- 128 × 128 pixel crops, clamped to each target's dimensions, of texture-backed color and depth attachments. Depth is float32; colors retain HDR float32 or integer values. Raw values are preserved instead of interpreting packed shader metadata as display colors.
- Current shader program, sampler/other ordinary uniform values (first array element, converted to float), up to 32 texture-unit bindings, draw-target mappings, per-target blend/color-write state, depth convention, depth range and stencil state. Uniform blocks are not dumped.
- Up to eight encountered programs' available GLSL sources, GPU/OpenGL version, Minecraft version, installed mod IDs/versions and prediction/shader enable state.
- Prediction opaque/water before, bound and after checkpoints. Iris pipeline checkpoints before/after deferred and before/after composite inspect the main and up to two encountered LOD targets.
- After the cutout-terrain event, up to four distinct entity/eyes/armor shader names are inspected immediately after Iris applies the shader, before that draw. This records actual skin/overlay/material sampler bindings rather than assuming restored prediction bindings are the entity's bindings. It also supports a prediction-disabled baseline. It does not capture every part or every entity; these checkpoints may be absent with an unsupported Iris layout.
- Without shaders, vanilla entity submission and end-of-level checkpoints. If optional Iris methods are unavailable, capture ends at the next frame with a partial report; missing stages must not be interpreted as successful validation.

This is a stage comparison, **not** a full RenderDoc pixel history or a record of every entity draw. Reflection/portal views can introduce additional callbacks; the report preserves snapshot order and framebuffer IDs rather than claiming all entries are the same main view. No shadow/portal safety changes to rendering are inferred from capture data alone.

Readback restores the previous read framebuffer, the sampled framebuffer's own read-buffer selection, pixel-pack buffer and pixel-pack layout, plus raw active texture state used by inspection. It never alters draw buffers, depth/color contents, or Minecraft/Iris cached bindings. Multisampled or non-texture attachments are skipped; no hidden resolve pass is added.

## Inspect locally

```text
python tools/prediction/inspect-render-capture.py path/to/frame-123.zip build/render-inspection
```

Requires Pillow. The output includes a contact sheet, raw-channel PNG previews and report.json. Depth/integer previews are normalized **per crop** and are for orientation only; compare the raw values and recorded projection/depth convention for correctness. Color previews clamp to [0,1] and are not tonemapped game screenshots. Captures may contain player imagery and shader source; share the ZIP intentionally.

## Repair decision

EMF submits custom parts through VertexConsumer/RenderType; ETF adapts Iris's FullyBufferedMultiBufferSource. Iris batches opaque entity draws before deferred processing. Eclipse's inspected Voxy adapter separates LOD depth from vanilla depth and uses multiple shared color/material/transparency attachments; its entity shader also clears transparency-layer outputs. These facts motivate checking the common VSS/Iris/Voxy boundary, not hard-coding ETF/EMF version numbers or forcing skin opacity.

If entity depth or texture inputs are wrong, correct the responsible VSS state/binding/depth handoff. If entity output is correct before composite but wrong afterwards, trace the differing attachment and repair the demonstrated composition contract. Do not substitute vanilla depth unconditionally, clear shared color targets, redraw entities, or flush each model part. GPU fixtures validate readback isolation and foreground MRT replacement, but only an affected-world capture can identify which branch applies to this report.

## Validation

`PredictionFrameCaptureGpuTest` draws a foreground entity into two color outputs and depth over a terrain background. It checks that skin and transparent-layer clearing survive readback, cutout pixels preserve the background, and hostile pack-buffer/pack-layout/read-buffer/texture-unit states are restored. `PredictionRenderTargetGpuTest` covers the existing production prediction shader, depth conventions, foreground occlusion, texture-cache isolation and Iris MRT contract. These tests do not launch ETF/EMF or Eclipse.

# Prediction / Iris entity state isolation

Reported environment: Minecraft 1.21.1 / NeoForge, Iris 1.8.14-beta.1, Voxy 0.4.11-unknown, and Eclipse-Shader-Unstable. The player reports flickering Touhou Little Maid models and skeleton horses with prediction and shaders enabled, but normal rendering without shaders. The precise shader build and complete modpack were not supplied.

## Reproduced defect

Voxy binds OpenGL state directly. During its callback, the real texture bindings and raster state can intentionally differ from Minecraft's cached state. Prediction used RenderSystem / GlStateManager for temporary state, then restored Voxy's real bindings through the same cached APIs. That replaced Minecraft's original cache with temporary Voxy values. A later entity bind could be skipped as redundant even though the driver still had a different texture bound.

A hidden GPU regression reproduced this on the previous implementation: the requested entity texture ID was 3 but the bound texture remained 2. The test now covers the production material-binding function and following entity transitions for culling, depth testing, depth function, depth writes and blending, including failed-draw cleanup.

Iris additionally intercepts cached blend/depth writes while shader overrides are locked. Calling those APIs inside Voxy can overwrite deferred restoration values. Isolated prediction draws now use raw GL for these temporary bindings/states and restore the surrounding native pass without touching Minecraft caches or those Iris lock APIs. The normal rendering path retains cached APIs. The Iris path also avoids calling turnOnLightLayer again; it uses the lightmap already provided by the world render.

All material, mask and upload bindings in this path use the same scoped helper. Uploads start on a captured local texture unit. The scope restores native textures, samplers, active unit, texture buffer binding, VAO, program, framebuffers, viewport, depth/cull state and indexed blending, and releases isolation on exceptions.

## Verification and limits

Run in each loader repository:

```powershell
.\gradlew.bat test --tests '*PredictionRenderTargetGpuTest' --tests '*PredictionRendererTest' assemble --offline -I tools/prediction/gpu-tests.gradle --console=plain
```

Local logs: `build/entity-flicker/baseline-gpu.log` (NeoForge failing reproduction), `fixed-gpu.log` (first fixed run), and `final-validation.log` (each loader). Original player media and extracted frames remain local under ignored paths.

Both loaders passed the final build and six selected tests each (five renderer tests and one GPU suite), with zero failures/errors/skips. Both JARs contain the new isolation helper and all five native resources match their source files.

These tests exercise real GPU state and existing prediction shader fixtures. They do not launch the player's complete Iris/Eclipse/modpack environment or prove that every visible flicker in that environment is resolved. Same-location validation with the rebuilt JAR remains necessary.

The fix adds no terrain queries, entity scans, render targets or per-entity hooks. It changes temporary state binding/restoration within existing prediction callbacks; no measured FPS improvement is claimed.

# Vulkan feasibility assessment

Assessed 2026-09-22 against the current VSS source and VulkanMod upstream dev branch.

Vulkan is callable from a Java mod through LWJGL, but it is not a drop-in renderer
switch for VSS. Current prediction rendering directly uses OpenGL buffers,
textures, framebuffer objects, GLSL programs, draw calls and synchronization.
Voxy depth/coverage and Iris pass integration also share OpenGL resources.

## Options

- Keep Minecraft/Voxy on OpenGL and render prediction in Vulkan: technically
  possible with supported external-memory/semaphore interop, compatible resource
  formats and explicit cross-API synchronization. Requires a Vulkan backend plus
  color/depth sharing and shader-pack integration. GPU copies or CPU readback are
  a costly fallback. This does not remove Minecraft/Voxy CPU submission costs.
- Run with VulkanMod: requires adapting the complete client rendering integration,
  including the Voxy side. Its current dev metadata targets Fabric/Minecraft
  1.21.11, not these Forge 1.20.1 and NeoForge 1.21.1 builds. WindowMixin requests
  GLFW_NO_API, and GlStateManagerM replaces Minecraft wrapper calls; it does not
  transparently translate VSS's arbitrary direct LWJGL OpenGL calls. Upstream now
  points users to Beryl for shaders; existing Iris hooks are not that interface.
- Vulkan compute for prediction: a separate algorithm port. CPU density graphs,
  mod callbacks, placement and Java fallback are not accelerated just by changing
  APIs. Batch transfer, GPU contention and numerical parity need measurement.

## Recommendation

Do not add Vulkan to 0.3.3. First measure the current game bottleneck, then pursue
OpenGL batching/shared buffers and reduced submission/state-query costs where
profiles justify them. Several of these techniques do not require Vulkan.
Keep sampling/cache/mesh CPU data separate from the graphics backend so a future
Vulkan proof of concept can reuse them. A proof of concept should validate opaque
terrain, depth handoff, translucency and resource lifecycle before claiming Voxy
or shader compatibility. No performance percentage or delivery estimate is
supported by the present source-only assessment. No Vulkan code was added.

## Sources

- https://github.com/xCollateral/VulkanMod
- https://github.com/xCollateral/VulkanMod/blob/dev/src/main/java/net/vulkanmod/mixin/window/WindowMixin.java
- https://github.com/xCollateral/VulkanMod/blob/dev/src/main/java/net/vulkanmod/mixin/render/GlStateManagerM.java
- https://github.com/xCollateral/VulkanMod/blob/dev/src/main/resources/fabric.mod.json
- VSS PredictionRenderer, PredictionRenderTarget, PredictionIrisBridge,
  PredictionTerrainProgram and StrictVoxyContractTest.
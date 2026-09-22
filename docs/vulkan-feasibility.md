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

## FP64 follow-up (2026-09-22)

Vulkan compute does not add FP64 execution units or increase their throughput.
`shaderFloat64` is an optional device feature permitting 64-bit shader types,
not a performance guarantee. A GPU kernel limited by double-precision arithmetic
does not escape that limit by moving from OpenGL/OpenCL/CUDA to Vulkan. Different
compilers, batching and scheduling can change results, but require measurement.

Current VSS density evaluation runs on CPU in Java/Rust (`density.rs` uses f64;
`terrain.rs` branches on density > 0). No Vulkan terrain backend is active.
Therefore low GPU utilization is not evidence of GPU FP64 saturation in current
VSS, and this review does not establish that CPU prediction is FP64-throughput
limited. Cache access, graph dispatch, branches and mod callbacks also contribute.

A compute-only backend could batch supported density graphs and return results
to the existing CPU mesh pipeline without replacing the OpenGL renderer. It would
still need graph lowering, exact random/noise semantics, double support checks,
float operation/rounding controls, asynchronous transfer, GPU workload budgeting
and Java/Rust fallback. FP64 alone does not guarantee Java-identical output:
contraction/FMA, operation order and floor/threshold behavior must be verified.

Using FP32 for initial classification and recomputing uncertain samples on CPU
is a separate mixed-precision research direction. A fixed epsilon near zero
is not a general correctness proof; conservative propagated error bounds and
all affected decisions (noise range branches, material/fluid/biome thresholds)
would be needed. Unsupported mod nodes require fallback. Large absolute world
coordinates lose low bits in FP32; local coordinates alone do not reproduce the
complete noise algorithm. No FP32 substitution was made here.

Conclusion: Vulkan compute might accelerate supported workloads through GPU
parallelism, but cannot be advertised as a solution to insufficient hardware
FP64 throughput. No speedup measurement or Vulkan implementation is claimed.
The implemented work in this iteration stays in OpenGL: bounded fenced buffer
reuse. Exact packed-quad instancing was prototyped and checked for equivalent
pixels/depth, but retained only in tests after larger meshes increased GPU cost.

Specification source checked:
https://github.com/KhronosGroup/Vulkan-Docs/blob/main/chapters/features.adoc#features-shaderFloat64

package me.cortex.voxy.client.core;

// Legacy Voxy shape used only by depth bridge tests; no OpenGL context is needed.
public class NormalRenderPipeline {
    public final me.cortex.voxy.client.core.rendering.util.DepthFramebuffer fb =
            new me.cortex.voxy.client.core.rendering.util.DepthFramebuffer();
}

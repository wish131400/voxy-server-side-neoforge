package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Voxy uses raw GL while Minecraft/Iris retain a different cached state.
 * Inside its callback, prediction must neither change those caches nor feed
 * Iris's deferred blend/depth overrides. The enclosing State restores raw GL.
 * Render-thread only; ordinary Minecraft passes keep their cached API path.
 */
final class PredictionGlState {
    static boolean isolated;

    static void activeTexture(int unit) {
        if (isolated) GL13.glActiveTexture(unit); else RenderSystem.activeTexture(unit);
    }

    static void bindTexture(int texture) {
        if (isolated) GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture); else RenderSystem.bindTexture(texture);
    }

    static void enableDepthTest() {
        if (isolated) GL11.glEnable(GL11.GL_DEPTH_TEST); else RenderSystem.enableDepthTest();
    }

    static void depthFunc(int function) {
        if (isolated) GL11.glDepthFunc(function); else RenderSystem.depthFunc(function);
    }

    static void depthMask(boolean write) {
        if (isolated) GL11.glDepthMask(write); else RenderSystem.depthMask(write);
    }

    static void disableCull() {
        if (isolated) GL11.glDisable(GL11.GL_CULL_FACE); else RenderSystem.disableCull();
    }

    static void enableCull() {
        if (isolated) GL11.glEnable(GL11.GL_CULL_FACE); else RenderSystem.enableCull();
    }

    static void disableBlend() {
        if (isolated) GL11.glDisable(GL11.GL_BLEND); else RenderSystem.disableBlend();
    }

    private PredictionGlState() { }
}

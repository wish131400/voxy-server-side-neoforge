package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Voxy uses raw GL while Minecraft/Iris retain a different cached state.
 * Inside its callback, prediction must neither change those caches nor feed
 * Iris's deferred blend/depth overrides. The enclosing State restores raw GL.
 * Render-thread only. Ordinary passes update Minecraft's cache and also install
 * the native state: a previous raw Voxy call may have changed it behind that cache.
 */
final class PredictionGlState {
    static boolean isolated;

    static void activeTexture(int unit) {
        if (!isolated) RenderSystem.activeTexture(unit);
        GL13.glActiveTexture(unit);
    }

    static void bindTexture(int texture) {
        if (!isolated) RenderSystem.bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    static void enableDepthTest() {
        if (!isolated) RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    static void depthFunc(int function) {
        if (!isolated) RenderSystem.depthFunc(function);
        GL11.glDepthFunc(function);
    }

    static void depthMask(boolean write) {
        if (!isolated) RenderSystem.depthMask(write);
        GL11.glDepthMask(write);
    }

    static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        if (!isolated) RenderSystem.colorMask(red, green, blue, alpha);
        GL11.glColorMask(red, green, blue, alpha);
    }

    static void disableCull() {
        if (!isolated) RenderSystem.disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    static void enableCull() {
        if (!isolated) RenderSystem.enableCull();
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    static void disableBlend() {
        if (!isolated) RenderSystem.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);
    }

    private PredictionGlState() { }
}

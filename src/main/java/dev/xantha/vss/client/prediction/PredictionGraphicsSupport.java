package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** Checks API features, never a vendor allowlist. Called only on the render thread. */
final class PredictionGraphicsSupport {
    private static Boolean ordinary;
    private static Boolean iris;

    static boolean available(boolean shaderPack) {
        Boolean cached = shaderPack ? iris : ordinary;
        if (cached != null) return cached;
        var caps = GL.getCapabilities();
        String reason = unsupportedReason(shaderPack, caps.OpenGL32, caps.OpenGL46,
                GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS),
                GL11.glGetInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
        boolean supported = reason == null;
        if (shaderPack) iris = supported; else ordinary = supported;
        if (!supported) VSSLogger.warn("VSS prediction " + (shaderPack ? "Iris" : "ordinary")
                + " renderer unavailable: " + reason + "; GPU=" + GL11.glGetString(GL11.GL_RENDERER)
                + ", OpenGL=" + GL11.glGetString(GL11.GL_VERSION));
        return supported;
    }

    static String unsupportedReason(boolean iris, boolean gl32, boolean gl46, int fragmentUnits, int vertexUnits) {
        if (!gl32) return "OpenGL 3.2 is required";
        if (iris && !gl46) return "the Voxy/Iris shader path requires OpenGL 4.6";
        if (fragmentUnits < 8 || vertexUnits < 2) return "insufficient texture units (fragment >= 8, vertex >= 2)";
        return null;
    }

    private PredictionGraphicsSupport() { }
}

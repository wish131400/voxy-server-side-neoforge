package dev.xantha.vss.client.prediction;

import static org.lwjgl.opengl.GL33C.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;

/** Per-draw sampler overrides; the shared Minecraft atlas is never reconfigured. */
final class PredictionAtlasSampler {
    private static int sampler, atlas = -1;
    private static long revision = Long.MIN_VALUE;

    static void bind(int atlasId) {
        if (atlasId <= 0 || !(GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects)) return;
        if (sampler == 0) {
            sampler = glGenSamplers();
            glSamplerParameteri(sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glSamplerParameteri(sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) glSamplerParameterf(sampler,
                    EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                    Math.min(4F, glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT)));
        }
        long current = VssLodSpriteTable.materialRevision();
        if (atlas != atlasId || revision != current) {
            // Atlas is bound on unit zero by bindMaterialTextures. Resource
            // reload/disabled mipmaps must not leave a mip sampler incomplete.
            int max = glGetTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL);
            boolean mipmaps = max > 0 && glGetTexLevelParameteri(GL_TEXTURE_2D, 1, GL_TEXTURE_WIDTH) > 0;
            glSamplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
            atlas = atlasId; revision = current;
        }
        glBindSampler(0, sampler);
    }

    private PredictionAtlasSampler() { }
}

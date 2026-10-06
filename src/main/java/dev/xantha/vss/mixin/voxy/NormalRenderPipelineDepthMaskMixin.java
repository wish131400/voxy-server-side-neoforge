package dev.xantha.vss.mixin.voxy;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.NormalRenderPipeline", remap = false)
public abstract class NormalRenderPipelineDepthMaskMixin {
    @Unique
    private boolean vss$previousDepthMask;

    @Unique
    private boolean vss$sourceZeroToOne;

    @Unique private int vss$texture4;
    @Unique private int vss$sampler4;
    @Unique private int vss$texture5;
    @Unique private int vss$sampler5;
    @Unique private int vss$depthFunction;

    @Inject(method = "postOpaquePreTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;I)V",
            at = @At("RETURN"), remap = false, require = 0)
    private void vss$predictionBeforeWater(@Coerce Object viewport, int sourceFramebuffer, CallbackInfo ci) {
        dev.xantha.vss.client.prediction.PredictionNormalTranslucencyBridge.beforeTranslucent(this, viewport, sourceFramebuffer);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;transformBlitDepth(Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;IILme/cortex/voxy/client/core/rendering/Viewport;Lorg/joml/Matrix4f;)V"),
            remap = false, require = 0)
    private void vss$matchPredictionFog(@Coerce Object viewport, int sourceFramebuffer, int width, int height, CallbackInfo ci) {
        dev.xantha.vss.client.prediction.PredictionNormalDepthBridge.bind(vss$sourceZeroToOne);
        dev.xantha.vss.client.prediction.PredictionFogBridge.bindVoxy();
        dev.xantha.vss.client.prediction.PredictionNormalTranslucencyBridge.bindFinal(viewport, sourceFramebuffer);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("HEAD"), remap = false, require = 0)
    private void vss$enableDepthWritesForFinalBlit(CallbackInfo ci) {
        // The 1.21.1 Voxy port can declare zero-to-one while GL still maps
        // raster depth from [-1,1]. Never infer the texture encoding from that flag.
        vss$sourceZeroToOne = dev.xantha.vss.client.prediction.PredictionNormalDepthBridge.sourceZeroToOne();
        vss$previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        vss$texture4 = org.lwjgl.opengl.GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, 4);
        vss$sampler4 = org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 4);
        vss$texture5 = org.lwjgl.opengl.GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, 5);
        vss$sampler5 = org.lwjgl.opengl.GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 5);
        vss$depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GL11.glDepthMask(true);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;transformBlitDepth(Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;IILme/cortex/voxy/client/core/rendering/Viewport;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER), remap = false, require = 0)
    private void vss$captureCompositedDepth(@Coerce Object viewport, int sourceFramebuffer,
                                           int width, int height, CallbackInfo ci) {
        // finish may skip the blit when environmental fog covers the view.
        // Uncomposited offscreen geometry cannot occlude prediction.
        dev.xantha.vss.client.prediction.PredictionVoxyDepth.capture(this, viewport, sourceFramebuffer, vss$sourceZeroToOne);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("RETURN"), remap = false, require = 0)
    private void vss$restoreDepthWritesAfterFinalBlit(@Coerce Object viewport, int sourceFramebuffer,
                                                     int width, int height, CallbackInfo ci) {
        GL11.glDepthMask(vss$previousDepthMask);
        GL11.glDepthFunc(vss$depthFunction);
        int active = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, vss$texture4);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, vss$texture5);
        org.lwjgl.opengl.GL13.glActiveTexture(active);
        org.lwjgl.opengl.GL33.glBindSampler(4, vss$sampler4);
        org.lwjgl.opengl.GL33.glBindSampler(5, vss$sampler5);
    }
}

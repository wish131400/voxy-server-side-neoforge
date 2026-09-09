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

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;transformBlitDepth(Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;IILme/cortex/voxy/client/core/rendering/Viewport;Lorg/joml/Matrix4f;)V"),
            remap = false, require = 0)
    private void vss$matchPredictionFog(CallbackInfo ci) {
        dev.xantha.vss.client.prediction.PredictionFogBridge.bindVoxy();
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("HEAD"), remap = false, require = 0)
    private void vss$enableDepthWritesForFinalBlit(CallbackInfo ci) {
        vss$previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glDepthMask(true);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("RETURN"), remap = false, require = 0)
    private void vss$restoreDepthWritesAfterFinalBlit(@Coerce Object viewport, int sourceFramebuffer,
                                                     int width, int height, CallbackInfo ci) {
        GL11.glDepthMask(vss$previousDepthMask);
        dev.xantha.vss.client.prediction.PredictionVoxyDepth.capture(this, viewport, sourceFramebuffer);
    }
}

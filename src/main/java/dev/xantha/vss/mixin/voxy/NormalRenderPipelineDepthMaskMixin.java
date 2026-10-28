package dev.xantha.vss.mixin.voxy;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.NormalRenderPipeline", remap = false)
public abstract class NormalRenderPipelineDepthMaskMixin {
    @Unique private boolean vss$previousDepthMask;
    @Unique private boolean vss$sourceZeroToOne;
    @Unique private int vss$depthFunction;

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("HEAD"), remap = false, require = 0)
    private void vss$enableDepthWrites(CallbackInfo ci) {
        vss$sourceZeroToOne = dev.xantha.vss.client.prediction.PredictionNormalDepthBridge.sourceZeroToOne();
        vss$previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        vss$depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GL11.glDepthMask(true);
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V",
            at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;transformBlitDepth(Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;IILme/cortex/voxy/client/core/rendering/Viewport;Lorg/joml/Matrix4f;)V"),
            remap = false, require = 0)
    private void vss$bindFog(@Coerce Object viewport, int sourceFramebuffer, int width, int height, CallbackInfo ci) {
        dev.xantha.vss.client.prediction.PredictionNormalDepthBridge.bind(vss$sourceZeroToOne);
        dev.xantha.vss.client.prediction.PredictionFogBridge.bindVoxy();
    }

    @Inject(method = "finish(Lme/cortex/voxy/client/core/rendering/Viewport;III)V", at = @At("RETURN"), remap = false, require = 0)
    private void vss$restoreDepthWrites(CallbackInfo ci) {
        GL11.glDepthMask(vss$previousDepthMask);
        GL11.glDepthFunc(vss$depthFunction);
    }
}

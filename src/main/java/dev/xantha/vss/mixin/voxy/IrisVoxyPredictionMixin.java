package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.client.prediction.PredictionIrisBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.IrisVoxyRenderPipeline", remap = false)
public abstract class IrisVoxyPredictionMixin {
    @Inject(method = "postOpaquePreTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V",
            at = @At("HEAD"), remap = false, require = 0)
    private void vss$opaque(@Coerce Object viewport, CallbackInfo ci) {
        PredictionIrisBridge.render(this, viewport, false);
    }

    @Inject(method = "postOpaquePreTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;I)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL45C;glBlitNamedFramebuffer(IIIIIIIIIIII)V"),
            remap = false, require = 0)
    private void vss$opaqueWithSourceFramebuffer(@Coerce Object viewport, int sourceFramebuffer, CallbackInfo ci) {
        // Voxy 0.2.15 corrects shader depth before copying it to the water pass.
        PredictionIrisBridge.render(this, viewport, false);
    }

    @Inject(method = "setupAndBindTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;)V",
            at = @At("RETURN"), remap = false, require = 0)
    private void vss$translucent(@Coerce Object viewport, CallbackInfo ci) {
        PredictionIrisBridge.render(this, viewport, true);
    }

    @Inject(method = "free", at = @At("HEAD"), remap = false, require = 0)
    private void vss$release(CallbackInfo ci) { PredictionIrisBridge.release(this); }
}

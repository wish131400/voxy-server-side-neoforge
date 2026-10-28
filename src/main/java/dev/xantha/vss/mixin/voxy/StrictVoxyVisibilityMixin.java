package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictLodVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class StrictVoxyVisibilityMixin {
    @Inject(method = "shutdown()V", at = @At("HEAD"), require = 1)
    private void vss$closePredictionSource(CallbackInfo ci) { dev.xantha.vss.compat.VoxyPredictionBridge.renderClosed(this); }
    @Inject(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V", at = @At("HEAD"), require = 1)
    private void vss$strictFrame(@Coerce Object viewport, CallbackInfo ci) {
        StrictLodVisibility.beginFrame(this, viewport);
        dev.xantha.vss.client.prediction.PredictionRenderer.observeUnifiedView(viewport);
    }
}

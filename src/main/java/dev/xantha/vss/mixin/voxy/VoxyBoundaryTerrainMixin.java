package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.client.prediction.PredictionVoxyBoundaryBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bind the conservative outer-skirt mask after Voxy selects its opaque program. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer", remap = false)
public abstract class VoxyBoundaryTerrainMixin {
    @Inject(method = "renderTerrain(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;JJI)V",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER), remap = false, require = 0)
    private void vss$bindOuterSkirtMask(@Coerce Object viewport, long indirectOffset,
                                        long drawCountOffset, int maxDrawCount, CallbackInfo ci) {
        PredictionVoxyBoundaryBridge.bind();
    }

    @Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/voxy/client/core/gl/shader/Shader;bind()V",
                    shift = At.Shift.AFTER), remap = false, require = 0)
    private void vss$bindOuterSkirtMaskTranslucent(@Coerce Object viewport, CallbackInfo ci) {
        PredictionVoxyBoundaryBridge.bind();
    }
}

package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.client.prediction.PredictionIrisBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shared by immediate and deferred Iris translucency, before target/state cleanup. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer", remap = false)
public abstract class VoxyTranslucentPredictionMixin {
    @Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V",
                    shift = At.Shift.AFTER), remap = false, require = 0)
    private void vss$afterRealTranslucency(@Coerce Object viewport, CallbackInfo ci) {
        PredictionIrisBridge.finishTranslucent(viewport);
    }
}

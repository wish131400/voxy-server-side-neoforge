package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager", remap = false)
public abstract class PredictionVoxyWorldEventMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void vss$eventsReady(CallbackInfo ci) { VoxyPredictionBridge.eventHookSeen(); }

    @Redirect(method = "worldEvent", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/SectionUpdateRouter;forwardEvent(Lme/cortex/voxy/common/world/WorldSection;I)V"), require = 1)
    private void vss$renderChildren(@Coerce Object router, @Coerce Object section, int flags) {
        VoxyPredictionBridge.forwardEvent(this, router, section, flags);
    }
}

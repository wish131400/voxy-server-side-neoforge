package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", remap = false)
public abstract class PredictionVoxyMeshSourceMixin {
    @Inject(method = "processJob", at = @At("RETURN"), require = 1)
    private void vss$clearAttempt(CallbackInfo ci) { VoxyPredictionBridge.meshAttemptFinished(); }
    @Coerce
    @Redirect(method = "processJob", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory;generateMesh(Lme/cortex/voxy/common/world/WorldSection;)Lme/cortex/voxy/client/core/rendering/building/BuiltSection;"), require = 1)
    private Object vss$freshPredictionMesh(@Coerce Object factory, @Coerce Object section) {
        return VoxyPredictionBridge.generateMesh(this, factory, section);
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setResultConsumer", at = @At("HEAD"), argsOnly = true, require = 1)
    private java.util.function.Consumer<Object> vss$currentPredictionResult(java.util.function.Consumer<Object> consumer) {
        return VoxyPredictionBridge.meshConsumer(this, consumer);
    }
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void vss$sourceReady(CallbackInfo ci) { VoxyPredictionBridge.hookSeen(false); }

    @Inject(method = "acquireSection(J)Lme/cortex/voxy/common/world/WorldSection;",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void vss$predictionSnapshot(long position, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(VoxyPredictionBridge.meshSection(this, position, cir.getReturnValue()));
    }
}

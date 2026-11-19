package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.VSSPredictionThreadOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium 0.8 keeps pending values separately from persisted bindings. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.config.structure.StatefulOption", remap = false)
public abstract class SodiumPredictionThreadOptionsMixin {
    @Inject(method = "loadValueInitial()V", at = @At("RETURN"), remap = false)
    private void vss$linkPredictionControls(CallbackInfo ci) {
        VSSPredictionThreadOptions.registerSodium08(this);
    }

    @Inject(method = "modifyValue(Ljava/lang/Object;)V", at = @At("RETURN"), remap = false)
    private void vss$predictionThreadsChanged(Object value, CallbackInfo ci) {
        VSSPredictionThreadOptions.changed(this);
    }
}

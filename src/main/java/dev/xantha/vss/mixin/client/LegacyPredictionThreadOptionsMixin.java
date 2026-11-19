package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.VSSPredictionThreadOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Links the pending controls in Sodium 0.5-0.7 and Embeddium. */
@Pseudo
@Mixin(targets = {
        "net.caffeinemc.mods.sodium.client.gui.options.OptionImpl",
        "me.jellysquid.mods.sodium.client.gui.options.OptionImpl"
}, remap = false)
public abstract class LegacyPredictionThreadOptionsMixin {
    @Inject(method = "setValue(Ljava/lang/Object;)V", at = @At("RETURN"), remap = false)
    private void vss$predictionThreadsChanged(Object value, CallbackInfo ci) {
        VSSPredictionThreadOptions.changed(this);
    }
}

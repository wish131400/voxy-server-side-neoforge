package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionPauseLimit;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class PredictionPauseLimitMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void vss$pausedFrameLimit(CallbackInfoReturnable<Integer> result) {
        Minecraft client = (Minecraft) (Object) this;
        var config = VSSClientConfig.CONFIG;
        result.setReturnValue(PredictionPauseLimit.apply(result.getReturnValue(), config.predictionPausedFps,
                config.enablePrediction, client.level != null, client.isPaused()));
    }
}

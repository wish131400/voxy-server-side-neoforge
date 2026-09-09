package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.client.prediction.PredictionFogBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader", remap = false)
public abstract class PredictionFogShaderMixin {
    @Inject(method = "parse(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void vss$sharedPredictionFog(String path, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(PredictionFogBridge.patch(path, cir.getReturnValue()));
    }
}

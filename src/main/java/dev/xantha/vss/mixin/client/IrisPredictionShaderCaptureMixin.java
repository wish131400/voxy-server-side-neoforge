package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the actual entity sampler bindings after Iris applies them, only on a requested frame. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class IrisPredictionShaderCaptureMixin {
    @Inject(method = "apply", at = @At("RETURN"), require = 0, remap = false)
    private void vss$shaderApplied(CallbackInfo ci) { PredictionRenderCapture.shaderApplied(this); }
}

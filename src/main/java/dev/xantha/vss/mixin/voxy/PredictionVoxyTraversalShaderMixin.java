package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader", remap = false)
public abstract class PredictionVoxyTraversalShaderMixin {
    @Inject(method = "parse(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 1)
    private static void vss$deferredSubdivision(String path, CallbackInfoReturnable<String> cir) {
        String source=VoxyPredictionBridge.patchSubdivisionRequests(path, cir.getReturnValue());
        cir.setReturnValue(dev.xantha.vss.compat.VoxySurfaceHeights.patchShader(path,source));
    }
}

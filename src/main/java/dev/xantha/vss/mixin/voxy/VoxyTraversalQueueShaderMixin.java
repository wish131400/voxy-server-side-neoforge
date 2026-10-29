package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyTraversalShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader", remap = false)
public abstract class VoxyTraversalQueueShaderMixin {
    @Inject(method = "parse(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 1)
    private static void vss$boundedQueues(String path, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(VoxyTraversalShader.patch(path, cir.getReturnValue()));
    }
}

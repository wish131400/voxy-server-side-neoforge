package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional diagnostics; no entity internals or ETF/EMF version-specific hooks. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPredictionCaptureMixin {
    @Inject(method = "beginTranslucents", at = @At("HEAD"), require = 0, remap = false)
    private void vss$beforeDeferred(CallbackInfo ci) { PredictionRenderCapture.checkpoint("before-deferred"); }

    @Inject(method = "beginTranslucents", at = @At("RETURN"), require = 0, remap = false)
    private void vss$afterDeferred(CallbackInfo ci) { PredictionRenderCapture.checkpoint("after-deferred"); }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), require = 0, remap = false)
    private void vss$beforeComposite(CallbackInfo ci) { PredictionRenderCapture.checkpoint("before-composite"); }

    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"), require = 0, remap = false)
    private void vss$afterComposite(CallbackInfo ci) {
        PredictionRenderCapture.checkpoint("after-composite");
        PredictionRenderCapture.finish("after-composite");
    }
}

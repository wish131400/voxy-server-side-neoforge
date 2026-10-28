package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeManager", remap = false)
public abstract class PredictionVoxyDetailMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void vss$detailReady(CallbackInfo ci) { VoxyPredictionBridge.detailHookSeen(); }

    @Inject(method = "processRequest", at = @At("HEAD"), cancellable = true, require = 1)
    private void vss$requestDetail(long position, CallbackInfo ci) {
        if (!VoxyPredictionBridge.requestDetail(this, position)) ci.cancel();
    }

    @Inject(method = "makeLeafChildRequest", at = @At("HEAD"), cancellable = true, require = 1)
    private void vss$awaitSamples(int nodeId, CallbackInfo ci) {
        if (!VoxyPredictionBridge.allowSubdivision(this, nodeId)) ci.cancel();
    }

    @Inject(method = "writeNode(IJ)V", at = @At("RETURN"), require = 1)
    private void vss$waitingGpuFlag(int nodeId, long address, CallbackInfo ci) {
        VoxyPredictionBridge.writeSubdivisionState(this, address);
    }

    @Inject(method = "processChildChange", at = @At("RETURN"), require = 1)
    private void vss$publishedSamples(long position, byte children, CallbackInfo ci) {
        VoxyPredictionBridge.resumeSubdivision(this, position);
    }

    @Inject(method = "_recurseRemoveNode", at = @At("HEAD"), require = 1)
    private void vss$removedNode(long position, boolean onlyChildren, CallbackInfo ci) {
        VoxyPredictionBridge.forgetSubdivision(this, position);
    }
}

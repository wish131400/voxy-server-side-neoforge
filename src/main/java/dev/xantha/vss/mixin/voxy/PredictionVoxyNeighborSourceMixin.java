package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.compat.VoxyFluidSurface;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory", remap = false)
public abstract class PredictionVoxyNeighborSourceMixin {
    @Unique private boolean vss$hasFluidHeights;
    @Unique private boolean vss$legacyFluid;
    @Unique private long[] vss$fluidVoxels;
    @Shadow private static long packPartialQuadData(int modelId,long state,long metadata) {throw new AssertionError();}
    @Inject(method="prepareSectionData",at=@At("HEAD"),require=1)
    private void vss$beginFluidHeights(long[] raw,CallbackInfoReturnable<Integer> ci) {
        vss$hasFluidHeights=false;vss$fluidVoxels=raw;
    }
    @Redirect(method="prepareSectionData",at=@At(value="INVOKE",target=
            "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory;packPartialQuadData(IJJ)J"),require=1)
    private long vss$packFluidHeight(int model,long state,long metadata) {
        long original=packPartialQuadData(model,state,metadata);
        if(!vss$legacyFluid)return original;
        long packed=VoxyFluidSurface.pack(original,state,metadata);
        vss$hasFluidHeights|=(state&16L)!=0 && (metadata&(1L<<52))!=0;
        return packed;
    }
    @Inject(method="generateXFaces",at=@At("RETURN"),require=1)
    private void vss$liquidTransitions(CallbackInfo ci) {
        try {if(vss$legacyFluid)VoxyFluidSurface.meshTransitions(this,vss$fluidVoxels,vss$hasFluidHeights);}
        finally {vss$fluidVoxels=null;}
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void vss$neighborsReady(CallbackInfo ci) {
        vss$legacyFluid=VoxyFluidSurface.legacyMesher(getClass());
        VoxyPredictionBridge.hookSeen(true);
    }

    @Group(name="vss$faceReader",min=1,max=1)
    @Inject(method="acquireNeighborData(Lme/cortex/voxy/common/world/WorldSection;I)V",at=@At("HEAD"),cancellable=true,require=0)
    private void vss$copyFaces(@Coerce Object section,int mask,CallbackInfo ci) {
        if(VoxyPredictionBridge.copyNeighborFaces(this,section,mask))ci.cancel();
    }
    @Group(name="vss$faceReader",min=1,max=1)
    @Inject(method="acquireNeighborData(Lme/cortex/voxy/common/world/WorldSection;I)I",at=@At("HEAD"),cancellable=true,require=0)
    private void vss$copyFacesWithAvailability(@Coerce Object section,int mask,CallbackInfoReturnable<Integer> ci) {
        // Availability-returning backends retain their native missing-neighbor policy.
        // Their layout is deliberately excluded from the optional plane-reader allowlist.
    }

    @Coerce
    @Group(name = "vss$neighbors", min = 6, max = 6)
    @Redirect(method = "acquireNeighborData", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/common/world/WorldEngine;acquire(IIII)Lme/cortex/voxy/common/world/WorldSection;"), require = 0)
    private Object vss$predictionNeighbor(@Coerce Object world, int lod, int x, int y, int z) {
        return VoxyPredictionBridge.neighborSection(world, lod, x, y, z);
    }

    @Coerce
    @Group(name = "vss$neighbors", min = 6, max = 6)
    @Redirect(method = "acquireNeighborData", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/common/world/WorldEngine;acquireIfExists(IIII)Lme/cortex/voxy/common/world/WorldSection;"), require = 0)
    private Object vss$predictionNeighborIfExists(@Coerce Object world, int lod, int x, int y, int z) {
        return VoxyPredictionBridge.neighborSectionIfExists(world, lod, x, y, z);
    }
}

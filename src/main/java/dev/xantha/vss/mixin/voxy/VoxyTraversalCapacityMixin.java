package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyCapacityPlan;
import dev.xantha.vss.compat.VoxyTraversalCapacity;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser", remap = false)
public abstract class VoxyTraversalCapacityMixin implements VoxyTraversalCapacity.Owner {
    @Shadow private int topNodeCount;
    @Shadow @Final private Int2IntOpenHashMap topNode2idxMapping;
    @Unique private VoxyCapacityPlan vss$initialPlan;
    @Unique private VoxyTraversalCapacity vss$capacity;

    @Unique private VoxyCapacityPlan vss$plan() {
        if (vss$initialPlan == null) vss$initialPlan = VoxyTraversalCapacity.initialPlan();
        return vss$initialPlan;
    }
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 200000), require = 1)
    private int vss$initialRootArray(int original) { return vss$plan().initialCapacity(); }

    @ModifyConstant(method = "<init>", constant = @Constant(longValue = 800000L), require = 3)
    private long vss$initialQueues(long original) { return vss$plan().initialCapacity() * 4L; }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void vss$created(CallbackInfo ci) { vss$capacity = new VoxyTraversalCapacity(this, vss$plan()); }

    @Inject(method = "addTLN", at = @At("HEAD"), require = 1)
    private void vss$reserveRoot(int id, CallbackInfo ci) { vss$capacity.beforeAdd(id, topNodeCount, topNode2idxMapping); }
    @Inject(method = "remTLN", at = @At("HEAD"), require = 1)
    private void vss$verifyRemoval(int id, CallbackInfo ci) { vss$capacity.beforeRemove(id, topNodeCount, topNode2idxMapping); }
    @Inject(method = "addTLN", at = @At("RETURN"), require = 1)
    private void vss$addedRoot(int id, CallbackInfo ci) { vss$capacity.changed(topNodeCount, topNode2idxMapping.size(), true); }
    @Inject(method = "remTLN", at = @At("RETURN"), require = 1)
    private void vss$removedRoot(int id, CallbackInfo ci) { vss$capacity.changed(topNodeCount, topNode2idxMapping.size(), false); }
    @Inject(method = "traverseInternal", at = @At("HEAD"), require = 1)
    private void vss$reserveTraversal(CallbackInfo ci) { vss$capacity.beforeTraversal(); }

    @Override public VoxyTraversalCapacity vss$capacity() { return vss$capacity; }
}

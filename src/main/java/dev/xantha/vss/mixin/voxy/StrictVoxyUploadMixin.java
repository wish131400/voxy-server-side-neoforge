package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyPipeline;
import java.lang.invoke.VarHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager", remap = false)
public abstract class StrictVoxyUploadMixin implements dev.xantha.vss.compat.StrictVoxyWorkState {
    @Unique private volatile boolean vss$publishing;
    @Unique private final java.util.List<StrictVoxyPipeline.Work> vss$completed = new java.util.ArrayList<>();

    @Redirect(method = "run()V", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentLinkedDeque;poll()Ljava/lang/Object;", ordinal = 1), require = 1)
    private Object vss$geometryResult(java.util.concurrent.ConcurrentLinkedDeque<?> queue) {
        Object result = queue.poll();
        if (result instanceof StrictVoxyPipeline.Mesh mesh && mesh.vss$work() != null) vss$completed.add(mesh.vss$work());
        return result;
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE",
            target = "Ljava/lang/invoke/VarHandle;compareAndSet(Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;Ljava/lang/Void;Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager$SyncResults;)Z"), require = 1)
    private boolean vss$publishWork(VarHandle handle, @Coerce Object owner, Void expected, @Coerce Object result) {
        ((StrictVoxyPipeline.Batch) result).vss$completedWork().addAll(vss$completed);
        vss$completed.clear();
        return handle.compareAndSet(owner, expected, result);
    }

    @Override public boolean vss$isPublishing() { return vss$publishing; }

    @Inject(method = "run()V", at = @At(value = "INVOKE", ordinal = 0,
            target = "Ljava/util/concurrent/atomic/AtomicInteger;addAndGet(I)I"), require = 1)
    private void vss$beginPublish(CallbackInfo ci) { vss$publishing = true; }

    @Inject(method = "run()V", at = @At("RETURN"), require = 1)
    private void vss$endPublish(CallbackInfo ci) { vss$publishing = false; }

    @Redirect(method = "tick", at = @At(value = "INVOKE", ordinal = 0,
            target = "Ljava/lang/invoke/VarHandle;compareAndSet(Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager;Ljava/lang/Void;Lme/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager$SyncResults;)Z"), require = 1)
    private boolean vss$uploaded(VarHandle handle, @Coerce Object owner, Void expected, @Coerce Object result) {
        StrictLodVisibility.uploaded(owner, result);
        for (var work : ((StrictVoxyPipeline.Batch) result).vss$completedWork()) work.uploaded();
        ((StrictVoxyPipeline.Batch) result).vss$completedWork().clear();
        return handle.compareAndSet(owner, expected, result);
    }
}

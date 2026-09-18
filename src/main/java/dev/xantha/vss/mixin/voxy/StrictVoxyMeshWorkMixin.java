package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.StrictVoxyPipeline;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", remap = false)
public abstract class StrictVoxyMeshWorkMixin implements StrictVoxyPipeline.Source {
    @Unique private final StrictVoxyPipeline vss$pipeline = new StrictVoxyPipeline();
    @Unique private final ThreadLocal<StrictVoxyPipeline.Work> vss$current = new ThreadLocal<>();
    public StrictVoxyPipeline vss$pipeline() { return vss$pipeline; }

    // Track only new tasks, before a worker can take them. A deduplicated enqueue is not another task.
    @Redirect(method = "enqueueTask(J)V", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/PriorityBlockingQueue;add(Ljava/lang/Object;)Z"), require = 1)
    private boolean vss$queued(PriorityBlockingQueue<Object> queue, Object task) {
        vss$pipeline.queued(StrictLodVisibility.meshPosition(task));
        return queue.add(task);
    }

    @Redirect(method = "processJob", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/PriorityBlockingQueue;poll()Ljava/lang/Object;"), require = 1)
    private Object vss$started(PriorityBlockingQueue<?> queue) {
        Object task = queue.poll();
        if (task != null) vss$current.set(vss$pipeline.started(StrictLodVisibility.meshPosition(task)));
        return task;
    }

    @Redirect(method = "processJob", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"), require = 2)
    private void vss$result(Consumer<Object> consumer, Object mesh) {
        ((StrictVoxyPipeline.Mesh) mesh).vss$work(vss$current.get());
        consumer.accept(mesh);
    }

    @Inject(method = "processJob", at = @At("RETURN"), require = 1)
    private void vss$finishedAttempt(CallbackInfo ci) { vss$current.remove(); }
}

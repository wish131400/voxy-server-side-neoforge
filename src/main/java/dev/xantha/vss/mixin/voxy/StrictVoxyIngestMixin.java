package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictLodVisibility;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class StrictVoxyIngestMixin {
    @Unique private final ThreadLocal<Object> vss$currentIngest = new ThreadLocal<>();

    @Redirect(method = "processJob()V", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentLinkedDeque;pop()Ljava/lang/Object;"), require = 1)
    private Object vss$trackIngest(ConcurrentLinkedDeque<?> queue) {
        Object task = queue.pop(); vss$currentIngest.set(task); return task;
    }
    @Inject(method = "processJob()V", at = @At("RETURN"), require = 1)
    private void vss$committedIngest(CallbackInfo ci) {
        StrictLodVisibility.ingestCompleted(vss$currentIngest.get());
        vss$currentIngest.remove();
    }
}

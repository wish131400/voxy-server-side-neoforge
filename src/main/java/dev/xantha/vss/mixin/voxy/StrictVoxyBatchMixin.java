package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictVoxyPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager$SyncResults", remap = false)
public abstract class StrictVoxyBatchMixin implements StrictVoxyPipeline.Batch {
    @Unique private java.util.List<StrictVoxyPipeline.Work> vss$completed = new java.util.ArrayList<>();

    @Override
    public synchronized void vss$appendCompletedWork(java.util.Collection<StrictVoxyPipeline.Work> work) {
        vss$completed.addAll(work);
    }

    @Override
    public synchronized java.util.List<StrictVoxyPipeline.Work> vss$takeCompletedWork() {
        var completed = vss$completed;
        vss$completed = new java.util.ArrayList<>();
        return completed;
    }

    @Override
    public synchronized void vss$restoreCompletedWork(java.util.Collection<StrictVoxyPipeline.Work> work) {
        if (work.isEmpty()) return;
        var restored = new java.util.ArrayList<StrictVoxyPipeline.Work>(work.size() + vss$completed.size());
        restored.addAll(work);
        restored.addAll(vss$completed);
        vss$completed = restored;
    }
}

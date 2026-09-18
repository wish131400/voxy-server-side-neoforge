package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictVoxyPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager$SyncResults", remap = false)
public abstract class StrictVoxyBatchMixin implements StrictVoxyPipeline.Batch {
    @Unique private final java.util.List<StrictVoxyPipeline.Work> vss$completed = new java.util.ArrayList<>();
    public java.util.List<StrictVoxyPipeline.Work> vss$completedWork() { return vss$completed; }
}

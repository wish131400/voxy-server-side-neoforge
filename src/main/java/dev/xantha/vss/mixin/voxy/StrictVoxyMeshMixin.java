package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictVoxyPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.BuiltSection", remap = false)
public abstract class StrictVoxyMeshMixin implements StrictVoxyPipeline.Mesh {
    @Unique private StrictVoxyPipeline.Work vss$work;
    public StrictVoxyPipeline.Work vss$work() { return vss$work; }
    public void vss$work(StrictVoxyPipeline.Work work) { vss$work = work; }
}

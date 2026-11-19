package dev.xantha.vss.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context")
public interface SurfaceRulesContextAccessor {
    @Accessor("blockX") int vss$blockX();
    @Accessor("blockY") int vss$blockY();
    @Accessor("blockZ") int vss$blockZ();
}

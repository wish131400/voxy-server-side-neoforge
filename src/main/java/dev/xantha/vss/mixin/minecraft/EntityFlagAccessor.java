package dev.xantha.vss.mixin.minecraft;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityFlagAccessor {
    @Invoker("setSharedFlag")
    void vss$setSharedFlag(int flag, boolean enabled);
}

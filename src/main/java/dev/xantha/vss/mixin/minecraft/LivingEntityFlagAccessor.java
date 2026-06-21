package dev.xantha.vss.mixin.minecraft;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityFlagAccessor {
    @Invoker("setLivingEntityFlag")
    void vss$setLivingEntityFlag(int flag, boolean enabled);

    @Invoker("updateSwingTime")
    void vss$updateSwingTime();

    @Accessor("useItem")
    void vss$setUseItem(ItemStack stack);

    @Accessor("useItemRemaining")
    int vss$getUseItemRemaining();

    @Accessor("useItemRemaining")
    void vss$setUseItemRemaining(int ticks);

    @Accessor("fallFlyTicks")
    void vss$setFallFlyTicks(int ticks);
}

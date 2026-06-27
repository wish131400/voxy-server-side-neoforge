package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.FallFlyingFlagAccess;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Entity.class)
public abstract class EntityFallFlyingFlagAccessorMixin implements FallFlyingFlagAccess {
    private static final int FALL_FLYING_FLAG = 7;

    @Shadow
    protected abstract void setSharedFlag(int flag, boolean value);

    @Override
    public void vss$setFallFlyingFlag(boolean fallFlying) {
        setSharedFlag(FALL_FLYING_FLAG, fallFlying);
    }
}

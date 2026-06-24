package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.networking.server.DirtyColumnBroadcaster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelBlockUpdateDirtyColumnMixin {
    @Inject(method = "sendBlockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;I)V", at = @At("HEAD"), require = 0)
    private void vss$markSentBlockUpdateDirty(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        DirtyColumnBroadcaster.markDirtyBlock((Object) this, pos);
    }
}

package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.networking.server.dirty.DirtyColumnBroadcaster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkDirtyColumnMixin {
    @Shadow
    private Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void vss$markServerColumnDirty(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) {
            DirtyColumnBroadcaster.markDirtyBlock(this.level, pos);
        }
    }
}

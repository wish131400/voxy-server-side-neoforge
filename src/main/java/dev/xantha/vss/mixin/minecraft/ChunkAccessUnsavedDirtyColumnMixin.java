package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.networking.server.DirtyColumnBroadcaster;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessUnsavedDirtyColumnMixin {
    @Inject(method = "setUnsaved", at = @At("RETURN"))
    private void vss$markUnsavedChunkDirty(boolean unsaved, CallbackInfo ci) {
        if (!unsaved || !((Object) this instanceof LevelChunk chunk)) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        DirtyColumnBroadcaster.markDirtyColumn(chunk.getLevel(), pos.x, pos.z);
    }
}

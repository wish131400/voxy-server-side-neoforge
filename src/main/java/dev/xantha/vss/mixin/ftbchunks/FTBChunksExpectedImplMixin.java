package dev.xantha.vss.mixin.ftbchunks;

import dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.forge.FTBChunksExpectedImpl", remap = false)
public final class FTBChunksExpectedImplMixin {
    @Inject(method = "addChunkToForceLoaded", at = @At("HEAD"), cancellable = true)
    private static void vss$makeFtbChunksForceLoadAsync(ServerLevel level, String modId, UUID owner, int chunkX, int chunkZ, boolean add, CallbackInfo ci) {
        if (FTBChunksForceLoadCompat.tryHandle(level, modId, owner, chunkX, chunkZ, add)) {
            ci.cancel();
        }
    }
}

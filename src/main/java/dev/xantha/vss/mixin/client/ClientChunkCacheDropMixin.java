package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.VSSClientNetworking;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheDropMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "drop", at = @At("HEAD"))
    private void vss$forgetSyncedLodColumnOnChunkDrop(ChunkPos pos, CallbackInfo ci) {
        VSSClientNetworking.onClientChunkDropped(level.dimension(), pos.x, pos.z);
    }
}

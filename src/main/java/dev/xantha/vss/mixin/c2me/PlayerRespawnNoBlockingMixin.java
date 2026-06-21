package dev.xantha.vss.mixin.c2me;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerRespawnNoBlockingMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private static void vss$avoidBlockingRespawnChunkLoad(
            ServerLevel level,
            BlockPos pos,
            float angle,
            boolean forced,
            boolean alive,
            CallbackInfoReturnable<Optional<Vec3>> cir) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            cir.setReturnValue(Optional.empty());
        }
    }
}

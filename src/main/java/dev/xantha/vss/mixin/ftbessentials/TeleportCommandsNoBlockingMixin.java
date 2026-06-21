package dev.xantha.vss.mixin.ftbessentials;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "dev.ftb.mods.ftbessentials.command.TeleportCommands", remap = false)
public abstract class TeleportCommandsNoBlockingMixin {
    @Redirect(
            method = "findBlockPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;m_204166_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private static Holder<Biome> vss$getBiomeWithoutLoading(ServerLevel level, BlockPos pos) {
        if (!vss$isChunkLoaded(level, pos)) {
            return level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        }
        return level.getBiome(pos);
    }

    @Redirect(
            method = "findBlockPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;m_46745_(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private static LevelChunk vss$getChunkWithoutBlocking(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Redirect(
            method = "findBlockPos",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;m_5452_(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos vss$getHeightmapPosWithoutLoading(ServerLevel level, Heightmap.Types type, BlockPos pos) {
        if (!vss$isChunkLoaded(level, pos)) {
            return new BlockPos(pos.getX(), 0, pos.getZ());
        }
        return level.getHeightmapPos(type, pos);
    }

    @Unique
    private static boolean vss$isChunkLoaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
}

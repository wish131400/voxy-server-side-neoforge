package dev.xantha.vss.networking.client;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

final class IntegratedPregenImporter {
    private static final LocalPregenTasks TASKS = new LocalPregenTasks(128, 32L * 1024 * 1024);

    static boolean supports(MinecraftServer server) {
        var local = Minecraft.getInstance().getSingleplayerServer();
        return local != null && local == server && !local.isPublished()
                && ModCompat.isVoxyLoaded() && VSSClientConfig.CONFIG.receiveServerLods
                && VSSClientNetworking.isClientLodSessionActive();
    }

    static boolean offer(MinecraftServer server, ResourceKey<Level> dimension, EncodedColumnData data,
            LodRequestManager manager, BooleanSupplier valid, Consumer<Boolean> completed) {
        var level = Minecraft.getInstance().level;
        if (!supports(server) || manager == null || level == null || !level.dimension().equals(dimension)
                || !data.completeColumn() || !data.hasBody()) return false;
        return TASKS.offer(data.encodedSize(), () -> {
            if (!valid.getAsBoolean() || !supports(server) || Minecraft.getInstance().level != level
                    || VSSClientNetworking.currentPregenManager() != manager) return false;
            if (data.encodedCrc32c() != EncodedColumnData.crc32c(data.encodedBytes())) return false;
            var part = new VoxelColumnS2CPayload(-1, dimension, data, 0L, 0, 1, data.sectionYs());
            var assembled = new ClientColumnTransferAssembler.AssembledColumn(-1, 0L,
                    data.chunkX(), data.chunkZ(), dimension, data.columnStamp(), false, true,
                    List.of(part), data.encodedSize());
            var decoded = ClientColumnProcessor.decodeColumn(assembled, level.registryAccess().registryOrThrow(Registries.BIOME));
            if (!valid.getAsBoolean() || !supports(server) || Minecraft.getInstance().level != level
                    || VSSClientNetworking.currentPregenManager() != manager) return false;
            return manager.processLocalPregenColumn(dimension, data.chunkX(), data.chunkZ(), data.columnStamp(),
                    decoded.replacementSectionYs(), () -> {
                        if (!valid.getAsBoolean() || Minecraft.getInstance().level != level) return false;
                        boolean accepted = VSSApi.dispatchColumnAndReport(level, dimension, data.chunkX(), data.chunkZ(), decoded);
                        if (accepted) {
                            manager.recordStrictSections(data.chunkX(), data.chunkZ(), decoded);
                            ClientPredictionState.onExactColumn(dimension, data.chunkX(), data.chunkZ(), decoded);
                        }
                        return accepted;
                    });
        }, completed);
    }
}

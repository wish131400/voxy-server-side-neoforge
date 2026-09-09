package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionDiagnosticsTest {
    @Test void statisticsAcceptEmptyAndDifferentLengthDimensionLayouts() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var field = ClientPredictionState.class.getDeclaredField("MANAGERS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var managers = (Map<ResourceKey<Level>, PredictionTileManager>) field.get(null);
        var previous = Map.copyOf(managers);
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        managers.clear();
        try {
            assertDoesNotThrow(ClientPredictionState::diagnostics);
            for (int horizon : new int[]{1024, 65536}) {
                VSSClientConfig.CONFIG.predictionDistanceBlocks = horizon;
                var dimension = horizon == 1024 ? Level.OVERWORLD : Level.NETHER;
                var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),
                        42L, -64, 384, "noise", "minecraft:overworld", 1L);
                var manager = new PredictionTileManager(dimension, new ClientTerrainSampler(42, profile),
                        new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null);
                managers.put(dimension, manager);
                String stats = assertDoesNotThrow(ClientPredictionState::diagnostics);
                int levels = managers.values().stream().mapToInt(m -> m.readyLodCounts().length).max().orElseThrow();
                assertTrue(stats.contains((levels - 1) + ":0"));
                assertFalse(stats.contains("|" + levels + ":"), "do not report nonexistent legacy LOD levels");
            }
            managers.remove(Level.NETHER).close();
            assertDoesNotThrow(ClientPredictionState::diagnostics);
        } finally {
            managers.values().forEach(PredictionTileManager::close);
            managers.clear(); managers.putAll(previous);
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
        }
    }
}

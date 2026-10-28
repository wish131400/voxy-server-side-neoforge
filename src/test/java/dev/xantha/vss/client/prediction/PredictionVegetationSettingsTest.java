package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionVegetationSettingsTest {
    @BeforeAll static void bootstrap() {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build", "tmp", "vegetation-settings-tests"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (var block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates()) {
            if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
            state.initCache();
        }
        var tags = BuiltInRegistries.BLOCK.getTags().collect(java.util.stream.Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        tags.put(BlockTags.LOGS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LOG)));
        tags.put(BlockTags.LEAVES, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_LEAVES)));
        tags.put(BlockTags.PLANKS, List.of(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.OAK_PLANKS)));
        BuiltInRegistries.BLOCK.bindTags(tags);
        PredictionVegetationTraits.invalidate();
    }

    @Test void obsoleteContentSwitchesAndDistancesCannotDisableCompleteVoxels() throws Exception {
        var gson = new Gson();
        var config = normalized(gson.fromJson("{\"predictionTrees\":false,\"predictionStructures\":false,"
                + "\"predictionFineDistanceBlocks\":1,\"predictionSurfaceDistanceBlocks\":1,"
                + "\"predictionVegetationDensity\":\"low\",\"predictionDistanceBlocks\":65536}", VSSClientConfig.class));
        assertEquals(65536, config.predictionDistanceBlocks);
        String saved = gson.toJson(config);
        for (String field : List.of("predictionTrees", "predictionStructures", "predictionFineDistanceBlocks",
                "predictionSurfaceDistanceBlocks", "predictionVegetationDensity")) {
            assertFalse(saved.contains(field), field);
            assertThrows(NoSuchFieldException.class, () -> VSSClientConfig.class.getDeclaredField(field));
        }
    }

    @Test void spyglassSwitchSuppressesFocusAndCancelsScopedWorkOnly() throws Exception {
        var config = VSSClientConfig.CONFIG;
        boolean previous = config.predictionSpyglassLoading;
        var focus = new VssLodFocus(2048, 0, 128, 5000);
        try (var manager = manager()) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            @SuppressWarnings("unchecked") var desired = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "desiredKeys");
            @SuppressWarnings("unchecked") var scoped = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "scopePending");
            desired.add(key); scoped.add(key);
            var current = PredictionTileManager.class.getDeclaredMethod("captureCurrent",
                    PredictionTileManager.PredictionTileKey.class, long.class, long.class); current.setAccessible(true);
            long revision = ((AtomicLong) field(manager, "meshRevision")).get();
            config.predictionSpyglassLoading = true;
            assertEquals(true, current.invoke(manager, key, 0L, revision));
            config.predictionSpyglassLoading = false;
            assertEquals(false, current.invoke(manager, key, 0L, revision));
            scoped.remove(key);
            assertEquals(true, current.invoke(manager, key, 0L, revision), "ordinary work continues");
            manager.tick(0, 64, 0, 100, focus, 0);
            assertNull(field(manager, "buildFocus"));
            assertTrue(scoped.isEmpty(), "disabled targeting must not queue scope tasks");
        } finally {
            config.predictionSpyglassLoading = previous;
        }
    }

    static VSSClientConfig normalized(VSSClientConfig config) throws Exception {
        var method = VSSClientConfig.class.getDeclaredMethod("validate"); method.setAccessible(true); method.invoke(config);
        return config;
    }

    static PredictionTileManager manager() {
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
                "noise", "minecraft:overworld", 1L);
        var sampler = ClientTerrainSampler.custom(42L, profile, (x, z) -> 64);
        var budget = new PredictionMemoryBudget(PredictionMemoryBudget.BUILD_BYTES - 1, 0,
                () -> Long.MAX_VALUE, System::nanoTime);
        return new PredictionTileManager(Level.OVERWORLD, sampler, budget, null);
    }

    static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }

    static void tree(Map<BlockPos, BlockState> blocks, int x, int z) {
        blocks.put(new BlockPos(x, 64, z), Blocks.OAK_LOG.defaultBlockState());
        blocks.put(new BlockPos(x, 65, z), Blocks.OAK_LOG.defaultBlockState());
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            blocks.put(new BlockPos(x + dx, 66, z + dz), Blocks.OAK_LEAVES.defaultBlockState());
    }
}

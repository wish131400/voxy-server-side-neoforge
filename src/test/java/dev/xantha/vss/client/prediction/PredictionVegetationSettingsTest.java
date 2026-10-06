package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import dev.xantha.vss.config.PredictionVegetationDensity;
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

    @Test void defaultsAndLegacyConfigurationMigrateToMediumOrLow() throws Exception {
        var gson = new Gson();
        var fresh = normalized(new VSSClientConfig());
        assertEquals("medium", fresh.predictionVegetationDensity);
        assertTrue(fresh.predictionSpyglassLoading);
        assertEquals("medium", normalized(gson.fromJson("{\"predictionTrees\":true}", VSSClientConfig.class)).predictionVegetationDensity);
        var disabled = normalized(gson.fromJson("{\"predictionTrees\":false}", VSSClientConfig.class));
        assertEquals("low", disabled.predictionVegetationDensity);
        assertTrue(disabled.predictionTrees);
        var explicit = normalized(gson.fromJson(
                "{\"predictionTrees\":false,\"predictionVegetationDensity\":\"HIGH\",\"predictionSpyglassLoading\":false}", VSSClientConfig.class));
        assertEquals("high", explicit.predictionVegetationDensity);
        assertFalse(explicit.predictionSpyglassLoading);
        assertEquals("medium", normalized(gson.fromJson("{\"predictionVegetationDensity\":\"unknown\"}", VSSClientConfig.class)).predictionVegetationDensity);
        var roundTrip = normalized(gson.fromJson(gson.toJson(explicit), VSSClientConfig.class));
        assertEquals(explicit.predictionVegetationDensity, roundTrip.predictionVegetationDensity);
        assertEquals(explicit.predictionSpyglassLoading, roundTrip.predictionSpyglassLoading);
    }

    @Test void densityChoicesAreStableNestedSubsetsWithTheExpectedRatios() {
        int low = 0, medium = 0, total = 256 * 128;
        for (int z = -64; z < 64; z++) for (int x = -128; x < 128; x++) {
            boolean a = PredictionVegetationDensity.LOW.keep(x, z);
            boolean b = PredictionVegetationDensity.MEDIUM.keep(x, z);
            assertTrue(!a || b);
            assertTrue(PredictionVegetationDensity.HIGH.keep(x, z));
            assertEquals(a, PredictionVegetationDensity.LOW.keep(x, z));
            if (a) low++;
            if (b) medium++;
        }
        assertEquals(.25, low / (double) total, .02);
        assertEquals(.5, medium / (double) total, .02);
        System.out.println("VEGETATION_RATIO low=" + low + "/" + total + ", medium=" + medium + "/" + total);
    }

    @Test void selectedTreesAndTallPlantsKeepEverySegmentWithoutChangingCanonicalData() {
        Map<BlockPos, BlockState> original = new LinkedHashMap<>();
        for (int z = -4; z < 4; z++) for (int x = -4; x < 4; x++) {
            tree(original, x * 12, z * 12);
            original.put(new BlockPos(x * 12 + 5, 64, z * 12 + 5), Blocks.TALL_GRASS.defaultBlockState());
            original.put(new BlockPos(x * 12 + 5, 65, z * 12 + 5), Blocks.TALL_GRASS.defaultBlockState());
        }
        var snapshot = Map.copyOf(original);
        for (var density : PredictionVegetationDensity.values()) {
            var selected = PredictionVegetationSelection.select(original, density);
            for (int z = -4; z < 4; z++) for (int x = -4; x < 4; x++) {
                var expectedTree = new HashMap<BlockPos, BlockState>(); tree(expectedTree, x * 12, z * 12);
                for (var pos : expectedTree.keySet())
                    assertEquals(density.keep(x * 12, z * 12), selected.containsKey(pos), pos.toString());
                assertEquals(density.keep(x * 12 + 5, z * 12 + 5), selected.containsKey(new BlockPos(x * 12 + 5, 64, z * 12 + 5)));
                assertEquals(selected.containsKey(new BlockPos(x * 12 + 5, 64, z * 12 + 5)),
                        selected.containsKey(new BlockPos(x * 12 + 5, 65, z * 12 + 5)));
            }
        }
        assertEquals(snapshot, original);
        var reversed = new LinkedHashMap<BlockPos, BlockState>();
        var keys = new ArrayList<>(original.keySet()); Collections.reverse(keys);
        for (var key : keys) reversed.put(key, original.get(key));
        assertEquals(PredictionVegetationSelection.select(original, PredictionVegetationDensity.MEDIUM),
                PredictionVegetationSelection.select(reversed, PredictionVegetationDensity.MEDIUM));
    }

    @Test void selectionPreservesBuildingsTerrainCutsFluidsAndStandaloneTemplateWood() {
        int x = 0;
        while (PredictionVegetationDensity.LOW.keep(x, 0)) x++;
        var original = new HashMap<BlockPos, BlockState>();
        tree(original, x, 0);
        original.put(new BlockPos(x + 1, 64, 0), Blocks.OAK_PLANKS.defaultBlockState());
        original.put(new BlockPos(x + 8, 64, 0), Blocks.OAK_LOG.defaultBlockState());
        original.put(new BlockPos(x + 9, 64, 0), Blocks.STONE.defaultBlockState());
        original.put(new BlockPos(x + 10, 64, 0), Blocks.WATER.defaultBlockState());
        original.put(new BlockPos(x + 11, 64, 0), Blocks.AIR.defaultBlockState());
        original.put(new BlockPos(x + 12, 64, 0), Blocks.SNOW.defaultBlockState());
        original.put(new BlockPos(x + 13, 64, 0), Blocks.OAK_LEAVES.defaultBlockState());
        assertEquals(original, PredictionVegetationSelection.select(original, PredictionVegetationDensity.LOW));
    }

    @Test void clippingAtTileBoundariesKeepsTheSameWholeTreeDecision() {
        int z = 4;
        while (PredictionVegetationDensity.MEDIUM.keep(31, z)) z++;
        var blocks = new HashMap<BlockPos, BlockState>(); tree(blocks, 31, z);
        var sources = List.of(new PredictionVegetationDisplayCache.Source(0, Map.copyOf(blocks)));
        var cache = new PredictionVegetationDisplayCache();
        for (var density : List.of(PredictionVegetationDensity.MEDIUM, PredictionVegetationDensity.HIGH)) {
            for (int baseX : new int[]{0, 32}) {
                try (var build = cache.begin(baseX, 0, 32, 1, true, density)) {
                    var tile = cache.tile(build, sources, ignored -> false);
                    assertEquals(density == PredictionVegetationDensity.HIGH, !tile.blocks().isEmpty());
                }
            }
        }
        assertEquals(blocks, sources.get(0).blocks());
    }

    @Test void displayCacheSeparatesDensitiesAndReusesCompleteSourceSelections() {
        var cache = new PredictionVegetationDisplayCache();
        var original = new HashMap<BlockPos, BlockState>();
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++)
            original.put(new BlockPos(x, 64, z), Blocks.SHORT_GRASS.defaultBlockState());
        var sources = List.of(new PredictionVegetationDisplayCache.Source(0, Map.copyOf(original)));
        var outputs = new EnumMap<PredictionVegetationDensity, PredictionVegetation.Tile>(PredictionVegetationDensity.class);
        for (var density : PredictionVegetationDensity.values()) {
            try (var build = cache.begin(0, 0, 32, 1, true, density)) {
                var tile = cache.tile(build, sources, ignored -> false); outputs.put(density, tile);
                for (var pos : original.keySet()) assertEquals(density.keep(pos.getX(), pos.getZ()), tile.blocks().containsKey(pos));
            }
            try (var build = cache.begin(0, 0, 32, 1, true, density)) {
                assertSame(outputs.get(density), cache.tile(build, sources, ignored -> false));
            }
        }
        assertTrue(outputs.get(PredictionVegetationDensity.LOW).blocks().size()
                < outputs.get(PredictionVegetationDensity.MEDIUM).blocks().size());
        assertSame(outputs.get(PredictionVegetationDensity.HIGH), cache.tile(0, 0, 32, 1, true, sources, ignored -> false));
        cache.invalidate(0, 0);
        original.put(new BlockPos(0, 65, 0), Blocks.STONE.defaultBlockState());
        var changed = List.of(new PredictionVegetationDisplayCache.Source(0, Map.copyOf(original)));
        try (var build = cache.begin(0, 0, 32, 1, true, PredictionVegetationDensity.MEDIUM)) {
            var tile = cache.tile(build, changed, ignored -> false);
            assertNotSame(outputs.get(PredictionVegetationDensity.MEDIUM), tile);
            assertEquals(Blocks.STONE.defaultBlockState(), tile.blocks().get(new BlockPos(0, 65, 0)));
        }
    }

    @Test void sourceDensitySelectionsRefreshWhenRegistryTagsChange() {
        int x = 0;
        while (PredictionVegetationDensity.MEDIUM.keep(x, 0)) x++;
        var sources = List.of(new PredictionVegetationDisplayCache.Source(0, Map.of(
                new BlockPos(x, 64, 0), Blocks.OAK_LOG.defaultBlockState(),
                new BlockPos(x, 65, 0), Blocks.STONE.defaultBlockState())));
        var cache = new PredictionVegetationDisplayCache();
        var tags = BuiltInRegistries.BLOCK.getTags().collect(java.util.stream.Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        try {
            try (var build = cache.begin(0, 0, 32, 1, true, PredictionVegetationDensity.MEDIUM)) {
                assertEquals(sources.get(0).blocks(), cache.tile(build, sources, ignored -> false).blocks());
            }
            var changedTags = new HashMap<>(tags);
            var leaves = new ArrayList<>(tags.get(BlockTags.LEAVES));
            leaves.add(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE));
            changedTags.put(BlockTags.LEAVES, leaves);
            BuiltInRegistries.BLOCK.bindTags(changedTags);
            PredictionVegetationTraits.invalidate();
            try (var build = cache.begin(0, 0, 32, 1, true, PredictionVegetationDensity.MEDIUM)) {
                assertTrue(cache.tile(build, sources, ignored -> false).blocks().isEmpty(),
                        "same source map must be reselected after plant classification changes");
            }
        } finally {
            BuiltInRegistries.BLOCK.bindTags(tags);
            PredictionVegetationTraits.invalidate();
        }
    }

    @Test void simpleRepresentativesUseTheSameStableDensityAndUpdateBounds() {
        var forms = new ArrayList<PredictionSimpleVegetation.Form>();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            forms.add(new PredictionSimpleVegetation.Form(0, x, z, 64, 6, null, 0x55aa33));
        var original = new PredictionSimpleVegetation.Result(List.copyOf(forms), null, 70);
        for (var density : PredictionVegetationDensity.values()) {
            var filtered = PredictionVegetationSelection.select(original, -32, -48, density);
            assertEquals(forms.stream().filter(f -> density.keep(-32 + f.x(), -48 + f.z())).toList(), filtered.forms());
            assertEquals(70, filtered.maxY());
        }
        assertEquals(256, original.forms().size());
    }

    @Test void meshCacheIdentitiesIncludeDensityButCanonicalPlacementContextsAreReused() throws Exception {
        var config = VSSClientConfig.CONFIG;
        String previousDensity = config.predictionVegetationDensity;
        try (var manager = manager()) {
            Object before = field(manager, "vegetation");
            var method = PredictionTileManager.class.getDeclaredMethod("vegetationForBuild", int.class); method.setAccessible(true);
            int settings = (int) field(manager, "surfaceContextSettings");
            byte[] previous = null;
            for (var density : PredictionVegetationDensity.values()) {
                config.predictionVegetationDensity = density.configName();
                assertSame(before, method.invoke(manager, settings | density.settingsBits()));
                assertEquals(density, PredictionVegetationDensity.fromSettings(density.settingsBits()));
                var identity = PredictionMeshCodec.baseSignature(new byte[]{1, 2, 3}, new ClientColumnSample[0],
                        new int[0], new int[0], new int[0], 63, 0, 1, true, settings | density.settingsBits());
                if (previous != null) assertFalse(Arrays.equals(previous, identity));
                previous = identity;
            }
        } finally { config.predictionVegetationDensity = previousDensity; }
    }

    @Test void spyglassSwitchSuppressesFocusAndCancelsScopedWorkOnly() throws Exception {
        var config = VSSClientConfig.CONFIG;
        boolean previous = config.predictionSpyglassLoading;
        var focus = new VssLodFocus(2048, 0, 128, 5000);
        var focusField = ClientPredictionState.class.getDeclaredField("lastFocus"); focusField.setAccessible(true);
        Object priorFocus = focusField.get(null);
        try (var manager = manager()) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            @SuppressWarnings("unchecked") var desired = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "desiredKeys");
            @SuppressWarnings("unchecked") var scoped = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "scopePending");
            desired.add(key); scoped.add(key);
            var current = PredictionTileManager.class.getDeclaredMethod("captureCurrent",
                    PredictionTileManager.PredictionTileKey.class, long.class, long.class); current.setAccessible(true);
            long revision = ((AtomicLong) field(manager, "meshRevision")).get();
            config.predictionSpyglassLoading = true; focusField.set(null, focus);
            assertSame(focus, ClientPredictionState.currentFocus());
            assertEquals(true, current.invoke(manager, key, 0L, revision));
            config.predictionSpyglassLoading = false;
            assertNull(ClientPredictionState.currentFocus());
            assertEquals(false, current.invoke(manager, key, 0L, revision));
            scoped.remove(key);
            assertEquals(true, current.invoke(manager, key, 0L, revision), "ordinary work continues");
            manager.tick(0, 64, 0, 100, focus, 0);
            assertNull(field(manager, "buildFocus"));
            assertTrue(scoped.isEmpty(), "disabled targeting must not queue scope tasks");
        } finally {
            config.predictionSpyglassLoading = previous; focusField.set(null, priorFocus);
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

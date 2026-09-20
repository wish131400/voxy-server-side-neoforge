package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.*;
import net.minecraft.world.level.levelgen.feature.featuresize.TwoLayersFeatureSize;
import net.minecraft.world.level.levelgen.feature.foliageplacers.BlobFoliagePlacer;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.trunkplacers.StraightTrunkPlacer;
import net.minecraft.world.level.levelgen.placement.*;
import org.junit.jupiter.api.*;

class PredictionVegetationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path diskDirectory;
    private static HolderLookup.Provider lookup;
    private static Map<TagKey<Block>, List<Holder<Block>>> previousTags;

    @BeforeAll
    static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = VanillaRegistries.createLookup();
        previousTags = BuiltInRegistries.BLOCK.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>(previousTags);
        tags.put(BlockTags.LOGS, holders(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG));
        tags.put(BlockTags.LEAVES, holders(Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES));
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT));
        tags.put(BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.AIR, Blocks.SHORT_GRASS, Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.VINE));
        BuiltInRegistries.BLOCK.bindTags(tags);
    }

    @AfterAll
    static void restoreTags() { BuiltInRegistries.BLOCK.bindTags(previousTags); }

    private static List<Holder<Block>> holders(Block... blocks) {
        return java.util.Arrays.stream(blocks).map(block -> (Holder<Block>) BuiltInRegistries.BLOCK
                .getHolder(BuiltInRegistries.BLOCK.getId(block)).orElseThrow()).toList();
    }

    @Test
    void storedVanillaTreesRestoreWithoutGeneratingAndDirtyChunkEvictsThem() {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        Map<BlockPos, BlockState> expected;
        try (var cache = new PredictionDiskCache(diskDirectory, 1)) {
            var vegetation = new PredictionVegetation(sampler, cache);
            expected = vegetation.chunk(-17, 23);
            assertTrue(expected.values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG)));
        }
        try (var cache = new PredictionDiskCache(diskDirectory, 1)) {
            var vegetation = new PredictionVegetation(sampler, cache);
            assertEquals(expected, vegetation.chunk(-17, 23));
            assertTrue(vegetation.diagnostics().contains(",chunks=0,blocks=0,"), "warm surface must not replay features");
            assertEquals(1, cache.hits());
            cache.invalidateChunk(-17, 23);
            vegetation.invalidate(-17, 23);
            cache.flush();
            assertEquals(expected, vegetation.chunk(-17, 23));
            assertTrue(vegetation.diagnostics().contains(",chunks=1,"), "dirty source must generate again");
        }
    }

    @Test
    void cachedUnsupportedLeafDistancesSettleWithoutReplayingWorldgen() {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of());
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int settings = (config.predictionTrees ? 1 : 0) | (config.predictionStructures ? 2 : 0) | 28;
        var key = PredictionDiskCache.Key.surface(0, 0, settings);
        var pos = new BlockPos(1, 80, 0);
        var source = Map.of(pos.west(), Blocks.BIRCH_LOG.defaultBlockState(),
                pos, Blocks.BIRCH_LEAVES.defaultBlockState());
        try (var cache = new PredictionDiskCache(diskDirectory, 1)) {
            try (var lease = cache.lease(key)) { assertTrue(cache.writeSurface(lease, source)); }
            var vegetation = new PredictionVegetation(sampler, cache);
            var restored = vegetation.chunk(0, 0);
            assertEquals(1, restored.get(pos).getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE));
            assertTrue(vegetation.diagnostics().contains(",chunks=0,blocks=0,"));
            try (var lease = cache.lease(key)) { assertEquals(restored, cache.readSurface(lease)); }
        }
    }

    @Test
    void cachedStackedBambooIsRepairedAndPersistedWithoutRegeneration() {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of());
        int settings = (dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionTrees ? 1 : 0)
                | (dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionStructures ? 2 : 0) | 28;
        var key = PredictionDiskCache.Key.surface(0, 0, settings);
        var source = new HashMap<BlockPos, BlockState>();
        for (int y = 64; y <= 90; y++) source.put(new BlockPos(2, y, 3), Blocks.BAMBOO.defaultBlockState());
        source.put(new BlockPos(2, 75, 3), Blocks.BAMBOO.defaultBlockState()
                .setValue(net.minecraft.world.level.block.BambooStalkBlock.STAGE, 1));
        try (var cache = new PredictionDiskCache(diskDirectory, 29)) {
            try (var lease = cache.lease(key)) { assertTrue(cache.writeSurface(lease, source)); }
            var vegetation = new PredictionVegetation(sampler, cache, true);
            var result = vegetation.chunk(0, 0);
            assertEquals(12, result.size());
            assertTrue(vegetation.diagnostics().contains(",chunks=0,blocks=0,"));
            assertSame(result, vegetation.chunk(0, 0));
        }
        try (var cache = new PredictionDiskCache(diskDirectory, 29); var lease = cache.lease(key)) {
            var result = cache.readSurface(lease);
            assertEquals(12, result.size(), "next session reads the repaired cache without regeneration");
            assertFalse(result.containsKey(new BlockPos(2, 76, 3)));
        }
    }

    @Test
    void duplicateChunkYieldsWhileOtherChunksAndCacheHitsRemainAvailable() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                calls.incrementAndGet();
                if (context.origin().getX() < 16) {
                    entered.countDown();
                    try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return true;
            }
        };
        var vegetation = new PredictionVegetation(sampler(42, Blocks.GRASS_BLOCK,
                List.of(placed(new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE), 1))));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> vegetation.chunk(0,0));
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                        assertThrows(PredictionWorkDeferred.class, () -> vegetation.chunk(0,0)));
                var other = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> vegetation.chunk(16,0));
                assertSame(other,vegetation.chunk(16,0));
                assertEquals(2,calls.get(), "duplicate requests must not replay features");
            } finally { release.countDown(); }
            var result = owner.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertSame(result,vegetation.chunk(0,0));
            assertEquals(2,calls.get());
        }
    }

    @Test
    void failedOwnerReleasesChunkReservationWithoutPublishingPartialData() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                if (calls.getAndIncrement() == 0) throw new AssertionError("abort owner");
                return true;
            }
        };
        var vegetation = new PredictionVegetation(sampler(42, Blocks.GRASS_BLOCK,
                List.of(placed(new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE), 1))));
        assertThrows(AssertionError.class, () -> vegetation.chunk(0,0));
        assertDoesNotThrow(() -> vegetation.chunk(0,0));
        assertEquals(2,calls.get());
    }

    @Test void coarseCanopyUsesMajorityLeavesAndKeepsPureTrunksAndGroundEdits() {
        var blocks = new HashMap<BlockPos,BlockState>();
        for (int z=0;z<4;z++) for (int y=68;y<72;y++) for (int x=-4;x<0;x++)
            blocks.put(new BlockPos(x,y,z),Blocks.BIRCH_LEAVES.defaultBlockState());
        for (int y=64;y<72;y++) blocks.put(new BlockPos(-4,y,0),Blocks.BIRCH_LOG.defaultBlockState());
        var reduced = PredictionVegetation.reduceBlocks(blocks,4);
        assertTrue(reduced.get(new BlockPos(-4,68,0)).is(Blocks.BIRCH_LEAVES),"A narrow trunk must not repaint the canopy");
        assertTrue(reduced.get(new BlockPos(-4,64,0)).is(Blocks.BIRCH_LOG),"Unmixed trunks remain wood");
        var mixedStates = new HashMap<BlockPos,BlockState>();
        for (int i=0;i<6;i++) mixedStates.put(new BlockPos(i%2,68,i/2),Blocks.BIRCH_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE,i+1));
        for (int i=0;i<4;i++) mixedStates.put(new BlockPos(2,68+i,0),Blocks.BIRCH_LOG.defaultBlockState());
        assertTrue(PredictionVegetation.reduceBlocks(mixedStates,4).get(new BlockPos(0,68,0)).is(Blocks.BIRCH_LEAVES),
                "Leaf distance states must vote as one material");
        var reversed = new java.util.LinkedHashMap<BlockPos,BlockState>();
        blocks.entrySet().stream().sorted(java.util.Comparator.comparingInt(e -> -e.getKey().hashCode()))
                .forEach(e -> reversed.put(e.getKey(),e.getValue()));
        assertEquals(reduced,PredictionVegetation.reduceBlocks(reversed,4));
        blocks.put(new BlockPos(-4,68,0),Blocks.SAND.defaultBlockState());
        assertTrue(PredictionVegetation.reduceBlocks(blocks,4).get(new BlockPos(-4,68,0)).is(Blocks.SAND),
                "A snapped canopy must not replace an exact terrain edit");
    }

    @Test void nonVegetationStagesKeepTheirOriginalOrderAndSeedIndices() {
        var sand = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,new SimpleBlockConfiguration(
                BlockStateProvider.simple(Blocks.SAND))),1);
        var grass = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,new SimpleBlockConfiguration(
                BlockStateProvider.simple(Blocks.SHORT_GRASS))),1);
        var stages = Map.of(GenerationStep.Decoration.LOCAL_MODIFICATIONS,List.of(sand),
                GenerationStep.Decoration.VEGETAL_DECORATION,List.of(grass));
        var sampler = sampler(42,Blocks.GRASS_BLOCK,stages);
        var actual = new PredictionVegetation(sampler).chunk(0,0);
        var level = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long seed = random.setDecorationSeed(42,0,0);
        for (var step : List.of(GenerationStep.Decoration.LOCAL_MODIFICATIONS,GenerationStep.Decoration.VEGETAL_DECORATION)) {
            random.setFeatureSeed(seed,0,step.ordinal());
            stages.get(step).getFirst().placeWithBiomeCheck(level,sampler.generatorContext(),random,new BlockPos(0,-64,0));
        }
        assertTrue(actual.values().stream().anyMatch(s -> s.is(Blocks.SAND)),"Local surface materials must not be skipped");
        assertEquals(PredictionVegetation.surfaceBlocks(level),actual);
    }

    @Test void lavaLakeFeatureActuallyReachesTheSurfaceCache() {
        var lake = placed(new ConfiguredFeature<>(Feature.LAKE,new LakeFeature.Configuration(
                BlockStateProvider.simple(Blocks.LAVA),BlockStateProvider.simple(Blocks.STONE))),1);
        boolean lava = false;
        for (int seed=1;seed<=8 && !lava;seed++) {
            var sampler = sampler(seed,Blocks.STONE,Map.of(GenerationStep.Decoration.LAKES,List.of(lake)));
            var generated = new PredictionVegetation(sampler).chunk(0,0);
            lava = generated.values().stream().anyMatch(state -> state.is(Blocks.LAVA));
        }
        assertTrue(lava,"Vanilla surface lakes must survive stage replay and exterior extraction");
    }

    @Test
    void scopedVanillaPlantsPublishBeforeAnUnrelatedDistantRootFinishes() throws Exception {
        PlacedFeature plants = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.SHORT_GRASS))), 3);
        PredictionProgressiveLoadingTest.verifyLocalProgress(sampler(48271, Blocks.GRASS_BLOCK, List.of(plants)), true);
    }

    @Test
    void usesVanillaDecorationSeedGlobalIndexAndRealTreePlacement() {
        PlacedFeature plants = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.SHORT_GRASS))), 3);
        PlacedFeature trees = tree();
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(plants, trees));
        var prediction = new PredictionVegetation(sampler, null, false);
        int chunkX = -17, chunkZ = 23;
        Map<BlockPos, BlockState> actual = prediction.chunk(chunkX, chunkZ);
        assertTrue(actual.values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG)),
                "fixture must generate actual vanilla trees");

        // Oracle: invoke the vanilla placed features with the vanilla chunk
        // decoration seed and their indices, independently of the cache.
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, chunkX, chunkZ);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long decoration = random.setDecorationSeed(48271, chunkX * 16, chunkZ * 16);
        BlockPos origin = new BlockPos(chunkX * 16, -64, chunkZ * 16);
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        random.setFeatureSeed(decoration, 0, step);
        plants.placeWithBiomeCheck(level, sampler.generatorContext(), random, origin);
        random.setFeatureSeed(decoration, 1, step);
        trees.placeWithBiomeCheck(level, sampler.generatorContext(), random, origin);
        Map<BlockPos, BlockState> expected = new HashMap<>(level.placed());
        // Tree placement also replaces its soil block. Surface edits must survive
        // alongside the trunk/canopy rather than silently leaving grass beneath it.
        expected.entrySet().removeIf(entry -> entry.getKey().getY() < 63 || entry.getValue().isAir());
        assertTrue(actual.values().stream().anyMatch(state -> state.is(Blocks.DIRT)));
        assertEquals(expected, actual, "coordinates, trunk, foliage and block states must match vanilla placement");
        assertSame(actual, prediction.chunk(chunkX, chunkZ), "zoom levels share the cached chunk result");
        assertNotEquals(actual, new PredictionVegetation(sampler(48272, Blocks.GRASS_BLOCK,
                List.of(plants, trees))).chunk(chunkX, chunkZ), "world seed must change placement");
    }

    @Test
    void groundPredicatesAndAbsentBiomeFeaturesDoNotCreateFallbackTrees() {
        assertTrue(new PredictionVegetation(sampler(17, Blocks.RED_SAND, List.of(tree())))
                .chunk(0, 0).isEmpty(), "trees rejected by real soil predicates stay absent");
        var empty = new PredictionVegetation(sampler(17, Blocks.GRASS_BLOCK, List.of()));
        assertTrue(empty.chunk(0, 0).isEmpty());
        var sample = column(Blocks.GRASS_BLOCK);
        var hints = new ClientColumnSample(64, 64, 0, sample.topBlockIndex(), 99,
                1, 255, 12, 0, ClientColumnSample.FLAG_TREE_HERE, 1,
                sample.underBlockIndex(), sample.deepBlockIndex(), 40, 20, 0, -64);
        ClientColumnSample[] samples = new ClientColumnSample[9 * 9];
        java.util.Arrays.fill(samples, hints);
        for (int spacing : new int[]{1, 2, 4, 8}) {
            var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, spacing, 9,
                    true, new PredictionFeatureStampCache(), null, null, 0, 0,
                    PredictionVegetation.Tile.EMPTY);
            assertEquals(8 * 8 * 6, mesh.vertexCount(),
                    "native tree/structure hints cannot create placeholder geometry at spacing " + spacing);
        }
    }

    @Test
    void failedFeatureRollsBackPartialBlocksWithoutReplacingThemWithTemplates() {
        Feature<NoneFeatureConfiguration> broken = new Feature<>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                context.level().setBlock(context.origin(), Blocks.OAK_LOG.defaultBlockState(), 3);
                throw new UnsupportedOperationException("test unsupported server capability");
            }
        };
        var sampler = sampler(17, Blocks.GRASS_BLOCK, List.of(placed(
                new ConfiguredFeature<>(broken, NoneFeatureConfiguration.INSTANCE), 1)));
        assertTrue(new PredictionVegetation(sampler).chunk(0, 0).isEmpty());
    }

    @Test
    void refinementAndAdjacentTilesKeepWorldCoordinatesAndCapturedAirStaysEmpty() {
        var prediction = new PredictionVegetation(sampler(48271, Blocks.GRASS_BLOCK, List.of(tree())));
        var fine = prediction.tile(-16, -16, 32, 1, true, (x, z) -> false);
        var zoom = prediction.tile(-16, -16, 32, 2, true, (x, z) -> false);
        assertFalse(fine.cells().isEmpty());
        assertEquals(positions(fine), positions(zoom));
        var left = prediction.tile(-16, -16, 16, 1, true, (x, z) -> false);
        var right = prediction.tile(0, -16, 16, 1, true, (x, z) -> false);
        assertTrue(java.util.Collections.disjoint(positions(left), positions(right)));
        assertTrue(positions(fine).containsAll(positions(left)));
        assertTrue(positions(fine).containsAll(positions(right)));
        var captured = prediction.tile(-16, -16, 32, 1, true, (x, z) -> x >= 0);
        assertTrue(positions(captured).stream().allMatch(pos -> pos.getX() < 0),
                "authoritative empty space must not regrow predicted trees");
        for (int spacing : new int[]{4, 8}) {
            var coarse = prediction.tile(-16, -16, 32, spacing, true, (x, z) -> false);
            assertTrue(coarse.cells().values().stream().flatMap(List::stream)
                    .allMatch(voxel -> voxel.size() <= spacing / 2), "coarse trees stay bounded voxels");
        }
    }

    @Test
    void managerPublishesHorizonThenTerrainThenVisiblePlants() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks, radius = config.predictionSurfaceDistanceBlocks;
        boolean remember = config.rememberTerrain;
        config.predictionDistanceBlocks = 1024;
        config.predictionSurfaceDistanceBlocks = 128;
        config.rememberTerrain = false;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Feature<NoneFeatureConfiguration> marker = new Feature<>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                calls.incrementAndGet();
                for (int y = 0; y < 4; y++) context.level().setBlock(context.origin().above(y), Blocks.OAK_LOG.defaultBlockState(), 3);
                return true;
            }
        };
        var sampler = sampler(42, Blocks.GRASS_BLOCK, List.of(placed(new ConfiguredFeature<>(marker, NoneFeatureConfiguration.INSTANCE), 1)));
        var budget = new PredictionMemoryBudget(768L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD, sampler, budget)) {
            manager.tick(32, 80, 32, .01, null);
            awaitManager(manager);
            assertTrue(manager.readyCount() > 0);
            assertEquals(0, calls.get(), "initial whole-horizon coverage must not run decoration");
            // A fast worker can publish a parent while the first tick is
            // still admitting children. Test coverage, not that race's timing.
            var initialKeys = manager.readyTiles().stream().map(tile -> tile.key())
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(initialKeys.stream().allMatch(key -> key.lod() == manager.layout().levelCount() - 1
                    || PredictionTileManager.coveredByAncestor(initialKeys, Set.of(), key.dimension(), manager.layout(), key)),
                    "initial children must retain their coarse parent coverage");
            long mediumDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (calls.get() == 0 && System.nanoTime() < mediumDeadline) {
                for (int tick = 0; tick < 5; tick++) manager.tick(32, 80, 32, .01, null);
                awaitManager(manager);
            }
            assertTrue(calls.get() > 0, manager.surfaceDiagnostics());
            assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.depthBound().maxY() >= 68),
                    "placed surface blocks must reach published renderable meshes, not only the feature cache");
            var counts = manager.readyTiles().stream().filter(tile -> tile.depthBound().maxY() >= 68).toList();
            assertTrue(counts.stream().allMatch(tile -> tile.spacingBlocks() <= 2));
        } finally {
            config.predictionDistanceBlocks = distance; config.predictionSurfaceDistanceBlocks = radius; config.rememberTerrain = remember;
        }
    }

    @Test void compareCanonicalSurfaceRestoreWithRepeatedRepair() {
        var source = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        var generated = new PredictionVegetation(source, null, true);
        var grids = new java.util.ArrayList<Map<BlockPos,BlockState>>();
        for (int x=0;x<32;x++) grids.add(generated.chunk(x,7));
        long[][] times = new long[2][7];
        try (var disk = new PredictionDiskCache(diskDirectory,77)) {
            for (int x=0;x<32;x++) try (var lease=disk.lease(PredictionDiskCache.Key.surface(x,7,31))) {
                assertTrue(disk.writeSurface(lease,grids.get(x),true));
            }
            for (int round=-2;round<7;round++) for (int mode : round%2==0 ? new int[]{0,1} : new int[]{1,0}) {
                long start=System.nanoTime();
                for (int x=0;x<32;x++) try (var lease=disk.lease(PredictionDiskCache.Key.surface(x,7,31))) {
                    var blocks=disk.readSurfaceData(lease).blocks();
                    if (mode==0) blocks=PredictionLeafStates.settle(PredictionBamboo.normalize(blocks));
                    assertEquals(grids.get(x),blocks);
                }
                if (round>=0) times[mode][round]=System.nanoTime()-start;
            }
        }
        for (var series:times) java.util.Arrays.sort(series);
        System.out.printf(java.util.Locale.ROOT,"SURFACE_RESTORE chunks=32 blocks=%d repeatedRepairMs=%.3f canonicalMs=%.3f speedup=%.3f%n",
                grids.stream().mapToInt(Map::size).sum(),times[0][3]/1e6,times[1][3]/1e6,(double)times[0][3]/times[1][3]);
    }

    @Test
    void captureRefreshKeepsCachedDecorationAndWorldEditsStillInvalidateIt() throws Exception {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        var budget = new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD, sampler, budget)) {
            var field = PredictionTileManager.class.getDeclaredField("vegetation");
            field.setAccessible(true);
            var plants = (PredictionVegetation) field.get(manager);
            var expected = new HashMap<Integer, PredictionVegetation.Tile>();
            for (int spacing : new int[]{1, 2, 4, 8}) {
                expected.put(spacing, plants.tile(-16, -16, 32, spacing, true, (x,z) -> false));
                assertFalse(expected.get(spacing).cells().isEmpty());
            }
            String before = plants.diagnostics();
            manager.acceptExactColumn(0, 0);
            assertTrue(manager.isAuthoritative(0, 0), "arrival must retain the request scheduling ownership claim");
            manager.capturedTerrainChanged(0, 0);
            for (int spacing : new int[]{1, 2, 4, 8}) {
                var refreshed = plants.cachedDisplay(-16, -16, 32, spacing, (x,z) -> false);
                assertEquals(expected.get(spacing).cells(), refreshed.cells(), "capture must retain decoration at spacing " + spacing);
                assertEquals(expected.get(spacing).blocks(), refreshed.blocks());
            }
            assertEquals(before, plants.diagnostics(), "refresh must not rerun features or evict placement");
            manager.invalidate(0, 0);
            assertFalse(plants.hasCachedChunk(0, 0), "actual edits must still evict stale placement");
        }
    }

    private static void awaitManager(PredictionTileManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, manager.pendingCount(), "bounded stage must finish");
    }

    @Test
    void readyNearTilesGetTreesEvenWhenAnotherNearTileCannotFinish() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks, radius = config.predictionSurfaceDistanceBlocks;
        boolean remember = config.rememberTerrain, trees = config.predictionTrees, structures = config.predictionStructures;
        config.predictionDistanceBlocks = 4096;
        config.predictionSurfaceDistanceBlocks = 768;
        config.rememberTerrain = false;
        config.predictionTrees = true;
        config.predictionStructures = false;
        var base = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        var delayed = new ClientTerrainSampler(48271, base.profile()) {
            @Override public ClientColumnSample sample(int x, int z) { return column(Blocks.GRASS_BLOCK); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int spacing) {
                if (spacing <= 2 && x >= 160 && x < 224 && z >= 0 && z < 64)
                    throw new java.util.concurrent.CancellationException("one nearby tile still pending");
                return sample(x, z);
            }
            @Override NoiseBasedChunkGenerator generatorContext() { return base.generatorContext(); }
            @Override RandomState randomStateContext() { return base.randomStateContext(); }
            @Override BiomeSource biomeSourceContext() { return base.biomeSourceContext(); }
            @Override RegistryAccess decorationAccess() { return RegistryAccess.EMPTY; }
        };
        var budget = new PredictionMemoryBudget(176L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD, delayed, budget)) {
            // Give the single worker enough turns to establish medium terrain
            // beyond the immediate ground before a bounded decoration turn.
            for (int cycle = 0; cycle < 120; cycle++) {
                for (int tick = 0; tick < 5; tick++) manager.tick(32, 176, 32, 1300, null, 128);
                awaitManager(manager);
                if (manager.readyTiles().stream().anyMatch(tile -> tile.depthBound().maxY() > 68)) break;
            }
            assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.depthBound().maxY() > 68),
                    "a stalled neighbor and a realistic pixel scale must not block every tree: " + manager.surfaceDiagnostics());
            assertTrue(budget.usedBytes() <= budget.limitBytes());
        } finally {
            config.predictionDistanceBlocks = distance;
            config.predictionSurfaceDistanceBlocks = radius;
            config.rememberTerrain = remember;
            config.predictionTrees = trees;
            config.predictionStructures = structures;
        }
    }

    @Test
    void enablingTreesAfterTerrainFillsMemoryStillPublishesNearbyTrees() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks, radius = config.predictionSurfaceDistanceBlocks;
        boolean remember = config.rememberTerrain, trees = config.predictionTrees, structures = config.predictionStructures;
        config.predictionDistanceBlocks = 4096;
        config.predictionSurfaceDistanceBlocks = 768;
        config.rememberTerrain = false;
        config.predictionTrees = false;
        config.predictionStructures = false;
        var budget = new PredictionMemoryBudget(176L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 1);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD,
                sampler(48271, Blocks.GRASS_BLOCK, List.of(tree())), budget)) {
            // Compacted terrain retains fewer bytes; a fixed number of planner
            // turns no longer guarantees pressure. Wait for the actual budget
            // condition while keeping a bounded test deadline.
            long fillDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!budget.exhausted() && System.nanoTime() < fillDeadline) {
                for (int tick = 0; tick < 5; tick++) manager.tick(32, 176, 32, 1300, null, 128);
                awaitManager(manager);
            }
            assertTrue(budget.exhausted(), "terrain must first consume the available build headroom: bytes="
                    + budget.usedBytes() + ",ready=" + manager.readyCount() + "," + manager.surfaceDiagnostics());
            assertTrue(manager.readyTiles().stream().allMatch(tile -> tile.depthBound().maxY() == 64));
            var previousKeys = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key).toList();
            config.predictionTrees = true;
            for (int cycle = 0; cycle < 35; cycle++) {
                for (int tick = 0; tick < 5; tick++) manager.tick(32, 176, 32, 1300, null, 128);
                awaitManager(manager);
                if (manager.readyTiles().stream().anyMatch(tile -> tile.depthBound().maxY() > 68)) break;
            }
            assertTrue(manager.readyTiles().stream().anyMatch(tile -> tile.depthBound().maxY() > 68),
                    "a full stationary terrain cache must allow surface upgrades: " + manager.surfaceDiagnostics());
            var resident = manager.readyTiles().stream().map(PredictionTileManager.PredictionTile::key)
                    .collect(Collectors.toSet());
            for (var key : previousKeys) assertTrue(resident.contains(key)
                    || PredictionTileManager.coveredByAncestor(resident, Set.of(), key.dimension(), manager.layout(), key),
                    "reclaiming detail must preserve complete parent coverage");
            assertTrue(budget.usedBytes() <= budget.limitBytes());
        } finally {
            config.predictionDistanceBlocks = distance;
            config.predictionSurfaceDistanceBlocks = radius;
            config.rememberTerrain = remember;
            config.predictionTrees = trees;
            config.predictionStructures = structures;
        }
        assertEquals(0, budget.usedBytes());
    }

    private static Set<BlockPos> positions(PredictionVegetation.Tile tile) {
        return tile.cells().values().stream().flatMap(List::stream).map(voxel ->
                new BlockPos(tile.baseX() + voxel.x(), voxel.y(), tile.baseZ() + voxel.z()))
                .collect(Collectors.toSet());
    }

    @Test @SuppressWarnings("unchecked")
    void completedFineTerrainStartsItsSurfaceWithoutAnotherPlannerTick() throws Exception {
        var plants = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.SHORT_GRASS))),3);
        var source = sampler(48271,Blocks.GRASS_BLOCK,List.of(plants));
        var budget = new PredictionMemoryBudget(1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,2);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD,source,budget,null)) {
            var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD,0,0,0);
            for (String name : List.of("desiredKeys","terrainLeaves","surfaceDesired")) {
                var field = manager.getClass().getDeclaredField(name); field.setAccessible(true);
                ((Set<PredictionTileManager.PredictionTileKey>)field.get(manager)).add(key);
            }
            var readyField = manager.getClass().getDeclaredField("ready"); readyField.setAccessible(true);
            var ready = (Map<PredictionTileManager.PredictionTileKey,PredictionTileManager.PredictionTile>)readyField.get(manager);
            ready.put(key,new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    null,new PredictionDepthBound(64,64),0,1,32,2));
            var enqueue = manager.getClass().getDeclaredMethod("enqueue",PredictionTileManager.PredictionTileKey.class,int.class,int.class,boolean.class);
            enqueue.setAccessible(true); enqueue.invoke(manager,key,0,0,false);
            var surfacesField = manager.getClass().getDeclaredField("surfaceReady"); surfacesField.setAccessible(true);
            var surfaces = (Set<PredictionTileManager.PredictionTileKey>)surfacesField.get(manager);
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!surfaces.contains(key) && System.nanoTime()<deadline) Thread.sleep(5);
            assertTrue(surfaces.contains(key),"no tick was issued: finishing terrain must request its own plants: "+manager.surfaceDiagnostics());
            assertTrue(ready.get(key).depthBound().maxY()>64);
            assertEquals(0,manager.failedTileCount());
            awaitManager(manager);
        }
    }

    @Test void reusableTreesAreStableAcrossChunkOrderAndConcurrentJobs() throws Exception {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        var forward = new PredictionVegetation(sampler);
        var reverse = new PredictionVegetation(sampler);
        var expected = new HashMap<Integer, Map<BlockPos, BlockState>>();
        for (int x = -12; x < 12; x++) expected.put(x, forward.chunk(x, 7));
        for (int x = 11; x >= -12; x--) assertEquals(expected.get(x), reverse.chunk(x, 7));
        var concurrent = new PredictionVegetation(sampler);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int x = -12; x < 12; x++) {
                int chunk = x;
                jobs.add(executor.submit(() -> assertEquals(expected.get(chunk), concurrent.chunk(chunk, 7))));
            }
            for (var job : jobs) job.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(forward.diagnostics().contains("treeModels={built=8,"), forward.diagnostics());
        assertTrue(forward.diagnostics().contains("fallback=0"), forward.diagnostics());
    }

    @Test void treeModelIdentityAndMixedSelectorsPreserveActualFeatureBranches() {
        var oak = tree();
        var birch = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.TREE,
                new TreeConfiguration.TreeConfigurationBuilder(BlockStateProvider.simple(Blocks.BIRCH_LOG),
                        new StraightTrunkPlacer(4, 1, 0), BlockStateProvider.simple(Blocks.BIRCH_LEAVES),
                        new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                        new TwoLayersFeatureSize(1, 0, 1)).ignoreVines().build())), oak.placement());
        var sampler = sampler(42, Blocks.GRASS_BLOCK, List.of(oak, birch));
        var cache = new PredictionTreeModels(RegistryAccess.EMPTY, sampler.generatorContext());
        var plainOak = new PlacedFeature(oak.feature(), List.of());
        var plainBirch = new PlacedFeature(birch.feature(), List.of());
        var a = new dev.xantha.vss.client.prediction.feature.FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState());
        var b = new dev.xantha.vss.client.prediction.feature.FeatureStampLevel(42, RegistryAccess.EMPTY, Blocks.GRASS_BLOCK.defaultBlockState());
        cache.place(plainOak, a, net.minecraft.util.RandomSource.create(1), new BlockPos(0,64,0));
        cache.place(plainBirch, b, net.minecraft.util.RandomSource.create(1), new BlockPos(0,64,0));
        assertTrue(a.placed().values().stream().anyMatch(s -> s.is(Blocks.OAK_LOG)));
        assertTrue(b.placed().values().stream().anyMatch(s -> s.is(Blocks.BIRCH_LOG)));
        assertFalse(b.placed().values().stream().anyMatch(s -> s.is(Blocks.OAK_LOG)));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var custom = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
                calls.incrementAndGet(); return true;
            }
        };
        var fallback = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(custom, NoneFeatureConfiguration.INSTANCE)), List.of());
        var selector = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.RANDOM_SELECTOR,
                new RandomFeatureConfiguration(List.of(new WeightedPlacedFeature(Holder.direct(fallback), 1)), Holder.direct(plainOak)))), List.of());
        assertTrue(cache.supports(selector));
        assertFalse(cache.supports(fallback));
        cache.place(selector, a, net.minecraft.util.RandomSource.create(1), new BlockPos(16,64,0));
        assertEquals(1, calls.get(), "selected custom branches must execute their original code");
    }

    @Test void reusableTreesRejectWaterAndProtectStructureBlocksBeforeWriting() {
        var tree = new PlacedFeature(tree().feature(), List.of());
        var sampler = sampler(42, Blocks.GRASS_BLOCK, List.of(tree));
        var models = new PredictionTreeModels(RegistryAccess.EMPTY, sampler.generatorContext());
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
        var pos = new BlockPos(0,64,0);
        level.beginFeature(); level.setBlock(pos, Blocks.WATER.defaultBlockState(), 0, 0); level.endFeature(true);
        var water = Map.copyOf(level.placed());
        models.place(tree, level, net.minecraft.util.RandomSource.create(1), pos);
        assertEquals(water, level.placed());
        var building = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, 0, 0);
        building.beginStructure(); building.setBlock(pos.above(2), Blocks.OAK_PLANKS.defaultBlockState(), 0, 0); building.endFeature(true);
        var before = Map.copyOf(building.placed());
        models.place(tree, building, net.minecraft.util.RandomSource.create(1), pos);
        assertEquals(before, building.placed(), "blocked trunk must not leave a partial tree or soil edit");
    }

    @Test void visualAndExactSurfaceCachesHaveSeparateIdentitiesAndBothInvalidate() {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        try (var disk = new PredictionDiskCache(diskDirectory, 1)) {
            var exact = new PredictionVegetation(sampler, disk, false);
            exact.chunk(0,0);
            var visual = new PredictionVegetation(sampler, disk, true);
            visual.chunk(0,0);
            assertEquals(0, disk.hits(), "visual policy cannot silently reuse old exact entries");
            new PredictionVegetation(sampler, disk, true).chunk(0,0);
            new PredictionVegetation(sampler, disk, false).chunk(0,0);
            assertEquals(2, disk.hits());
            disk.invalidateChunk(0,0); disk.flush();
            new PredictionVegetation(sampler, disk, true).chunk(0,0);
            new PredictionVegetation(sampler, disk, false).chunk(0,0);
            assertEquals(2, disk.hits(), "dirty update invalidates both policies");
        }
    }

    @Test void reusableTreeOnRustTerrainDoesNotCreateDecorationProxy() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var tree = tree();
        var base = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree));
        var registry = new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE, com.mojang.serialization.Lifecycle.stable());
        Registry.register(registry, ResourceLocation.parse("test:tree"), tree);
        registry.freeze();
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        var context = new ClientTerrainSampler(48271, base.profile()) {
            @Override NoiseBasedChunkGenerator generatorContext() { return base.generatorContext(); }
            @Override RandomState randomStateContext() { return base.randomStateContext(); }
            @Override BiomeSource biomeSourceContext() { return base.biomeSourceContext(); }
            @Override RegistryAccess decorationAccess() { return access; }
        };
        var doc = LithostitchedNativeTest.document();
        doc.getAsJsonObject("settings").add("surface_rule", com.google.gson.JsonParser.parseString("""
                {"type":"minecraft:block","result_state":{"Name":"minecraft:grass_block"}}
                """));
        var features = new com.google.gson.JsonObject();
        features.add("test:tree", PlacedFeature.DIRECT_CODEC.encodeStart(
                lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), tree).getOrThrow());
        doc.add("placed_features", features);
        doc.add("possible_biomes", com.google.gson.JsonParser.parseString("[\"minecraft:plains\"]"));
        var order = new com.google.gson.JsonArray();
        for (int i=0;i<GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();i++) order.add(new com.google.gson.JsonArray());
        order.add(com.google.gson.JsonParser.parseString("[\"test:tree\"]"));
        doc.getAsJsonObject("biomes").getAsJsonObject("minecraft:plains").add("features", order);
        try (var rust = new RustTerrainSampler(RustWorldgenBackend.create(48271,0,doc.toString()), base.profile(), context)) {
            assertTrue(rust.supports("test:tree"), "fixture must otherwise enter native decoration");
            var count = RustVegetationStage.class.getDeclaredField("PROXIES");
            count.setAccessible(true);
            var proxies = (java.util.concurrent.atomic.LongAdder)count.get(null);
            long before = proxies.sum();
            var vegetation = new PredictionVegetation(rust);
            var blocks = vegetation.chunk(0,0);
            assertTrue(blocks.values().stream().anyMatch(s -> s.is(Blocks.OAK_LOG)), vegetation.diagnostics());
            assertEquals(before, proxies.sum(), "tree reuse must bypass the 5x5 native proxy, not merely add another cache after it");
            assertTrue(vegetation.diagnostics().contains("fallback=0"), vegetation.diagnostics());
        }
    }

    @Test void compareExactAndReusableTreesOnFixedChunks() {
        var sampler = sampler(48271, Blocks.GRASS_BLOCK, List.of(tree()));
        var times = new long[3][7];
        for (int round = -2; round < 7; round++) {
            for (boolean reuse : round % 2 == 0 ? new boolean[]{false,true} : new boolean[]{true,false}) {
                var prediction = new PredictionVegetation(sampler, null, reuse);
                long started = System.nanoTime();
                int blocks = 0;
                for (int x = -32; x < 32; x++) blocks += prediction.chunk(x,7).size();
                long nanos = System.nanoTime() - started;
                assertTrue(blocks > 1000);
                if (round >= 0) times[reuse ? 1 : 0][round] = nanos;
                if (round == 6) System.out.println("TREE_REUSE reuse=" + reuse + " blocks=" + blocks + " " + prediction.diagnostics());
                if (reuse) {
                    // Evict chunk results, retain only the reusable model cache.
                    for (int x = -32; x < 32; x++) prediction.invalidate(x,7);
                    started = System.nanoTime();
                    int warmBlocks = 0;
                    for (int x = -32; x < 32; x++) warmBlocks += prediction.chunk(x,7).size();
                    nanos = System.nanoTime() - started;
                    assertEquals(blocks, warmBlocks);
                    if (round >= 0) times[2][round] = nanos;
                }
            }
        }
        for (var series : times) java.util.Arrays.sort(series);
        System.out.printf(java.util.Locale.ROOT, "TREE_REUSE chunks=64 exactMs=%.3f coldModelsMs=%.3f warmModelsMs=%.3f speedup=%.3f%n",
                times[0][3]/1e6, times[1][3]/1e6, times[2][3]/1e6, (double)times[0][3]/times[1][3]);
    }

    @Test void nativeGrassPlacementAndExtractionShareDisplayGround() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var plant = placed(new ConfiguredFeature<>(Feature.SIMPLE_BLOCK,
                new SimpleBlockConfiguration(BlockStateProvider.simple(Blocks.SHORT_GRASS))),4);
        var base=sampler(48271,Blocks.GRASS_BLOCK,List.of(plant));
        var registry=new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE,com.mojang.serialization.Lifecycle.stable());
        Registry.register(registry,ResourceLocation.parse("test:native_grass"),plant);registry.freeze();
        var access=new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        var context=new ClientTerrainSampler(48271,base.profile()) {
            @Override NoiseBasedChunkGenerator generatorContext(){return base.generatorContext();}
            @Override RandomState randomStateContext(){return base.randomStateContext();}
            @Override BiomeSource biomeSourceContext(){return base.biomeSourceContext();}
            @Override RegistryAccess decorationAccess(){return access;}
        };
        var doc=LithostitchedNativeTest.document();
        doc.add("biome_source",com.google.gson.JsonParser.parseString(
                "{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
        doc.add("input_states",com.google.gson.JsonParser.parseString("[{\"Name\":\"minecraft:short_grass\"}]"));
        doc.getAsJsonObject("settings").add("surface_rule",com.google.gson.JsonParser.parseString(
                "{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:grass_block\"}}"));
        var features=new com.google.gson.JsonObject();
        features.add("test:native_grass",PlacedFeature.DIRECT_CODEC.encodeStart(
                lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),plant).getOrThrow());
        doc.add("placed_features",features);doc.add("possible_biomes",com.google.gson.JsonParser.parseString("[\"minecraft:plains\"]"));
        var order=new com.google.gson.JsonArray();
        for(int i=0;i<9;i++) order.add(new com.google.gson.JsonArray());
        order.add(com.google.gson.JsonParser.parseString("[\"test:native_grass\"]"));
        doc.getAsJsonObject("biomes").getAsJsonObject("minecraft:plains").add("features",order);
        try(var rust=new RustTerrainSampler(RustWorldgenBackend.create(48271,0,doc.toString()),base.profile(),context)) {
            for(int x=0;x<16;x+=8) for(int z=0;z<16;z+=8)
                rust.sampleDisplayGrid(x,z,1,8,8,new ClientColumnSample[64]);
            var prediction=new PredictionVegetation(rust);
            var blocks=prediction.chunk(0,0);
            assertTrue(blocks.values().stream().anyMatch(s->s.is(Blocks.SHORT_GRASS)),prediction.diagnostics());
            assertTrue(rust.diagnostics().contains("nativeFeatures=1"),rust.diagnostics());
            var stats=com.google.gson.JsonParser.parseString(RustWorldgenBackend.decorationQueryStats(rust.handle())).getAsJsonObject();
            assertTrue(stats.get("pages").getAsInt()>0);
            assertEquals(stats.get("columns"),stats.get("cached"),"native placement and Java extraction must both reuse rendered ground");
        }
    }

    @Test void decoratedTreeSkeletonsKeepContextualVinesAndFallbacks() {
        var base = (TreeConfiguration) tree().feature().value().config();
        var config = new TreeConfiguration.TreeConfigurationBuilder(base.trunkProvider, base.trunkPlacer,
                base.foliageProvider, base.foliagePlacer, base.minimumSize).ignoreVines().decorators(List.of(
                net.minecraft.world.level.levelgen.feature.treedecorators.TrunkVineDecorator.INSTANCE,
                new net.minecraft.world.level.levelgen.feature.treedecorators.LeaveVineDecorator(1))).build();
        var decorated = new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.TREE,config)),tree().placement());
        var sampler = sampler(48271,Blocks.GRASS_BLOCK,List.of(decorated));
        var prediction = new PredictionVegetation(sampler);
        var reverse = new PredictionVegetation(sampler);
        var maps = new HashMap<Integer, Map<BlockPos,BlockState>>();
        for (int x=-4; x<4; x++) maps.put(x,prediction.chunk(x,7));
        for (int x=3; x>=-4; x--) assertEquals(maps.get(x),reverse.chunk(x,7));
        assertTrue(maps.values().stream().flatMap(m -> m.values().stream()).anyMatch(s -> s.is(Blocks.VINE)));
        assertTrue(prediction.diagnostics().contains("fallback=0"),prediction.diagnostics());
        var bee = new TreeConfiguration.TreeConfigurationBuilder(base.trunkProvider,base.trunkPlacer,
                base.foliageProvider,base.foliagePlacer,base.minimumSize).decorators(List.of(
                new net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator(1))).build();
        var models = new PredictionTreeModels(RegistryAccess.EMPTY,sampler.generatorContext());
        assertFalse(models.supports(new PlacedFeature(Holder.direct(new ConfiguredFeature<>(Feature.TREE,bee)),List.of())));
        var level = new PredictionDecorationLevel(sampler,sampler,RegistryAccess.EMPTY,0,0);
        level.beginStructure();
        for (int y=64;y<80;y++) level.setBlock(new BlockPos(-1,y,0),Blocks.OAK_PLANKS.defaultBlockState(),0,0);
        level.endFeature(true);
        models.place(new PlacedFeature(decorated.feature(),List.of()),level,net.minecraft.util.RandomSource.create(1),new BlockPos(0,64,0));
        for (int y=64;y<80;y++) assertTrue(level.getBlockState(new BlockPos(-1,y,0)).is(Blocks.OAK_PLANKS));
    }

    @Test void compareDecoratedJungleSkeletonsOnFixedChunks() {
        var configured = lookup.lookupOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(
                net.minecraft.resources.ResourceKey.create(Registries.CONFIGURED_FEATURE,
                        ResourceLocation.withDefaultNamespace("mega_jungle_tree")));
        var feature = new PlacedFeature(configured, List.of(CountPlacement.of(2),InSquarePlacement.spread(),
                HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG),BiomeFilter.biome()));
        var sampler = sampler(48271,Blocks.GRASS_BLOCK,List.of(feature));
        var times = new long[3][7];
        for (int round=-2;round<7;round++) {
            for (boolean reuse : round%2==0 ? new boolean[]{false,true}:new boolean[]{true,false}) {
                var prediction = new PredictionVegetation(sampler,null,reuse);
                long start=System.nanoTime(); int blocks=0;
                for(int x=-16;x<16;x++) blocks+=prediction.chunk(x,7).size();
                long elapsed=System.nanoTime()-start;
                assertTrue(blocks>1000,prediction.diagnostics());
                if(round>=0) times[reuse?1:0][round]=elapsed;
                if(round==6) System.out.println("JUNGLE_REUSE reuse="+reuse+" blocks="+blocks+" "+prediction.diagnostics());
                if(reuse) {
                    for(int x=-16;x<16;x++) prediction.invalidate(x,7);
                    start=System.nanoTime(); int warm=0;
                    for(int x=-16;x<16;x++) warm+=prediction.chunk(x,7).size();
                    if(round>=0) times[2][round]=System.nanoTime()-start;
                    assertEquals(blocks,warm);
                }
            }
        }
        for(var series:times) java.util.Arrays.sort(series);
        System.out.printf(java.util.Locale.ROOT,"JUNGLE_REUSE chunks=32 exactMs=%.3f coldMs=%.3f warmMs=%.3f%n",
                times[0][3]/1e6,times[1][3]/1e6,times[2][3]/1e6);
    }

    private static PlacedFeature tree() {
        var configured = new ConfiguredFeature<>(Feature.TREE, new TreeConfiguration.TreeConfigurationBuilder(
                BlockStateProvider.simple(Blocks.OAK_LOG), new StraightTrunkPlacer(4, 1, 0),
                BlockStateProvider.simple(Blocks.OAK_LEAVES),
                new BlobFoliagePlacer(ConstantInt.of(2), ConstantInt.of(0), 3),
                new TwoLayersFeatureSize(1, 0, 1)).ignoreVines().build());
        return new PlacedFeature(Holder.direct(configured), List.of(CountPlacement.of(2),
                InSquarePlacement.spread(), HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG),
                net.minecraft.data.worldgen.placement.PlacementUtils.filteredByBlockSurvival(Blocks.OAK_SAPLING),
                BiomeFilter.biome()));
    }

    private static PlacedFeature placed(ConfiguredFeature<?, ?> feature, int count) {
        return new PlacedFeature(Holder.direct(feature), List.of(CountPlacement.of(count),
                InSquarePlacement.spread(), HeightmapPlacement.onHeightmap(Heightmap.Types.WORLD_SURFACE_WG),
                BiomeFilter.biome()));
    }

    private static ClientTerrainSampler sampler(long seed, Block ground, List<PlacedFeature> features) {
        return sampler(seed,ground,Map.of(GenerationStep.Decoration.VEGETAL_DECORATION,features));
    }

    private static ClientTerrainSampler sampler(long seed, Block ground, Map<GenerationStep.Decoration,List<PlacedFeature>> features) {
        var generation = new BiomeGenerationSettings.PlainBuilder();
        features.forEach((step,list) -> list.forEach(feature -> generation.addFeature(step,Holder.direct(feature))));
        var biome = new Biome.BiomeBuilder().hasPrecipitation(true).temperature(.7F).downfall(.5F)
                .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0)
                        .waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(generation.build()).build();
        var source = new FixedBiomeSource(Holder.direct(biome));
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), seed);
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), seed,
                -64, 384, "noise", "minecraft:overworld", 1);
        return new ClientTerrainSampler(seed, profile) {
            @Override public ClientColumnSample sample(int x, int z) { return column(ground); }
            @Override public ClientColumnSample sampleForLod(int x, int z, int spacing) { return column(ground); }
            @Override NoiseBasedChunkGenerator generatorContext() { return generator; }
            @Override RandomState randomStateContext() { return random; }
            @Override BiomeSource biomeSourceContext() { return source; }
            @Override RegistryAccess decorationAccess() { return RegistryAccess.EMPTY; }
        };
    }

    private static ClientColumnSample column(Block ground) {
        return new ClientColumnSample(64, 64, 0, BuiltInRegistries.BLOCK.getId(ground), 0,
                0, 0, 0, 0, 0, 0, BuiltInRegistries.BLOCK.getId(Blocks.DIRT),
                BuiltInRegistries.BLOCK.getId(Blocks.STONE), ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}

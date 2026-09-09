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
        tags.put(BlockTags.LOGS, holders(Blocks.OAK_LOG, Blocks.BIRCH_LOG));
        tags.put(BlockTags.LEAVES, holders(Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES));
        tags.put(BlockTags.DIRT, holders(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT));
        tags.put(BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.AIR, Blocks.SHORT_GRASS, Blocks.OAK_LEAVES));
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
        var prediction = new PredictionVegetation(sampler);
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
                () -> Long.MAX_VALUE, System::nanoTime);
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
                () -> Long.MAX_VALUE, System::nanoTime);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD,
                sampler(48271, Blocks.GRASS_BLOCK, List.of(tree())), budget)) {
            for (int cycle = 0; cycle < 150 && !budget.exhausted(); cycle++) {
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

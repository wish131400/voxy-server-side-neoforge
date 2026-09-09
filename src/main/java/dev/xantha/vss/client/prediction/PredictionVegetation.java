package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** World-coordinate vegetation shared by all prediction LOD levels. */
final class PredictionVegetation {
    private static final int MAX_CHUNKS = 512;
    private static final int MAX_BLOCKS = 262_144;
    private static final net.minecraft.core.Direction[] EXTERIOR_FACES = {
            net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST};
    private final ClientTerrainSampler terrain;
    private final ClientTerrainSampler context;
    private final RegistryAccess access;
    private final List<List<PlacedFeature>> featureSteps;
    private final PredictionSurfaceStructures structures;
    private final PredictionDiskCache diskCache;
    private final int settings;
    private long cacheRevision;
    private final Map<Long, Map<BlockPos, BlockState>> chunks = new LinkedHashMap<>(64, .75F, true);
    private final Set<Long> generating = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> loggedFailures = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> missingRegistryFailures = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> unsupportedFeatures = ConcurrentHashMap.newKeySet();
    private volatile String lastFeatureFailure = "none";
    private final VssLodSampleCache terrainColumns = new VssLodSampleCache(32_768);
    private int cachedBlocks;
    private final java.util.concurrent.atomic.LongAdder generatedChunks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder generatedBlocks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failedFeatures = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder meshedBlocks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder memoryHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder diskHits = new java.util.concurrent.atomic.LongAdder();

    boolean available() {
        for (int step = 0; step < featureSteps.size(); step++)
            for (var feature : featureSteps.get(step)) if (enabled(step,feature)) return true;
        return VSSClientConfig.CONFIG.predictionStructures && structures.available();
    }

    String diagnostics() {
        return "features=" + featureSteps.stream().mapToInt(List::size).sum() + ",chunks=" + generatedChunks.sum()
                + ",blocks=" + generatedBlocks.sum() + ",meshBlocks=" + meshedBlocks.sum()
                + ",surfaceMemoryHits=" + memoryHits.sum() + ",surfaceDiskHits=" + diskHits.sum()
                + ",skippedFeatures=" + failedFeatures.sum() + ",disabledRegistryFeatures="
                + missingRegistryFailures.size() + ",unsupportedFeatures=" + unsupportedFeatures.size()
                + ",featureFailure=" + lastFeatureFailure + "," + structures.diagnostics();
    }

    PredictionVegetation(ClientTerrainSampler terrain) {
        this(terrain, null);
    }

    PredictionVegetation(ClientTerrainSampler terrain, PredictionDiskCache diskCache) {
        this.terrain = terrain;
        this.diskCache = diskCache;
        this.settings = (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0) | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0);
        this.context = terrain.decorationContext();
        this.access = context.decorationAccess();
        List<List<PlacedFeature>> found = List.of();
        if (context.generatorContext() != null && context.randomStateContext() != null && access != null) {
            try {
                var generator = context.generatorContext();
                var steps = FeatureSorter.buildFeaturesPerStep(
                        List.copyOf(generator.getBiomeSource().possibleBiomes()),
                        biome -> generator.getBiomeGenerationSettings(biome).features(), true);
                found = steps.stream().map(step -> List.copyOf(step.features())).toList();
            } catch (RuntimeException failure) {
                if (VSSClientConfig.CONFIG.debugLogging) {
                    VSSLogger.debug("VSS vegetation placement context unavailable: " + failure);
                }
            }
        }
        this.featureSteps = List.copyOf(found);
        this.structures = new PredictionSurfaceStructures(context);
    }

    Tile tile(int baseX, int baseZ, int span, int spacing, boolean trees,
              BiPredicate<Integer, Integer> captured) {
        if (spacing > 8 || !available()) return Tile.EMPTY;
        int voxelSize = Math.max(1, spacing / 2);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int cz = Math.floorDiv(baseZ, 16) - 1; cz <= Math.floorDiv(baseZ + span - 1, 16) + 1; cz++) {
            for (int cx = Math.floorDiv(baseX, 16) - 1; cx <= Math.floorDiv(baseX + span - 1, 16) + 1; cx++) {
                if (Thread.currentThread().isInterrupted()) throw new PredictionMemoryBudget.MeshLimitException();
                for (var entry : chunk(cx, cz).entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockState state = entry.getValue();
                    if (pos.getX() < baseX - voxelSize || pos.getX() >= baseX + span + voxelSize
                            || pos.getZ() < baseZ - voxelSize || pos.getZ() >= baseZ + span + voxelSize
                            || captured.test(pos.getX(), pos.getZ())) continue;
                    if (vegetation(state) && !trees || !solid(state) && spacing > 2) continue;
                    blocks.put(pos, state);
                }
            }
        }
        Tile tile = boundedTile(blocks, baseX, baseZ, span, spacing, voxelSize);
        meshedBlocks.add(tile.cells().values().stream().mapToInt(List::size).sum());
        return tile;
    }

    static Tile boundedTile(Map<BlockPos, BlockState> blocks, int baseX, int baseZ,
                            int span, int spacing, int initialSize) {
        int size = initialSize;
        Map<BlockPos, BlockState> reduced = reduceBlocks(blocks, size);
        // Leave room for terrain and cliff walls within the mesh's hard cap.
        // Dense forests simplify on a fixed world grid before allocation,
        // instead of making the entire terrain tile fail and retry forever.
        while (vegetationVertices(reduced, size) > 131_072 && size < 8) {
            size *= 2;
            reduced = reduceBlocks(blocks, size, true);
        }
        return Tile.of(reduced, baseX, baseZ, span, spacing, size);
    }

    static Map<BlockPos, BlockState> reduceBlocks(Map<BlockPos, BlockState> blocks, int size) {
        return reduceBlocks(blocks,size,false);
    }

    private static Map<BlockPos, BlockState> reduceBlocks(Map<BlockPos, BlockState> blocks, int size, boolean thinBamboo) {
        if (size <= 1) return blocks;
        Map<BlockPos, BlockState> result = new HashMap<>();
        Map<BlockPos, Map<BlockState, Integer>> votes = new HashMap<>();
        // Count original blocks at every level, not winners from the previous
        // reduction. A single log must not repaint an entire leaf-filled voxel.
        for (var entry : blocks.entrySet()) {
                BlockPos p = entry.getKey();
                if (entry.getValue().is(Blocks.BAMBOO)) {
                    // Thin multipart stalks remain one-block models. Under
                    // pressure keep complete stalks on a sparser horizontal grid.
                    if (!thinBamboo || Math.floorMod(p.getX(), size) == 0 && Math.floorMod(p.getZ(), size) == 0)
                        result.put(p, entry.getValue());
                    continue;
                }
                if (!woody(entry.getValue())) {
                    result.put(p, entry.getValue());
                    continue;
                }
                BlockPos key = new BlockPos(Math.floorDiv(p.getX(), size) * size,
                        Math.floorDiv(p.getY(), size) * size, Math.floorDiv(p.getZ(), size) * size);
                votes.computeIfAbsent(key, ignored -> new HashMap<>()).merge(entry.getValue(), 1, Integer::sum);
        }
        votes.forEach((pos, counts) -> {
            Map<net.minecraft.world.level.block.Block,Integer> materialCounts = new HashMap<>();
            counts.forEach((state,count) -> materialCounts.merge(state.getBlock(),count,Integer::sum));
            result.putIfAbsent(pos, counts.entrySet().stream().max(Comparator
                    .comparingInt((Map.Entry<BlockState,Integer> entry) -> materialCounts.get(entry.getKey().getBlock()))
                    .thenComparingInt(entry -> entry.getKey().is(BlockTags.LEAVES) ? 1 : 0)
                    .thenComparingInt(Map.Entry::getValue)
                    .thenComparingInt(entry -> -net.minecraft.world.level.block.Block.getId(entry.getKey())))
                    .orElseThrow().getKey());
        });
        return result;
    }

    private static long vegetationVertices(Map<BlockPos, BlockState> blocks, int size) {
        long vertices = 0;
        for (var entry : blocks.entrySet()) {
            // Four sides + top + up to four double-sided leaf quads.
            if (!renderable(entry.getValue())) continue;
            if (entry.getValue().is(Blocks.BAMBOO)) { vertices += 54; continue; }
            if (!solid(entry.getValue())) { vertices += 12; continue; }
            BlockPos p = entry.getKey();
            int step = voxelSize(entry.getValue(), size);
            for (var direction : EXTERIOR_FACES) {
                BlockState neighbor = blocks.get(p.relative(direction, step));
                if (neighbor == null || !solid(neighbor)) vertices += 6;
            }
        }
        return vertices;
    }

    Map<BlockPos, BlockState> chunk(int x, int z) {
        long key = (long) x << 32 | z & 0xFFFFFFFFL;
        // Cached immutable vegetation does not need a generation permit.
        synchronized (chunks) {
            var cached = chunks.get(key);
            if (cached != null) { memoryHits.increment(); return cached; }
        }
        if (!generating.add(key)) throw new PredictionWorkDeferred();
        try {
            long revision;
            synchronized (chunks) {
                var cached = chunks.get(key);
                if (cached != null) { memoryHits.increment(); return cached; }
                revision = cacheRevision;
            }
            Map<BlockPos, BlockState> result;
            try (var lease = diskCache == null ? null : diskCache.lease(PredictionDiskCache.Key.surface(x, z, settings))) {
                result = lease == null ? null : diskCache.readSurface(lease);
                if (result != null) diskHits.increment();
                if (result == null) {
                    result = generate(x, z);
                    // A toggle during generation cannot save partial content
                    // under the old settings identity.
                    int current = (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0) | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0);
                    if (lease != null && current == settings) diskCache.writeSurface(lease, result);
                }
            }
            synchronized (chunks) {
                if (revision != cacheRevision) return result;
                chunks.put(key, result);
                cachedBlocks += result.size();
                while (chunks.size() > MAX_CHUNKS || cachedBlocks > MAX_BLOCKS) {
                    var first = chunks.entrySet().iterator();
                    cachedBlocks -= first.next().getValue().size();
                    first.remove();
                }
            }
            return result;
        } finally {
            generating.remove(key);
        }
    }

    void invalidate(int chunkX, int chunkZ) {
        synchronized (chunks) {
            cacheRevision++;
            for (int z = chunkZ - 2; z <= chunkZ + 2; z++) for (int x = chunkX - 2; x <= chunkX + 2; x++) {
                var removed = chunks.remove((long) x << 32 | z & 0xFFFFFFFFL);
                if (removed != null) cachedBlocks -= removed.size();
            }
        }
        // Density data is deterministic, but do not keep cached virtual
        // ground assumptions across authoritative updates.
        terrainColumns.clear();
    }

    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ) {
        var level = new PredictionDecorationLevel(terrain, context, access, chunkX, chunkZ, terrainColumns);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        BlockPos origin = new BlockPos(chunkX * 16, terrain.profile().minY(), chunkZ * 16);
        long seed = random.setDecorationSeed(terrain.profile().seed(), origin.getX(), origin.getZ());
        try (var nativeStage = terrain instanceof RustTerrainSampler rust
                ? new RustVegetationStage(rust, level, chunkX, chunkZ) : null) {
            for (int step = 0; step < Math.max(featureSteps.size(), GenerationStep.Decoration.values().length); step++) {
                structures.place(level, chunkX, chunkZ, seed, step);
                List<PlacedFeature> features = step < featureSteps.size() ? featureSteps.get(step) : List.of();
                int currentStep = step;
                if (features.stream().noneMatch(feature -> enabled(currentStep,feature))) continue;
                if (nativeStage != null) nativeStage.selectStep(features, step);
                for (int index = 0; index < features.size(); index++) {
                    if (Thread.currentThread().isInterrupted()) throw new PredictionMemoryBudget.MeshLimitException();
                    PlacedFeature feature = features.get(index);
                    if (!enabled(step,feature)) continue;
                    if (missingRegistryFailures.contains(feature) || unsupportedFeatures.contains(feature)) continue;
                    if (feature.placement().stream().noneMatch(
                            modifier -> modifier instanceof net.minecraft.world.level.levelgen.placement.BiomeFilter)) {
                        // Unfiltered modded features must still belong to a local biome.
                        int y = level.column(origin.getX(), origin.getZ()).surfaceY();
                        if (!context.generatorContext().getBiomeGenerationSettings(
                                level.getBiome(new BlockPos(origin.getX(), y, origin.getZ()))).hasFeature(feature)) continue;
                    }
                    if (nativeStage != null && nativeStage.place(index)) continue;
                    if (nativeStage != null) nativeStage.beforeJava();
                    // Keep the global index even when other features were filtered.
                    random.setFeatureSeed(seed, index, step);
                    level.beginFeature();
                    boolean success = false;
                    try {
                        PredictionSurfaceFeatureAdapters.place(feature,level,context.generatorContext(),random,origin);
                        success = true;
                    } catch (RuntimeException failure) {
                        if (PredictionMissingRegistry.permanent(failure, access)) missingRegistryFailures.add(feature);
                        else if (failure instanceof UnsupportedOperationException) unsupportedFeatures.add(feature);
                        lastFeatureFailure = failure.toString();
                        failedFeatures.increment();
                        if (VSSClientConfig.CONFIG.debugLogging && loggedFailures.add(feature))
                            VSSLogger.debug("VSS skipped unsupported surface placement: " + feature + ": " + failure);
                    } finally {
                        level.endFeature(success);
                        if (nativeStage != null) nativeStage.afterJava();
                    }
                }
                if (nativeStage != null) nativeStage.finish();
            }
        }
        Map<BlockPos, BlockState> exterior = surfaceBlocks(level);
        generatedChunks.increment();
        generatedBlocks.add(exterior.size());
        return Map.copyOf(exterior);
    }

    private boolean enabled(int step, PlacedFeature feature) {
        return surfaceFeature(step,feature,context.profile().dimension().equals(net.minecraft.world.level.Level.NETHER.location()),
                VSSClientConfig.CONFIG.predictionTrees,VSSClientConfig.CONFIG.predictionStructures);
    }

    static boolean surfaceFeature(int step, PlacedFeature feature, boolean nether, boolean trees, boolean structures) {
        if (step < 0 || step >= GenerationStep.Decoration.values().length) return false;
        var stage = GenerationStep.Decoration.values()[step];
        return switch (stage) {
            case RAW_GENERATION, LAKES, LOCAL_MODIFICATIONS, TOP_LAYER_MODIFICATION -> true;
            case SURFACE_STRUCTURES -> structures;
            case VEGETAL_DECORATION -> trees;
            case UNDERGROUND_ORES -> feature.feature().value().feature() == net.minecraft.world.level.levelgen.feature.Feature.DISK;
            case UNDERGROUND_DECORATION -> nether;
            default -> false;
        };
    }

    /** Keep surface replacements and contiguous cuts, including air and irrigation water.
     * Disconnected underground writes are not part of a surface-only prediction. */
    static Map<BlockPos, BlockState> surfaceBlocks(PredictionDecorationLevel level) {
        Map<Long, Integer> floors = new HashMap<>();
        Map<BlockPos, BlockState> exterior = new HashMap<>();
        level.placed().forEach((pos, state) -> {
            if (state.isAir() && pos.getY() >= level.column(pos.getX(), pos.getZ()).surfaceY()) return;
            long key = (long) pos.getX() << 32 | pos.getZ() & 0xFFFFFFFFL;
            int floor = floors.computeIfAbsent(key, ignored -> {
                int y = level.column(pos.getX(), pos.getZ()).surfaceY();
                var cursor = new BlockPos.MutableBlockPos(pos.getX(), y - 1, pos.getZ());
                while (y > level.getMinBuildHeight() && level.placed().containsKey(cursor.setY(y - 1))) y--;
                return y;
            });
            if (pos.getY() >= floor && (renderable(state) || state.isAir()
                    || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                    && (!level.isStructureBlock(pos) || VSSClientConfig.CONFIG.predictionStructures))
                exterior.put(pos, state);
        });
        return exterior;
    }

    // Terrain edits, roads, crops and structure shapes retain exact footprints.
    // Snapping farmland and air cuts into tree-sized voxels buries their models.
    static int voxelSize(BlockState state, int treeSize) { return woody(state) ? treeSize : 1; }

    static boolean woody(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK);
    }

    static boolean solid(BlockState state) { return woody(state) || !vegetation(state) || state.is(Blocks.CACTUS); }

    static boolean renderable(BlockState state) {
        return !state.isAir() && !state.is(Blocks.BARRIER) && !state.is(Blocks.STRUCTURE_BLOCK)
                && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.JIGSAW)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
    }

    private static boolean vegetation(BlockState state) {
        return woody(state) || state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                && !state.is(Blocks.LILY_PAD) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BAMBOO);
    }

    record Voxel(int x, int y, int z, int size, BlockState state) { }

    record Tile(Map<Integer, List<Voxel>> cells, Map<BlockPos, BlockState> blocks,
                int baseX, int baseZ, int voxelSize, int maxY) {
        static final Tile EMPTY = new Tile(Map.of(), Map.of(), 0, 0, 1, Integer.MIN_VALUE);

        static Tile of(Map<BlockPos, BlockState> blocks, int baseX, int baseZ, int span,
                       int spacing, int voxelSize) {
            Map<Integer, List<Voxel>> cells = new HashMap<>();
            int maxY = Integer.MIN_VALUE;
            for (var entry : blocks.entrySet()) {
                BlockPos p = entry.getKey();
                int x = p.getX() - baseX, z = p.getZ() - baseZ;
                if (x < 0 || z < 0 || x >= span || z >= span) continue;
                int size = PredictionVegetation.voxelSize(entry.getValue(), voxelSize);
                int cell = z / spacing * (span / spacing) + x / spacing;
                cells.computeIfAbsent(cell, ignored -> new ArrayList<>())
                        .add(new Voxel(x, p.getY(), z, size, entry.getValue()));
                maxY = Math.max(maxY, p.getY() + size);
            }
            cells.values().forEach(list -> list.sort(Comparator.comparingInt(Voxel::y)
                    .thenComparingInt(Voxel::z).thenComparingInt(Voxel::x)));
            return new Tile(cells, blocks, baseX, baseZ, voxelSize, maxY);
        }

        List<Voxel> cell(int cell) { return cells.getOrDefault(cell, List.of()); }

        boolean occupied(int x, int y, int z) {
            BlockState state = blocks.get(new BlockPos(baseX + x, y, baseZ + z));
            return state != null && solid(state) && PredictionSurfaceShapes.occludes(state);
        }
    }
}

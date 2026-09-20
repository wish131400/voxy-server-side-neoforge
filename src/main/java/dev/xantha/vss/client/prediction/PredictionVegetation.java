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
    private final PredictionTreeModels treeModels;
    private final PredictionDiskCache diskCache;
    private final int settings;
    private long cacheRevision;
    private final Map<Long, Map<BlockPos, BlockState>> chunks = new LinkedHashMap<>(64, .75F, true);
    private final Set<Long> generating = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> loggedFailures = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> missingRegistryFailures = ConcurrentHashMap.newKeySet();
    private final Set<PlacedFeature> unsupportedFeatures = ConcurrentHashMap.newKeySet();
    private volatile String lastFeatureFailure = "none";
    private final VssLodSampleCache terrainColumns;
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
                + ",featureFailure=" + lastFeatureFailure + "," + structures.diagnostics()
                + (treeModels == null ? "" : "," + treeModels.diagnostics());
    }

    PredictionVegetation(ClientTerrainSampler terrain) {
        this(terrain, null);
    }

    PredictionVegetation(ClientTerrainSampler terrain, PredictionDiskCache diskCache) {
        this(terrain, diskCache, true);
    }

    // Exact feature replay remains available to compatibility tests and paired benchmarks.
    PredictionVegetation(ClientTerrainSampler terrain, PredictionDiskCache diskCache, boolean reuseTrees) {
        this.terrain = terrain;
        this.terrainColumns = new VssLodSampleCache(terrain.interiorTerrain() ? 512 : 32_768);
        this.diskCache = diskCache;
        this.settings = (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0) | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0)
                | (reuseTrees ? 28 : 0) | (terrain.interiorTerrain() ? 96 : 0);
        this.context = terrain.decorationContext();
        this.access = context.decorationAccess();
        this.treeModels = reuseTrees ? new PredictionTreeModels(access, context.generatorContext()) : null;
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

    /** Read existing placement only; this path must never trigger world generation. */
    boolean hasCachedChunk(int x, int z) {
        long key = (long)Math.floorDiv(x, 16) << 32 | Math.floorDiv(z, 16) & 0xffffffffL;
        synchronized (chunks) { return chunks.containsKey(key); }
    }

    /** Read existing placement only; this path must never trigger world generation. */
    Tile cachedDisplay(int baseX, int baseZ, int span, int spacing,
                       BiPredicate<Integer,Integer> captured) {
        if (spacing > 8) return Tile.EMPTY;
        int voxelSize = Math.max(1, spacing / 2);
        List<Map<BlockPos,BlockState>> existing = new ArrayList<>();
        synchronized (chunks) {
            int minX = Math.floorDiv(baseX, 16) - 1, maxX = Math.floorDiv(baseX + span - 1, 16) + 1;
            int minZ = Math.floorDiv(baseZ, 16) - 1, maxZ = Math.floorDiv(baseZ + span - 1, 16) + 1;
            // Small fine tiles use direct lookup; wide sparse tiles visit only resident chunks.
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > chunks.size()) {
                for (var entry : chunks.entrySet()) {
                    int cx = (int) (entry.getKey() >> 32), cz = (int) (long) entry.getKey();
                    if (cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ) existing.add(entry.getValue());
                }
            } else for (int cz = minZ; cz <= maxZ; cz++) {
                for (int cx = minX; cx <= maxX; cx++) {
                    var cached = chunks.get((long) cx << 32 | cz & 0xFFFFFFFFL);
                    if (cached != null) existing.add(cached);
                }
            }
        }
        Map<BlockPos,BlockState> blocks = new HashMap<>();
        for (var chunk : existing) for (var e : chunk.entrySet()) {
            var p = e.getKey(); var state = e.getValue();
            if (p.getX()<baseX-voxelSize || p.getX()>=baseX+span+voxelSize
                    || p.getZ()<baseZ-voxelSize || p.getZ()>=baseZ+span+voxelSize
                    || captured.test(p.getX(),p.getZ())) continue;
            if (vegetation(state) && !VSSClientConfig.CONFIG.predictionTrees || !solid(state) && spacing > 2) continue;
            blocks.put(p,state);
        }
        // A capture refresh must use the same geometry as the decorated tile.
        return boundedTile(blocks, baseX, baseZ, span, spacing, voxelSize);
    }

    static Tile cachedRepresentative(Map<BlockPos,BlockState> blocks, int x, int z, int span, int spacing) {
        if (blocks.isEmpty()) return Tile.EMPTY;
        // Stable spatial selection, bounded before meshing. Retain original
        // geometry rather than inventing a canopy box or rescaling its blocks.
        var selected = new LinkedHashMap<BlockPos,BlockState>();
        blocks.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator
                .<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getY)))
                .limit(4096).forEach(e -> selected.put(e.getKey(),e.getValue()));
        return Tile.of(Map.copyOf(selected),x,z,span,spacing,1).withExteriorEnvelope();
    }

    private final Map<Long,Float> forestCoverage = new ConcurrentHashMap<>();
    private void noteForest(long chunkKey, Map<BlockPos,BlockState> blocks) {
        int cx=(int)(chunkKey>>32), cz=(int)chunkKey;
        Set<Long> columns=new java.util.HashSet<>();
        for(var e:blocks.entrySet()) if(e.getValue().is(BlockTags.LEAVES)) {
            var p=e.getKey();
            if(Math.floorDiv(p.getX(),16)==cx && Math.floorDiv(p.getZ(),16)==cz)
                columns.add((long)p.getX()<<32 | p.getZ() & 0xffffffffL);
        }
        forestCoverage.put(chunkKey,Math.min(1,columns.size()/256F));
    }

    int forestTint(ClientColumnSample sample, int x, int z, int spacing, int grass, int foliage) {
        if (spacing<=8 || !VSSClientConfig.CONFIG.predictionTrees || sample.hasFluid() || sample.snow()
                || sample.ice() || sample.topBlockIndex()!=PredictionMaterialPalette.grassBlockIndex()) return grass;
        float cover=forestCoverage.getOrDefault((long)Math.floorDiv(x,16)<<32 | Math.floorDiv(z,16)&0xffffffffL,0F);
        return forestTint(grass,foliage,cover);
    }

    static int forestTint(int grass,int foliage,float coverage) {
        float weight=Math.max(0,Math.min(1,coverage))*.35F;
        int color=grass&0xff000000;
        for(int shift:new int[]{0,8,16}) color|=Math.round(((grass>>>shift)&255)*(1-weight)
                +((foliage>>>shift)&255)*weight)<<shift;
        return color;
    }

    static Tile boundedTile(Map<BlockPos, BlockState> blocks, int baseX, int baseZ,
                            int span, int spacing, int initialSize) {
        if (spacing <= 2) {
            Map<BlockPos, BlockState> fine = fineBlocks(blocks, initialSize);
            boolean envelope = initialSize > 1 || fineVegetationVertices(fine, 1) > 131_072;
            if (envelope) {
                int thinning = Math.max(2, initialSize);
                fine = fineBlocks(blocks, thinning);
                while (groundCoverVertices(fine) > 98_304 && thinning < 8) fine = fineBlocks(blocks, thinning *= 2);
            }
            Tile tile = Tile.of(fine, baseX, baseZ, span, spacing, 1);
            return envelope ? tile.withExteriorEnvelope() : tile;
        }
        int size = initialSize;
        Map<BlockPos, BlockState> reduced = reduceBlocks(blocks, size, true);
        // Leave room for terrain and cliff walls within the mesh's hard cap.
        // Dense forests simplify on a fixed world grid before allocation,
        // instead of making the entire terrain tile fail and retry forever.
        // Distant coarse tiles may still use representative tree voxels.
        // The fine path above never enters this size-increasing fallback.
        while (vegetationVertices(reduced, size) > 131_072 && size < 8) {
            size *= 2;
            reduced = reduceBlocks(blocks, size, true);
        }
        return Tile.of(reduced, baseX, baseZ, span, spacing, size);
    }

    private static long groundCoverVertices(Map<BlockPos, BlockState> blocks) {
        long vertices = 0;
        for (BlockState state : blocks.values()) {
            if (state.is(Blocks.BAMBOO)) vertices += 54;
            else if (thinGroundCover(state)) vertices += 12;
        }
        return vertices;
    }

    private static Map<BlockPos, BlockState> fineBlocks(Map<BlockPos, BlockState> blocks, int thinning) {
        Map<BlockPos, BlockState> result = new HashMap<>(blocks.size());
        blocks.forEach((p, state) -> {
            if (thinning > 1 && (thinGroundCover(state) || state.is(Blocks.BAMBOO))
                    && (Math.floorMod(p.getX(), thinning) != 0 || Math.floorMod(p.getZ(), thinning) != 0)) return;
            // Resource packs may select different leaf models by distance and
            // persistence. Merge matching rendered materials in the mesh, never
            // rewrite a healthy leaf to the default decaying state here.
            result.put(p, state);
        });
        return result;
    }

    /**
     * Conservative estimate before the final per-cell rectangle merge.
     * Complete cubes merge into exposed vertical runs; shaped blocks and
     * bamboo retain their individual model geometry.
     */
    static long fineVegetationVertices(Map<BlockPos, BlockState> blocks, int size) {
        long vertices = 0;
        // Count a run only at its first exposed face. Looking at its immediate
        // predecessor is equivalent to sorting every vertical column, without
        // allocating a TreeMap node (and boxed Y) for every placed block.
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (var entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            if (!renderable(state)) continue;
            if (state.is(Blocks.BAMBOO)) { vertices += 54; continue; }
            if (!solid(state)) { vertices += 12; continue; }
            BlockPos p = entry.getKey();
            int step = voxelSize(state, size);
            if (!mergeable(state, step)) {
                // Shape interiors can emit faces even next to another block.
                vertices += PredictionSurfaceShapes.boxes(state, step).size() * 30L;
                continue;
            }
            BlockState above = blocks.get(neighborPos.set(p.getX(), p.getY() + 1, p.getZ()));
            if (!occluding(above)) vertices += 6;
            BlockState below = blocks.get(neighborPos.set(p.getX(), p.getY() - 1, p.getZ()));
            for (var direction : EXTERIOR_FACES) {
                if (direction == net.minecraft.core.Direction.UP) continue;
                int nx = p.getX() + direction.getStepX(), nz = p.getZ() + direction.getStepZ();
                BlockState neighbor = blocks.get(neighborPos.set(nx, p.getY(), nz));
                if (occluding(neighbor)) continue;
                BlockState predecessorNeighbor = below == state
                        ? blocks.get(neighborPos.set(nx, p.getY() - 1, nz)) : null;
                if (below != state || occluding(predecessorNeighbor)) vertices += 6;
            }
        }
        return vertices;
    }

    private static boolean occluding(BlockState state) {
        return state != null && solid(state) && PredictionSurfaceShapes.occludes(state);
    }

    static boolean mergeable(BlockState state, int size) {
        return size == 1 && renderable(state) && solid(state) && PredictionSurfaceShapes.occludes(state);
    }

    private static boolean thinGroundCover(BlockState state) {
        return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS);
    }

    static Map<BlockPos, BlockState> reduceBlocks(Map<BlockPos, BlockState> blocks, int size) {
        return reduceBlocks(blocks,size,false);
    }

    private static Map<BlockPos, BlockState> reduceBlocks(Map<BlockPos, BlockState> blocks, int size, boolean thinCover) {
        if (size <= 1) return blocks;
        Map<BlockPos, BlockState> result = new HashMap<>();
        Map<BlockPos, Map<BlockState, Integer>> votes = new HashMap<>();
        // Count original blocks at every level, not winners from the previous
        // reduction. A single log must not repaint an entire leaf-filled voxel.
        for (var entry : blocks.entrySet()) {
                BlockPos p = entry.getKey();
                if (entry.getValue().is(Blocks.BAMBOO) || thinGroundCover(entry.getValue())) {
                    // Keep complete stalks and double-height plants on a stable
                    // world grid only under mesh pressure; never enlarge them.
                    if (!thinCover || Math.floorMod(p.getX(), size) == 0 && Math.floorMod(p.getZ(), size) == 0)
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
                var data = lease == null ? null : diskCache.readSurfaceData(lease);
                result = data == null ? null : data.blocks();
                if (result != null) {
                    diskHits.increment();
                    if (!data.canonical()) {
                        result = PredictionBamboo.normalize(result);
                        if (treeModels != null) result = PredictionLeafStates.settle(result);
                        diskCache.writeSurface(lease, result, true);
                    }
                }
                if (result == null) {
                    result = generate(x, z);
                    // A toggle during generation cannot save partial content
                    // under the old settings identity.
                    int current = (VSSClientConfig.CONFIG.predictionTrees ? 1 : 0) | (VSSClientConfig.CONFIG.predictionStructures ? 2 : 0);
                    if (lease != null && current == (settings & 3)) diskCache.writeSurface(lease, result, true);
                }
            }
            synchronized (chunks) {
                if (revision != cacheRevision) return result;
                chunks.put(key, result);
                noteForest(key, result);
                cachedBlocks += result.size();
                while (chunks.size() > MAX_CHUNKS || cachedBlocks > MAX_BLOCKS) {
                    var first = chunks.entrySet().iterator();
                    var retired = first.next();
                    cachedBlocks -= retired.getValue().size();
                    forestCoverage.remove(retired.getKey());
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
                long key = (long)x << 32 | z & 0xFFFFFFFFL;
                forestCoverage.remove(key);
                var removed = chunks.remove(key);
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
        // Native and Java placements share complete cave columns and ordered edits.
        try (var nativeStage = terrain instanceof RustTerrainSampler rust
                ? new RustVegetationStage(rust, level, chunkX, chunkZ, treeModels != null) : null) {
            for (int step = 0; step < Math.max(featureSteps.size(), GenerationStep.Decoration.values().length); step++) {
                level.useDisplayTerrain(false);
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
                    boolean reusableTree = treeModels != null && treeModels.supports(feature);
                    level.useDisplayTerrain(reusableTree);
                    if (feature.placement().stream().noneMatch(
                            modifier -> modifier instanceof net.minecraft.world.level.levelgen.placement.BiomeFilter)) {
                        // Unfiltered modded features must still belong to a local biome.
                        if (!belongsToColumn(level, feature, origin)) continue;
                    }
                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;
                    if (nativeStage != null) nativeStage.beforeJava();
                    level.useDisplayTerrain(reusableTree);
                    // Keep the global index even when other features were filtered.
                    random.setFeatureSeed(seed, index, step);
                    level.beginFeature();
                    boolean success = false;
                    try {
                        if (reusableTree) treeModels.place(feature, level, random, origin);
                        else PredictionSurfaceFeatureAdapters.place(feature,level,context.generatorContext(),random,origin);
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
        Map<BlockPos, BlockState> exterior = PredictionBamboo.normalize(surfaceBlocks(level));
        if (treeModels != null) exterior = PredictionLeafStates.settle(exterior);
        generatedChunks.increment();
        generatedBlocks.add(exterior.size());
        return Map.copyOf(exterior);
    }

    private boolean enabled(int step, PlacedFeature feature) {
        return surfaceFeature(step,feature,context.profile().dimension().equals(net.minecraft.world.level.Level.NETHER.location()),
                VSSClientConfig.CONFIG.predictionTrees,VSSClientConfig.CONFIG.predictionStructures);
    }

    private boolean belongsToColumn(PredictionDecorationLevel level, PlacedFeature feature, BlockPos origin) {
        var column = level.column(origin.getX(), origin.getZ());
        if (column.volume() != null) {
            var volume = column.volume();
            for (int i = 0; i < volume.size(); i++) {
                int y = volume.top(i);
                if (!volume.occupied(y, false) && context.generatorContext().getBiomeGenerationSettings(
                        level.getBiome(new BlockPos(origin.getX(), y, origin.getZ()))).hasFeature(feature)) return true;
            }
            return false;
        }
        return context.generatorContext().getBiomeGenerationSettings(
                level.getBiome(new BlockPos(origin.getX(), column.surfaceY(), origin.getZ()))).hasFeature(feature);
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
        if (level.interiorTerrain()) {
            Map<BlockPos, BlockState> interior = new HashMap<>();
            level.placed().forEach((pos, state) -> {
                // The volume mesher applies these edits at their exact footprint,
                // including rooms carved into rock and fluid replacements.
                if ((renderable(state) || state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
                        && (!level.isStructureBlock(pos) || VSSClientConfig.CONFIG.predictionStructures))
                    interior.put(pos, state);
            });
            return interior;
        }
        Map<Long, Integer> floors = new HashMap<>();
        Map<BlockPos, BlockState> exterior = new HashMap<>();
        level.placed().forEach((pos, state) -> {
            if (state.isAir() && pos.getY() >= level.exteriorColumn(pos.getX(), pos.getZ()).surfaceY()) return;
            long key = (long) pos.getX() << 32 | pos.getZ() & 0xFFFFFFFFL;
            int floor = floors.computeIfAbsent(key, ignored -> {
                int y = level.exteriorColumn(pos.getX(), pos.getZ()).surfaceY();
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
        return state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM)
                || state.is(Blocks.NETHER_WART_BLOCK) || state.is(Blocks.WARPED_WART_BLOCK);
    }

    static boolean solid(BlockState state) { return !fire(state) && (woody(state) || !vegetation(state) || state.is(Blocks.CACTUS)); }

    static boolean fire(BlockState state) { return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE); }

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
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BAMBOO)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS);
    }

    record Voxel(int x, int y, int z, int size, BlockState state) { }

    static PredictionMesh meshWithinBudget(Tile original, int span, int spacing,
                                           java.util.function.Function<Tile, PredictionMesh> build) {
        Tile current = original;
        int reduction = spacing <= 2 ? 1 : original.voxelSize();
        while (true) {
            try { return build.apply(current); }
            catch (PredictionMemoryBudget.MeshLimitException limit) {
                if (current.blocks().isEmpty() || reduction >= 8) throw limit;
                // Retry geometry from retained blocks, never replay worldgen.
                // Always reduce from the original map to retain material votes.
                current = boundedTile(original.blocks(), original.baseX(), original.baseZ(),
                        span, spacing, reduction *= 2);
            }
        }
    }

    record Tile(Map<Integer, List<Voxel>> cells, Map<BlockPos, BlockState> blocks,
                int baseX, int baseZ, int voxelSize, int maxY, it.unimi.dsi.fastutil.longs.Long2IntMap exteriorTops,
                it.unimi.dsi.fastutil.longs.Long2IntMap exteriorFloors) {
        static final Tile EMPTY = new Tile(Map.of(), Map.of(), 0, 0, 1, Integer.MIN_VALUE, it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP, it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP);

        Tile withoutExteriorEnvelope() {
            return exteriorTops.isEmpty() ? this : new Tile(cells, blocks, baseX, baseZ, voxelSize, maxY,
                    it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP, it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP);
        }

        Tile withExteriorEnvelope() {
            var tops = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            tops.defaultReturnValue(Integer.MIN_VALUE);
            blocks.forEach((p, state) -> {
                if (mergeable(state, 1)) {
                    long key = columnKey(p.getX() - baseX, p.getZ() - baseZ);
                    tops.put(key, Math.max(tops.get(key), p.getY()));
                }
            });
            var floors = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(tops.size());
            floors.defaultReturnValue(Integer.MIN_VALUE);
            var cursor = new BlockPos.MutableBlockPos();
            for (var entry : tops.long2IntEntrySet()) {
                long key = entry.getLongKey();
                int x = (int) (key >> 32) + baseX, z = (int) key + baseZ, bottom = entry.getIntValue();
                BlockState below;
                while ((below = blocks.get(cursor.set(x, bottom - 1, z))) != null && mergeable(below, 1)) bottom--;
                floors.put(key, bottom);
            }
            return new Tile(cells, blocks, baseX, baseZ, 1, maxY,
                    it.unimi.dsi.fastutil.longs.Long2IntMaps.unmodifiable(tops),
                    it.unimi.dsi.fastutil.longs.Long2IntMaps.unmodifiable(floors));
        }

        boolean exteriorFaceVisible(Voxel voxel, int direction) {
            if (exteriorTops.isEmpty() || !mergeable(voxel.state(), voxel.size())) return true;
            if (voxel.y() < exteriorFloors.get(columnKey(voxel.x(), voxel.z()))) return false;
            int dx = direction == 3 ? -1 : direction == 4 ? 1 : 0;
            int dz = direction == 1 ? -1 : direction == 2 ? 1 : 0;
            int top = exteriorTops.getOrDefault(columnKey(voxel.x() + dx, voxel.z() + dz), Integer.MIN_VALUE);
            // Keep existing roof/outer faces; never move a leaf or fill an air
            // cell. Hidden internal canopy layers are optional far-view detail.
            return direction == 0 ? voxel.y() >= top : voxel.y() > top;
        }

        private static long columnKey(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }

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
            return new Tile(cells, blocks, baseX, baseZ, voxelSize, maxY, it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP, it.unimi.dsi.fastutil.longs.Long2IntMaps.EMPTY_MAP);
        }

        List<Voxel> cell(int cell) { return cells.getOrDefault(cell, List.of()); }

        boolean occupied(int x, int y, int z) {
            BlockState state = blocks.get(new BlockPos(baseX + x, y, baseZ + z));
            return state != null && solid(state) && PredictionSurfaceShapes.occludes(state);
        }
    }
}

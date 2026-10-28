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
import net.neoforged.fml.ModList;

/** World-coordinate vegetation shared by all prediction LOD levels. */
final class PredictionVegetation {
    static final int PREDICATE_SETTINGS_VERSION = 1 << 8;
    // Invalidate incomplete structure placements/finished meshes, keeping raw terrain samples.
    static final int STRUCTURE_RUNTIME_SETTINGS_VERSION = 1 << 12;
    private static final int PREDICATE_SETTINGS = ModList.get() != null && ModList.get().isLoaded("byepregen")
            ? PREDICATE_SETTINGS_VERSION : 0;
    private static final int MAX_CHUNKS = 512;
    private static final int MAX_BLOCKS = 262_144;
    private static final GenerationStep.Decoration[] DECORATION_STEPS = GenerationStep.Decoration.values();
    private static final net.minecraft.core.Direction[] EXTERIOR_FACES = {
            net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST};
    private final ClientTerrainSampler terrain;
    private final ClientTerrainSampler context;
    private final RegistryAccess access;
    private final List<List<PlacedFeature>> featureSteps;
    private volatile EnabledFeatures enabledFeatures;
    private record EnabledFeatures(int[][] indices) { }
    private final Set<PlacedFeature> biomeFiltered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final PredictionSurfaceStructures structures;
    private final PredictionTreeModels treeModels;
    private final PredictionDiskCache diskCache;
    private final int settings;
    private final Map<Long, Map<BlockPos, BlockState>> chunks = new LinkedHashMap<>(64, .75F, true);
    private final Map<Long, Generation> generationJobs = new ConcurrentHashMap<>();
    private static final class Generation {
        volatile boolean valid = true;
        final Set<Long> dirtyChunks = new java.util.HashSet<>();
    }
    private final PredictionVegetationDisplayCache displayCache = new PredictionVegetationDisplayCache();
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

    static int predicateSettings() { return PREDICATE_SETTINGS; }

    static int runtimeSettings() { return STRUCTURE_RUNTIME_SETTINGS_VERSION | PREDICATE_SETTINGS; }

    boolean available() {
        for (var step : enabledFeatures().indices()) if (step.length != 0) return true;
        return structures.available();
    }

    /** Counters only: sampling never waits for placement, tree models or display-cache locks. */
    Map<String,Object> performanceSnapshot() {
        return Map.of("initialized",true,"generatedChunks",generatedChunks.sum(),
                "generatedBlocks",generatedBlocks.sum(),"meshedBlocks",meshedBlocks.sum(),
                "memoryHits",memoryHits.sum(),"diskHits",diskHits.sum(),
                "activeChunks",generationJobs.size(),"failedFeatures",failedFeatures.sum(),
                "lastFailure",lastFeatureFailure);
    }

    String diagnostics() {
        return "features=" + featureSteps.stream().mapToInt(List::size).sum() + ",chunks=" + generatedChunks.sum()
                + ",blocks=" + generatedBlocks.sum() + ",meshBlocks=" + meshedBlocks.sum()
                + ",surfaceMemoryHits=" + memoryHits.sum() + ",surfaceDiskHits=" + diskHits.sum()
                + "," + displayCache.diagnostics()
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
        this.settings = 3
                | (reuseTrees ? 28 : 0) | (terrain.interiorTerrain() ? 96 : 0) | runtimeSettings();
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
        for (var step : featureSteps) for (var feature : step) {
            for (var modifier : feature.placement()) {
                if (modifier instanceof net.minecraft.world.level.levelgen.placement.BiomeFilter) {
                    biomeFiltered.add(feature);
                    break;
                }
            }
        }
        this.structures = new PredictionSurfaceStructures(context);
    }

    Tile tile(int baseX, int baseZ, int span, int spacing, boolean trees,
              BiPredicate<Integer, Integer> captured) {
        return tile(baseX, baseZ, span, spacing, trees, captured, () -> true);
    }

    Tile tile(int baseX, int baseZ, int span, int spacing, boolean trees,
              BiPredicate<Integer, Integer> captured, java.util.function.BooleanSupplier current) {
        requireCurrent(current);
        if (spacing > 8 || !available()) return Tile.EMPTY;
        var sources = generationSources(baseX, baseZ, span, current);
        requireCurrent(current);
        Tile tile = PredictionVegetationDisplayCache.merge(baseX, baseZ, span, spacing, trees, sources,
                pos -> captured.test(pos.getX(), pos.getZ()));
        meshedBlocks.add(tile.cells().values().stream().mapToInt(List::size).sum());
        return tile;
    }

    /** Read existing placement only; this path must never trigger world generation. */
    boolean hasCachedChunk(int x, int z) {
        long key = (long)Math.floorDiv(x, 16) << 32 | Math.floorDiv(z, 16) & 0xffffffffL;
        synchronized (chunks) { return chunks.containsKey(key); }
    }

    Tile tileForRendering(int x, int z, int span, int spacing, boolean trees,
            java.util.function.LongPredicate captured, java.util.function.BooleanSupplier current) {
        requireCurrent(current);
        if (spacing > 8 || !available()) return Tile.EMPTY;
        try (var build = displayCache.begin(x, z, span, spacing, trees)) {
            var sources = generationSources(x, z, span, current);
            requireCurrent(current);
            var result = displayCache.tile(build, sources, captured);
            meshedBlocks.add(result.cells().values().stream().mapToInt(List::size).sum());
            return result;
        }
    }

    private List<PredictionVegetationDisplayCache.Source> generationSources(int x, int z, int span,
            java.util.function.BooleanSupplier current) {
        var sources = new ArrayList<PredictionVegetationDisplayCache.Source>();
        for (int cz = Math.floorDiv(z, 16) - 1; cz <= Math.floorDiv(z + span - 1, 16) + 1; cz++)
            for (int cx = Math.floorDiv(x, 16) - 1; cx <= Math.floorDiv(x + span - 1, 16) + 1; cx++) {
                requireCurrent(current);
                sources.add(new PredictionVegetationDisplayCache.Source(
                        PredictionVegetationDisplayCache.chunkKey(cx, cz), chunk(cx, cz, current)));
            }
        return sources;
    }

    /** Avoid preparing a whole surface tile when one of its required chunks
     * already belongs to another job. This does not reserve or generate work. */
    void deferIfRenderingBusy(int x, int z, int span, int spacing,
                             java.util.function.BooleanSupplier current) {
        requireCurrent(current);
        if (generationJobs.isEmpty() || spacing > 8 || !available()) return;
        // Match tileForRendering's complete source footprint. Captured columns
        // are filtered after gathering sources, so they remain dependencies.
        for (int cz = Math.floorDiv(z, 16) - 1; cz <= Math.floorDiv(z + span - 1, 16) + 1; cz++)
            for (int cx = Math.floorDiv(x, 16) - 1; cx <= Math.floorDiv(x + span - 1, 16) + 1; cx++) {
                long key = PredictionVegetationDisplayCache.chunkKey(cx, cz);
                if (!generationJobs.containsKey(key)) continue;
                synchronized (chunks) {
                    if (chunks.containsKey(key)) continue;
                }
                requireCurrent(current);
                throw new PredictionWorkDeferred();
            }
    }

    Tile cachedDisplayForRendering(int x, int z, int span, int spacing, java.util.function.LongPredicate captured) {
        if (spacing > 8) return Tile.EMPTY;
        try (var build = displayCache.begin(x, z, span, spacing, true)) {
            return displayCache.tile(build, cachedSources(x, z, span), captured);
        }
    }

    private List<PredictionVegetationDisplayCache.Source> cachedSources(int x, int z, int span) {
        var sources = new ArrayList<PredictionVegetationDisplayCache.Source>();
        int minX = Math.floorDiv(x, 16) - 1, maxX = Math.floorDiv(x + span - 1, 16) + 1;
        int minZ = Math.floorDiv(z, 16) - 1, maxZ = Math.floorDiv(z + span - 1, 16) + 1;
        synchronized (chunks) {
            if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > chunks.size()) {
                for (var entry : chunks.entrySet()) {
                    int cx = (int) (entry.getKey() >> 32), cz = (int) (long) entry.getKey();
                    if (cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ)
                        sources.add(new PredictionVegetationDisplayCache.Source(entry.getKey(), entry.getValue()));
                }
            } else for (int cz = minZ; cz <= maxZ; cz++) for (int cx = minX; cx <= maxX; cx++) {
                long key = PredictionVegetationDisplayCache.chunkKey(cx, cz);
                var cached = chunks.get(key);
                if (cached != null) sources.add(new PredictionVegetationDisplayCache.Source(key, cached));
            }
        }
        sources.sort(Comparator.<PredictionVegetationDisplayCache.Source>comparingInt(s -> (int) s.key())
                .thenComparingInt(s -> (int) (s.key() >> 32)));
        return sources;
    }

    /** Read existing placement only; this path must never trigger world generation. */
    Tile cachedDisplay(int baseX, int baseZ, int span, int spacing,
                       BiPredicate<Integer,Integer> captured) {
        if (spacing > 8) return Tile.EMPTY;
        return PredictionVegetationDisplayCache.merge(baseX, baseZ, span, spacing,
                true, cachedSources(baseX, baseZ, span),
                pos -> captured.test(pos.getX(), pos.getZ()));
    }

    Map<BlockPos, BlockState> chunk(int x, int z) {
        return chunk(x, z, () -> true);
    }

    private Map<BlockPos, BlockState> chunk(int x, int z, java.util.function.BooleanSupplier current) {
        requireCurrent(current);
        long key = (long) x << 32 | z & 0xFFFFFFFFL;
        Generation job;
        // Reservation and local invalidation registration must be atomic.
        synchronized (chunks) {
            var cached = chunks.get(key);
            if (cached != null) { memoryHits.increment(); return cached; }
            if (generationJobs.containsKey(key)) throw new PredictionWorkDeferred();
            job = new Generation();
            generationJobs.put(key, job);
        }
        java.util.function.BooleanSupplier generationCurrent = () -> job.valid && current.getAsBoolean();
        try {
            requireCurrent(generationCurrent);
            Map<BlockPos, BlockState> result;
            try (var lease = diskCache == null ? null : diskCache.lease(PredictionDiskCache.Key.surface(x, z, settings))) {
                var data = lease == null ? null : diskCache.readSurfaceData(lease);
                result = data == null ? null : data.blocks();
                if (result != null) {
                    diskHits.increment();
                    if (!data.canonical()) {
                        result = PredictionBamboo.normalize(result);
                        if (treeModels != null) result = PredictionLeafStates.settle(result);
                    }
                    if (!data.weatherChecked()) result = restoreWeather(x, z, result, generationCurrent);
                    if (!data.canonical() || !data.weatherChecked()) {
                        diskCache.writeSurfaceLater(lease, result, true);
                    }
                }
                if (result == null) {
                    result = generate(x, z, generationCurrent);
                    if (lease != null) diskCache.writeSurfaceLater(lease, result, true);
                }
            }
            requireCurrent(generationCurrent);
            synchronized (chunks) {
                requireCurrent(generationCurrent);
                chunks.put(key, result);
                cachedBlocks += result.size();
                while (chunks.size() > MAX_CHUNKS || cachedBlocks > MAX_BLOCKS) {
                    var first = chunks.entrySet().iterator();
                    var retired = first.next();
                    cachedBlocks -= retired.getValue().size();
                    first.remove();
                }
            }
            return result;
        } finally {
            synchronized (chunks) {
                generationJobs.remove(key, job);
                // A factory can finish after the first local eviction. Do not retain
                // its old ground assumptions for a subsequent job in this footprint.
                for (long dirty : job.dirtyChunks)
                    terrainColumns.removeChunk((int) (dirty >> 32), (int) dirty);
            }
        }
    }

    void invalidate(int chunkX, int chunkZ) {
        synchronized (chunks) {
            displayCache.invalidate(chunkX, chunkZ);
            terrainColumns.removeChunk(chunkX, chunkZ);
            for (int z = chunkZ - 2; z <= chunkZ + 2; z++) for (int x = chunkX - 2; x <= chunkX + 2; x++) {
                long key = (long)x << 32 | z & 0xFFFFFFFFL;
                var job = generationJobs.get(key);
                if (job != null) {
                    job.valid = false;
                    job.dirtyChunks.add(PredictionVegetationDisplayCache.chunkKey(chunkX, chunkZ));
                }
                var removed = chunks.remove(key);
                if (removed != null) cachedBlocks -= removed.size();
            }
        }
        // Only raw per-coordinate terrain is shared here, never placed blocks or
        // captures. A local edit cannot change an unrelated raw column sample.
    }

    private Map<BlockPos, BlockState> restoreWeather(int chunkX, int chunkZ, Map<BlockPos, BlockState> blocks,
                                                       java.util.function.BooleanSupplier current) {
        int step = GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal();
        if (step >= featureSteps.size()) return blocks;
        var features = featureSteps.get(step);
        if (features.stream().noneMatch(feature -> feature.feature().value().feature()
                == net.minecraft.world.level.levelgen.feature.Feature.FREEZE_TOP_LAYER)) return blocks;
        var level = new PredictionDecorationLevel(terrain, context, access, chunkX, chunkZ, terrainColumns);
        level.restoreSurface(blocks);
        var origin = new BlockPos(chunkX * 16, terrain.profile().minY(), chunkZ * 16);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long seed = random.setDecorationSeed(terrain.profile().seed(), origin.getX(), origin.getZ());
        for (int index = 0; index < features.size(); index++) {
            requireCurrent(current);
            var feature = features.get(index);
            if (feature.feature().value().feature() != net.minecraft.world.level.levelgen.feature.Feature.FREEZE_TOP_LAYER
                    || !belongsToColumn(level, feature, origin)) continue;
            random.setFeatureSeed(seed, index, step);
            level.beginFeature();
            boolean success = false;
            try {
                feature.placeWithBiomeCheck(level, context.generatorContext(), random, origin);
                success = true;
            } catch (PredictionWorkDeferred | java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS cached snow upgrade skipped: " + failure);
            } finally { level.endFeature(success); }
        }
        // Keep cached structures, cuts and exact leaf states. Only replay the
        // bounded weather feature; never rebuild trees, terrain or structures.
        return Map.copyOf(level.placed());
    }

    private Map<BlockPos, BlockState> generate(int chunkX, int chunkZ, java.util.function.BooleanSupplier current) {
        var level = new PredictionDecorationLevel(terrain, context, access, chunkX, chunkZ, terrainColumns);
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        BlockPos origin = new BlockPos(chunkX * 16, terrain.profile().minY(), chunkZ * 16);
        long seed = random.setDecorationSeed(terrain.profile().seed(), origin.getX(), origin.getZ());
        Map<Boolean, List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> columnBiomes = null;
        var enabled = enabledFeatures().indices();
        // Native and Java placements share complete cave columns and ordered edits.
        try (var nativeStage = terrain instanceof RustTerrainSampler rust
                ? new RustVegetationStage(rust, level, chunkX, chunkZ, treeModels != null) : null) {
            for (int step = 0; step < Math.max(featureSteps.size(), DECORATION_STEPS.length); step++) {
                requireCurrent(current);
                level.useDisplayTerrain(false);
                structures.place(level, chunkX, chunkZ, seed, step);
                List<PlacedFeature> features = step < featureSteps.size() ? featureSteps.get(step) : List.of();
                if (step >= enabled.length || enabled[step].length == 0) continue;
                if (nativeStage != null) nativeStage.selectStep(features, step);
                for (int index : enabled[step]) {
                    requireCurrent(current);
                    PlacedFeature feature = features.get(index);
                    if (missingRegistryFailures.contains(feature) || unsupportedFeatures.contains(feature)) continue;
                    boolean reusableTree = treeModels != null && treeModels.supports(feature);
                    level.useDisplayTerrain(reusableTree);
                    if (!biomeFiltered.contains(feature)) {
                        // Unfiltered modded features must still belong to a local biome.
                        if (columnBiomes == null) columnBiomes = new HashMap<>();
                        if (!belongsToColumn(level, feature, origin, columnBiomes)) continue;
                    }
                    if (!reusableTree && nativeStage != null && nativeStage.place(index)) continue;
                    if (nativeStage != null) nativeStage.beforeJava();
                    level.useDisplayTerrain(reusableTree);
                    // Keep the global index even when other features were filtered.
                    random.setFeatureSeed(seed, index, step);
                    level.beginFeature();
                    boolean success = false;
                    long javaStarted = nativeStage == null ? 0 : System.nanoTime();
                    try {
                        if (reusableTree) treeModels.place(feature, level, random, origin);
                        else PredictionSurfaceFeatureAdapters.place(feature,level,context.generatorContext(),random,origin);
                        success = true;
                    } catch (PredictionWorkDeferred | java.util.concurrent.CancellationException cancelled) {
                        throw cancelled;
                    } catch (RuntimeException failure) {
                        if (PredictionMissingRegistry.permanent(failure, access)) missingRegistryFailures.add(feature);
                        else if (failure instanceof UnsupportedOperationException) unsupportedFeatures.add(feature);
                        lastFeatureFailure = failure.toString();
                        failedFeatures.increment();
                        if (VSSClientConfig.CONFIG.debugLogging && loggedFailures.add(feature))
                            VSSLogger.debug("VSS skipped unsupported surface placement: " + feature + ": " + failure);
                    } finally {
                        level.endFeature(success);
                        if (nativeStage != null)
                            nativeStage.afterJava(index, reusableTree, success, System.nanoTime() - javaStarted);
                    }
                }
                if (nativeStage != null) nativeStage.finish();
            }
        }
        requireCurrent(current);
        Map<BlockPos, BlockState> exterior = PredictionBamboo.normalize(surfaceBlocks(level));
        if (treeModels != null) exterior = PredictionLeafStates.settle(exterior);
        generatedChunks.increment();
        generatedBlocks.add(exterior.size());
        return Map.copyOf(exterior);
    }

    private static void requireCurrent(java.util.function.BooleanSupplier current) {
        if (Thread.currentThread().isInterrupted() || !current.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("prediction decoration changed");
        }
    }

    private EnabledFeatures enabledFeatures() {
        var cached = enabledFeatures;
        if (cached != null) return cached;
        var indices = new int[featureSteps.size()][];
        for (int step = 0; step < indices.length; step++) {
            var features = featureSteps.get(step);
            var selected = new int[features.size()];
            int count = 0;
            for (int index = 0; index < features.size(); index++)
                if (enabled(step, features.get(index), true, true)) selected[count++] = index;
            indices[step] = java.util.Arrays.copyOf(selected, count);
        }
        var result = new EnabledFeatures(indices);
        enabledFeatures = result;
        return result;
    }

    private boolean enabled(int step, PlacedFeature feature, boolean trees, boolean structures) {
        return surfaceFeature(step,feature,context.profile().dimension().equals(net.minecraft.world.level.Level.NETHER.location()),
                trees, structures);
    }

    private boolean belongsToColumn(PredictionDecorationLevel level, PlacedFeature feature, BlockPos origin) {
        return belongsToColumn(level, feature, origin, new HashMap<>());
    }

    boolean belongsToColumn(PredictionDecorationLevel level, PlacedFeature feature, BlockPos origin,
            Map<Boolean, List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> columnBiomes) {
        var biomes = columnBiomes.computeIfAbsent(level.usesDisplayTerrain(), ignored -> {
            var column = level.column(origin.getX(), origin.getZ());
            var found = new java.util.LinkedHashSet<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>();
            if (column.volume() != null) {
                var volume = column.volume();
                for (int i = 0; i < volume.size(); i++) {
                    int y = volume.top(i);
                    if (!volume.occupied(y, false))
                        found.add(level.getBiome(new BlockPos(origin.getX(), y, origin.getZ())));
                }
            } else {
                found.add(level.getBiome(new BlockPos(origin.getX(), column.surfaceY(), origin.getZ())));
            }
            return List.copyOf(found);
        });
        for (var biome : biomes) {
            if (context.generatorContext().getBiomeGenerationSettings(biome).hasFeature(feature)) return true;
        }
        return false;
    }

    static boolean surfaceFeature(int step, PlacedFeature feature, boolean nether, boolean trees, boolean structures) {
        if (step < 0 || step >= DECORATION_STEPS.length) return false;
        var stage = DECORATION_STEPS[step];
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
                if (renderable(state) || state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
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
                    || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock))
                exterior.put(pos, state);
        });
        return exterior;
    }

    // Terrain edits, roads, crops and structure shapes retain exact footprints.
    // Snapping farmland and air cuts into tree-sized voxels buries their models.
    static int voxelSize(BlockState state, int treeSize) { return woody(state) ? treeSize : 1; }

    static boolean woody(BlockState state) {
        return PredictionVegetationTraits.of(state).woody();
    }

    static boolean solid(BlockState state) { return PredictionVegetationTraits.of(state).solid(); }

    static boolean fire(BlockState state) { return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE); }

    static boolean renderable(BlockState state) {
        return PredictionVegetationTraits.of(state).renderable();
    }

    static boolean vegetation(BlockState state) {
        return PredictionVegetationTraits.of(state).vegetation();
    }

    record Voxel(int x, int y, int z, int size, BlockState state) { }

    record Tile(Map<Integer, List<Voxel>> cells, Map<BlockPos, BlockState> blocks,
                int baseX, int baseZ, int voxelSize, int maxY) {
        Tile {
            var immutableCells = new HashMap<Integer, List<Voxel>>();
            cells.forEach((cell,values) -> immutableCells.put(cell,List.copyOf(values)));
            cells = Map.copyOf(immutableCells);
            blocks = blocks.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new HashMap<>(blocks));
        }
        static final Tile EMPTY = new Tile(Map.of(),Map.of(),0,0,1,Integer.MIN_VALUE);

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
            return new Tile(cells,blocks,baseX,baseZ,voxelSize,maxY);
        }

        List<Voxel> cell(int cell) { return cells.getOrDefault(cell, List.of()); }

    }


}

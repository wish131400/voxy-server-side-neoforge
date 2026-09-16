package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureSimulator;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.*;
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider;
import net.minecraft.world.level.levelgen.feature.treedecorators.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** Visual tree geometry, scoped to one worldgen context. Placement still uses the actual biome features. */
final class PredictionTreeModels {
    private static final int VARIANTS = 8, MAX_MODELS = 256, MAX_BLOCKS = 65_536;
    private final RegistryAccess access;
    private final ChunkGenerator generator;
    private final Map<PlacedFeature, Boolean> supported = new ConcurrentHashMap<>();
    private final Map<Key, List<Cell>> models = new LinkedHashMap<>(64, .75F, true);
    private final Object[] buildLocks = Stream.generate(Object::new).limit(16).toArray();
    private int retainedBlocks;
    private final LongAdder builds = new LongAdder(), hits = new LongAdder(), fallbacks = new LongAdder();
    private final LongAdder placed = new LongAdder(), buildNanos = new LongAdder();

    PredictionTreeModels(RegistryAccess access, ChunkGenerator generator) {
        this.access = access;
        this.generator = generator;
    }

    boolean supports(PlacedFeature feature) {
        return supported.computeIfAbsent(feature, f -> supports(f.feature().value(), 0));
    }

    private static boolean supports(ConfiguredFeature<?, ?> feature, int depth) {
        if (depth > 16) return false;
        if (feature.feature() == Feature.TREE && feature.config() instanceof TreeConfiguration tree) {
            // Only audited vanilla vine/cocoa decorators are replayed against
            // the real environment. Custom decorators and roots keep their generator.
            return tree.rootPlacer.isEmpty() && tree.decorators.stream().allMatch(PredictionTreeModels::contextualDecorator)
                    && tree.trunkProvider instanceof SimpleStateProvider
                    && tree.foliageProvider instanceof SimpleStateProvider
                    && tree.dirtProvider instanceof SimpleStateProvider
                    && vanillaImplementation(tree.trunkPlacer) && vanillaImplementation(tree.foliagePlacer)
                    && vanillaImplementation(tree.minimumSize);
        }
        if (feature.feature() == Feature.RANDOM_SELECTOR && feature.config() instanceof RandomFeatureConfiguration config)
            return supports(config.defaultFeature.value().feature().value(), depth + 1)
                    || config.features.stream().anyMatch(f -> supports(f.feature.value().feature().value(), depth + 1));
        if (feature.feature() == Feature.SIMPLE_RANDOM_SELECTOR && feature.config() instanceof SimpleRandomFeatureConfiguration config)
            return config.features.size() > 0 && config.features.stream()
                    .anyMatch(f -> supports(f.value().feature().value(), depth + 1));
        if (feature.feature() == Feature.RANDOM_BOOLEAN_SELECTOR && feature.config() instanceof RandomBooleanFeatureConfiguration config)
            return supports(config.featureTrue.value().feature().value(), depth + 1)
                    || supports(config.featureFalse.value().feature().value(), depth + 1);
        return false;
    }

    private static boolean vanillaImplementation(Object value) {
        return value.getClass().getName().startsWith("net.minecraft.world.level.levelgen.feature.");
    }

    private static boolean contextualDecorator(TreeDecorator decorator) {
        return decorator.getClass() == TrunkVineDecorator.class
                || decorator.getClass() == LeaveVineDecorator.class
                || decorator.getClass() == CocoaDecorator.class;
    }

    void place(PlacedFeature feature, WorldGenLevel level, RandomSource random, BlockPos origin) {
        place(feature, level, random, origin, true);
    }

    private void place(PlacedFeature feature, WorldGenLevel level, RandomSource random, BlockPos origin, boolean biomeCheck) {
        var decoration = level instanceof PredictionDecorationLevel d ? d : null;
        boolean previous = decoration != null && decoration.usesDisplayTerrain();
        if (decoration != null && !supports(feature)) decoration.useDisplayTerrain(false);
        try {
        var context = new PlacementContext(level, generator, biomeCheck ? Optional.of(feature) : Optional.empty());
        Stream<BlockPos> positions = Stream.of(origin);
        for (var modifier : feature.placement())
            positions = positions.flatMap(pos -> modifier.getPositions(context, random, pos));
        positions.forEach(pos -> placeConfigured(feature.feature().value(), level, random, pos));
        } finally { if (decoration != null) decoration.useDisplayTerrain(previous); }
    }

    private void placeConfigured(ConfiguredFeature<?, ?> feature, WorldGenLevel level, RandomSource random, BlockPos pos) {
        if (!supports(feature, 0)) {
            fallbacks.increment();
            placeExact(feature,level,random,pos);
            return;
        }
        if (feature.feature() == Feature.RANDOM_SELECTOR && feature.config() instanceof RandomFeatureConfiguration config) {
            for (var choice : config.features) if (random.nextFloat() < choice.chance) {
                place(choice.feature.value(), level, random, pos, false);
                return;
            }
            place(config.defaultFeature.value(), level, random, pos, false);
        } else if (feature.feature() == Feature.SIMPLE_RANDOM_SELECTOR && feature.config() instanceof SimpleRandomFeatureConfiguration config) {
            place(config.features.get(random.nextInt(config.features.size())).value(), level, random, pos, false);
        } else if (feature.feature() == Feature.RANDOM_BOOLEAN_SELECTOR && feature.config() instanceof RandomBooleanFeatureConfiguration config) {
            place((random.nextBoolean() ? config.featureTrue : config.featureFalse).value(), level, random, pos, false);
        } else {
            BlockState ground = level.getBlockState(pos.below());
            // Don't invent an island/soil patch to make a cached tree fit.
            if (!ground.is(BlockTags.DIRT) || !ground.getFluidState().isEmpty()
                    || !level.getFluidState(pos).isEmpty()) return;
            int variant = variant(level.getSeed(), pos);
            List<Cell> model = model(feature, ground, variant);
            if (model.isEmpty()) {
                fallbacks.increment();
                placeExact(feature,level,random,pos);
            } else if (replay(model, level, pos, (TreeConfiguration) feature.config(), random)) placed.increment();
        }
    }

    static int variant(long seed, BlockPos pos) {
        return (int) mix(seed ^ (long) pos.getX() * 0x9E3779B97F4A7C15L
                ^ (long) pos.getZ() * 0xC2B2AE3D27D4EB4FL) & (VARIANTS - 1);
    }

    private void placeExact(ConfiguredFeature<?, ?> feature, WorldGenLevel level, RandomSource random, BlockPos pos) {
        if (level instanceof PredictionDecorationLevel decoration) {
            boolean display = decoration.usesDisplayTerrain();
            decoration.useDisplayTerrain(false);
            try { feature.place(level,generator,random,pos); }
            finally { decoration.useDisplayTerrain(display); }
        } else feature.place(level,generator,random,pos);
    }

    private List<Cell> model(ConfiguredFeature<?, ?> feature, BlockState ground, int variant) {
        Key key = new Key(feature, ground, variant);
        synchronized (models) {
            var result = models.get(key);
            if (result != null) { hits.increment(); return result; }
        }
        synchronized (buildLocks[(key.hashCode() & Integer.MAX_VALUE) % buildLocks.length]) {
            synchronized (models) {
                var result = models.get(key);
                if (result != null) { hits.increment(); return result; }
            }
            long start = System.nanoTime();
            List<Cell> result;
            try { result = build(feature, ground, variant); }
            finally { builds.increment(); buildNanos.add(System.nanoTime() - start); }
            synchronized (models) {
                models.put(key, result);
                retainedBlocks += result.size();
                var iterator = models.values().iterator();
                while (models.size() > MAX_MODELS || retainedBlocks > MAX_BLOCKS) {
                    retainedBlocks -= iterator.next().size(); iterator.remove();
                }
            }
            return result;
        }
    }

    private List<Cell> build(ConfiguredFeature<?, ?> feature, BlockState ground, int variant) {
        long seed = mix(0x565353545245454CL ^ variant);
        var level = new FeatureStampLevel(seed, access, ground) {
            private int writes;
            @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
                if (++writes > 32_768 || Math.abs(pos.getX()) > 15 || Math.abs(pos.getZ()) > 15
                        || pos.getY() < GROUND_Y - 1 || pos.getY() > GROUND_Y + 63)
                    throw new UnsupportedOperationException("tree exceeds reusable model bounds");
                return super.setBlock(pos, state, flags, recursion);
            }
        };
        try {
            // Cache the trunk/canopy only. Vines and cocoa depend on the actual
            // surrounding air and run after the accepted geometry is placed.
            var tree = (TreeConfiguration) feature.config();
            if (!tree.decorators.isEmpty()) {
                var builder = new TreeConfiguration.TreeConfigurationBuilder(tree.trunkProvider, tree.trunkPlacer,
                        tree.foliageProvider, tree.foliagePlacer, tree.minimumSize).dirt(tree.dirtProvider);
                if (tree.ignoreVines) builder.ignoreVines();
                if (tree.forceDirt) builder.forceDirt();
                feature = new ConfiguredFeature<>(Feature.TREE, builder.build());
            }
            if (!feature.place(level, generator, RandomSource.create(seed), FeatureSimulator.ORIGIN)
                    || level.placed().size() > 4096) return List.of();
            return level.placed().entrySet().stream().filter(e -> !e.getValue().isAir())
                    .map(e -> new Cell(e.getKey().subtract(FeatureSimulator.ORIGIN), e.getValue()))
                    .sorted(Comparator.comparingInt((Cell c) -> c.offset.getY())
                            .thenComparingInt(c -> c.offset.getZ()).thenComparingInt(c -> c.offset.getX())).toList();
        } catch (UnsupportedOperationException unsupported) {
            return List.of();
        }
    }

    /** Preflight before writes: obstructed trunks reject a whole model; leaves can meet a cliff/other canopy. */
    private static boolean replay(List<Cell> model, WorldGenLevel level, BlockPos origin,
                                  TreeConfiguration tree, RandomSource random) {
        // Preflight supplies the write pass too: no second block query for
        // every leaf. Nothing writes into this per-job level during preflight.
        BlockPos[] accepted = new BlockPos[model.size()];
        for (int i = 0; i < model.size(); i++) {
            var cell = model.get(i);
            BlockPos pos = origin.offset(cell.offset);
            if (!level.ensureCanWrite(pos)) return false;
            BlockState current = level.getBlockState(pos);
            if (level instanceof PredictionDecorationLevel decoration && decoration.isStructureBlock(pos)) return false;
            if (cell.offset.getY() < 0) {
                if (!current.is(BlockTags.DIRT) || !current.getFluidState().isEmpty()) return false;
            } else if (!cell.state.is(BlockTags.LEAVES)
                    && (!replaceable(current) || !current.getFluidState().isEmpty())) return false;
            if (!cell.state.is(BlockTags.LEAVES) || (replaceable(current) && current.getFluidState().isEmpty()))
                accepted[i] = pos;
        }
        Set<BlockPos> logs = tree.decorators.isEmpty() ? null : new HashSet<>();
        Set<BlockPos> leaves = tree.decorators.isEmpty() ? null : new HashSet<>();
        for (int i = 0; i < model.size(); i++) {
            BlockPos pos = accepted[i];
            if (pos == null) continue;
            var cell = model.get(i);
            level.setBlock(pos, cell.state, 19, 0);
            if (logs != null) {
                if (cell.state.is(BlockTags.LOGS)) logs.add(pos);
                else if (cell.state.is(BlockTags.LEAVES)) leaves.add(pos);
            }
        }
        if (logs != null && !logs.isEmpty()) {
            var context = new TreeDecorator.Context(level, (pos, state) -> {
                if (level.ensureCanWrite(pos)
                        && !(level instanceof PredictionDecorationLevel decoration && decoration.isStructureBlock(pos)))
                    level.setBlock(pos, state, 19, 0);
            }, random, logs, leaves, Set.of());
            for (var decorator : tree.decorators) decorator.place(context);
        }
        return true;
    }

    private static boolean replaceable(BlockState state) {
        return state.isAir() || state.is(BlockTags.REPLACEABLE_BY_TREES);
    }

    String diagnostics() {
        synchronized (models) {
            return "treeModels={built=" + builds.sum() + ",hits=" + hits.sum() + ",fallback=" + fallbacks.sum()
                    + ",placed=" + placed.sum() + ",buildMs=" + buildNanos.sum() / 1_000_000
                    + ",models=" + models.size() + ",blocks=" + retainedBlocks + "}";
        }
    }

    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    private record Key(ConfiguredFeature<?, ?> feature, BlockState ground, int variant) { }
    private record Cell(BlockPos offset, BlockState state) { }
}

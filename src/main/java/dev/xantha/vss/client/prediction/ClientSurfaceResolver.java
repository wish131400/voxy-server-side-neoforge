package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Evaluates the synced Minecraft surface rules against predicted column geometry. */
final class ClientSurfaceResolver {
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES = new DensityFunctions.BeardifierOrMarker() {
        @Override public double compute(DensityFunction.FunctionContext context) { return 0; }
        @Override public double minValue() { return 0; }
        @Override public double maxValue() { return 0; }
    };
    private final NoiseBasedChunkGenerator generator;
    private final RandomState randomState;
    private final LevelHeightAccessor heights;
    private final Registry<Biome> biomes;
    private final PredictionBiomeCache biomeCache;
    private final ThreadLocal<Worker> workers = new ThreadLocal<>();

    ClientSurfaceResolver(NoiseBasedChunkGenerator generator, RandomState randomState,
                          LevelHeightAccessor heights, Registry<Biome> biomes) {
        this(generator, randomState, heights, biomes,
                new PredictionBiomeCache(generator.getBiomeSource(), randomState.sampler()));
    }

    ClientSurfaceResolver(NoiseBasedChunkGenerator generator, RandomState randomState,
                          LevelHeightAccessor heights, Registry<Biome> biomes, PredictionBiomeCache biomeCache) {
        this.generator = generator;
        this.randomState = randomState;
        this.heights = heights;
        this.biomes = biomes;
        this.biomeCache = biomeCache;
    }

    ClientColumnSample resolve(ClientColumnSample sample, int x, int z,
                                ClientTerrainSampler.TerrainFunction terrain) {
        try {
            Worker worker = workers.get();
            // NoiseChunk caches preliminary heights. Bound its lifetime even when
            // a worker travels indefinitely through coarse tiles.
            if (worker == null || worker.columns >= 4096) {
                worker = new Worker();
                workers.set(worker);
            }
            worker.columns++;
            worker.x = x;
            worker.z = z;
            worker.terrain = terrain;
            Access.UPDATE_XZ.invokeExact(worker.context, x, z);
            int top = worker.material(sample, 0);
            int under = worker.material(sample, 1);
            int deep = worker.material(sample, 6);
            Biome biome = worker.biomeAt(new BlockPos(x, sample.surfaceY(), z)).value();
            int flags = sample.flags() & ~(ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE);
            int weatherY = sample.hasFluid() ? sample.fluidY() - 1 : sample.surfaceY();
            if (weatherY >= heights.getMinBuildHeight() && weatherY < heights.getMaxBuildHeight()
                    && biome.coldEnoughToSnow(new BlockPos(x, weatherY, z))) {
                if (sample.hasFluid()) {
                    if (sample.fluid() == 1) flags |= ClientColumnSample.FLAG_ICE;
                } else if (sample.hasSurface() && PredictionMaterialPalette.supportsSnow(top)) {
                    // SurfaceRules produce the ground block. SnowAndFreezeFeature
                    // adds the covering layer later; expose it before decoration.
                    flags |= ClientColumnSample.FLAG_SNOW;
                }
            }
            return new ClientColumnSample(sample.surfaceY(), sample.fluidY(), sample.biomeIndex(),
                    top, sample.structureIndex(), sample.treeKind(), sample.treeDensity(), sample.treeHeight(),
                    sample.fluid(), flags, sample.groundFeatureKind(), under, deep,
                    sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor());
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Minecraft surface rule evaluation failed", failure);
        }
    }

    private final class Worker {
        private final Object context;
        private final Object rule;
        private ClientTerrainSampler.TerrainFunction terrain;
        private int x;
        private int z;
        private int columns;

        @SuppressWarnings("unchecked")
        private Worker() throws ReflectiveOperationException {
            NoiseGeneratorSettings settings = generator.generatorSettings().value();
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, heights, biomes, null) {
                @Override public int getHeight(Heightmap.Types type, int localX, int localZ) {
                    return terrain.surfaceY((x & ~15) + (localX & 15), (z & ~15) + (localZ & 15)) - 1;
                }
            };
            Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
            NoiseChunk noise = FreeTerraForgedCompat.withSurfaceChunk(randomState, chunk,
                    () -> NoiseChunk.forChunk(chunk, randomState, NO_STRUCTURES,
                            settings, (bx, by, bz) -> fluid, Blender.empty()));
            context = Access.CONSTRUCTOR.newInstance(randomState.surfaceSystem(), randomState, chunk, noise,
                    (Function<BlockPos, Holder<Biome>>) this::biomeAt, biomes,
                    new WorldGenerationContext(generator, heights));
            rule = ((Function<Object, Object>) (Object) settings.surfaceRule()).apply(context);
        }

        private Holder<Biome> biomeAt(BlockPos pos) {
            return biomeCache.get(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
        }

        private int material(ClientColumnSample sample, int depth) throws Throwable {
            int y = Math.max(heights.getMinBuildHeight(), sample.surfaceY() - 1 - depth);
            int floor = sample.floating() ? sample.surfaceBottom() : heights.getMinBuildHeight();
            int water = sample.hasFluid() ? sample.fluidY() : Integer.MIN_VALUE;
            Access.UPDATE_Y.invokeExact(context, depth + 1, Math.max(1, y - floor + 1), water, x, y, z);
            BlockState state = (BlockState) Access.APPLY.invokeExact(rule, x, y, z);
            if (state == null) state = generator.generatorSettings().value().defaultBlock();
            return BuiltInRegistries.BLOCK.getId(state.getBlock());
        }
    }

    // The rule context is protected vanilla API. Resolve its signatures once;
    // workers reuse compiled rules and method handles, with no per-column lookup.
    private static final class Access {
        private static final Constructor<?> CONSTRUCTOR;
        private static final MethodHandle UPDATE_XZ;
        private static final MethodHandle UPDATE_Y;
        private static final MethodHandle APPLY;

        static {
            try {
                Class<?> contextType = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Context");
                Class<?> ruleType = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule");
                CONSTRUCTOR = contextType.getDeclaredConstructor(SurfaceSystem.class, RandomState.class,
                        ChunkAccess.class, NoiseChunk.class, Function.class, Registry.class, WorldGenerationContext.class);
                CONSTRUCTOR.setAccessible(true);
                UPDATE_XZ = method(contextType, void.class, 2);
                UPDATE_Y = method(contextType, void.class, 6);
                APPLY = method(ruleType, BlockState.class, 3);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static MethodHandle method(Class<?> owner, Class<?> result, int integers)
                throws ReflectiveOperationException {
            Class<?>[] parameters = new Class<?>[integers];
            java.util.Arrays.fill(parameters, int.class);
            for (var method : owner.getDeclaredMethods()) {
                if (method.getReturnType() == result && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method)
                            .asType(MethodType.methodType(result, Object.class, parameters));
                }
            }
            throw new NoSuchMethodException(owner.getName() + " with " + integers + " integer arguments");
        }
    }
}

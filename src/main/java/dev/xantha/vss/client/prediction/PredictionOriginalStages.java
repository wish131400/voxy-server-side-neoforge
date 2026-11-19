package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.GeneratorStageCapabilities;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Original generator calls on isolated chunks. No live world, disk writes or server tickets. */
final class PredictionOriginalStages extends ClientTerrainSampler {
    private final ClientTerrainSampler source;
    private final LevelHeightAccessor heights;
    private final GeneratorStageCapabilities capabilities;
    private final Method noise, surface;
    private final StructureManager structures;
    // Unknown generators may own mutable caches. Serialize this bounded context rather
    // than sharing their undocumented state between prediction workers.
    private final LinkedHashMap<Long, ProtoChunk> chunks = new LinkedHashMap<>(16, .75f, true);
    private boolean closed, surfaceFailed;
    private RuntimeException terrainFailure;

    PredictionOriginalStages(ClientTerrainSampler source) {
        super(source, null);
        this.source = source;
        heights = LevelHeightAccessor.create(profile().minY(), profile().height());
        capabilities = GeneratorStageCapabilities.inspect(generatorContext().getClass());
        if (!capabilities.isolated()) throw new IllegalArgumentException("Generator requires a world/region stage context");
        noise = Arrays.stream(NoiseBasedChunkGenerator.class.getMethods()).filter(m ->
                CompletableFuture.class.isAssignableFrom(m.getReturnType())
                && Arrays.asList(m.getParameterTypes()).contains(ChunkAccess.class)
                && (m.getParameterTypes()[0] == Blender.class || m.getParameterTypes()[0] == Executor.class
                    && m.getParameterTypes()[1] == Blender.class)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This Minecraft terrain stage requires a world region"));
        surface = Arrays.stream(NoiseBasedChunkGenerator.class.getMethods()).filter(m ->
                m.getReturnType() == void.class && Arrays.asList(m.getParameterTypes()).contains(WorldGenerationContext.class))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No isolated surface stage"));
        structures = new StructureManager(null, new WorldOptions(profile().seed(), false, false), null) {
            @Override public List<StructureStart> startsForStructure(ChunkPos pos, java.util.function.Predicate<Structure> filter) {
                return List.of();
            }
            @Override public RegistryAccess registryAccess() { return decorationAccess(); }
        };
    }

    private ProtoChunk empty(ChunkPos pos) {
        return new ProtoChunk(pos, UpgradeData.EMPTY, heights,
                decorationAccess().registryOrThrow(Registries.BIOME), null);
    }

    private void active() {
        if (closed || Thread.currentThread().isInterrupted()) throw new CancellationException("Original generator context closed");
        if (terrainFailure != null) throw new IllegalStateException("Original terrain stage unavailable; use authoritative LOD", terrainFailure);
    }

    private synchronized ProtoChunk chunk(int x, int z) {
        active();
        ChunkPos pos = new ChunkPos(x >> 4, z >> 4);
        var cached = chunks.get(((long)(x >> 4) << 32 | (z >> 4) & 0xffffffffL));
        if (cached != null) return cached;
        ProtoChunk terrain = empty(pos);
        try {
            terrain.fillBiomesFromNoise(biomeSourceContext(), randomStateContext().sampler());
            if (capabilities.columns() && !capabilities.terrain()) {
                for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    active();
                    int xx = pos.getMinBlockX() + dx, zz = pos.getMinBlockZ() + dz;
                    var column = generatorContext().getBaseColumn(xx, zz, heights, randomStateContext());
                    for (int y = profile().minY(); y < profile().minY() + profile().height(); y++)
                        terrain.setBlockState(new BlockPos(xx, y, zz), column.getBlock(y), false);
                }
            } else {
                Object result = invoke(noise, terrain);
                Object generated;
                try { generated = ((CompletableFuture<?>) result).join(); }
                catch (java.util.concurrent.CompletionException failure) {
                    if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
                    throw failure;
                }
                if (generated != terrain) throw new IllegalStateException("Generator returned a different chunk context");
            }
            active();
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException failure) {
            terrainFailure = failure;
            VSSLogger.warn("VSS original terrain stage unavailable for " + profile().dimension()
                    + "; requires additional initialization/world state", failure);
            throw failure;
        }
        // A failed surface stage must not corrupt or discard completed terrain.
        ProtoChunk completed = terrain;
        if (!surfaceFailed) {
            ProtoChunk candidate = empty(pos);
            var cursor = new BlockPos.MutableBlockPos();
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                active();
                for (int y = profile().minY(); y < profile().minY() + profile().height(); y++) {
                    cursor.set(pos.getMinBlockX() + dx, y, pos.getMinBlockZ() + dz);
                    var state = terrain.getBlockState(cursor);
                    if (!state.isAir()) candidate.setBlockState(cursor, state, false);
                }
            }
            candidate.fillBiomesFromNoise(biomeSourceContext(), randomStateContext().sampler());
            try { invoke(surface, candidate); active(); completed = candidate; }
            catch (CancellationException cancelled) { throw cancelled; }
            catch (RuntimeException failure) {
                surfaceFailed = true;
                VSSLogger.warn("VSS original surface stage unavailable for " + profile().dimension()
                        + "; keeping completed terrain until authoritative LOD arrives", failure);
            }
        }
        chunks.put(((long)(x >> 4) << 32 | (z >> 4) & 0xffffffffL), completed);
        while (chunks.size() > 16) chunks.remove(chunks.keySet().iterator().next());
        return completed;
    }

    private Object invoke(Method method, ProtoChunk chunk) {
        Object[] args = new Object[method.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (type == Executor.class) args[i] = (Executor) Runnable::run;
            else if (type == Blender.class) args[i] = Blender.empty();
            else if (type == RandomState.class) args[i] = randomStateContext();
            else if (type == StructureManager.class) args[i] = structures;
            else if (type == ChunkAccess.class) args[i] = chunk;
            else if (type == WorldGenerationContext.class) args[i] = new WorldGenerationContext(generatorContext(), heights);
            else if (type == BiomeManager.class) args[i] = new BiomeManager(this::noiseBiome, BiomeManager.obfuscateSeed(profile().seed()));
            else if (type == net.minecraft.core.Registry.class) args[i] = decorationAccess().registryOrThrow(Registries.BIOME);
            else throw new IllegalArgumentException("Unsupported original stage parameter: " + type.getName());
        }
        try { return method.invoke(generatorContext(), args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Original generator stage failed", cause);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Original generator stage inaccessible", failure); }
    }

    @Override public ClientColumnSample sample(int x, int z) {
        var column = volume(x, z);
        var sample = column.asSample();
        if (column.size() == 0) return new ClientColumnSample(profile().minY(),profile().minY(),-1,
                ClientColumnSample.NO_BLOCK,0,0,0,0,0,ClientColumnSample.FLAG_NO_SURFACE,0,
                ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,column);
        var biome = noiseBiome(x >> 2, (sample.surfaceY() - 1) >> 2, z >> 2);
        var key = biome == null ? null : biome.unwrapKey().orElse(null);
        int index = key == null ? -1 : cacheBiomeIndices().getOrDefault(key.location().toString(), -1);
        int water = sample.surfaceY(), fluid = 0;
        for (int i = column.size() - 1; i >= 0; i--) if (column.fluid(i) != 0 && column.top(i) > water) {
            water = column.top(i); fluid = column.fluid(i); break;
        }
        return new ClientColumnSample(sample.surfaceY(),water,index,sample.topBlockIndex(),0,0,0,0,fluid,sample.flags(),0,
                column.blockAt(sample.surfaceY()-2),column.blockAt(sample.surfaceY()-7),sample.surfaceBottom(),
                sample.lowerTop(),sample.lowerBottom(),sample.spanFloor(),column);
    }
    private PredictionColumnVolume volume(int x, int z) {
        ProtoChunk chunk = chunk(x, z);
        var cursor = new BlockPos.MutableBlockPos();
        var registry = BuiltInRegistries.BLOCK;
        return PredictionColumnVolume.sample(profile().minY(), profile().height(), y -> {
            var state = chunk.getBlockState(cursor.set(x, y, z));
            return state.isAir() ? -1 : registry.getId(state.getBlock());
        }, id -> {
            var state = registry.byId(id).defaultBlockState();
            return state.getFluidState().isEmpty() ? 0 : state.is(net.minecraft.world.level.block.Blocks.LAVA) ? 2 : 1;
        });
    }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return sample(x, z); }
    @Override public ClientColumnSample samplePreview(int x, int z) { return sample(x, z); }
    @Override public ClientColumnSample samplePreview(int x, int z, int step) { return sample(x, z); }
    @Override int previewSurfaceY(int x, int z) { return surfaceY(x, z); }
    @Override public int surfaceY(int x, int z) { return sample(x, z).surfaceY(); }
    @Override public int groundY(int x, int z) { return surfaceY(x, z); }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) { return volume(x, z); }
    @Override ClientColumnSample sampleInterior(int x, int z) { return sample(x, z); }
    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top, java.util.function.BooleanSupplier valid) {
        boolean[] occupied = new boolean[top - bottom];
        for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
            if (!valid.getAsBoolean()) throw new CancellationException();
            var column = volume(x + dx, z + dz);
            if (dx == 0 && dz == 0 && !column.occupied(top - 1, false)) return null;
            for (int y = bottom; y < top; y++) occupied[y - bottom] |= column.occupied(y, false);
        }
        return occupied;
    }
    @Override synchronized public void close() { if (closed) return; closed = true; chunks.clear(); source.close();
        if ((Object) generatorContext() instanceof AutoCloseable owner) try { owner.close(); }
        catch (Exception failure) { dev.xantha.vss.common.VSSLogger.warn("VSS original generator cleanup failed",failure); }
    }
}

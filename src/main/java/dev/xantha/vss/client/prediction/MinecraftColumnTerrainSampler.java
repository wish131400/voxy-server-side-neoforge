package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.material.Fluids;

/** NoiseChunk-backed outer columns for data packs with order-sensitive density caches. */
final class MinecraftColumnTerrainSampler extends ClientTerrainSampler {
    private final LinkedHashMap<Long, Column> columns = new LinkedHashMap<>(256, 0.75f, true);
    private final LevelHeightAccessor heights;

    MinecraftColumnTerrainSampler(ClientTerrainSampler source) {
        super(source, null);
        heights = LevelHeightAccessor.create(profile().minY(), profile().height());
    }

    private Column column(int x, int z) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        long key = (long) x << 32 | z & 0xffffffffL;
        synchronized (columns) {
            var cached = columns.get(key);
            if (cached != null) return cached;
        }
        var probe = new ExteriorProbe(generatorContext().generatorSettings().value().noiseSettings()
                .clampToHeightAccessor(heights));
        int floor;
        try {
            // Vanilla's predicate overload stops at the exterior without allocating
            // a full NoiseColumn or evaluating every underground block. Keep its
            // exact NoiseChunk traversal, interpolation order and aquifers.
            var found = (java.util.OptionalInt) Access.ITERATE.invokeExact(generatorContext(), heights,
                    randomStateContext(), x, z, (org.apache.commons.lang3.mutable.MutableObject<?>) null,
                    (java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState>) probe);
            floor = found.orElse(profile().minY());
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Minecraft exterior column sampling failed",failure); }
        var result = new Column(floor, Math.max(floor, probe.fluidY), probe.fluid);
        synchronized (columns) {
            columns.put(key, result);
            while (columns.size() > 4096) columns.remove(columns.keySet().iterator().next());
        }
        return result;
    }

    @Override public int surfaceY(int x, int z) { return column(x, z).floor; }
    @Override public int groundY(int x, int z) { return surfaceY(x, z); }

    @Override ClientColumnSample resolveSurface(ClientColumnSample sample, int x, int z, TerrainFunction terrain) {
        var column = column(x, z);
        // Surface rules must be evaluated against the actual NoiseChunk column.
        // The previous order evaluated at the density-estimated height, then
        // replaced only the coordinates; rivers and snow therefore inherited
        // the wrong block/material and appeared as green terraces.
        int floor = column.floor;
        int fluidY = column.fluidY;
        int fluid = column.fluid;
        var biome = noiseBiome(net.minecraft.core.QuartPos.fromBlock(x),
                net.minecraft.core.QuartPos.fromBlock(floor - 1),
                net.minecraft.core.QuartPos.fromBlock(z));
        int biomeIndex = sample.biomeIndex();
        String path = biome.unwrapKey().map(k -> k.location().getPath()).orElse("");
        boolean snow = fluid == 1 && biome.value().coldEnoughToSnow(
                new net.minecraft.core.BlockPos(x, fluidY - 1, z));
        int flags = snow ? (fluid == 1 ? ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE
                : ClientColumnSample.FLAG_SNOW) : 0;
        int top = PredictionMaterialPalette.representativeBlock(path, snow, fluid == 2, false);
        var actual = super.resolveSurface(new ClientColumnSample(floor, fluidY, biomeIndex, top,
                sample.structureIndex(), sample.treeKind(), sample.treeDensity(), sample.treeHeight(),
                fluid, flags, sample.groundFeatureKind(), sample.underBlockIndex(), sample.deepBlockIndex(),
                sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor()), x, z, terrain);
        return new ClientColumnSample(floor, fluidY, actual.biomeIndex(), actual.topBlockIndex(),
                actual.structureIndex(), actual.treeKind(), actual.treeDensity(), actual.treeHeight(), fluid,
                actual.flags(), actual.groundFeatureKind(), actual.underBlockIndex(), actual.deepBlockIndex(),
                actual.surfaceBottom(), actual.lowerTop(), actual.lowerBottom(), actual.spanFloor());
    }

    private record Column(int floor, int fluidY, int fluid) { }

    private static final class ExteriorProbe implements java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> {
        private int y, fluidY, fluid;
        private ExteriorProbe(net.minecraft.world.level.levelgen.NoiseSettings settings) {
            int cellHeight = settings.getCellHeight();
            y = (Math.floorDiv(settings.minY(), cellHeight) + Math.floorDiv(settings.height(),cellHeight)) * cellHeight - 1;
            fluidY = settings.minY();
        }
        @Override public boolean test(net.minecraft.world.level.block.state.BlockState block) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            int current = y--;
            if (block.isAir()) return false;
            if (block.getFluidState().isEmpty()) return true;
            if (fluid == 0) {
                fluid = block.getFluidState().is(Fluids.LAVA) ? 2 : 1;
                fluidY = current + 1;
            }
            return false;
        }
    }

    private static final class Access {
        private static final java.lang.invoke.MethodHandle ITERATE;
        static {
            try {
                var owner = net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator.class;
                Class<?>[] parameters = {LevelHeightAccessor.class,net.minecraft.world.level.levelgen.RandomState.class,
                        int.class,int.class,org.apache.commons.lang3.mutable.MutableObject.class,java.util.function.Predicate.class};
                var method = java.util.Arrays.stream(owner.getDeclaredMethods())
                        .filter(m -> m.getReturnType() == java.util.OptionalInt.class && java.util.Arrays.equals(m.getParameterTypes(),parameters))
                        .findFirst().orElseThrow(() -> new NoSuchMethodException("Minecraft exterior column iterator"));
                method.setAccessible(true);
                ITERATE = java.lang.invoke.MethodHandles.lookup().unreflect(method);
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
}

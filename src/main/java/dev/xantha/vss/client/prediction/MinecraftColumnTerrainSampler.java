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
        var noise = generatorContext().getBaseColumn(x, z, heights, randomStateContext());
        int floor = profile().minY(), fluidY = floor, fluid = 0;
        for (int y = heights.getMaxBuildHeight() - 1; y >= heights.getMinBuildHeight(); y--) {
            var block = noise.getBlock(y);
            if (block.isAir()) continue;
            if (!block.getFluidState().isEmpty()) {
                if (fluid == 0) {
                    fluid = block.getFluidState().is(Fluids.LAVA) ? 2 : 1;
                    fluidY = y + 1;
                }
            } else {
                floor = y + 1;
                break;
            }
        }
        var result = new Column(floor, Math.max(floor, fluidY), fluid);
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
}

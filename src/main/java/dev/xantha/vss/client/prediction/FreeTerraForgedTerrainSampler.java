package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Ensures density and biome queries can see FreeTerraForged's filtered tiles. */
final class FreeTerraForgedTerrainSampler extends ClientTerrainSampler implements AutoCloseable {
    private final Object cache;
    private final MethodHandle provide;
    private final MethodHandle cellLookup;
    private final FreeTerraForgedWater water;

    FreeTerraForgedTerrainSampler(DimensionProfile profile, NoiseBasedChunkGenerator generator,
            RandomState randomState, LevelHeightAccessor heights,
            ClientWorldgenRegistries registries, RegistryAccess clientRegistries) {
        super(profile.seed(), profile, generator, randomState, heights,
                generator.generatorSettings().value().seaLevel(), registries.structureIds(), registries, clientRegistries);
        try {
            var api = new FreeTerraForgedCompat.Api(getClass().getClassLoader());
            Object context = api.context.invoke(randomState);
            cache = context == null ? null : context.getClass().getField("cache").get(context);
            provide = cache == null ? null : MethodHandles.publicLookup()
                    .unreflect(cache.getClass().getMethod("provideAtChunk", int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            Class<?> tileType = cache == null ? null : cache.getClass().getMethod("provideAtChunk", int.class, int.class).getReturnType();
            cellLookup = tileType == null ? null : MethodHandles.publicLookup()
                    .unreflect(tileType.getMethod("lookup", int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            water = context == null ? null : new FreeTerraForgedWater(context);
            dev.xantha.vss.common.VSSLogger.info("VSS FreeTerraForged adapter ready: dimension="
                    + profile.dimension() + ", filteredTerrain=" + (cache != null));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged filtered tile API unavailable", failure);
        }
    }

    private Object prepare(int blockX, int blockZ) {
        if (provide == null || FreeTerraForgedDensity.coarse()) return null;
        try {
            // Use the mod's cache ownership/expiry. Holding its pooled tiles in
            // another cache would let eviction recycle cells still in use.
            Object tile = (Object) provide.invokeExact(cache, blockX >> 4, blockZ >> 4);
            if (tile == null) throw new IllegalStateException("FreeTerraForged returned no terrain tile");
            return tile;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("FreeTerraForged tile generation failed", failure);
        }
    }

    @Override public int surfaceY(int x, int z) { prepare(x, z); return super.surfaceY(x, z); }
    @Override public int groundY(int x, int z) { prepare(x, z); return super.groundY(x, z); }
    @Override Holder<Biome> noiseBiome(int x, int y, int z) {
        prepare(x << 2, z << 2);
        return super.noiseBiome(x, y, z);
    }

    @Override public ClientColumnSample sampleForLod(int x, int z, int stepBlocks) {
        return FreeTerraForgedDensity.coarse(false, () -> super.sampleForLod(x, z, stepBlocks));
    }

    @Override public ClientColumnSample samplePreview(int x, int z) {
        return FreeTerraForgedDensity.coarse(true, () -> super.samplePreview(x,z));
    }

    @Override int surfaceColorForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.surfaceColorForLod(x,y,z,preview));
    }
    @Override int foliageColorForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.foliageColorForLod(x,y,z,preview));
    }
    @Override int waterTintForLod(int x,int y,int z,boolean preview) {
        return FreeTerraForgedDensity.coarse(preview, () -> super.waterTintForLod(x,y,z,preview));
    }

    @Override ClientColumnSample resolveSurface(ClientColumnSample sample, int x, int z, TerrainFunction terrain) {
        if (water != null && !FreeTerraForgedDensity.coarse()) {
            try {
                Object tile = prepare(x, z);
                Object cell = (Object) cellLookup.invokeExact(tile, x, z);
                int waterY = cell == null ? Integer.MIN_VALUE : Math.min(
                        profile().minY() + profile().height(), water.surfaceY(cell));
                if (waterY > sample.surfaceY() && waterY > sample.fluidY()) {
                    int flags = sample.flags() & ~(ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE);
                    if (noiseBiome(x >> 2, (waterY - 1) >> 2, z >> 2).value().coldEnoughToSnow(
                            new net.minecraft.core.BlockPos(x, waterY - 1, z))) {
                        flags |= ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_ICE;
                    }
                    sample = new ClientColumnSample(sample.surfaceY(), waterY, sample.biomeIndex(), sample.topBlockIndex(),
                            sample.structureIndex(), sample.treeKind(), sample.treeDensity(), sample.treeHeight(), 1, flags,
                            sample.groundFeatureKind(), sample.underBlockIndex(), sample.deepBlockIndex(),
                            sample.surfaceBottom(), sample.lowerTop(), sample.lowerBottom(), sample.spanFloor());
                }
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged surface water sampling failed", failure); }
        }
        return super.resolveSurface(sample, x, z, terrain);
    }

    @Override public void close() {
        try { FreeTerraForgedCompat.releaseTileCache(cache); }
        catch (ReflectiveOperationException failure) {
            dev.xantha.vss.common.VSSLogger.warn("Could not release FreeTerraForged prediction tile cache", failure);
        }
    }
}

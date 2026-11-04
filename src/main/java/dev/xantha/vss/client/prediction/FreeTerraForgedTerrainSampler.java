package dev.xantha.vss.client.prediction;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Ensures density and biome queries can see FreeTerraForged's filtered tiles. */
final class FreeTerraForgedTerrainSampler extends ClientTerrainSampler implements AutoCloseable {
    private final FreeTerraForgedCells cells;
    private final AutoCloseable runtimeOwner;
    private final FreeTerraForgedWater water;
    private final FreeTerraForgedSamples exactSamples = new FreeTerraForgedSamples(32768);
    private final FreeTerraForgedSamples previewSamples = new FreeTerraForgedSamples(8192);
    private volatile FreeTerraForgedExterior exterior;
    private volatile boolean closed;

    FreeTerraForgedTerrainSampler(DimensionProfile profile, NoiseBasedChunkGenerator generator,
            RandomState randomState, LevelHeightAccessor heights,
            ClientWorldgenRegistries registries, RegistryAccess clientRegistries) {
        this(profile, generator, randomState, heights, registries, clientRegistries, null);
    }

    FreeTerraForgedTerrainSampler(DimensionProfile profile, NoiseBasedChunkGenerator generator,
            RandomState randomState, LevelHeightAccessor heights,
            ClientWorldgenRegistries registries, RegistryAccess clientRegistries, AutoCloseable runtimeOwner) {
        super(profile.seed(), profile, generator, randomState, heights,
                generator.generatorSettings().value().seaLevel(), registries, clientRegistries);
        try {
            var api = new FreeTerraForgedCompat.Api(getClass().getClassLoader());
            Object context = api.context.invoke(randomState);
            cells = context == null ? null : new FreeTerraForgedCells(context);
            this.runtimeOwner = runtimeOwner;
            water = context == null ? null : new FreeTerraForgedWater(context);
            dev.xantha.vss.common.VSSLogger.info("VSS FreeTerraForged adapter ready: dimension="
                    + profile.dimension() + ", filteredTerrain=" + (cells != null)
                    + ", storage=" + (cells == null ? "inactive" : cells.tiled() ? "tile" : "chunk"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FreeTerraForged filtered tile API unavailable", failure);
        }
    }

    private Object prepare(int blockX, int blockZ) {
        return cells == null || FreeTerraForgedDensity.coarse() ? null : cells.cellAt(blockX, blockZ);
    }

    private synchronized FreeTerraForgedExterior exterior() {
        if (closed) throw new java.util.concurrent.CancellationException();
        if (exterior == null) exterior = new FreeTerraForgedExterior(generatorContext(), randomStateContext(),
                LevelHeightAccessor.create(profile().minY(), profile().height()));
        return exterior;
    }

    @Override boolean[] exteriorFootprint(int x, int z, int step, int bottom, int top,
            java.util.function.BooleanSupplier valid) {
        return exteriorFootprints(new int[]{x}, new int[]{z}, step, new int[]{bottom}, new int[]{top}, 1, valid)[0];
    }

    @Override boolean[][] exteriorFootprints(int[] xs, int[] zs, int step, int[] bottoms, int[] tops, int count,
            java.util.function.BooleanSupplier valid) {
        return FreeTerraForgedDensity.coarse(false, () -> exterior().sample(xs, zs, step, bottoms, tops, count, valid));
    }

    @Override SamplingScope exteriorRequest() { return exterior().request(); }

    @Override SamplingScope terrainRequest() { return cells == null ? super.terrainRequest() : cells.request(); }

    @Override ClientColumnSample retainAfterCapture(ClientColumnSample sample) {
        // Captures, area mips and city summaries belong to mutable inputs. Seed-only
        // surfaces and their sampled occupancy remain valid within this sampler.
        // Enrichment still visits new cliffs and floods from retained overhangs.
        return sample == null || sample.captured() || !sample.reusableFor(false)
                || sample.floating() || PredictionExteriorColumns.interiorVolume(sample)
                ? null : sample;
    }

    @Override String densityCompilerDiagnostics() {
        var active = exterior;
        return super.densityCompilerDiagnostics() + ",ftfExterior={" + (active == null ? "state=idle" : active.diagnostics()) + "}"
                + ",ftfSamples={" + exactSamples.diagnostics() + "},ftfPreviews={" + previewSamples.diagnostics() + "}"
                + ",ftfRegions={" + (cells == null ? "state=idle" : cells.diagnostics()) + "}";
    }

    @Override public int surfaceY(int x, int z) { prepare(x, z); return super.surfaceY(x, z); }
    @Override public int groundY(int x, int z) { prepare(x, z); return super.groundY(x, z); }
    @Override Holder<Biome> noiseBiome(int x, int y, int z) {
        prepare(x << 2, z << 2);
        return super.noiseBiome(x, y, z);
    }

    @Override public ClientColumnSample sampleForLod(int x, int z, int stepBlocks) {
        return sampleSurface(x, z);
    }

    @Override public ClientColumnSample sampleSurface(int x, int z) {
        return exactSamples.get(x, z, () -> FreeTerraForgedDensity.coarse(false, () -> super.sampleSurface(x, z)));
    }

    @Override public ClientColumnSample samplePreview(int x, int z) {
        return previewSamples.get(x, z, () -> FreeTerraForgedDensity.coarse(true, () -> super.samplePreview(x,z)));
    }

    @Override boolean supportsLodGrid() { return true; }

    @Override boolean sampleLodGridRequest(int x, int z, int step, int size, ClientColumnSample[] target,
                                          boolean preview, java.util.function.BooleanSupplier valid) {
        sampleLodGrid(x, z, step, size, size, target, 0, size, preview, valid);
        return true;
    }

    @Override boolean sampleLodGrid(int x, int z, int step, int width, int height,
            ClientColumnSample[] target, int offset, int stride, boolean preview) {
        return sampleLodGrid(x, z, step, width, height, target, offset, stride, preview, () -> true);
    }

    boolean sampleLodGrid(int x, int z, int step, int width, int height,
            ClientColumnSample[] target, int offset, int stride, boolean preview,
            java.util.function.BooleanSupplier valid) {
        return FreeTerraForgedGrid.sample(x, z, step, width, height, target, offset, stride, () -> {
            if (closed || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            return valid.getAsBoolean();
        }, preview ? this::samplePreview : this::sampleSurface);
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
                Object cell = prepare(x, z);
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
        synchronized (this) {
            closed = true;
            exactSamples.close(); previewSamples.close();
            if (exterior != null) exterior.close();
        }
        try {
            if (runtimeOwner != null) runtimeOwner.close();
            else if (cells != null) cells.close();
        }
        catch (Exception failure) {
            dev.xantha.vss.common.VSSLogger.warn("Could not release FreeTerraForged prediction tile cache", failure);
        }
        finally { super.close(); }
    }
}

package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.biome.Climate;

/** Exact climate roots used by Java biome routing, including native terrain's compatibility context. */
final class PredictionClimateSampler implements AutoCloseable {
    private final PredictionHorizontalClimate horizontal = new PredictionHorizontalClimate();
    private final DensityCompilation compilation;
    private final Climate.Sampler sampler;

    PredictionClimateSampler(Climate.Sampler source) {
        var roots = DensityMemo.wrapRoots(horizontal.wrap(source.temperature(), source.humidity(), source.continentalness(),
                source.erosion(), source.depth(), source.weirdness()));
        compilation = new DensityCompilation(roots);
        roots = compilation.roots();
        sampler = new Climate.Sampler(roots[0], roots[1], roots[2], roots[3], roots[4], roots[5], source.spawnTarget());
        FreeTerraForgedCompat.copyClimateContext(source, sampler);
        RtfTerraBlenderRegions.carry(source, sampler);
        FabricBiomeCompat.copyClimateContext(source, sampler);
    }

    Climate.Sampler sampler() { return sampler; }

    String diagnostics() { return compilation.diagnostics(); }

    String horizontalDiagnostics() { return horizontal.diagnostics(); }
    void bindHorizontal(long world, net.minecraft.world.level.levelgen.RandomState random, com.google.gson.JsonObject document, long seed) { horizontal.bind(world, random, document, seed); }
    void detachHorizontal() { horizontal.detach(); }

    @Override public void close() { horizontal.close(); compilation.close(); }
}

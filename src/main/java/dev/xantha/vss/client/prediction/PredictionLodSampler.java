package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Direct dispatch to a selected source. Registry checks and JSON parsing never occur in these methods. */
final class PredictionLodSampler extends ClientTerrainSampler {
    private final PredictionLodSource source;
    private final List<Holder<Biome>> sourceBiomes;
    private final AtomicBoolean closed = new AtomicBoolean();

    PredictionLodSampler(PredictionLodContext context, PredictionLodSource source) {
        super(base(context), null);
        this.source = source;
        sourceBiomes = List.copyOf(source.biomes());
        bindPredictionBackend("kind=LOD_SOURCE,backend=" + context.plan().backend() + ",revision="
                + context.plan().revision() + ",executor=source,clientRevision=" + source.cacheRevision(),
                context.plan().backend() + ':' + context.plan().revision() + ':' + source.cacheRevision()
                        + ':' + context.cacheIdentity());
    }
    private static ClientTerrainSampler base(PredictionLodContext context) {
        ClientTerrainSampler initialized = context.initializedNoiseContext();
        return initialized != null ? initialized : new ClientTerrainSampler(context.profile().seed(), context.profile());
    }
    @Override public int surfaceY(int x, int z) { return source.surfaceY(x, z); }
    @Override public int groundY(int x, int z) { return source.surfaceY(x, z); }
    @Override public ClientColumnSample sample(int x, int z) { return source.sample(x, z, 1); }
    @Override public ClientColumnSample sampleSurface(int x, int z) { return source.sample(x, z, 1); }
    @Override public ClientColumnSample sampleForLod(int x, int z, int step) { return source.sample(x, z, step); }
    @Override public ClientColumnSample samplePreview(int x, int z, int step) { return source.preview(x, z, step); }
    @Override public ClientColumnSample samplePreview(int x, int z) { return source.preview(x, z, 1); }
    @Override boolean sampleLodGrid(int x, int z, int step, int width, int height,
            ClientColumnSample[] target, int offset, int stride, boolean preview) {
        source.sampleGrid(x, z, step, width, height, target, offset, stride, preview);
        return true;
    }
    @Override public boolean exactWorldgen() { return true; }
    @Override boolean interiorTerrain() { return source.interiorTerrain(); }
    // A selected LOD provider owns its spans/volumes; the inherited noise context is only auxiliary.
    @Override boolean floatingTerrain() { return false; }
    @Override PredictionColumnVolume exteriorColumn(int x, int z) { return source.column(x, z); }
    @Override ClientColumnSample sampleInterior(int x, int z) { return source.sample(x, z, 1); }
    @Override ClientColumnSample wallEvidence(int x, int z, ClientColumnSample sample) { return sample; }
    @Override Holder<Biome> noiseBiome(int x, int y, int z) {
        Holder<Biome> result = source.biome(x, y, z);
        return result != null ? result : super.noiseBiome(x, y, z);
    }
    @Override List<String> cacheBiomes() {
        return sourceBiomes.isEmpty() ? super.cacheBiomes() : sourceBiomes.stream()
                .map(b -> b.unwrapKey().map(k -> k.location().toString()).orElseThrow()).toList();
    }
    @Override public int waterTint(int x, int y, int z) {
        int result = source.waterTint(x, y, z);
        return result == Integer.MIN_VALUE ? super.waterTint(x, y, z) : result;
    }
    @Override public int surfaceColor(int x, int y, int z) {
        int result = source.surfaceColor(x, y, z);
        return result == Integer.MIN_VALUE ? super.surfaceColor(x, y, z) : result;
    }
    @Override public int foliageColor(int x, int y, int z) {
        int result = source.foliageColor(x, y, z);
        return result == Integer.MIN_VALUE ? super.foliageColor(x, y, z) : result;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { source.close(); } finally { super.close(); }
    }
}

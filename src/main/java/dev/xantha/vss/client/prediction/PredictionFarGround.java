package dev.xantha.vss.client.prediction;

/** Shared display-only anchor pages; exact placement and captured terrain never read this cache. */
final class PredictionFarGround {
    private final ClientTerrainSampler sampler;
    private final PredictionSharedCache<Long,ClientColumnSample[]> pages = new PredictionSharedCache<>(2048);
    private final long seed;
    PredictionFarGround(ClientTerrainSampler sampler) {
        this.sampler = sampler;
        seed = sampler.profile().seed() ^ PredictionFarDecoration.stringHash(sampler.profile().dimension().toString());
    }
    long root(int gx, int gz) {
        return PredictionFarDecoration.mix(seed ^ ((long)gx * 0x9e3779b97f4a7c15L) ^ ((long)gz * 0xc2b2ae3d27d4eb4fL));
    }
    int x(int gx, long root) { return gx * 5 + (int)Long.remainderUnsigned(PredictionFarDecoration.mix(root ^ 11), 5) - 2; }
    int z(int gz, long root) { return gz * 5 + (int)Long.remainderUnsigned(PredictionFarDecoration.mix(root ^ 17), 5) - 2; }

    ClientColumnSample at(int gx, int gz, Runnable check) {
        int px = Math.floorDiv(gx, 8), pz = Math.floorDiv(gz, 8);
        long key = (long)px << 32 | pz & 0xffffffffL;
        var page = pages.get(key, () -> build(px * 8, pz * 8, check), check);
        return page[Math.floorMod(gz, 8) * 8 + Math.floorMod(gx, 8)];
    }
    private ClientColumnSample[] build(int gx, int gz, Runnable check) {
        int[] positions = new int[128];
        for (int i = 0; i < 64; i++) {
            long root = root(gx + i % 8, gz + i / 8);
            positions[i * 2] = x(gx + i % 8, root);
            positions[i * 2 + 1] = z(gz + i / 8, root);
        }
        check.run();
        var result = new ClientColumnSample[64];
        if (sampler instanceof RustTerrainSampler rust) rust.sampleDisplayPoints(positions, 64, result);
        else for (int i = 0; i < 64; i++) {
            check.run(); result[i] = sampler.sampleForLod(positions[i * 2], positions[i * 2 + 1], 1);
        }
        return result;
    }
}

package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.List;
import net.minecraft.world.level.biome.BiomeSource;

/** Cache identity and palette only. This context must never generate terrain. */
final class PredictionCacheSampler extends ClientTerrainSampler {
    private final List<String> biomes;
    private final JsonObject templates;
    private final boolean floating;

    PredictionCacheSampler(DimensionProfile profile, BiomeSource source, JsonObject templates) {
        super(profile.seed(), profile);
        biomes = source.possibleBiomes().stream().map(b -> b.unwrapKey()
                .map(key -> key.location().toString()).orElse("")).toList();
        this.templates = templates;
        floating = profile.levelKey().equals(net.minecraft.world.level.Level.END)
                && !BetterEndCompat.isBetterEndSource(source);
    }

    @Override List<String> cacheBiomes() { return biomes; }
    @Override JsonObject structureTemplates() { return templates; }
    @Override boolean floatingTerrain() { return floating; }
    @Override int initialTerrainCellAxis(int lod) { return PredictionWorkOrder.initialCellAxis(lod); }
    @Override public int surfaceY(int x, int z) { throw new IllegalStateException("Cache context cannot generate terrain"); }
    @Override public ClientColumnSample sample(int x, int z) { throw new IllegalStateException("Cache context cannot generate terrain"); }
}

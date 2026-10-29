package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.common.worldgen.WorldgenJson;

/** Publication-time diagnostics and algorithm isolation; no work on frame or sample paths. */
final class PredictionBackendSelection {
    private PredictionBackendSelection() { }
    static void bind(ClientTerrainSampler sampler, JsonObject generator, JsonObject registries) {
        if (sampler instanceof PredictionLodSampler) return;
        TerrainPredictionPlan plan = generator == null ? TerrainPredictionPlan.noise("vss:legacy", 1, false)
                : TerrainPredictionPlan.read(generator);
        String executor = sampler instanceof PredictionCacheSampler ? "cache" : sampler instanceof RustTerrainSampler ? "rust"
                : sampler instanceof PredictionFlatTerrainSampler ? "constant-layers" : "java";
        String identity = null;
        String override = sampler.terrainBackendOverride();
        // Existing noise/cache paths keep their stable directories. New algorithm projections cannot
        // restore another provider's finished meshes, even when both describe the same world seed.
        if (generator != null && (plan.kind() == TerrainPredictionPlan.Kind.FLAT_LAYERS
                || plan.kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH && !plan.backend().equals("vss:noise_graph")))
            identity = plan.backend() + ':' + plan.revision() + ':' + WorldgenJson.sha256(generator)
                    + ':' + WorldgenJson.sha256(registries);
        boolean explicitOverride = override != null && plan.kind() == TerrainPredictionPlan.Kind.UNAVAILABLE;
        if (explicitOverride) identity = override + ':' + WorldgenJson.sha256(generator) + ':' + WorldgenJson.sha256(registries);
        // Previously completed samples lack server tags/End routing semantics.
        if ((registries != null && registries.has("registry_tags"))
                || (generator != null && generator.has("vss_terrablender_end")))
            identity = "worldgen-compat-r2:" + WorldgenJson.sha256(generator == null ? new JsonObject() : generator)
                    + ':' + WorldgenJson.sha256(registries == null ? new JsonObject() : registries);
        sampler.bindPredictionBackend("kind=" + (explicitOverride ? "BACKEND_OVERRIDE,snapshotKind=" + plan.kind() : plan.kind())
                + ",backend=" + plan.backend() + (override == null ? "" : ",selectedBackend=" + override)
                + ",revision=" + plan.revision() + ",executor=" + executor
                + (plan.reason().isEmpty() ? "" : ",reason=" + plan.reason()), identity);
    }
}

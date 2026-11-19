package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.common.worldgen.WorldgenJson;

/** Publication-time diagnostics and algorithm isolation; no work on frame or sample paths. */
final class PredictionBackendSelection {
    private PredictionBackendSelection() { }
    static void bind(ClientTerrainSampler sampler, JsonObject generator, JsonObject registries) {
        if (sampler instanceof PredictionLodSampler) {
            sampler.bindPredictionBackend(sampler.predictionBackendDiagnostics(), cacheIdentity(sampler.predictionCacheNamespace()));
            return;
        }
        TerrainPredictionPlan plan = generator == null ? TerrainPredictionPlan.noise("vss:legacy", 1, false)
                : TerrainPredictionPlan.read(generator);
        String executor = sampler instanceof PredictionCacheSampler ? "cache" : sampler instanceof RustTerrainSampler ? "rust"
                : sampler instanceof PredictionFlatTerrainSampler ? "constant-layers" : "java";
        String identity = null;
        String override = sampler.terrainBackendOverride();
        // New algorithm projections cannot
        // restore another provider's finished meshes, even when both describe the same world seed.
        if (generator != null && (plan.kind() == TerrainPredictionPlan.Kind.FLAT_LAYERS
                || plan.kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH && !plan.backend().equals("vss:noise_graph")))
            identity = plan.backend() + ':' + plan.revision() + ':' + WorldgenJson.cacheSha256(generator)
                    + ':' + WorldgenJson.sha256(registries);
        boolean explicitOverride = override != null && plan.kind() == TerrainPredictionPlan.Kind.UNAVAILABLE;
        if (explicitOverride) identity = override + ':' + WorldgenJson.cacheSha256(generator) + ':' + WorldgenJson.sha256(registries);
        // Previously completed samples lack server tags/End routing semantics.
        if ((registries != null && registries.has("registry_tags"))
                || (generator != null && generator.has("vss_terrablender_end")))
            identity = "worldgen-compat-r2:" + WorldgenJson.cacheSha256(generator == null ? new JsonObject() : generator)
                    + ':' + WorldgenJson.sha256(registries == null ? new JsonObject() : registries);
        // Surface contexts previously bound terrain rules to the origin chunk.
        // Early cache display and the final sampler must reject the same old records.
        if (generator != null && generator.has("vss_freeterraforged")
                && generator.get("vss_freeterraforged").getAsBoolean())
            identity = "terrain-surface-context-r2:" + (identity == null ? "legacy" : identity);
        var execution = generator == null ? null : PredictionExecutionPlan.resolve(generator, registries);
        // Older Java/TerraBlender paths could skip their declared surface stage.
        if (execution != null && (execution.javaSurface() || generator.has("vss_original_stages")))
            identity = "staged-worldgen-r3:" + WorldgenJson.cacheSha256(generator) + ':' + WorldgenJson.sha256(registries);
        if (!dev.xantha.vss.api.worldgen.DensityFunctionReplacements.empty())
            identity = "density-replacements-r1:" + dev.xantha.vss.api.worldgen.DensityFunctionReplacements.identity() + ':' + identity;
        // The old 16-block preview scan could permanently cache a lower terrain layer.
        // Early cache display and initialized samplers must select the same corrected records.
        if (generator != null && plan.kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH)
            identity = "density-preview-height-r1:" + (identity == null ? "legacy" : identity);
        sampler.bindPredictionBackend("kind=" + (explicitOverride ? "BACKEND_OVERRIDE,snapshotKind=" + plan.kind() : plan.kind())
                + ",backend=" + plan.backend() + (override == null ? "" : ",selectedBackend=" + override)
                + ",revision=" + plan.revision() + ",executor=" + executor
                + (execution == null ? "" : "," + execution.diagnostics())
                + (plan.reason().isEmpty() ? "" : ",reason=" + plan.reason()), cacheIdentity(identity));
    }

    private static String cacheIdentity(String backend) {
        // Old encoders could persist a valid but incorrect name after a registry remap.
        // Preserve those files, but never promote them into this corrected cache.
        return backend != null && backend.startsWith("session-block-mappings-r1:") ? backend
                : "session-block-mappings-r1:" + (backend == null ? "legacy" : backend);
    }
}

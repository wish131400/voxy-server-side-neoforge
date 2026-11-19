package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;

/** Resolved once: a surface or feature codec cannot veto an independent terrain executor. */
record PredictionExecutionPlan(boolean nativeTerrain, boolean javaSurface, String terrainReason) {
    static PredictionExecutionPlan resolve(JsonObject generator, JsonObject registries) {
        String reason = PredictionWorldgenCapabilities.nativeTerrainRejection(generator, registries == null ? new JsonObject() : registries);
        JsonObject settings=generator.has("settings") && generator.get("settings").isJsonObject()
                ? generator.getAsJsonObject("settings") : new JsonObject();
        var rule=settings.has("material_rule")?settings.get("material_rule"):settings.get("surface_rule");
        boolean surface=generator.has("vss_surface_stage") || generator.has("vss_java_biomes")
                || rule!=null && !PredictionWorldgenCapabilities.nativeSafe(rule);
        return new PredictionExecutionPlan(reason == null, surface, reason == null ? "" : reason);
    }

    JsonObject nativeGenerator(JsonObject generator) {
        if (!javaSurface) return generator;
        JsonObject projected = generator.deepCopy();
        // The original rule object stays in the Java execution context. Native terrain
        // produces occupancy and default material, then Java resolves the surface bands.
        var settings = projected.getAsJsonObject("settings");
        JsonObject material = new JsonObject();
        material.addProperty("type", "minecraft:block");
        material.add("result_state", settings.get("default_block"));
        settings.add(settings.has("material_rule")?"material_rule":"surface_rule", material);
        return projected;
    }

    ClientTerrainSampler javaSampler(ClientTerrainSampler source, JsonObject generator) {
        return javaSurface && !generator.has("vss_original_stages") && !source.stagedSurface()
                ? new PredictionSurfaceTerrainSampler(source) : source;
    }

    String diagnostics() {
        return "terrain=" + (nativeTerrain ? "rust-or-java" : "java") + ",surface="
                + (javaSurface ? "original-java" : "terrain-backend") + ",decoration=per-feature";
    }
}

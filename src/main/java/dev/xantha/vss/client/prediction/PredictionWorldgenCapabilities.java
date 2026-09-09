package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A successful codec decode is required; approximate noise is never a compatibility adapter. */
final class PredictionWorldgenCapabilities {
    private static final java.util.Set<String> NATIVE_MOD_CODECS = java.util.Set.of(
            "tectonic:invert",
            "lithostitched:axis", "lithostitched:ceil", "lithostitched:floor", "lithostitched:sin",
            "lithostitched:cos", "lithostitched:sqrt", "lithostitched:mix", "lithostitched:select",
            "lithostitched:shift", "lithostitched:fast_noise", "lithostitched:perlin",
            "lithostitched:simplex", "lithostitched:cellular",
            "reterraforged:noise", "reterraforged:clamp_to_nearest_unit", "reterraforged:linear_spline",
            "reterraforged:constant", "reterraforged:perlin", "reterraforged:perlin2",
            "reterraforged:simplex", "reterraforged:simplex2", "reterraforged:white",
            "reterraforged:shift", "reterraforged:frequency", "reterraforged:add", "reterraforged:multiply",
            "reterraforged:min", "reterraforged:max", "reterraforged:abs", "reterraforged:invert",
            "reterraforged:power", "reterraforged:clamp", "reterraforged:map", "reterraforged:alpha",
            "reterraforged:threshold");
    static String rejection(JsonObject generator) {
        if (generator.has("vss_unsupported_reason")) return generator.get("vss_unsupported_reason").getAsString();
        if (!generator.has("settings") || !generator.has("biome_source")) return "missing noise settings/biome source";
        return null;
    }

    static boolean nativeSafe(JsonElement element) {
        return nativeRejection(element, "worldgen") == null;
    }

    /** Decoration codecs run in Java and are not dependencies of the Rust terrain graph. */
    static String nativeTerrainRejection(JsonObject generator, JsonObject registries) {
        String reason = nativeRejection(generator, "generator");
        if (reason != null) return reason;
        if (registries.has("vss_force_java") && registries.get("vss_force_java").getAsBoolean()) {
            return "registries.vss_force_java";
        }
        return nativeRejection(terrainDocument(generator, registries), "terrain");
    }

    /** Only terrain dependencies participate in terrain codec compatibility checks. */
    static JsonObject terrainDocument(JsonObject generator, JsonObject registries) {
        JsonObject root = new JsonObject();
        for (String name : new String[]{"settings", "biome_source"})
            if (generator.has(name)) root.add(name, generator.get(name));
        for (String name : new String[]{"noises", "biomes"})
            if (registries.has(name)) root.add(name, registries.get(name).deepCopy());
        JsonObject densities = new JsonObject();
        if (registries.has("density_functions")) {
            var definitions = registries.getAsJsonObject("density_functions");
            collectDensityDependencies(generator, definitions, densities);
        }
        root.add("density_functions", densities);
        if (root.has("biomes")) for (JsonElement value : root.getAsJsonObject("biomes").asMap().values()) {
            if (!value.isJsonObject()) continue;
            value.getAsJsonObject().remove("features");
            value.getAsJsonObject().remove("carvers");
        }
        return root;
    }

    private static void collectDensityDependencies(JsonElement value, JsonObject definitions, JsonObject result) {
        var pending = new java.util.ArrayDeque<JsonElement>();
        pending.add(value);
        while (!pending.isEmpty()) {
            var element = pending.removeLast();
            if (element.isJsonArray()) element.getAsJsonArray().forEach(pending::add);
            else if (element.isJsonObject()) element.getAsJsonObject().asMap().values().forEach(pending::add);
            else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String key = element.getAsString();
                if (definitions.has(key) && !result.has(key)) {
                    result.add(key, definitions.get(key)); pending.add(definitions.get(key));
                }
            }
        }
    }

    private static String nativeRejection(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                String reason = nativeRejection(child, path + "[" + index++ + "]");
                if (reason != null) return reason;
            }
        } else if (element.isJsonObject()) {
            if (element.getAsJsonObject().has("vss_force_java")
                    && element.getAsJsonObject().get("vss_force_java").getAsBoolean()) return path + ".vss_force_java";
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("type") && entry.getValue().isJsonPrimitive()) {
                    String type = entry.getValue().getAsString();
                    if (type.contains(":") && !type.startsWith("minecraft:") && !NATIVE_MOD_CODECS.contains(type)) return path + ".type=" + type;
                }
                String reason = nativeRejection(entry.getValue(), path + "." + entry.getKey());
                if (reason != null) return reason;
            }
        }
        return null;
    }

    private PredictionWorldgenCapabilities() { }
}

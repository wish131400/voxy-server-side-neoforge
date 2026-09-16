package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** A successful codec decode is required; approximate noise is never a compatibility adapter. */
final class PredictionWorldgenCapabilities {
    private static final java.util.Set<String> NATIVE_MOD_CODECS = java.util.Set.of(
            // Blueprint's biome source wrapper. The native backend only accepts
            // it when the server also shipped a `vss_blueprint` snapshot, so a
            // client that sees the wrapper without one still fails closed.
            "blueprint:modded",
            // Alex's Caves' simplex-noise surface condition. The native backend
            // ports the exact table and f32 arithmetic; without it every
            // dimension whose surface rules use it (including the overworld)
            // stays on the Java sampler.
            "alexscaves:ac_simplex",
            // TerraBlender's namespace-dispatching surface rule and
            // Youkaishomecoming's four-corner noise condition. A live capture of
            // the overworld reported exactly these two as the last blockers.
            "terrablender:merged",
            "youkaishomecoming:noise",
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
        if (generator.get("biome_source").isJsonObject()
                && generator.getAsJsonObject("biome_source").has("type")
                && "blueprint:modded".equals(generator.getAsJsonObject("biome_source").get("type").getAsString())
                && !generator.has("vss_blueprint")) return "missing Blueprint biome slice snapshot";
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
            // Entities, features and carvers are decoration. They carry `type`
            // fields naming other mods' content (for example
            // alexscaves:tripodfish under spawners) and never participate in
            // the terrain density graph, so leaving them in makes the native
            // sampler reject dimensions whose terrain is entirely vanilla.
            value.getAsJsonObject().remove("features");
            value.getAsJsonObject().remove("carvers");
            value.getAsJsonObject().remove("spawners");
            // Biome `effects` carry particle/sound/sky identifiers; a live run
            // showed `alexscaves:sugar_flake` under effects.particle rejecting
            // twelve dimensions. They are pure client presentation and never
            // reach the terrain graph.
            value.getAsJsonObject().remove("effects");
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
                // VSS's own payload sections (vss_terrablender, vss_blueprint,
                // ...) are replayed by the native backends rather than by
                // worldgen codecs, so their bookkeeping fields must not be read
                // as codec type names. TerraBlender's section happens to carry
                // no `type` key, but Blueprint's provider dispatch does, and
                // scanning it would reject every wrapped dimension.
                if (entry.getKey().startsWith("vss_")) continue;
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

package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Lossless sharing using Minecraft's density holder fields, not larger packet limits. */
public final class DensityFunctionReferences {
    private static final Set<String> BINARY = Set.of("minecraft:add", "minecraft:mul", "minecraft:min", "minecraft:max");
    private static final Set<String> UNARY = Set.of("minecraft:abs", "minecraft:square", "minecraft:cube",
            "minecraft:half_negative", "minecraft:quarter_negative", "minecraft:squeeze",
            "minecraft:interpolated", "minecraft:flat_cache", "minecraft:cache_2d", "minecraft:cache_once",
            "minecraft:cache_all_in_cell", "minecraft:blend_density");
    private final JsonObject definitions = new JsonObject();
    private final Map<JsonElement, String> ids = new HashMap<>();

    public JsonObject definitions() { return definitions; }

    public JsonElement compact(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (var item : element.getAsJsonArray()) result.add(compact(item));
            return result;
        }
        if (!element.isJsonObject()) return element;
        JsonObject source = element.getAsJsonObject();
        JsonObject result = new JsonObject();
        String type = source.has("type") && source.get("type").isJsonPrimitive()
                ? source.get("type").getAsString() : "";
        for (var entry : source.entrySet()) {
            JsonElement child = compact(entry.getValue());
            if (holderField(type, entry.getKey()) && child.isJsonObject()) child = reference(child);
            result.add(entry.getKey(), child);
        }
        return result;
    }

    public JsonObject settings(JsonObject settings) {
        JsonObject result = compact(settings).getAsJsonObject();
        JsonObject router = result.getAsJsonObject("noise_router");
        if (router != null) for (var entry : router.entrySet()) {
            if (entry.getValue().isJsonObject()) entry.setValue(reference(entry.getValue()));
        }
        return result;
    }

    private JsonElement reference(JsonElement value) {
        String id = ids.get(value);
        if (id == null) {
            // A content-derived id stays stable across dimension/registry traversal order.
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(WorldgenJson.bytes(value));
                id = "vss:prediction_density/" + java.util.HexFormat.of().formatHex(hash);
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
            ids.put(value, id);
            definitions.add(id, value);
        }
        return new JsonPrimitive(id);
    }

    private static boolean holderField(String type, String field) {
        if (BINARY.contains(type)) return field.equals("argument1") || field.equals("argument2");
        if (UNARY.contains(type)) return field.equals("argument");
        return switch (type) {
            case "minecraft:range_choice" -> field.equals("input") || field.equals("when_in_range") || field.equals("when_out_of_range");
            case "minecraft:shifted_noise" -> field.equals("shift_x") || field.equals("shift_y") || field.equals("shift_z");
            case "minecraft:weird_scaled_sampler" -> field.equals("input");
            // Clamp.input is DIRECT_CODEC; custom codecs may also require inline values.
            default -> false;
        };
    }
}

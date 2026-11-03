package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.util.Map;
import java.util.Set;

/** Canonical Raw expressions: cache markers delegate, arithmetic retains its exact tree. */
final class PredictionClimateIdentity {
    private static final Set<String> MARKERS = Set.of("minecraft:flat_cache", "minecraft:cache_2d",
            "minecraft:cache_once", "minecraft:cache_all_in_cell", "minecraft:interpolated", "minecraft:cache");
    private PredictionClimateIdentity() { }
    static boolean same(JsonElement a, JsonElement b) {
        if (a.isJsonPrimitive() && b.isJsonPrimitive() && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber())
            return Double.doubleToRawLongBits(a.getAsDouble()) == Double.doubleToRawLongBits(b.getAsDouble());
        if (a.isJsonObject() && b.isJsonObject()) {
            var x = a.getAsJsonObject(); var y = b.getAsJsonObject();
            if (!x.keySet().equals(y.keySet())) return false;
            for (var entry : x.entrySet()) if (!same(entry.getValue(), y.get(entry.getKey()))) return false;
            return true;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            var x = a.getAsJsonArray(); var y = b.getAsJsonArray();
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
    static JsonElement normalize(JsonElement value) { return normalize(value, Map.of(), 0); }
    static JsonElement normalize(JsonElement value, Map<JsonElement, JsonElement> noises, int depth) {
        if (depth > 128) throw new IllegalArgumentException("Climate expression depth");
        JsonElement noise = noises.get(value); if (noise != null) return noise;
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            JsonElement type = object.get("type");
            if (type != null && type.isJsonPrimitive() && MARKERS.contains(type.getAsString())) {
                JsonElement child = object.has("input") ? object.get("input") : object.get("argument");
                if (child != null) return normalize(child, noises, depth + 1);
            }
            var result = new JsonObject();
            object.entrySet().forEach(entry -> result.add(entry.getKey(), normalize(entry.getValue(), noises, depth + 1)));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray(); value.getAsJsonArray().forEach(child -> result.add(normalize(child, noises, depth + 1)));
            return result;
        }
        return value;
    }
}

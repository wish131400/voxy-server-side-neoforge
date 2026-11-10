package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Projects the released Lithostitched codec's delegate contract, without mutating profile/cache inputs. */
final class RustBiomeSourceProjection {
    private static final int MAX_WRAPPERS = 16;

    private RustBiomeSourceProjection() { }

    static JsonObject generator(JsonObject generator) {
        JsonElement source = generator.get("biome_source");
        JsonElement projected = source;
        int wrappers = 0;
        while (projected != null && projected.isJsonObject()) {
            JsonObject object = projected.getAsJsonObject();
            if (!object.has("type") || !object.get("type").isJsonPrimitive()
                    || !"lithostitched:injector".equals(object.get("type").getAsString())) break;
            // 1.8.1's MapCodec encodes only `delegate`, and decodes with Function.identity().
            // Extra fields could describe another contract: leave them to the normal rejection path.
            if (++wrappers > MAX_WRAPPERS || object.size() != 2
                    || !object.has("delegate") || !object.get("delegate").isJsonObject()) return generator;
            projected = object.get("delegate");
        }
        if (projected == source) return generator;
        JsonObject result = new JsonObject();
        generator.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        result.add("biome_source", projected);
        return result;
    }
}

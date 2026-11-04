package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;

/** Immutable registry inputs used when the native material plan is built. */
final class RustSurfaceRegistries {
    private static final String SURFACE_RULES = "lithostitched:surface_rule";

    static void copy(JsonObject registries, JsonObject document) {
        if (registries.has("custom_registries"))
            document.add("custom_registries", registries.get("custom_registries"));
        if (registries.has("registry_tags")) {
            JsonObject tags = registries.getAsJsonObject("registry_tags");
            if (tags.has(SURFACE_RULES)) {
                JsonObject required = new JsonObject();
                required.add(SURFACE_RULES, tags.get(SURFACE_RULES));
                document.add("registry_tags", required);
            }
        }
    }
}

package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.neoforged.fml.ModList;

/** Server-only, optional policy for structure starts that depend on Structurify's runtime claims. */
final class StructurifyPredictionSnapshot {
    private StructurifyPredictionSnapshot() { }

    static JsonObject capture() {
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded("structurify")) return null;
        try {
            return captureFrom("com.faboslav.structurify.common.Structurify",
                    "com.faboslav.structurify.common.config.StructurifyConfigSerializer");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return unsupported("Structurify runtime rules could not be captured", null);
        }
    }

    static JsonObject captureFrom(String ownerName, String serializerName) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(ownerName);
        Object config = owner.getMethod("getConfig").invoke(null);
        if (config == null) return unsupported("Structurify has no active configuration", null);
        Class<?> serializer = Class.forName(serializerName);
        Method save = null;
        for (Method method : serializer.getMethods()) {
            if (method.getName().equals("save") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0].isAssignableFrom(config.getClass())
                    && method.getParameterTypes()[1] == boolean.class) {
                save = method;
                break;
            }
        }
        if (save == null) return unsupported("Structurify configuration serializer is unavailable", null);
        Object result = save.invoke(null, config, false);
        JsonElement json = result instanceof JsonElement element ? element
                : result instanceof String string ? JsonParser.parseString(string) : null;
        if (json == null || !json.isJsonObject())
            return unsupported("Structurify configuration cannot be serialized", null);
        JsonObject copy = json.getAsJsonObject().deepCopy();
        copy.remove("config_datetime");
        return unsupported("Structurify runtime placement checks are not replayed", digest(canonical(copy)));
    }

    private static JsonObject unsupported(String reason, String configHash) {
        JsonObject result = new JsonObject();
        result.addProperty("provider", "structurify");
        result.addProperty("prediction_supported", false);
        result.addProperty("reason", reason);
        if (configHash != null) result.addProperty("config_sha256", configHash);
        return result;
    }

    private static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            StringBuilder out = new StringBuilder("{");
            for (String name : element.getAsJsonObject().keySet().stream().sorted().toList()) {
                if (out.length() != 1) out.append(',');
                out.append(new com.google.gson.JsonPrimitive(name)).append(':')
                        .append(canonical(element.getAsJsonObject().get(name)));
            }
            return out.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            for (JsonElement part : element.getAsJsonArray()) {
                if (out.length() != 1) out.append(',');
                out.append(canonical(part));
            }
            return out.append(']').toString();
        }
        return element.toString();
    }

    private static String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}

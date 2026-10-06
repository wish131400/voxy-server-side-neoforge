package dev.xantha.vss.common.worldgen;

import com.google.gson.*;
import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable object order for persisted worldgen identities; array order is semantic. */
public final class WorldgenJson {
    public static byte[] bytes(JsonElement value) {
        var text = new StringWriter();
        try (var writer = new JsonWriter(text)) {
            writer.setSerializeNulls(true);
            write(writer, value);
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Hash the same canonical bytes without retaining another full snapshot string. */
    public static String sha256(JsonElement value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), StandardCharsets.UTF_8)))) {
                writer.setSerializeNulls(true);
                write(writer, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void write(JsonWriter writer, JsonElement value) throws IOException {
        if (value.isJsonObject()) {
            writer.beginObject();
            for (String key : value.getAsJsonObject().keySet().stream().sorted().toList()) {
                writer.name(key);write(writer,value.getAsJsonObject().get(key));
            }
            writer.endObject();
        } else if (value.isJsonArray()) {
            writer.beginArray();for(var child:value.getAsJsonArray())write(writer,child);writer.endArray();
        } else if (value.isJsonNull()) writer.nullValue();
        else {
            var primitive=value.getAsJsonPrimitive();
            if(primitive.isBoolean())writer.value(primitive.getAsBoolean());
            else if(primitive.isNumber())writer.value(primitive.getAsNumber());
            else writer.value(primitive.getAsString());
        }
    }
    private WorldgenJson() { }
}

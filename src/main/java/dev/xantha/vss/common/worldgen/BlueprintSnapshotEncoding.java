package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.common.processing.LodByteCompression;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Keeps large, ordered Blueprint codec data inside the generator wire budget. */
public final class BlueprintSnapshotEncoding {
    private static final String PACKED = "deflate_v1";
    private static final int MAX_EXPANDED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PACKED_CHARS = 1_500_000;

    private BlueprintSnapshotEncoding() { }

    public static JsonObject pack(byte[] raw) {
        if (raw.length > MAX_EXPANDED_BYTES) return null;
        var result = LodByteCompression.compressForNetwork(raw, false);
        if (result.method() != LodByteCompression.METHOD_DEFLATE
                || ((result.bytes().length + 2) / 3) * 4 > MAX_PACKED_CHARS) return null;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("raw_size", raw.length);
        envelope.addProperty(PACKED, Base64.getEncoder().encodeToString(result.bytes()));
        return envelope;
    }

    public static boolean packed(JsonObject section) { return section.has(PACKED); }

    public static JsonObject unpack(JsonObject section) {
        if (!packed(section)) return section;
        int size = Integer.parseInt(section.get("raw_size").getAsString());
        String encoded = section.get(PACKED).getAsString();
        if (size <= 0 || size > MAX_EXPANDED_BYTES || encoded.length() > MAX_PACKED_CHARS) {
            throw new IllegalArgumentException("Blueprint biome snapshot exceeds decode budget");
        }
        try {
            byte[] raw = LodByteCompression.decompress(Base64.getDecoder().decode(encoded),
                    LodByteCompression.METHOD_DEFLATE, size, MAX_EXPANDED_BYTES);
            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid Blueprint biome snapshot", failure);
        }
    }
}

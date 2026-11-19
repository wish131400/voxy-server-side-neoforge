package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintSnapshotEncodingTest {
    @Test
    void largeSliceDataRoundTripsWithoutReordering() {
        JsonObject original = new JsonObject();
        JsonArray slices = new JsonArray();
        original.add("slices", slices);
        original.addProperty("slices_seed", Long.MIN_VALUE + 107);
        for (String biome : new String[]{"autumnity:maple_forest", "minecraft:forest"}) {
            JsonObject slice = new JsonObject();
            slice.addProperty("name", biome);
            slice.addProperty("large_codec_data", biome.repeat(200_000));
            slices.add(slice);
        }
        byte[] raw = WorldgenJson.bytes(original);
        assertTrue(raw.length > 6_815_744);
        JsonObject packed = BlueprintSnapshotEncoding.pack(raw);
        assertNotNull(packed);
        assertEquals(original, BlueprintSnapshotEncoding.unpack(packed));
        assertSame(original, BlueprintSnapshotEncoding.unpack(original));
        packed.addProperty("raw_size", 17 * 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> BlueprintSnapshotEncoding.unpack(packed));
    }
}

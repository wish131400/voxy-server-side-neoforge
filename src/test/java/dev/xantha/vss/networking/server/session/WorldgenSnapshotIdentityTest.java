package dev.xantha.vss.networking.server.session;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.xantha.vss.common.worldgen.*;
import org.junit.jupiter.api.Test;

class WorldgenSnapshotIdentityTest {
    @Test void reorderedObjectsKeepDensityReferencesAndCompressedProfileBytes() throws Exception {
        var a=JsonParser.parseString("{\"noise_router\":{\"final_density\":{\"type\":\"minecraft:add\",\"argument1\":{\"type\":\"minecraft:noise\",\"noise\":\"minecraft:offset\",\"xz_scale\":1,\"y_scale\":0},\"argument2\":1}},\"list\":[1,2]}").getAsJsonObject();
        var b=JsonParser.parseString("{\"list\":[1,2],\"noise_router\":{\"final_density\":{\"argument2\":1,\"argument1\":{\"y_scale\":0,\"xz_scale\":1,\"noise\":\"minecraft:offset\",\"type\":\"minecraft:noise\"},\"type\":\"minecraft:add\"}}}").getAsJsonObject();
        assertEquals(a,b);assertArrayEquals(WorldgenJson.bytes(a),WorldgenJson.bytes(b));
        var left=new DensityFunctionReferences();var right=new DensityFunctionReferences();
        assertEquals(left.settings(a),right.settings(b));assertEquals(left.definitions(),right.definitions());
        var compress=WorldgenCodecSnapshot.class.getDeclaredMethod("compress",JsonObject.class);compress.setAccessible(true);
        var x=(WorldgenCodecSnapshot.Encoded)compress.invoke(null,a);var y=(WorldgenCodecSnapshot.Encoded)compress.invoke(null,b);
        assertArrayEquals(x.bytes(),y.bytes(),"cache fingerprints must survive registry insertion order changes");
        b.getAsJsonArray("list").set(0,new JsonPrimitive(2));
        assertFalse(java.util.Arrays.equals(WorldgenJson.bytes(a),WorldgenJson.bytes(b)),"ordered data remains part of the identity");
    }
}

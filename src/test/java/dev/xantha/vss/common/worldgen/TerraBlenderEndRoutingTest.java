package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TerraBlenderEndRoutingTest {
    static JsonObject area(String name) {
        var area = new JsonObject(); area.addProperty("size", 2);
        area.addProperty("default_biome", "minecraft:ocean");
        var entry = new JsonObject(); entry.addProperty("biome", name); entry.addProperty("weight", 7);
        var entries = new JsonArray(); entries.add(entry); area.add("biomes", entries); return area;
    }
    static JsonObject snapshot(long seed) {
        var root = new JsonObject(); root.addProperty("revision", 1); root.addProperty("seed", seed);
        root.addProperty("density_coordinates", "block");
        for (String name : new String[]{"highlands", "midlands", "edge", "islands"}) root.add(name, area("test:" + name));
        return root;
    }
    @Test void thresholdsAndCentralRadiusMatchTerraBlender() {
        var routing = new TerraBlenderEndRouting(snapshot(42), 42);
        assertEquals("minecraft:the_end", routing.sample(256, 0, 1));
        assertEquals("test:highlands", routing.sample(260, 0, Math.nextUp(.25)));
        assertEquals("test:midlands", routing.sample(260, 0, .25));
        assertEquals("test:midlands", routing.sample(260, 0, -.0625));
        assertEquals("test:edge", routing.sample(260, 0, Math.nextDown(-.0625)));
        assertEquals("test:edge", routing.sample(260, 0, -.21875));
        assertEquals("test:islands", routing.sample(260, 0, Math.nextDown(-.21875)));
        assertEquals("test:highlands", routing.sample(-257, 0, 1));
        // QuartPos.toBlock wraps in Java before SectionPos.blockToSectionCoord.
        assertTrue(TerraBlenderEndRouting.central(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertTrue(TerraBlenderEndRouting.central(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
    @Test void emptyAndZeroWeightedAreasUseOcean() {
        var root = snapshot(7);
        root.getAsJsonObject("highlands").add("biomes", new JsonArray());
        root.getAsJsonObject("islands").getAsJsonArray("biomes").get(0).getAsJsonObject().addProperty("weight", 0);
        var routing = new TerraBlenderEndRouting(root, 7);
        assertEquals("minecraft:ocean", routing.sample(4096, -1024, 1));
        assertEquals("minecraft:ocean", routing.sample(4096, -1024, -1));
    }
    @Test void duplicateBiomeIdentitiesShareTheSameZoomModeIndex() {
        var root = snapshot(42); var area = root.getAsJsonObject("highlands");
        var entries = new JsonArray();
        for (String name : new String[]{"test:a", "test:b", "test:a"}) {
            var item = new JsonObject(); item.addProperty("biome", name); item.addProperty("weight", 10); entries.add(item);
        }
        area.add("biomes", entries);
        var routing = new TerraBlenderEndRouting(root, 42);
        var expected = TerraBlenderGrid.build(42, 2, java.util.List.of(
                new TerraBlenderGrid.Region(0, 10), new TerraBlenderGrid.Region(1, 10), new TerraBlenderGrid.Region(0, 10)));
        for (int x = -2048; x <= 2048; x += 37) for (int z = -2048; z <= 2048; z += 53)
            if (!TerraBlenderEndRouting.central(x, z))
                assertEquals(expected.get(x, z) == 0 ? "test:a" : "test:b", routing.sample(x, z, 1));
    }
    @Test void malformedContractsCannotBecomeOrdinaryEndRouting() {
        assertThrows(RuntimeException.class, () -> new TerraBlenderEndRouting(snapshot(3), 4));
        for (int size : new int[]{-1, 33}) {
            var root = snapshot(3); root.getAsJsonObject("edge").addProperty("size", size);
            assertThrows(RuntimeException.class, () -> new TerraBlenderEndRouting(root, 3));
        }
        for (long weight : new long[]{-1, 2147483648L, 4294967296L}) {
            var root = snapshot(3); root.getAsJsonObject("edge").getAsJsonArray("biomes")
                    .get(0).getAsJsonObject().addProperty("weight", weight);
            assertThrows(RuntimeException.class, () -> new TerraBlenderEndRouting(root, 3));
        }
        var root = snapshot(3); root.addProperty("density_coordinates", "chunk_center");
        assertThrows(RuntimeException.class, () -> new TerraBlenderEndRouting(root, 3));
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The registry/tag transfer must reach the actual shipped native entry points. */
class RustSurfaceRegistriesNativeTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(java.nio.file.Path.of(library));
        assertTrue(RustTerrainSampler.available());
    }

    @Test void orderedReferencesAndEmptyTagsReachExactAndDisplayMaterialPlans() throws Exception {
        var registries = JsonParser.parseString("""
                {"custom_registries":{"lithostitched:surface_rule":{
                    "example:first":{"type":"minecraft:block","result_state":{"Name":"minecraft:granite"}},
                    "example:last":{"type":"minecraft:block","result_state":{"Name":"minecraft:dirt"}}}},
                 "registry_tags":{"lithostitched:surface_rule":{
                    "example:ordered":["example:last","example:first"],"example:empty":[]}}}
                """).getAsJsonObject();
        String[] sets = {"\"example:first\"", "[\"example:last\",\"example:first\"]", "\"#example:ordered\"", "\"#example:empty\""};
        String[] states = {"granite", "dirt", "dirt", "stone"};
        for (int index = 0; index < sets.length; index++) {
            var doc = LithostitchedNativeTest.document();
            RustSurfaceRegistries.copy(registries, doc);
            doc.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            var settings = doc.getAsJsonObject("settings");
            settings.addProperty("aquifers_enabled", false);
            settings.addProperty("ore_veins_enabled", false);
            settings.getAsJsonObject("noise_router").add("final_density", JsonParser.parseString("""
                    {"type":"minecraft:y_clamped_gradient","from_y":0,"to_y":128,"from_value":1,"to_value":-1}
                    """));
            settings.add("surface_rule", JsonParser.parseString("""
                    {"type":"minecraft:sequence","sequence":[
                      {"type":"lithostitched:reference","rules":%s},
                      {"type":"minecraft:block","result_state":{"Name":"minecraft:stone"}}]}
                    """.formatted(sets[index])));
            long reference = RustWorldgenBackend.create(42, 0, doc.toString());
            try {
                settings.add("surface_rule", JsonParser.parseString("""
                        {"type":"minecraft:block","result_state":{"Name":"minecraft:%s"}}
                        """.formatted(states[index])));
                long plain = RustWorldgenBackend.create(42, 0, doc.toString());
                try {
                    for (boolean display : new boolean[]{false, true})
                        assertArrayEquals(records(plain, display), records(reference, display), sets[index]);
                } finally { RustWorldgenBackend.close(plain); }
            } finally { RustWorldgenBackend.close(reference); }
        }
    }

    private static int[] records(long world, boolean display) {
        var input = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (int[] point : new int[][]{{-17,-31},{0,0},{1537,-2049},{100000,-100000}})
            input.putInt(point[0]).putInt(point[1]);
        var output = ByteBuffer.allocateDirect(4 * 40).order(ByteOrder.LITTLE_ENDIAN);
        int count = display ? RustWorldgenBackend.displayPoints(world, input, output, 4)
                : RustWorldgenBackend.surfacePoints(world, input, output, 4);
        assertEquals(4, count);
        int[] values = new int[40];
        for (int i = 0; i < values.length; i++) values[i] = output.getInt(i * 4);
        return values;
    }
}

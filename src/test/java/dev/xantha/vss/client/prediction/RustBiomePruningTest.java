package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Production JNI proof, real float climate and persisted-palette mapping. */
class RustBiomePruningTest {
    @BeforeAll static void bootstrap() {
        dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap();
        String library=System.getProperty("vss.testNativeLibrary");
        if (library!=null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }
    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test void proofCoversActualQuartQueriesAndMapsIntoReorderedJavaPalette() throws Exception {
        var doc=RustBiomeIdentityTest.document();
        long world=RustWorldgenBackend.create(1,13,doc.toString());
        var described=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
        var names=new ArrayList<String>();
        described.getAsJsonArray("biome_names").forEach(name -> names.add(name.getAsString()));
        Collections.reverse(names);
        var context=new ClientTerrainSampler(1,RustBiomeIdentityTest.PROFILE) {
            @Override List<String> cacheBiomes() { return names; }
        };
        try (var sampler=new RustTerrainSampler(world,RustBiomeIdentityTest.PROFILE,context)) {
            var possible=sampler.possibleBiomes(-18,30,-16,32);
            assertNotNull(possible);
            assertFalse(possible.isEmpty());
            assertTrue(possible.cardinality()<names.size());
            for (int x=-18;x<=-16;x++) for (int z=30;z<=32;z++) {
                var points=new int[3*9];
                for (int i=0;i<9;i++) { points[3*i]=x;points[3*i+1]=i*13-20;points[3*i+2]=z; }
                var actual=new int[9];sampler.sampleBiomeIndices(points,9,actual);
                for (int biome:actual) assertTrue(possible.get(biome));
            }
            assertEquals(possible,sampler.possibleBiomes(-18,30,-16,32));
            assertNull(sampler.possibleBiomes(7_500_000,0,7_500_001,0));
            assertNull(sampler.possibleBiomes(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0));
            assertNull(sampler.possibleBiomes(0,0,-1,0));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.possibleBiomes(
                    sampler.handle(),-18,30,-16,32,direct(0)));
            sampler.cancelWork();
            assertThrows(java.util.concurrent.CancellationException.class,()->sampler.possibleBiomes(0,0,0,0));
        }
    }

    @Test void yDependentClimateReturnsUnknownWithoutWritingOutput() throws Exception {
        var doc=RustBiomeIdentityTest.document();
        doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("temperature",
                JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-64,"
                        +"\"to_y\":320,\"from_value\":-1,\"to_value\":1}"));
        long world=RustWorldgenBackend.create(1,13,doc.toString());
        try {
            var output=direct(65536*4);output.putInt(0,0x12345678);
            assertEquals(-1,RustWorldgenBackend.possibleBiomes(world,0,0,0,0,output));
            assertEquals(0x12345678,output.getInt(0));
        } finally { RustWorldgenBackend.close(world); }
    }
}

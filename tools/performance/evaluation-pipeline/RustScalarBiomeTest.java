package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class RustScalarBiomeTest {
    @Test
    void scalarAndBatchAgreeAcrossCoordinatesWorldsModesAndCancellation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library=System.getProperty("vss.testNativeLibrary");
        if(library!=null)RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
        for(boolean modern:new boolean[]{false,true}) {
            var doc=LithostitchedNativeTest.document();
            if(modern) {
                Path root=Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen263");
                var density=JsonParser.parseString(Files.readString(root.resolve("overworld.json"))).getAsJsonObject();
                var features=JsonParser.parseString(Files.readString(root.resolve("features.json"))).getAsJsonObject();
                doc=features;
                for(String key:new String[]{"vss_density_semantics","settings","density_functions","noises"})doc.add(key,density.get(key));
            }
            for(long seed:new long[]{0,-731}) {
                long world=RustWorldgenBackend.create(seed,19,doc.toString());
                try {
                    var input=direct(256*12);var output=direct(256*4);
                    int[][] points=new int[256][3];
                    for(int i=0;i<256;i++) {
                        points[i]=new int[]{i*137-40000,i%19*13-80,32000-i*193};
                        for(int v:points[i])input.putInt(v);
                    }
                    assertEquals(256,RustWorldgenBackend.biomeIndices(world,input,output,256));
                    for(int i=255;i>=0;i--)assertEquals(output.getInt(i*4),RustWorldgenBackend.biomeIndex(world,points[i][0],points[i][1],points[i][2]));
                    for(int i=0;i<256;i++)assertEquals(output.getInt(i*4),RustWorldgenBackend.biomeIndex(world,points[i][0],points[i][1],points[i][2]));
                    for(int[] q:new int[][]{{Integer.MIN_VALUE,0,0},{0,500001,0},{0,0,7500001}})
                        assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndex(world,q[0],q[1],q[2]));
                    RustWorldgenBackend.cancel(world);
                    assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndex(world,points[0][0],points[0][1],points[0][2]));
                }finally{RustWorldgenBackend.close(world);}
                assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndex(world,0,0,0));
            }
        }
    }
    private static ByteBuffer direct(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
}

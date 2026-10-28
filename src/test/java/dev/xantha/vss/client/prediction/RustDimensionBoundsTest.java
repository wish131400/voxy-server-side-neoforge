package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
class RustDimensionBoundsTest {
    @Test void javaEditsAboveNetherNoiseHeightStayValidAndInvalidBatchesStayAtomic() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();assertTrue(RustTerrainSampler.available());
        var root=Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc=JsonParser.parseString(Files.readString(root.resolve("nether.json"))).getAsJsonObject();
        for(var e:Map.of("block_definitions","blocks","biomes","biomes","grass_colormap","grass","foliage_colormap","foliage").entrySet())
            doc.add(e.getKey(),JsonParser.parseString(Files.readString(root.resolve(e.getValue()+".json"))));
        doc.add("vss_dimension_bounds",JsonParser.parseString("{\"min_y\":0,\"height\":256}"));
        long world=RustWorldgenBackend.create(42,0,doc.toString());
        try {
            var desc=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
            var states=desc.getAsJsonArray("states");int stone=-1;
            for(int i=0;i<states.size();i++)if(states.get(i).getAsJsonObject().get("Name").getAsString().equals("minecraft:netherrack")){stone=i;break;}
            assertTrue(stone>=0);
            for(int mode:new int[]{0,1,2}) {
                long volume=RustWorldgenBackend.decorationProxy(world,0,0,mode);
                try {
                    var edits=ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN);
                    edits.putInt(1).putInt(160).putInt(1).putInt(stone);
                    assertEquals(1,RustWorldgenBackend.applyEdits(volume,edits,1));
                    edits.clear();edits.putInt(2).putInt(161).putInt(2).putInt(stone);
                    edits.putInt(2).putInt(256).putInt(2).putInt(stone);
                    var failure=assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.applyEdits(volume,edits,2));
                    assertTrue(failure.getMessage().contains("xyz="));
                    var out=ByteBuffer.allocateDirect(80*256*80*4).order(ByteOrder.LITTLE_ENDIAN);
                    RustWorldgenBackend.readVolume(volume,out);
                    assertEquals(stone,out.getInt(((33*80+33)*256+160)*4));
                    int air=out.getInt(((34*80+34)*256+160)*4);
                    assertEquals(air,out.getInt(((34*80+34)*256+161)*4),"invalid batch must not partially write");
                    assertEquals("minecraft:air",states.get(air).getAsJsonObject().get("Name").getAsString());
                } finally {RustWorldgenBackend.close(volume);}
            }
        } finally {RustWorldgenBackend.close(world);}
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The light entry and diagnostic entry must commit exactly the same transaction. */
class RustFeatureStatusTest {
    @Test
    void lightEntryKeepsBlocksJournalRandomAndFailureBehaviour() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library=System.getProperty("vss.testNativeLibrary");
        if(library!=null)RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
        boolean legacy=Boolean.getBoolean("vss.expectLegacyFeatureStatus");
        var doc=LithostitchedNativeTest.document();
        doc.add("biome_source",JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
        doc.add("possible_biomes",JsonParser.parseString("[\"minecraft:plains\"]"));
        doc.getAsJsonObject("biomes").getAsJsonObject("minecraft:plains").add("features",JsonParser.parseString("[[\"test:status\"]]"));
        doc.add("placed_features",JsonParser.parseString("""
                {"test:status":{"feature":{"type":"minecraft:simple_block","config":{"to_place":{
                  "type":"minecraft:weighted_state_provider","entries":[
                    {"data":{"Name":"minecraft:dandelion"},"weight":1},
                    {"data":{"Name":"minecraft:poppy"},"weight":2}]}}},
                  "placement":[{"type":"minecraft:count","count":16},{"type":"minecraft:in_square"},
                    {"type":"minecraft:height_range","height":{"absolute":0}}]}}
                """));
        doc.add("input_states",JsonParser.parseString("[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:dirt\"},{\"Name\":\"minecraft:dandelion\"},{\"Name\":\"minecraft:poppy\"}]"));
        for(long seed:new long[]{0,-731}) {
            long world=RustWorldgenBackend.create(seed,19,doc.toString()),a=0,b=0;
            try {
                var description=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
                var states=description.getAsJsonArray("states");int air=-1,dirt=-1;
                for(int i=0;i<states.size();i++)if(states.get(i).getAsJsonObject().get("Name").getAsString().equals("minecraft:air"))air=i;
                assertTrue(air>=0);int minY=doc.getAsJsonObject("settings").getAsJsonObject("noise").get("min_y").getAsInt();
                for(int i=0;i<states.size();i++)if(states.get(i).getAsJsonObject().get("Name").getAsString().equals("minecraft:dirt"))dirt=i;
                assertTrue(dirt>=0);
                int height=doc.getAsJsonObject("settings").getAsJsonObject("noise").get("height").getAsInt();int count=16*height*16;
                var input=direct(count*4);for(int i=0;i<count;i++)input.putInt(air);
                for(int x=0;x<16;x++)for(int z=0;z<16;z++)input.putInt(((x*16+z)*height-1-minY)*4,dirt);
                a=RustWorldgenBackend.createVolume(world,0,minY,0,16,height,16,input);
                b=RustWorldgenBackend.createVolume(world,0,minY,0,16,height,16,input);
                RustWorldgenBackend.decorationEntropy(a,42);RustWorldgenBackend.decorationEntropy(b,42);
                var expected=JsonParser.parseString(RustWorldgenBackend.placedFeature(a,"test:status",0,0,0,0)).getAsJsonObject();
                assertTrue(expected.get("placed").getAsBoolean());
                byte[] initialJournal=journal(a);
                assertTrue(initialJournal.length>0);
                if(legacy)RustWorldgenBackend.applyPlacedFeature(b,"test:status",0,0,0,0);
                else assertEquals(expected.get("placed").getAsBoolean()?1:0,RustWorldgenBackend.placedFeatureStatus(b,"test:status",0,0,0,0));
                assertArrayEquals(volume(a,count),volume(b,count));
                assertArrayEquals(initialJournal,journal(b));
                assertEquals(RustWorldgenBackend.placedFeature(a,"test:status",0,0,0,0),RustWorldgenBackend.placedFeature(b,"test:status",0,0,0,0));
                final long valid=b;
                assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.applyPlacedFeature(valid,"test:status",0,0,999,0));
                assertArrayEquals(volume(a,count),volume(b,count));
                RustWorldgenBackend.applyPlacedFeature(b,"test:status",0,0,0,0);
                RustWorldgenBackend.placedFeature(a,"test:status",0,0,0,0);
                assertArrayEquals(volume(a,count),volume(b,count));
                assertArrayEquals(journal(a),journal(b));
            } finally {if(a!=0)RustWorldgenBackend.close(a);if(b!=0)RustWorldgenBackend.close(b);RustWorldgenBackend.close(world);}
        }
    }
    private static ByteBuffer direct(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static int[] volume(long handle,int count){var out=direct(count*4);assertEquals(count,RustWorldgenBackend.readVolume(handle,out));var values=new int[count];out.asIntBuffer().get(values);return values;}
    private static byte[] journal(long handle){var out=direct(262_144*16);int count=RustWorldgenBackend.readEdits(handle,out);assertTrue(count>=0);byte[] bytes=new byte[count*16];out.get(bytes);return bytes;}
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import org.junit.jupiter.api.Test;

class PredictionTintReuseTest {
    @Test void gridTintsMatchIndependentNativeQueries() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        doc.getAsJsonObject("settings").addProperty("sea_level", -64);
        doc.getAsJsonObject("settings").addProperty("aquifers_enabled", false);
        try (var sampler = new RustTerrainSampler(RustWorldgenBackend.create(1,0,doc.toString()), profile,
                new ClientTerrainSampler(1,profile))) {
            var rows = sampler.sampleGrid(-32,-16,4,8,8);
            var out = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
            int dry=0;
            for (int i=0;i<rows.length;i++) {
                int x=-32+i%8*4,z=-16+i/8*4; var row=rows[i];
                assertEquals(3,RustWorldgenBackend.tints(sampler.handle(),x,row.surfaceY(),z,out));
                assertEquals(out.getInt(0),sampler.surfaceColor(x,row.surfaceY(),z));
                assertEquals(out.getInt(4)|0xff000000,sampler.foliageColor(x,row.surfaceY(),z));
                if(row.fluid()==0) dry++;
                assertEquals(3,RustWorldgenBackend.tints(sampler.handle(),x,row.fluidY(),z,out));
                assertEquals(out.getInt(8)|0xb2000000,sampler.waterTint(x,row.fluidY(),z));
            }
            assertTrue(dry>0);
            assertTrue(sampler.tintNativeCalls() < rows.length*2-dry);
        }
    }
    @Test void wetRecordDoesNotSeedTheOtherHeightAndReloadRejectsOldWork() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); assertTrue(RustTerrainSampler.available());
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), -64,384,"noise","minecraft:overworld",1L);
        var doc=LithostitchedNativeTest.document();doc.add("possible_biomes",new com.google.gson.JsonArray());
        try(var sampler=new RustTerrainSampler(RustWorldgenBackend.create(1,0,doc.toString()),profile,new ClientTerrainSampler(1,profile))) {
            int[] wet={40,64,1,0,0,0,0,0x123456,0x234567,0x345678};
            sampler.rememberColors(17,-19,wet,sampler.tintGeneration());
            assertEquals(0x123456,sampler.surfaceColor(17,63,-19));
            assertEquals(0,sampler.tintNativeCalls());
            var out=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
            RustWorldgenBackend.tints(sampler.handle(),17,64,-19,out);
            assertEquals(out.getInt(8)|0xb2000000,sampler.waterTint(17,64,-19));
            assertEquals(1,sampler.tintNativeCalls());
            wet[3] = ClientColumnSample.FLAG_APPROXIMATE;
            sampler.rememberColors(21,-19,wet,sampler.tintGeneration());
            RustWorldgenBackend.tints(sampler.handle(),21,63,-19,out);
            assertEquals(out.getInt(0),sampler.surfaceColor(21,63,-19));
            assertEquals(2,sampler.tintNativeCalls(),"display approximation must not seed exact tints");
        }
        var cache=new PredictionTintCache(2);long generation=cache.generation();
        cache.put(BlockPos.asLong(0,64,0),new int[]{1,2,3},generation);cache.clear();
        assertFalse(cache.put(BlockPos.asLong(0,64,0),new int[]{4,5,6},generation));
        assertNull(cache.get(BlockPos.asLong(0,64,0)));
        generation=cache.generation();cache.put(1,new int[]{1},generation);cache.put(2,new int[]{2},generation);
        assertNotNull(cache.get(1));cache.put(3,new int[]{3},generation);
        assertNull(cache.get(2));assertNotNull(cache.get(1));
    }
    @Test void collidingPackedCoordinatesKeepAllValuesAndReportLookupCost() {
        int size=4096; long[] keys=new long[size];
        var legacy=new java.util.LinkedHashMap<Long,int[]>(size,.75f,true);
        var mixed=new PredictionTintCache(size);
        for(int i=0;i<size;i++) {
            keys[i]=BlockPos.asLong(i*64,64,i);
            assertEquals(Long.hashCode(keys[0]),Long.hashCode(keys[i]));
            int[] value={i,i+1,i+2};legacy.put(keys[i],value);mixed.put(keys[i],value,0);
        }
        long oldNanos=0,newNanos=0;
        for(int round=0;round<4;round++) {
            long start=System.nanoTime();
            for(int i=0;i<size;i++) assertEquals(i,legacy.get(keys[i])[0]);
            long middle=System.nanoTime();
            for(int i=0;i<size;i++) assertEquals(i,mixed.get(keys[i])[0]);
            long end=System.nanoTime();
            if(round>0){oldNanos+=middle-start;newNanos+=end-middle;}
        }
        System.out.println("tint collision probe keys="+size+", legacyMs="+oldNanos/1e6+", mixedMs="+newNanos/1e6);
    }
}

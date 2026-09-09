package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RustGridReuseTest {
    @Test void retainedSamplesAvoidNativeWorkEvenAfterSamplerCachesAreGone() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        ClientColumnSample[] coarse;
        try (var old = new RustTerrainSampler(RustWorldgenBackend.create(1,0,doc.toString()),profile,
                new ClientTerrainSampler(1,profile))) {
            coarse = old.sampleGrid(-16,-16,8,4,4);
        }
        try (var fresh = new RustTerrainSampler(RustWorldgenBackend.create(1,0,doc.toString()),profile,
                new ClientTerrainSampler(1,profile))) {
            var retained = new ClientColumnSample[64];
            for(int z=0;z<4;z++) for(int x=0;x<4;x++) retained[z*16+x*2]=coarse[z*4+x];
            var fine = fresh.sampleGrid(-16,-16,4,8,8,retained);
            assertEquals(48,fresh.gridComputedPoints.sum(),"only missing points may cross JNI");
            assertEquals(16,fresh.gridCacheHits.sum());
            for(int z=0;z<8;z++) for(int x=0;x<8;x++)
                assertEquals(fresh.sample(-16+x*4,-16+z*4),fine[z*8+x]);
            assertNull(retained[1],"the caller's grid must remain immutable");
        }
    }

    @Test void retainedTileMappingIncludesEdgesButNeverInterpolatesMissingSamples() {
        var samples=new ClientColumnSample[33*33];
        var sample=new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        java.util.Arrays.fill(samples,sample);
        var tile=new PredictionTileManager.PredictionTile(null,new int[0],new int[0],samples,null,
                new PredictionDepthBound(64,64),0,1,32,16);
        int reused=0;
        for(int z=0;z<66;z++) for(int x=0;x<66;x++) {
            var actual=PredictionTileManager.retainedSample(tile,x,z,8);
            boolean aligned=x>=1 && z>=1 && x%2==1 && z%2==1;
            if(aligned) { assertSame(sample,actual);reused++; } else assertNull(actual);
        }
        assertEquals(1089,reused);
    }

    @Test void compareColdBorderSamplingCostWithoutChangingOutput() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc=LithostitchedNativeTest.document();
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        long[] before=new long[5],after=new long[5];
        for(int round=0;round<6;round++) {
            long oracle=RustWorldgenBackend.create(1,0,doc.toString());
            long handle=RustWorldgenBackend.create(1,0,doc.toString());
            try(var sampler=new RustTerrainSampler(handle,profile,new ClientTerrainSampler(1,profile))) {
                assertEquals(16, sampler.initialTerrainCellAxis(0));
                var outputs=new java.util.HashMap<Long,java.nio.ByteBuffer>();
                long start=System.nanoTime();
                for(int cx=-1;cx<=0;cx++) for(int cz=-1;cz<=0;cz++) {
                    var buffer=java.nio.ByteBuffer.allocateDirect(256*40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    RustWorldgenBackend.surfaceColumns(oracle,cx,cz,buffer);
                    outputs.put((long)cx<<32|cz&0xffffffffL,buffer);
                }
                long fullTime=System.nanoTime()-start;
                start=System.nanoTime();
                var grid=sampler.sampleGrid(-1,-1,1,8,8);
                long mixedTime=System.nanoTime()-start;
                for(int z=0;z<8;z++) for(int x=0;x<8;x++) {
                    int xx=x-1,zz=z-1;
                    var full=outputs.get((long)(xx>>4)<<32|(zz>>4)&0xffffffffL);
                    int offset=((xx&15)*16+(zz&15))*40;
                    assertEquals(full.getInt(offset),grid[z*8+x].surfaceY());
                    assertEquals(full.getInt(offset+4),grid[z*8+x].fluidY());
                }
                if(round>0) {before[round-1]=fullTime;after[round-1]=mixedTime;}
            } finally {RustWorldgenBackend.close(oracle);}
        }
        java.util.Arrays.sort(before);java.util.Arrays.sort(after);
        System.out.println("Cold vanilla 8x8 border, five measured rounds: full median ms="
                +before[2]/1_000_000.0+", mixed median ms="+after[2]/1_000_000.0);
    }

    @Test void nearBorderAvoidsFullNeighbourChunksAndMatchesTheirResults() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        long handle = RustWorldgenBackend.create(1, 0, doc.toString());
        try (var sampler = new RustTerrainSampler(handle, profile, new ClientTerrainSampler(1, profile))) {
            var grid=sampler.sampleGrid(-1,-1,1,8,8);
            assertEquals(1,sampler.fullChunkLoads.sum(),"only the dense interior should load a full chunk");
            assertEquals(15,sampler.gridComputedPoints.sum(),"three thin borders share sparse work");
            assertArrayEquals(grid,sampler.sampleGrid(-1,-1,1,8,8));
            assertEquals(1,sampler.fullChunkLoads.sum());
            assertEquals(15,sampler.gridComputedPoints.sum());
            var full=java.nio.ByteBuffer.allocateDirect(256*40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int cz=-1;cz<=0;cz++) for(int cx=-1;cx<=0;cx++) {
                assertEquals(256,RustWorldgenBackend.surfaceColumns(handle,cx,cz,full));
                for(int z=0;z<8;z++) for(int x=0;x<8;x++) {
                    int xx=x-1,zz=z-1;
                    if((xx>>4)!=cx||(zz>>4)!=cz) continue;
                    int offset=((xx&15)*16+(zz&15))*40;
                    var sample=grid[z*8+x];
                    assertEquals(full.getInt(offset),sample.surfaceY());
                    assertEquals(full.getInt(offset+4),sample.fluidY());
                    assertEquals(full.getInt(offset+8),sample.fluid());
                    assertEquals(full.getInt(offset+12),sample.flags());
                    int[] materials={sample.topBlockIndex(),sample.underBlockIndex(),sample.deepBlockIndex()};
                    for(int i=0;i<3;i++) assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(
                            sampler.states()[full.getInt(offset+16+i*4)].getBlock()),materials[i]);
                }
            }
            sampler.close();
            assertThrows(java.util.concurrent.CancellationException.class,()->sampler.sampleGrid(-1,-1,1,8,8));
        }
    }

    @Test void alignedRefinementOnlyComputesMissingPoints() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L);
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        long handle = RustWorldgenBackend.create(1, 0, doc.toString());
        try (var sampler = new RustTerrainSampler(handle, profile, new ClientTerrainSampler(1, profile))) {
            var coarse = sampler.sampleGrid(-16, -16, 8, 4, 4);
            assertEquals(16, sampler.gridComputedPoints.sum());
            assertArrayEquals(coarse, sampler.sampleGrid(-16, -16, 8, 4, 4));
            assertEquals(16, sampler.gridComputedPoints.sum());
            var fine = sampler.sampleGrid(-16, -16, 4, 8, 8);
            assertEquals(64, sampler.gridComputedPoints.sum());
            assertEquals(32, sampler.gridCacheHits.sum());
            for (int z=0; z<4; z++) for (int x=0; x<4; x++)
                assertEquals(coarse[z*4+x], fine[z*16+x*2]);
            for (int z=0; z<8; z++) for (int x=0; x<8; x++)
                assertEquals(sampler.sample(-16+x*4, -16+z*4), fine[z*8+x]);
        }
    }
}

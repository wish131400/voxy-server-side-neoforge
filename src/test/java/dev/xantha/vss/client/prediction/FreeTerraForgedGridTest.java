package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;

class FreeTerraForgedGridTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void fullTileFinishesEachRegionOnceAndPreservesCoordinatesCapturesAndStride() {
        for(int step : new int[]{1,2,4,16,64,128}) for(int origin : new int[]{-129,-64,-4,0,63,64}) {
            int width=18,height=19,stride=21,offset=3;
            var target=new ClientColumnSample[offset+stride*height];
            var captured=PredictionVoxelFixtures.sample(250);target[offset+stride+1]=captured;
            var regions=new HashSet<Long>();long[] current={Long.MIN_VALUE};
            var visited=new HashSet<Long>();
            assertTrue(FreeTerraForgedGrid.sample(origin,origin,step,width,height,target,offset,stride,()->true,(x,z)-> {
                long region=(long)Math.floorDiv(x,64)<<32 | Math.floorDiv(z,64)&0xffffffffL;
                if(region!=current[0]) {
                    assertTrue(regions.add(region),"a completed region must not be revisited");current[0]=region;
                }
                assertTrue(visited.add((long)x<<32 | z&0xffffffffL));
                return PredictionVoxelFixtures.sample(Math.floorMod(x*31+z,200));
            }));
            assertEquals(width*height-1,visited.size());assertSame(captured,target[offset+stride+1]);
            for(int dz=0;dz<height;dz++)for(int dx=0;dx<stride;dx++) {
                if(dx>=width)assertNull(target[offset+dz*stride+dx]);
                else if(dx!=1||dz!=1)assertEquals(Math.floorMod((origin+dx*step)*31+origin+dz*step,200),
                        target[offset+dz*stride+dx].surfaceY());
            }
        }
    }

    @Test void captureChangeStopsWithinARegionAndResumeKeepsCompletedAnswers() {
        var target=new ClientColumnSample[18*18];var queries=new AtomicInteger();
        assertFalse(FreeTerraForgedGrid.sample(-4,-4,4,18,18,target,0,18,()->queries.get()<5,(x,z)-> {
            queries.incrementAndGet();return PredictionVoxelFixtures.sample(73);
        }));
        assertEquals(5,queries.get());var completed=target.clone();
        assertTrue(FreeTerraForgedGrid.sample(-4,-4,4,18,18,target,0,18,()->true,(x,z)-> {
            queries.incrementAndGet();return PredictionVoxelFixtures.sample(74);
        }));
        assertEquals(target.length,queries.get());
        for(int i=0;i<target.length;i++)if(completed[i]!=null)assertSame(completed[i],target[i]);
    }

    @Test void managerUsesWholeTileRequestAndKeepsCaptureCancellationInsideTheRegion() throws Exception {
        var sampler=new GridSampler(true);
        try(var manager=manager(sampler)) {
            var target=new ClientColumnSample[18*18];var captured=PredictionVoxelFixtures.sample(250);target[5]=captured;
            grid(manager,target,()->sampler.queries<5);
            assertEquals(1,sampler.requests);assertEquals(0,sampler.batches);assertEquals(5,sampler.queries);
            assertSame(captured,target[5]);
            grid(manager,target,()->true);
            assertEquals(18*18-1,sampler.queries);assertEquals(2,sampler.requests);assertEquals(0,sampler.batches);
            for(var value:target)assertNotNull(value);
        }
    }

    @Test void backendsWithoutAWholeTileRequestStillReceiveSmallGrids() throws Exception {
        var sampler=new GridSampler(false);
        try(var manager=manager(sampler)) {
            grid(manager,new ClientColumnSample[18*18],()->true);
            assertEquals(1,sampler.requests);assertEquals(9,sampler.batches);assertEquals(18*18,sampler.queries);
        }
    }

    private static PredictionTileManager manager(ClientTerrainSampler sampler) {
        return new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD,sampler,
                new PredictionMemoryBudget(256L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null);
    }

    private static void grid(PredictionTileManager manager,ClientColumnSample[] target,BooleanSupplier valid) throws Exception {
        var method=PredictionTileManager.class.getDeclaredMethod("sampleGridFast",int.class,int.class,int.class,int.class,
                ClientColumnSample[].class,boolean.class,boolean.class,BooleanSupplier.class);
        method.setAccessible(true);method.invoke(manager,-64,64,4,18,target,false,false,valid);
    }

    private static final class GridSampler extends ClientTerrainSampler {
        final boolean whole;int queries,requests,batches;
        GridSampler(boolean whole) {
            super(ClientTerrainSampler.custom(42,PredictionTeleportLoadingTest.PROFILE,(x,z)->64),null);this.whole=whole;
        }
        @Override boolean supportsLodGrid(){return true;}
        @Override boolean sampleLodGridRequest(int x,int z,int step,int size,ClientColumnSample[] target,
                                               boolean preview,BooleanSupplier valid) {
            requests++;
            if(!whole)return false;
            assertEquals(-68,x);assertEquals(60,z);assertEquals(18,size);assertEquals(4,step);assertFalse(preview);
            FreeTerraForgedGrid.sample(x,z,step,size,size,target,0,size,valid,(xx,zz)->{
                queries++;return PredictionVoxelFixtures.sample(73);
            });return true;
        }
        @Override boolean sampleLodGrid(int x,int z,int step,int width,int height,ClientColumnSample[] target,
                                        int offset,int stride,boolean preview) {
            batches++;assertTrue(width<=8&&height<=8);
            return FreeTerraForgedGrid.sample(x,z,step,width,height,target,offset,stride,()->true,(xx,zz)->{
                queries++;return PredictionVoxelFixtures.sample(73);
            });
        }
    }
}

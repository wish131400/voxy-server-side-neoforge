package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionMemoryOptimizationTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void coveragePagesPreserveSettlingCoordinatesDimensionsAndRemoval() {
        var index = new PredictionExactCoverageIndex();
        long start = 123, settled = start + ExactCoverageGate.SETTLE_NANOS;
        for (int x : new int[]{-17,-16,-1,0,15,16,17}) for (int z : new int[]{-17,-1,0,16})
            assertTrue(index.confirm(Level.OVERWORLD,x,z,start));
        assertFalse(index.owns(Level.OVERWORLD,-1,-1,settled-1));
        assertTrue(index.owns(Level.OVERWORLD,-1,-1,settled));
        assertFalse(index.owns(Level.NETHER,-1,-1,settled));
        assertFalse(index.confirm(Level.OVERWORLD,-1,-1,settled));
        assertTrue(index.owns(Level.OVERWORLD,-1,-1,settled),"duplicate ingest must not restart settling");
        assertEquals(0, marked(index.snapshot(Level.OVERWORLD,0,0,32,settled-1)));
        assertEquals(28, marked(index.snapshot(Level.OVERWORLD,0,0,32,settled)));
        assertTrue(index.remove(Level.OVERWORLD,-1,-1));
        assertFalse(index.remove(Level.OVERWORLD,-1,-1));
        assertEquals(27, marked(index.snapshot(Level.OVERWORLD,0,0,32,settled)));
        int pages = index.pageCount();
        for(int i=10000;i<20000;i++) index.remove(Level.OVERWORLD,i,-i);
        assertEquals(pages,index.pageCount(),"missing results must not allocate retained pages");
        index.confirm(Level.OVERWORLD,100000,100000,start);
        index.confirm(Level.NETHER,0,0,start);
        assertTrue(index.retain(Level.OVERWORLD,0,0,64));
        assertEquals(pages,index.pageCount());
        assertEquals(27,marked(index.snapshot(Level.OVERWORLD,0,0,32,settled)));
        index.clear(); assertEquals(0,index.pageCount());
    }
    private static int marked(PredictionExactCoverageMask.Snapshot mask) {
        int count=0;for(byte b:mask.columns()) if(b!=0)count++;return count;
    }
    @Test void compactOffsetsCoverExactlyTheDiskInDistanceOrder() {
        for(int radius:new int[]{1,16,32,288}) {
            var offsets=PredictionCoverageOffsets.around(radius);var seen=new HashSet<Long>();int last=-1;
            for(int i=0;i<offsets.size();i++) {
                int x=offsets.x(i),z=offsets.z(i),d=x*x+z*z;
                assertTrue(d>=last && d<=radius*radius);last=d;
                assertTrue(seen.add(((long)x<<32)|(z&0xffffffffL)));
            }
            int expected=0;for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)if(x*x+z*z<=radius*radius)expected++;
            assertEquals(expected,offsets.size());
        }
    }
    @Test void compactionSharesOnlyFullyEqualSamplesAndPreservesEveryField() {
        var a=sample(64,0,ClientColumnSample.NO_SPAN);
        var b=sample(64,0,ClientColumnSample.NO_SPAN);
        var captured=sample(64,ClientColumnSample.FLAG_CAPTURED,ClientColumnSample.NO_SPAN);
        var cave=sample(64,0,40);
        var samples=new ClientColumnSample[]{a,b,captured,cave,sample(65,0,ClientColumnSample.NO_SPAN)};
        var before=samples.clone();
        assertEquals(4,PredictionSampleCompaction.compact(samples));
        assertArrayEquals(before,samples);assertSame(samples[0],samples[1]);
        assertNotSame(samples[0],samples[2]);assertNotSame(samples[0],samples[3]);
        assertEquals(4,PredictionSampleCompaction.compact(samples));
    }
    private static ClientColumnSample sample(int height,int flags,int bottom) {
        return new ClientColumnSample(height,0,1,2,0,0,0,0,0,flags,0,3,4,bottom,30,10,-64);
    }
    @Test void geometryLimitUnblocksOnlyWhenInputsChange() {
        var failure=new PredictionMeshFailure(1,2,64,3);
        assertTrue(failure.matches(1,2,64,3));
        assertFalse(failure.matches(2,2,64,3));assertFalse(failure.matches(1,3,64,3));
        assertFalse(failure.matches(1,2,128,3));assertFalse(failure.matches(1,2,64,4));
    }

    @Test @SuppressWarnings("unchecked") void managerDoesNotReplayUnchangedFailedWorkButResourceReloadRetries() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),42,-64,384,"noise","minecraft:overworld",1);
        var sampler = new ClientTerrainSampler(42,profile) {
            @Override public ClientColumnSample sample(int x,int z) { throw new PredictionMemoryBudget.MeshLimitException(); }
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { return sample(x,z); }
        };
        var budget = new PredictionMemoryBudget(256L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime);
        try(var manager = new PredictionTileManager(Level.OVERWORLD,sampler,budget,null)) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,manager.layout().levelCount()-1);
            var desiredField=PredictionTileManager.class.getDeclaredField("desiredKeys");desiredField.setAccessible(true);
            ((Set<Object>)desiredField.get(manager)).add(key);
            var enqueue=PredictionTileManager.class.getDeclaredMethod("enqueue",key.getClass(),int.class,int.class,boolean.class);
            enqueue.setAccessible(true);
            enqueue.invoke(manager,key,0,0,false);
            long deadline=System.nanoTime()+5_000_000_000L;
            while(manager.pendingCount()!=0 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(0,manager.pendingCount());assertEquals(1,manager.failedTileCount());
            var retry=PredictionTileManager.class.getDeclaredMethod("retryReady",key.getClass());retry.setAccessible(true);
            assertEquals(false,retry.invoke(manager,key));
            for(int i=0;i<10;i++)enqueue.invoke(manager,key,0,0,false);
            assertEquals(1,manager.failedTileCount());assertEquals(0,manager.pendingCount());
            manager.invalidateAppearance();
            assertEquals(true,retry.invoke(manager,key));
            enqueue.invoke(manager,key,0,0,false);
            deadline=System.nanoTime()+5_000_000_000L;
            while(manager.pendingCount()!=0 && System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(2,manager.failedTileCount());assertEquals(0,budget.usedBytes());
        }
    }
}

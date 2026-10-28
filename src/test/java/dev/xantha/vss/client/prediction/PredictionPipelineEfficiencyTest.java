package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.VoxySectionData;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionPipelineEfficiencyTest {
    @TempDir Path root;
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    @Test void coordinateInvalidationRejectsInflightReadsAndFindsUnloadedSettingsNamespaces() throws Exception {
        var historical=PredictionDiskCache.Key.surface(-1,-1,3);
        var future=PredictionDiskCache.Key.surface(-1,-1,16387);
        var outside=PredictionDiskCache.Key.surface(2,-1,16387);
        try(var cache=new PredictionDiskCache(root,77)) {
            for(var key:List.of(historical,future,outside))try(var lease=cache.lease(key)) {
                assertTrue(cache.writeSurface(lease,Map.of()));
            }
        }
        var field=PredictionDiskCache.class.getDeclaredField("COMMITS");field.setAccessible(true);
        var io=(ThreadPoolExecutor)field.get(null);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        try(var cache=new PredictionDiskCache(root,77);var old=cache.lease(future)) {
            io.execute(()->{entered.countDown();try {resume.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try {
                cache.invalidateChunk(-1,-1);
                assertFalse(old.valid());
                try(var blocked=cache.lease(historical)) {assertFalse(blocked.readable);assertNull(cache.readSurface(blocked));}
                var sharedField=PredictionDiskCache.class.getDeclaredField("shared");sharedField.setAccessible(true);
                var shared=sharedField.get(cache);
                var surfaces=shared.getClass().getDeclaredField("surfaceInvalidations");surfaces.setAccessible(true);
                var keys=shared.getClass().getDeclaredField("invalidations");keys.setAccessible(true);
                assertEquals(25,((Map<?,?>)surfaces.get(shared)).size());
                assertTrue(((Map<?,?>)keys.get(shared)).size()<256,"No historical settings expansion on the caller thread");
            } finally {resume.countDown();}
            cache.flush();
            try(var late=cache.lease(future)) {assertNull(cache.readSurface(late));assertTrue(cache.writeSurface(late,Map.of()));}
        } finally {resume.countDown();}
        try(var cache=new PredictionDiskCache(root,77)) {
            try(var lease=cache.lease(historical)) {assertNull(cache.readSurface(lease));}
            for(var key:List.of(future,outside))try(var lease=cache.lease(key)) {assertEquals(Map.of(),cache.readSurface(lease));}
        }
    }
    @Test void coldDeletionDoesNotPopulateTheReadHandleCacheAndKeepsOtherSlots() throws Exception {
        var selected=PredictionDiskCache.Key.surface(-1,-1,3);
        var neighbor=PredictionDiskCache.Key.surface(-2,-1,3);
        try(var cache=new PredictionDiskCache(root,77)) {
            for(var key:List.of(selected,neighbor))try(var lease=cache.lease(key)) {assertTrue(cache.writeSurface(lease,Map.of()));}
        }
        try(var storage=new PredictionRegionStorage(root)) {
            assertTrue(storage.deleteAll(List.of(selected)).isEmpty());
            var field=PredictionRegionStorage.class.getDeclaredField("open");field.setAccessible(true);
            assertTrue(((Map<?,?>)field.get(storage)).isEmpty());
            assertNull(storage.read(selected));assertNotNull(storage.read(neighbor));
        }
    }
    @Test void immutableFaceRunsDetachScratchAndReconstructRandomAndUniformPlanes() {
        var random=new Random(9981);
        for(int trial=0;trial<30;trial++) {
            var face=new long[1024];for(int i=0;i<face.length;i++)face[i]=trial%2==0 ? 19 : random.nextLong();
            var expected=face.clone();var compact=VoxySectionData.compactFaceCopy(face);
            Arrays.fill(face,0);compact.copyTo(face);assertArrayEquals(expected,face);
            assertArrayEquals(expected,compact.array());
            assertTrue(compact.bytes()<=8272);
        }
    }
    @Test void lazyPageTreesShareAcrossTranslationsAndPreserveExtremeQueries() throws Exception {
        var a=new PredictionVoxyBoxes.Builder();var b=new PredictionVoxyBoxes.Builder();
        for(int i=0;i<1024;i++) {
            int x=i%32,y=i%7,z=i/32;
            a.add(x,y,z,x+2,y+3,z+2,Blocks.OAK_LEAVES.defaultBlockState());
            b.add(x-1024,y,z+4096,x-1022,y+3,z+4098,Blocks.OAK_LEAVES.defaultBlockState());
        }
        var first=a.build();var second=b.build();var data=first.pageStorage(0);
        var field=data.getClass().getDeclaredField("index");field.setAccessible(true);
        // Use a new immutable layout identity: publication itself must not build trees.
        assertNull(field.get(data));first.index();second.index();assertNull(field.get(data));
        assertArrayEquals(first.index().intersect(3,1,1,8,6,5),second.index().intersect(-1021,1,4097,-1016,6,4101));
        assertNotNull(field.get(data));assertSame(data,second.pageStorage(0));
        var wide=PredictionVoxyBoxes.of(List.of(new PredictionVoxySource.Box(Integer.MIN_VALUE,-10,-10,
                Integer.MAX_VALUE,10,10,Blocks.STONE.defaultBlockState())));
        assertArrayEquals(new int[]{0},wide.index().intersect(-1,-1,-1,1,1,1));
    }
    @Test void dirtyRevisionAgeIsIndependentOfDemandAgeAndPublicationResetsIt() {
        var trace=new PredictionDetailTrace(1_000_000_000L,false,0);
        trace.dirty(10_000_000_000L,1);trace.event("cpu-budget",11_000_000_000L);
        var row=trace.snapshot(12_000_000_000L,true);
        assertEquals(11d,row.get("demandAgeSeconds"));assertEquals(2d,row.get("dirtyAgeSeconds"));
        assertEquals("cpu-budget",row.get("lastEvent"));assertEquals(1L,row.get("contentRevision"));
        trace.dirty(13_000_000_000L,2);
        assertEquals(1d,trace.snapshot(14_000_000_000L,true).get("dirtyAgeSeconds"));
        trace.published(15_000_000_000L,2);
        assertEquals(0d,trace.snapshot(20_000_000_000L,false).get("dirtyAgeSeconds"));
        assertEquals(19d,trace.snapshot(20_000_000_000L,false).get("demandAgeSeconds"));
    }
    @Test void failedNamespaceInventoryKeepsTheCoordinateBlockedAcrossReopen() throws Exception {
        var key=PredictionDiskCache.Key.surface(0,0,3);
        // A regular file in place of the root forces directory discovery to fail.
        java.nio.file.Files.writeString(root.resolve("blocked"),"not a directory");
        Path broken=root.resolve("blocked");
        try(var cache=new PredictionDiskCache(broken,77)) {
            cache.invalidateChunk(0,0);cache.flush();
            try(var lease=cache.lease(key)){assertFalse(lease.readable);}
        }
        try(var cache=new PredictionDiskCache(broken,77);var lease=cache.lease(key)) {
            assertFalse(lease.readable);assertNull(cache.readSurface(lease));
        }
    }
    @Test void anAcceptedReplacementWriteWaitsForCoordinateDeletion() throws Exception {
        var key=PredictionDiskCache.Key.surface(0,0,3);
        var field=PredictionDiskCache.class.getDeclaredField("COMMITS");field.setAccessible(true);
        var io=(ThreadPoolExecutor)field.get(null);
        var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        try(var cache=new PredictionDiskCache(root,77);var old=cache.lease(key)) {
            io.execute(()->{entered.countDown();try{resume.await(10,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try {
                var stale=cache.writeSurfaceLater(old,Map.of(),false);
                cache.invalidateChunk(0,0);
                try(var fresh=cache.lease(key)) {
                    var result=cache.writeSurfaceLater(fresh,Map.of(),false);
                    resume.countDown();assertTrue(result.get(10,TimeUnit.SECONDS));assertFalse(stale.get(10,TimeUnit.SECONDS));
                }
            } finally {resume.countDown();}
            try(var read=cache.lease(key)){assertEquals(Map.of(),cache.readSurface(read));}
        } finally {resume.countDown();}
    }
    @Test void coldIndexReplayMeasuresConstructionAndChecksQueriesAgainstTheFullTree() {
        var builder=new PredictionVoxyBoxes.Builder();
        for(int i=0;i<16384;i++) {
            int x=i%128,y=64+i%13,z=i/128;
            builder.add(x,y,z,x+2,y+3,z+2,Blocks.OAK_LEAVES.defaultBlockState());
        }
        var boxes=builder.build();
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long id=Thread.currentThread().getId(),start=bean.getThreadAllocatedBytes(id),time=System.nanoTime();
        var flat=new PredictionVoxyFeatures(boxes,boxes.size());
        long flatNanos=System.nanoTime()-time,flatBytes=bean.getThreadAllocatedBytes(id)-start;
        start=bean.getThreadAllocatedBytes(id);time=System.nanoTime();
        var paged=new PredictionVoxyFeatures(boxes);
        long pagedNanos=System.nanoTime()-time,pagedBytes=bean.getThreadAllocatedBytes(id)-start;
        for(int x=-5;x<128;x+=7)for(int z=-5;z<128;z+=11)
            assertArrayEquals(flat.intersect(x,60,z,x+8,82,z+8),paged.intersect(x,60,z,x+8,82,z+8));
        assertTrue(pagedBytes<flatBytes/4,"Cold construction must avoid allocating the complete tree");
        System.out.printf(Locale.ROOT,"COLD_INDEX_REPLAY boxes=%d flatBytes=%d pagedBytes=%d flatMs=%.3f pagedMs=%.3f%n",
                boxes.size(),flatBytes,pagedBytes,flatNanos/1e6,pagedNanos/1e6);
    }
}

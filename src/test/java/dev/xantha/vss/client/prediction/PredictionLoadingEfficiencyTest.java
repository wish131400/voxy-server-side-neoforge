package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionLoadingEfficiencyTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static ClientColumnSample sample(int y,int fluid,int flags) {
        return new ClientColumnSample(y,y+4,2,3,4,5,6,7,fluid,flags|ClientColumnSample.FLAG_CAPTURED,
                8,9,10,-10,-20,-30,-40);
    }
    private static PredictionCapturedTerrain.Node node(int y) {
        var columns=new ClientColumnSample[256];Arrays.fill(columns,sample(y,0,0));
        return new PredictionCapturedTerrain.Node(columns,null);
    }
    private static PredictionDiskCache disk() {
        var disk=mock(PredictionDiskCache.class);
        when(disk.hasCapturedTerrain()).thenReturn(true);when(disk.hasCapturedRegion(any())).thenReturn(true);
        return disk;
    }
    private static void waitFor(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10,TimeUnit.SECONDS),"blocked test operation was not released");
    }
    private static void save(PredictionCapturedTerrain store,int x,PredictionCapturedTerrain.Node node) throws Exception {
        var save=PredictionCapturedTerrain.class.getDeclaredMethod("save",PredictionDiskCache.Key.class,PredictionCapturedTerrain.Node.class);
        save.setAccessible(true);save.invoke(store,new PredictionDiskCache.Key(3,x,0,0),node);
    }
    @Test void concurrentMissesShareOneDecodeAndClearDoesNotResurrectIt() throws Exception {
        var disk=disk();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var old=node(64);var newer=node(80);var reads=new AtomicInteger();
        when(disk.readCapturedNode(any())).thenAnswer(invocation->{
            if(reads.getAndIncrement()==0){entered.countDown();waitFor(resume);return old;}return newer;
        });
        var store=new PredictionCapturedTerrain(disk);var pool=Executors.newFixedThreadPool(8);
        try {
            var first=pool.submit(()->store.exact(0,0));waitFor(entered);
            var waiting=new ArrayList<Future<ClientColumnSample>>();
            for(int i=0;i<7;i++)waiting.add(pool.submit(()->store.exact(0,0)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!store.diagnostics().contains("coalescedReads=7") && System.nanoTime()<deadline)Thread.sleep(1);
            assertTrue(store.diagnostics().contains("coalescedReads=7"));assertEquals(1,reads.get());
            store.clear();assertEquals(80,store.exact(0,0).surfaceY());
            resume.countDown();assertNull(first.get(10,TimeUnit.SECONDS));
            for(var value:waiting)assertNull(value.get(10,TimeUnit.SECONDS));
            assertEquals(80,store.exact(0,0).surfaceY());assertEquals(2,reads.get());
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test void aNewCaptureWinsOverAnOlderDiskReadEvenAfterTheNewEntryIsEvicted() throws Exception {
        var disk=disk();var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var old=node(64);
        when(disk.writeCapturedNode(any(),any())).thenReturn(true);
        when(disk.readCapturedNode(any())).thenAnswer(invocation->{entered.countDown();waitFor(resume);return old;});
        var store=new PredictionCapturedTerrain(disk,30_000);var pool=Executors.newSingleThreadExecutor();
        try {
            var read=pool.submit(()->store.exact(0,0));waitFor(entered);
            save(store,0,node(80));save(store,1,node(96));
            resume.countDown();assertEquals(80,read.get(10,TimeUnit.SECONDS).surfaceY());
            assertTrue(store.bytes()<=30_000);verify(disk,times(1)).readCapturedNode(any());
        } finally {resume.countDown();pool.shutdownNow();}
    }
    @Test void directSummaryMatchesThePreviousPerColumnReductionForAirSnowIceAndFluids() {
        var random=new Random(1729);
        for(int round=0;round<100;round++) {
            var columns=new ClientColumnSample[256];var previous=new PredictionCapturedTerrain.Summary[256];
            for(int i=0;i<columns.length;i++) {
                int flags=(random.nextBoolean()?ClientColumnSample.FLAG_NO_SURFACE:0)
                        |(random.nextBoolean()?ClientColumnSample.FLAG_ICE:0)|(random.nextBoolean()?ClientColumnSample.FLAG_SNOW:0);
                columns[i]=sample(random.nextInt(400)-64,random.nextInt(3),flags);
                previous[i]=PredictionCapturedTerrain.Summary.of(columns[i]);
            }
            assertEquals(PredictionCapturedTerrain.Summary.combine(previous),PredictionCapturedTerrain.Summary.columns(columns));
        }
    }
    @Test void fusedDecodePreservesEveryFieldAndMapsEachVolumeMaterial() throws Exception {
        for(var volume:new PredictionColumnVolume[]{null,new PredictionColumnVolume(new int[0]),
                new PredictionColumnVolume(new int[]{-64,-10,1,0,20,64,2,1})}) {
            var s=sample(64,2,ClientColumnSample.FLAG_ICE);
            s=new ClientColumnSample(s.surfaceY(),s.fluidY(),s.biomeIndex(),s.topBlockIndex(),s.structureIndex(),s.treeKind(),
                    s.treeDensity(),s.treeHeight(),s.fluid(),s.flags(),s.groundFeatureKind(),s.underBlockIndex(),s.deepBlockIndex(),
                    s.surfaceBottom(),s.lowerTop(),s.lowerBottom(),s.spanFloor(),volume);
            var bytes=new ByteArrayOutputStream();PredictionSampleStore.writeSample(new DataOutputStream(bytes),s);
            PredictionSampleStore.IdMapper biome=id->id+100,block=id->id+1000;
            var expected=PredictionSampleStore.remap(s,biome,block);
            var actual=PredictionSampleStore.readSample(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),true,biome,block);
            assertEquals(expected,actual);
            assertThrows(IOException.class,()->PredictionSampleStore.readSample(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),true,
                    biome,id->{throw new IOException("invalid palette");}));
            var legacy=Arrays.copyOf(bytes.toByteArray(),17*4);
            assertEquals(PredictionSampleStore.remap(PredictionSampleStore.readSample(new DataInputStream(new ByteArrayInputStream(legacy)),false),biome,block),
                    PredictionSampleStore.readSample(new DataInputStream(new ByteArrayInputStream(legacy)),false,biome,block));
        }
    }
    @Test void identicalContentSharesOneIndexBeforePublicationIncludingConcurrentBuilders() throws Exception {
        var boxes=new ArrayList<PredictionVoxySource.Box>();
        for(int i=0;i<4096;i++)boxes.add(new PredictionVoxySource.Box(i%64,64,i/64,i%64+1,70,i/64+1,Blocks.OAK_LEAVES.defaultBlockState()));
        var first=PredictionVoxyFeatures.of(boxes);var pool=Executors.newFixedThreadPool(4);
        try {
            var calls=new ArrayList<Future<PredictionVoxyFeatures>>();
            for(int i=0;i<16;i++)calls.add(pool.submit(()->PredictionVoxyFeatures.of(new ArrayList<>(boxes))));
            for(var call:calls)assertSame(first,call.get(10,TimeUnit.SECONDS));
            boxes.set(0,new PredictionVoxySource.Box(0,64,0,1,70,1,Blocks.OAK_LOG.defaultBlockState()));
            assertNotSame(first,PredictionVoxyFeatures.of(boxes));
        } finally {pool.shutdownNow();}
    }
    @Test void indexQueriesKeepOriginalOrderWithExtremeCoordinatesAndDuplicateCenters() {
        var boxes=new ArrayList<PredictionVoxySource.Box>();
        for(int i=0;i<2048;i++) {
            int x=i%2==0?Integer.MIN_VALUE+i:Integer.MAX_VALUE-i-8;
            boxes.add(new PredictionVoxySource.Box(x,-64,-8,x+8,128,8,Blocks.STONE.defaultBlockState()));
            boxes.add(new PredictionVoxySource.Box(-8,-64,-8,8,128,8,Blocks.OAK_LOG.defaultBlockState()));
        }
        var index=PredictionVoxyFeatures.of(boxes);var hits=new it.unimi.dsi.fastutil.ints.IntArrayList();
        for(int[] query:new int[][]{{Integer.MIN_VALUE,-64,-8,Integer.MIN_VALUE+100,128,8},
                {Integer.MAX_VALUE-100,-64,-8,Integer.MAX_VALUE,128,8},{-1,0,-1,1,1,1},{100,0,100,101,1,101}}) {
            var expected=new it.unimi.dsi.fastutil.ints.IntArrayList();
            for(int i=0;i<boxes.size();i++) {
                var b=boxes.get(i);
                if(b.minX()<query[3] && b.maxX()>query[0] && b.minY()<query[4] && b.maxY()>query[1]
                        && b.minZ()<query[5] && b.maxZ()>query[2])expected.add(i);
            }
            index.intersect(query[0],query[1],query[2],query[3],query[4],query[5],hits);
            assertArrayEquals(expected.toIntArray(),hits.toIntArray());
        }
    }
}

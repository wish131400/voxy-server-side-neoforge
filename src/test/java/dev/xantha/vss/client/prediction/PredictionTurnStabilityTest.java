package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionVoxySource.Detail;

class PredictionTurnStabilityTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void incrementalFamiliesMatchIndependentOverlapChecksThroughContentAndTopologyChanges() {
        var index=new PredictionScopeFamilies.Index();
        var residents=new HashMap<PredictionTileKey,Boolean>();var random=new Random(82712026);
        for(int step=0;step<1200;step++) {
            var key=new PredictionTileKey(random.nextBoolean()?Level.OVERWORLD:Level.NETHER,
                    random.nextInt(16)-8,random.nextInt(16)-8,random.nextInt(7));
            if(random.nextInt(4)==0) {residents.remove(key);index.remove(key);}
            else {boolean scoped=random.nextInt(4)==0;residents.put(key,scoped);index.put(key,scoped);}
            if(step%30==0)assertEquals(overlaps(residents),index.snapshot());
        }
        assertEquals(overlaps(residents),index.snapshot());
        var snapshot=index.snapshot();
        residents.forEach(index::put);
        assertSame(snapshot,index.snapshot(),"Content replacement must reuse unchanged family identity");
        for(var key:List.copyOf(residents.keySet()))index.remove(key);
        assertTrue(index.snapshot().isEmpty());
        index.clear();assertTrue(index.snapshot().isEmpty());
    }
    private static Set<PredictionTileKey> overlaps(Map<PredictionTileKey,Boolean> tiles) {
        var result=new HashSet<PredictionTileKey>();
        tiles.forEach((scope,isScope)->{
            if(!isScope)return;
            for(var key:tiles.keySet()) {
                var parent=key.lod()>=scope.lod()?key:scope;
                var child=key.lod()>=scope.lod()?scope:key;int shift=parent.lod()-child.lod();
                if(parent.dimension().equals(child.dimension()) && (child.tileX()>>shift)==parent.tileX()
                        && (child.tileZ()>>shift)==parent.tileZ())result.add(key);
            }
        });return result;
    }

    @Test void snapshotsTrackSameSizeChangesAndEveryMutableViewWithoutTouchingOldCopies() throws Exception {
        var copies=new PredictionInputSnapshots();Set<Integer> set=PredictionInputSnapshots.concurrentSet();
        Map<Integer,Integer> map=PredictionInputSnapshots.concurrentMap();
        for(int n=0;n<10000;n++) {set.add(n);map.put(n,n);}
        var firstSet=copies.set(set);var firstMap=copies.map(map);
        for(int n=0;n<100;n++) {assertSame(firstSet,copies.set(set));assertSame(firstMap,copies.map(map));}
        set.remove(1);set.add(-1);map.compute(1,(k,v)->-1);
        assertNotSame(firstSet,copies.set(set));assertNotSame(firstMap,copies.map(map));
        assertTrue(firstSet.contains(1));assertFalse(firstSet.contains(-1));assertEquals(1,firstMap.get(1));
        set.removeIf(n->n<0);map.keySet().removeIf(n->n==0);
        map.values().remove(-1);map.entrySet().iterator().next().setValue(-2);
        assertEquals(new HashSet<>(set),copies.set(set));assertEquals(new HashMap<>(map),copies.map(map));
        var pool=Executors.newSingleThreadExecutor();
        try {pool.submit(()->{for(int n=10000;n<11000;n++){set.add(n);map.merge(n,n,Integer::sum);}}).get(5,TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
        assertEquals(new HashSet<>(set),copies.set(set));assertEquals(new HashMap<>(map),copies.map(map));
        map.replaceAll((k,v)->v+1);assertEquals(new HashMap<>(map),copies.map(map));
        set.clear();map.clear();assertTrue(copies.set(set).isEmpty());assertTrue(copies.map(map).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->firstSet.clear());
        assertThrows(UnsupportedOperationException.class,()->firstMap.clear());
    }

    @Test void backgroundPriorityReplaysArrivalsCompletionRemovalAndCloseWithoutResurrectingWork() {
        var tasks=new ArrayDeque<Runnable>();var q=new PredictionVoxyDetailQueue(3,tasks::add);
        var a=new Detail(0,0,0);var b=new Detail(10,0,0);var c=new Detail(20,0,0);var d=new Detail(30,0,0);
        q.request(a,false);q.request(b,false);q.request(c,false);q.drainChanges();
        q.priorityAsync(1920,0,key->PredictionWorkView.VISIBLE);
        assertEquals(1,tasks.size(),"Sorting must be deferred to the preparation executor");
        q.request(a,true);q.removeIf(b::equals);q.request(d,false);
        tasks.remove().run();q.pollPriority();
        assertEquals(Set.of(a,c,d),q.keys());assertEquals(2,q.pending());assertEquals(1,q.residents());
        q.request(new Detail(29,0,0),false);q.request(new Detail(28,0,0),false);
        assertFalse(q.keys().contains(c),"New camera ranking must replace the old worst pending key");
        assertTrue(q.keys().contains(a),"Completed residents never rejoin pending sorting");
        q.priorityAsync(-1000,0,key->PredictionWorkView.BACKGROUND);q.clear();
        tasks.remove().run();q.pollPriority();assertTrue(q.keys().isEmpty());
    }
    @Test void aFailedSortRetriesAndRapidCameraChangesEventuallyPublishTheLatestPriority() {
        var tasks=new ArrayDeque<Runnable>();var q=new PredictionVoxyDetailQueue(2,tasks::add);
        var left=new Detail(0,0,0);var right=new Detail(20,0,0);
        q.request(left,false);q.request(right,false);
        var fails=new java.util.concurrent.atomic.AtomicBoolean(true);
        q.priorityAsync(1280,0,key->{if(fails.getAndSet(false))throw new IllegalStateException("injected rank failure");return 0;});
        tasks.remove().run();q.pollPriority();assertEquals(1,tasks.size(),"Failed background ranking must remain retryable");
        q.priorityAsync(0,0,key->0);
        tasks.remove().run();q.pollPriority();assertEquals(1,tasks.size(),"A newer camera view must get its own preparation");
        tasks.remove().run();q.pollPriority();assertTrue(tasks.isEmpty());
        q.request(new Detail(1,0,0),false);
        assertEquals(Set.of(left,new Detail(1,0,0)),q.keys());
    }

    private static volatile Object consumed;
    @Test void compareUnchangedInputSnapshotCostWithoutTimingAssertions() {
        var snapshots=new PredictionInputSnapshots();
        Set<Integer> raw=ConcurrentHashMap.newKeySet(), tracked=PredictionInputSnapshots.concurrentSet();
        for(int n=0;n<10000;n++) {raw.add(n);tracked.add(n);}
        assertEquals(snapshots.set(raw),snapshots.set(tracked));
        var before=new ArrayList<Double>();var after=new ArrayList<Double>();
        for(int iteration=0;iteration<12;iteration++) {
            long start=System.nanoTime();
            for(int repeat=0;repeat<100;repeat++)consumed=snapshots.set(raw);
            long middle=System.nanoTime();
            for(int repeat=0;repeat<100;repeat++)consumed=snapshots.set(tracked);
            long end=System.nanoTime();
            if(iteration>=5) {before.add((middle-start)/1e6);after.add((end-middle)/1e6);}
        }
        Collections.sort(before);Collections.sort(after);
        System.out.printf(Locale.ROOT,"UNCHANGED_INPUT entries=%d reads=100 equalsMedianMs=%.3f trackedMedianMs=%.3f (synthetic; not FPS)%n",
                raw.size(),before.get(3),after.get(3));
    }

}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PredictionTileStatesTest {
    @Test void iteratorPrefetchCannotRemoveTheNextOrRecycledIdentity() {
        var store=new PredictionTileStates<Integer>(0,1);var values=store.values(0);
        values.put(1,1L);values.put(2,2L);var it=values.entrySet().iterator();var first=it.next();
        assertTrue(it.hasNext());it.remove();assertFalse(values.containsKey(first.getKey()));
        assertEquals(1,values.size());
        it=values.entrySet().iterator();var stale=it.next();values.clear();values.put(stale.getKey(),77L);it.remove();
        assertEquals(77L,values.get(stale.getKey()));
    }
    @Test void concurrentTimestampIterationNeverPairsOldKeysWithRecycledValues() throws Exception {
        var states=new PredictionTileStates<Integer>(0,1);var values=states.values(0);
        var pool=Executors.newSingleThreadExecutor();var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            var writer=pool.submit(()->{for(int round=0;round<600;round++) {
                for(int k=0;k<256;k++){int key=round*256+k;values.put(key,(long)key);}
                values.clear();
            }running.set(false);});
            do {for(var entry:values.entrySet())assertEquals(entry.getKey().longValue(),entry.getValue().longValue());}while(running.get());
            writer.get(15,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
    }
    @Test void flagsAndTimestampsShareIdentityButKeepIndependentLifetimes() {
        var states=new PredictionTileStates<String>(3,2);
        var dirty=states.flag(0);var pending=states.flag(1);var complete=states.flag(2);
        var captures=states.values(0);var retries=states.values(1);
        dirty.add("a");pending.add("a");complete.add("a");captures.put("a",0L);retries.put("a",Long.MIN_VALUE);
        assertEquals(1,states.identities());assertEquals(0L,captures.get("a"));
        long old=states.handle("a");dirty.remove("a");pending.remove("a");
        assertTrue(complete.contains("a"));assertTrue(states.current("a",old));
        complete.remove("a");captures.clear();assertEquals(Long.MIN_VALUE,retries.get("a"));
        retries.clear();assertEquals(0,states.identities());assertFalse(states.current("a",old));
        dirty.add("a");assertNotEquals(old,states.handle("a"));
        for(int i=0;i<1000;i++){pending.add("temporary");pending.remove("temporary");}
        assertEquals(256,states.slotCapacity(),"Freed slots must be reused across moves/retries");
    }
    @Test void snapshotsKeepOldValuesAcrossSlotReuseAndReuseUnchangedPages() {
        var states=new PredictionTileStates<Integer>(2,1);var input=new PredictionInputSnapshots();
        var dirty=states.flag(0);var pending=states.flag(1);var epochs=states.values(0);
        dirty.add(1);epochs.put(1,7L);
        var old=input.set(dirty);var values=input.map(epochs);var keys=input.keys(epochs);
        assertSame(old,input.set(dirty));assertSame(values,input.map(epochs));assertSame(keys,input.keys(epochs));
        epochs.put(1,8L);assertSame(keys,input.keys(epochs));
        pending.add(1);assertSame(old,input.set(dirty));
        epochs.clear();dirty.clear();pending.clear();dirty.add(2);epochs.put(2,9L);
        assertEquals(Set.of(1),old);assertEquals(Map.of(1,7L),values);assertEquals(Set.of(1),keys);
        assertEquals(Set.of(2),input.set(dirty));assertEquals(Map.of(2,9L),input.map(epochs));
        var union=new PredictionInputSnapshots.Union<Integer>();
        assertEquals(Set.of(2),union.snapshot(input.set(dirty),input.set(pending),Set.of()));
        assertThrows(UnsupportedOperationException.class,()->old.add(4));
    }
    @Test void collectionOperationsMatchReferenceThroughRandomLifecycleChanges() {
        var states=new PredictionTileStates<Integer>(4,2);var snapshots=new PredictionInputSnapshots();
        var sets=new ArrayList<Set<Integer>>();var maps=new ArrayList<Map<Integer,Long>>();
        for(int i=0;i<4;i++)sets.add(new HashSet<>());for(int i=0;i<2;i++)maps.add(new HashMap<>());
        var random=new Random(1947);
        for(int n=0;n<12000;n++) {
            int key=random.nextInt(1500),column=random.nextInt(4);long value=random.nextInt(100);
            switch(random.nextInt(8)) {
                case 0,1 -> assertEquals(sets.get(column).add(key),states.flag(column).add(key));
                case 2 -> assertEquals(sets.get(column).remove(key),states.flag(column).remove(key));
                case 3 -> {int c=column%2;assertEquals(maps.get(c).put(key,value),states.values(c).put(key,value));}
                case 4 -> {int c=column%2;assertEquals(maps.get(c).remove(key),states.values(c).remove(key));}
                case 5 -> {int c=column%2;assertEquals(maps.get(c).merge(key,value,Long::sum),states.values(c).merge(key,value,Long::sum));}
                case 6 -> {if(n%41==0){sets.get(column).removeIf(k->k%3==0);states.flag(column).removeIf(k->k%3==0);}}
                default -> {int c=column%2;assertEquals(maps.get(c).remove(key,value),states.values(c).remove(key,value));}
            }
            if(n%97==0) {
                for(int c=0;c<4;c++){assertEquals(sets.get(c),states.flag(c));assertEquals(sets.get(c),snapshots.set(states.flag(c)));}
                for(int c=0;c<2;c++){assertEquals(maps.get(c),states.values(c));assertEquals(maps.get(c),snapshots.map(states.values(c)));}
                var identities=new HashSet<Integer>();sets.forEach(identities::addAll);maps.forEach(m->identities.addAll(m.keySet()));
                assertEquals(identities.size(),states.identities());
            }
        }
        states.values(0).replaceAll((k,v)->v+1);maps.get(0).replaceAll((k,v)->v+1);
        assertEquals(maps.get(0),states.values(0));
        states.values(0).entrySet().removeIf(e->e.getKey()%2==0);maps.get(0).entrySet().removeIf(e->e.getKey()%2==0);
        assertEquals(maps.get(0),states.values(0));
    }
    @Test void concurrentReservationsHaveOneWinnerAndNeverMixRecycledSlots() throws Exception {
        var states=new PredictionTileStates<Integer>(2,1);var pending=states.flag(0);var complete=states.flag(1);
        var epochs=states.values(0);var input=new PredictionInputSnapshots();
        var workers=Executors.newFixedThreadPool(4);
        try {
            for(int round=0;round<30;round++) {
                var start=new CountDownLatch(1);var jobs=new ArrayList<Future<Integer>>();
                for(int t=0;t<4;t++)jobs.add(workers.submit(()->{start.await();int won=0;
                    for(int i=0;i<2000;i++)if(pending.add(i)){won++;epochs.put(i,(long)i);complete.add(i);}
                    return won;}));
                start.countDown();int winners=0;for(var job:jobs)winners+=job.get(15,TimeUnit.SECONDS);
                assertEquals(2000,winners);assertEquals(2000,pending.size());
                assertEquals(2000,input.set(complete).size());
                epochs.forEach((key,value)->assertEquals(key.longValue(),value.longValue()));
                pending.clear();complete.clear();epochs.clear();assertEquals(0,states.identities());
            }
            assertEquals(2048,states.slotCapacity());
        } finally {workers.shutdownNow();}
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionInputSnapshotsTest {
    private static final class SpreadKey {
        static int equalities;
        final int id;
        SpreadKey(int id) {this.id=id;}
        public int hashCode() {return id;}
        public boolean equals(Object other) {equalities++;return other instanceof SpreadKey k && id==k.id;}
    }
    @Test void shardSelectionLeavesIndependentLowBitsForTheInnerHashTables() throws Exception {
        var snapshots=new PredictionInputSnapshots();Set<SpreadKey> set=PredictionInputSnapshots.concurrentSet();
        Map<SpreadKey,Integer> map=PredictionInputSnapshots.concurrentMap();
        // These hashes have unique low 16 bits. Sharding must not collapse them into tree buckets.
        for(int i=0;i<4096;i++){set.add(new SpreadKey(i));map.put(new SpreadKey(i),i);}
        var view=snapshots.set(set);var values=snapshots.map(map);SpreadKey.equalities=0;
        for(int i=0;i<4096;i++){assertTrue(view.contains(new SpreadKey(i)));assertEquals(i,values.get(new SpreadKey(i)));}
        assertTrue(SpreadKey.equalities<=2*4096,"Distinct hashes need one equality per successful lookup, got "+SpreadKey.equalities);
        var field=set.getClass().getDeclaredField("shards");field.setAccessible(true);
        int used=0;
        for(Object shard:(Object[])field.get(set)) {
            var slots=new HashSet<Integer>();
            for(Object value:(Set<?>)shard){int h=value.hashCode();slots.add((h^(h>>>16))&63);}
            used+=slots.size();
        }
        assertTrue(used>1800,"Inner hash bits must remain diverse across shards: "+used);
    }
    private static final class Key {
        static int hashes;
        final int id;
        Key(int id) { this.id = id; }
        public int hashCode() { hashes++; return id; }
        public boolean equals(Object other) { return other instanceof Key k && k.id == id; }
    }
    @Test void aSmallMutationCopiesABoundedPartOfLargeInputsAndNeverChangesOldViews() {
        var snapshots = new PredictionInputSnapshots();
        Set<Key> set = PredictionInputSnapshots.concurrentSet();
        Map<Key,Integer> map = PredictionInputSnapshots.concurrentMap();
        for (int i=0;i<32768;i++) { set.add(new Key(i)); map.put(new Key(i),i); }
        var oldSet=snapshots.set(set); var oldMap=snapshots.map(map);
        var oldEntries=oldMap.entrySet();
        set.remove(new Key(71)); set.add(new Key(40000)); map.put(new Key(71),-1);
        Key.hashes=0;
        var nextSet=snapshots.set(set); var nextMap=snapshots.map(map);
        assertTrue(Key.hashes<32768/8,"Single-key publication must not hash the full input: "+Key.hashes);
        assertEquals(new HashSet<>(set),nextSet);assertEquals(new HashMap<>(map),nextMap);
        assertTrue(oldSet.contains(new Key(71))); assertFalse(oldSet.contains(new Key(40000)));
        assertEquals(71,oldMap.get(new Key(71)));assertTrue(oldEntries.contains(Map.entry(new Key(71),71)));
        assertThrows(UnsupportedOperationException.class,()->oldEntries.iterator().next().setValue(-2));
        assertThrows(UnsupportedOperationException.class,()->oldMap.values().clear());
        assertThrows(UnsupportedOperationException.class,()->oldSet.iterator().remove());
        var it=nextSet.iterator();while(it.hasNext())it.next();assertThrows(NoSuchElementException.class,it::next);
    }
    @Test void canceledChangesReuseSnapshotIdentityAndClearCannotMutatePublishedBuckets() {
        var snapshots=new PredictionInputSnapshots();Set<Integer> set=PredictionInputSnapshots.concurrentSet();
        Map<Integer,Integer> map=PredictionInputSnapshots.concurrentMap();
        set.add(7);map.put(7,8);var firstSet=snapshots.set(set);var firstMap=snapshots.map(map);
        set.remove(7);set.add(7);map.put(7,9);map.put(7,8);
        assertSame(firstSet,snapshots.set(set));assertSame(firstMap,snapshots.map(map));
        set.clear();map.clear();set.add(-1);map.put(-1,-2);
        assertEquals(Set.of(-1),snapshots.set(set));assertEquals(Map.of(-1,-2),snapshots.map(map));
        assertEquals(Set.of(7),firstSet);assertEquals(Map.of(7,8),firstMap);
    }
    @Test void mixedMutationsMatchStandardCollectionsAcrossSuccessiveSnapshots() {
        var snapshots=new PredictionInputSnapshots();Set<Integer> set=PredictionInputSnapshots.concurrentSet();
        Map<Integer,Integer> map=PredictionInputSnapshots.concurrentMap();var random=new Random(82);
        var history=new ArrayList<Map<Integer,Integer>>();var expected=new ArrayList<Map<Integer,Integer>>();
        for(int round=0;round<200;round++) {
            for(int n=0;n<30;n++) {int k=random.nextInt(400)-200;if(random.nextBoolean()){set.add(k);map.merge(k,1,Integer::sum);}else{set.remove(k);map.remove(k);}}
            assertEquals(new HashSet<>(set),snapshots.set(set));assertEquals(new HashMap<>(map),snapshots.map(map));
            history.add(snapshots.map(map));expected.add(new HashMap<>(map));
        }
        assertEquals(expected,history);
    }
    private static volatile Object consumed;
    @Test void compareChangedInputPublicationWithoutTimingAssertions() {
        var snapshots=new PredictionInputSnapshots();Set<Integer> raw=new HashSet<>(),tracked=PredictionInputSnapshots.concurrentSet();
        for(int i=0;i<32768;i++){raw.add(i);tracked.add(i);}snapshots.set(tracked);
        var before=new ArrayList<Long>();var after=new ArrayList<Long>();
        for(int round=0;round<10;round++) {
            long a=System.nanoTime();
            for(int i=0;i<100;i++){raw.remove(i);consumed=Set.copyOf(raw);raw.add(i);}
            long b=System.nanoTime();
            for(int i=0;i<100;i++){tracked.remove(i);consumed=snapshots.set(tracked);tracked.add(i);}
            long c=System.nanoTime();if(round>=3){before.add(b-a);after.add(c-b);}
        }
        Collections.sort(before);Collections.sort(after);
        System.out.printf(Locale.ROOT,"CHANGED_INPUT entries=32768 publications=100 fullCopyMedianMs=%.3f incrementalMedianMs=%.3f (synthetic; not FPS)%n",before.get(3)/1e6,after.get(3)/1e6);
    }

    @org.junit.jupiter.api.Test void candidateUnionReusesUnchangedShardsAndIgnoresTimestampRefreshes() {
        var snapshots=new PredictionInputSnapshots();
        Set<Integer> a=PredictionInputSnapshots.concurrentSet(),b=PredictionInputSnapshots.concurrentSet();
        Map<Integer,Long> grace=PredictionInputSnapshots.concurrentMap();
        var union=new PredictionInputSnapshots.Union<Integer>();
        for(int i=0;i<10000;i++){a.add(i);b.add(i+5000);grace.put(i+10000,1L);}
        var first=union.snapshot(snapshots.set(a),snapshots.set(b),snapshots.keys(grace));
        assertEquals(20000,first.size());
        for(int i=0;i<10000;i++)grace.put(i+10000,2L);
        assertSame(first,union.snapshot(snapshots.set(a),snapshots.set(b),snapshots.keys(grace)));
        a.remove(0);b.add(20000);grace.keySet().remove(19999);
        var second=union.snapshot(snapshots.set(a),snapshots.set(b),snapshots.keys(grace));
        var expected=new java.util.HashSet<Integer>(a);expected.addAll(b);expected.addAll(grace.keySet());
        assertEquals(expected,second);assertTrue(first.contains(0));assertTrue(first.contains(19999));
        assertThrows(UnsupportedOperationException.class,()->second.iterator().remove());
        a.clear();b.clear();grace.clear();assertTrue(union.snapshot(snapshots.set(a),snapshots.set(b),snapshots.keys(grace)).isEmpty());
        union.clear();grace.put(1,3L);assertEquals(Set.of(1),union.snapshot(snapshots.set(a),snapshots.set(b),snapshots.keys(grace)));
    }


    @Test void membershipOnlyMapsDoNotAccumulateValueDeltaHistoryAndCanStartValueSnapshotsLater() throws Exception {
        var snapshots=new PredictionInputSnapshots();Map<Integer,Long> map=PredictionInputSnapshots.concurrentMap();
        for(int i=0;i<10000;i++){map.put(i,1L);map.remove(i);}
        map.put(1,2L);var first=snapshots.keys(map);
        map.replace(1,3L);map.compute(2,(k,v)->4L);map.putIfAbsent(3,5L);
        map.values().removeIf(v->v==5L);
        var field=map.getClass().getDeclaredField("changed");field.setAccessible(true);
        assertTrue(((Set<?>)field.get(map)).isEmpty());
        assertEquals(Set.of(1),first);assertEquals(Set.of(1,2),snapshots.keys(map));
        assertEquals(Map.of(1,3L,2,4L),snapshots.map(map));
        map.entrySet().iterator().next().setValue(7L);assertEquals(map,snapshots.map(map));
        map.clear();assertTrue(snapshots.map(map).isEmpty());assertTrue(snapshots.keys(map).isEmpty());
    }
}

package dev.xantha.vss.client.prediction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Function;

/** One identity index for independent tile flags and primitive timestamps.
 * Readers do not take the mutation monitor. Immutable snapshots retain changed
 * hash pages, not live slots; a recycled slot can never alter a worker's input. */
final class PredictionTileStates<K> {
    private static final int PAGE_SHIFT=8, PAGE_SIZE=1<<PAGE_SHIFT, WORDS=PAGE_SIZE/64;
    private static final class Page<K> {
        final AtomicReferenceArray<Slot<K>> slots=new AtomicReferenceArray<>(PAGE_SIZE);
        final AtomicLongArray states=new AtomicLongArray(PAGE_SIZE);
        final AtomicLongArray[] members, values;
        Page(int flags,int longs) {
            members=new AtomicLongArray[flags+longs];values=new AtomicLongArray[longs];
            Arrays.setAll(members,i->new AtomicLongArray(WORDS));
            Arrays.setAll(values,i->new AtomicLongArray(PAGE_SIZE));
        }
    }
    private record Slot<K>(K key,Page<K> page,int offset,int index,long generation) { }
    private final ConcurrentHashMap<K,Slot<K>> index=new ConcurrentHashMap<>();
    private final int flagCount,longCount;
    private final AtomicIntegerArray counts;
    private final List<StateSet> sets=new ArrayList<>();
    private final List<TimeMap> maps=new ArrayList<>();
    private final it.unimi.dsi.fastutil.ints.IntArrayList free=new it.unimi.dsi.fastutil.ints.IntArrayList();
    private volatile Page<K>[] pages;
    private int allocated;
    private long generation;

    @SuppressWarnings("unchecked")
    PredictionTileStates(int flags,int longs) {
        if(flags<0 || longs<0 || flags+longs>63)throw new IllegalArgumentException("Tile state columns");
        flagCount=flags;longCount=longs;counts=new AtomicIntegerArray(flags+longs);
        pages=(Page<K>[])new Page<?>[0];
        for(int i=0;i<flags+longs;i++)sets.add(new StateSet(i));
        for(int i=0;i<longs;i++)maps.add(new TimeMap(i));
    }
    Set<K> flag(int column) {
        if(column<0 || column>=flagCount)throw new IndexOutOfBoundsException(column);
        return sets.get(column);
    }
    Map<K,Long> values(int column) {return maps.get(column);}
    int identities() {return index.size();}
    synchronized int slotCapacity() {return pages.length*PAGE_SIZE;}
    long handle(K key) {var slot=index.get(key);return slot==null ? -1 : slot.generation();}
    boolean current(K key,long handle) {var slot=index.get(key);return slot!=null && slot.generation()==handle;}

    private Slot<K> slot(K key) {
        Objects.requireNonNull(key);
        var found=index.get(key);if(found!=null)return found;
        int id=free.isEmpty() ? allocated++ : free.removeInt(free.size()-1);
        int page=id>>PAGE_SHIFT,offset=id&(PAGE_SIZE-1);
        if(page==pages.length) {
            var next=Arrays.copyOf(pages,pages.length+1);
            next[page]=new Page<>(flagCount,longCount);pages=next;
        }
        var result=new Slot<>(key,pages[page],offset,id,++generation);
        result.page().slots.set(offset,result);index.put(key,result);return result;
    }
    private boolean present(Slot<K> slot,int column) {
        return slot!=null && (slot.page().states.get(slot.offset())&(1L<<column))!=0
                && slot.page().slots.get(slot.offset())==slot;
    }
    private void membership(Slot<K> slot,int column,boolean added) {
        long mask=1L<<column;
        var page=slot.page();int offset=slot.offset();
        long state=page.states.get(offset);
        page.states.set(offset,added ? state|mask : state&~mask);
        var words=page.members[column];int word=offset>>>6;long bit=1L<<(offset&63);
        words.set(word,added ? words.get(word)|bit : words.get(word)&~bit);
        counts.set(column,counts.get(column)+(added?1:-1));
        sets.get(column).changed(slot.key());
        if(!added && page.states.get(offset)==0) {
            index.remove(slot.key(),slot);page.slots.set(offset,null);free.add(slot.index());
        }
    }
    private synchronized boolean add(K key,int column) {
        var slot=slot(key);if(present(slot,column))return false;
        membership(slot,column,true);return true;
    }
    private synchronized boolean remove(Object key,int column,Slot<K> expected) {
        var slot=index.get(key);
        if(slot==null || expected!=null && slot!=expected || !present(slot,column))return false;
        if(column>=flagCount)maps.get(column-flagCount).changed(slot.key());
        membership(slot,column,false);return true;
    }
    private Long value(Object key,int column) {
        var slot=index.get(key);if(!present(slot,flagCount+column))return null;
        long value=slot.page().values[column].get(slot.offset());
        return present(slot,flagCount+column) ? value : null;
    }
    private synchronized Long put(K key,int column,long value) {
        var slot=slot(key);int field=flagCount+column;
        boolean had=present(slot,field);
        Long previous=had ? slot.page().values[column].get(slot.offset()) : null;
        slot.page().values[column].set(slot.offset(),value);
        if(!had)membership(slot,field,true);
        if(!Objects.equals(previous,value))maps.get(column).changed(key);
        return previous;
    }

    /** Bitmaps skip empty pages/words for small pending subsets of a large resident set. */
    private Iterator<Slot<K>> members(int column) {
        Page<K>[] captured=pages;
        return new Iterator<>() {
            int page,word;long remaining;int base;
            Slot<K> next,last;
            public boolean hasNext() {
                while(next==null) {
                    if(remaining!=0) {
                        int bit=Long.numberOfTrailingZeros(remaining);remaining&=remaining-1;
                        var candidate=captured[page-1].slots.get(base+bit);
                        if(present(candidate,column))next=candidate;
                    } else {
                        if(page==0 || word==WORDS) {
                            if(page==captured.length)return false;
                            page++;word=0;
                        }
                        base=word*64;remaining=captured[page-1].members[column].get(word++);
                    }
                }
                return true;
            }
            public Slot<K> next() {
                if(!hasNext())throw new NoSuchElementException();last=next;next=null;return last;
            }
            public void remove() {
                if(last==null)throw new IllegalStateException();
                PredictionTileStates.this.remove(last.key(),column,last);last=null;
            }
        };
    }
    private final class StateSet extends AbstractSet<K> implements PredictionInputSnapshots.SetSource<K> {
        final int column;
        Set<K> changes;
        Set<K>[] shards=PredictionInputSnapshots.emptySetShards();
        Set<K> snapshot=Set.of();
        StateSet(int column) {this.column=column;}
        void changed(K key) {if(changes!=null)changes.add(key);}
        public int size() {return counts.get(column);}
        public boolean contains(Object key) {return present(index.get(key),column);}
        public boolean add(K key) {
            if(column>=flagCount)throw new UnsupportedOperationException("Timestamp key set");
            return PredictionTileStates.this.add(key,column);
        }
        public boolean remove(Object key) {return PredictionTileStates.this.remove(key,column,null);}
        public void clear() {synchronized(PredictionTileStates.this) {var it=iterator();while(it.hasNext()){it.next();it.remove();}}}
        public Iterator<K> iterator() {
            var it=members(column);
            return new Iterator<>() {
                public boolean hasNext(){return it.hasNext();}
                public K next(){return it.next().key();}
                public void remove(){it.remove();}
            };
        }
        public Set<K> snapshot() {synchronized(PredictionTileStates.this) {
            if(changes==null) {changes=new HashSet<>();changes.addAll(this);}
            if(changes.isEmpty())return snapshot;
            var next=shards.clone();boolean[] copied=new boolean[shards.length];boolean updated=false;
            for(K key:changes) {
                int bucket=PredictionInputSnapshots.shard(key);boolean has=contains(key);
                if(shards[bucket].contains(key)==has)continue;
                if(!copied[bucket]){next[bucket]=new HashSet<>(shards[bucket]);copied[bucket]=true;}
                if(has)next[bucket].add(key);else next[bucket].remove(key);updated=true;
            }
            changes.clear();if(updated){shards=next;snapshot=new PredictionInputSnapshots.ShardSet<>(next,size());}
            return snapshot;
        }}
    }
    private final class TimeMap extends AbstractMap<K,Long> implements PredictionInputSnapshots.MapSource<K,Long> {
        final int column;
        Set<K> changes;
        Map<K,Long>[] shards=PredictionInputSnapshots.emptyMapShards();
        Map<K,Long> snapshot=Map.of();
        TimeMap(int column){this.column=column;}
        void changed(K key){if(changes!=null)changes.add(key);}
        public int size(){return counts.get(flagCount+column);}
        public boolean containsKey(Object key){return present(index.get(key),flagCount+column);}
        public Long get(Object key){return value(key,column);}
        public Long put(K key,Long value){return PredictionTileStates.this.put(key,column,Objects.requireNonNull(value));}
        public Long remove(Object key){synchronized(PredictionTileStates.this) {Long old=get(key);if(old!=null)PredictionTileStates.this.remove(key,flagCount+column,null);return old;}}
        public boolean remove(Object key,Object value){synchronized(PredictionTileStates.this) {Long old=get(key);if(old==null || !old.equals(value))return false;remove(key);return true;}}
        public void clear(){keySet().clear();}
        public Set<K> keySet(){return sets.get(flagCount+column);}
        public Set<K> keySnapshot(){return sets.get(flagCount+column).snapshot();}
        public Long putIfAbsent(K key,Long value){synchronized(PredictionTileStates.this){Long old=get(key);if(old==null)put(key,value);return old;}}
        public Long replace(K key,Long value){synchronized(PredictionTileStates.this){Long old=get(key);if(old!=null)put(key,value);return old;}}
        public boolean replace(K key,Long old,Long value){synchronized(PredictionTileStates.this){Long current=get(key);if(current==null || !current.equals(old))return false;put(key,value);return true;}}
        public Long compute(K key,BiFunction<? super K,? super Long,? extends Long> fn){synchronized(PredictionTileStates.this){Long next=fn.apply(key,get(key));if(next==null)remove(key);else put(key,next);return next;}}
        public Long computeIfAbsent(K key,Function<? super K,? extends Long> fn){synchronized(PredictionTileStates.this){Long old=get(key);return old!=null?old:compute(key,(k,v)->fn.apply(k));}}
        public Long computeIfPresent(K key,BiFunction<? super K,? super Long,? extends Long> fn){synchronized(PredictionTileStates.this){return get(key)==null?null:compute(key,fn);}}
        public Long merge(K key,Long value,BiFunction<? super Long,? super Long,? extends Long> fn){Objects.requireNonNull(value);return compute(key,(k,old)->old==null?value:fn.apply(old,value));}
        public void replaceAll(BiFunction<? super K,? super Long,? extends Long> fn){synchronized(PredictionTileStates.this){for(var entry:entrySet())put(entry.getKey(),Objects.requireNonNull(fn.apply(entry.getKey(),entry.getValue())));}}
        public Set<Entry<K,Long>> entrySet(){return new AbstractSet<>() {
            public int size(){return TimeMap.this.size();}
            public void clear(){TimeMap.this.clear();}
            public Iterator<Entry<K,Long>> iterator(){
                var it=members(flagCount+column);
                return new Iterator<>() {
                    Slot<K> nextSlot,last;
                    Long nextValue;
                    public boolean hasNext(){
                        while(nextSlot==null && it.hasNext()) {
                            var slot=it.next();long current=slot.page().values[column].get(slot.offset());
                            if(present(slot,flagCount+column)){nextSlot=slot;nextValue=current;}
                        }
                        return nextSlot!=null;
                    }
                    public Entry<K,Long> next(){
                        if(!hasNext())throw new NoSuchElementException();
                        var slot=nextSlot;long current=nextValue;last=slot;nextSlot=null;nextValue=null;
                        return new SimpleEntry<>(slot.key(),current) {
                            public Long setValue(Long value){Long old=TimeMap.this.put(getKey(),value);super.setValue(value);return old;}
                        };
                    }
                    public void remove(){
                        if(last==null)throw new IllegalStateException();
                        PredictionTileStates.this.remove(last.key(),flagCount+column,last);last=null;
                    }
                };
            }
        };}
        public Map<K,Long> snapshot(){synchronized(PredictionTileStates.this){
            if(changes==null){changes=new HashSet<>();changes.addAll(keySet());}
            if(changes.isEmpty())return snapshot;
            var next=shards.clone();boolean[] copied=new boolean[shards.length];boolean updated=false;
            for(K key:changes){
                int bucket=PredictionInputSnapshots.shard(key);Long value=get(key);
                if(Objects.equals(shards[bucket].get(key),value))continue;
                if(!copied[bucket]){next[bucket]=new HashMap<>(shards[bucket]);copied[bucket]=true;}
                if(value==null)next[bucket].remove(key);else next[bucket].put(key,value);updated=true;
            }
            changes.clear();if(updated){shards=next;snapshot=Collections.unmodifiableMap(new PredictionInputSnapshots.ShardMap<>(next,size()));}
            return snapshot;
        }}
    }
}

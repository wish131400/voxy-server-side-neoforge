package dev.xantha.vss.client.prediction;

import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Client-thread snapshots of the manager's fixed, lifetime-owned collections.
 * Entries retain immutable copies only; changes replace the copy, so in-flight
 * workers keep their original input. Do not pass temporary collection sources. */
final class PredictionInputSnapshots {
    interface SetSource<T> { Set<T> snapshot(); }
    interface MapSource<K,V> { Map<K,V> snapshot(); Set<K> keySnapshot(); }
    private final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();

    static <T> Set<T> concurrentSet() { return new TrackedSet<>(); }
    static <K,V> Map<K,V> concurrentMap() { return new TrackedMap<>(); }

    /** Mutations and snapshot creation share one monitor; readers keep concurrent lookup semantics.
     * Unchanged collections reuse their immutable identity without an O(n) equals pass. */
    private static final class TrackedSet<T> extends AbstractSet<T> {
        private final Set<T> values = ConcurrentHashMap.newKeySet();
        private Set<T> snapshot = Set.of();
        private final Set<T> changed = new HashSet<>();
        private Set<T>[] shards = emptySetShards();
        public int size() { return values.size(); }
        public boolean contains(Object key) { return values.contains(key); }
        public synchronized boolean add(T value) { boolean result = values.add(value); if (result) changed.add(value); return result; }
        public synchronized boolean remove(Object value) { boolean result = values.remove(value); if (result) { @SuppressWarnings("unchecked") T key = (T) value; changed.add(key); } return result; }
        public synchronized void clear() { values.clear(); snapshot = Set.of(); changed.clear(); shards = emptySetShards(); }
        public Iterator<T> iterator() {
            var iterator = values.iterator();
            return new Iterator<>() {
                private T current;
                public boolean hasNext() { return iterator.hasNext(); }
                public T next() { return current = iterator.next(); }
                public void remove() {
                    if (current == null) throw new IllegalStateException();
                    TrackedSet.this.remove(current); current = null;
                }
            };
        }
        synchronized Set<T> snapshot() {
            if (changed.isEmpty()) return snapshot;
            Set<T>[] next = shards.clone();
            boolean[] copied = new boolean[SHARDS];
            boolean updated = false;
            for (T key : changed) {
                int bucket = shard(key);
                boolean present = values.contains(key);
                if (shards[bucket].contains(key) == present) continue;
                if (!copied[bucket]) { next[bucket] = new HashSet<>(shards[bucket]); copied[bucket] = true; }
                if (present) next[bucket].add(key); else next[bucket].remove(key);
                updated = true;
            }
            changed.clear();
            if (updated) {
                shards = next;
                snapshot = new ShardSet<>(next, values.size());
            }
            return snapshot;
        }
    }

    private static final class TrackedMap<K,V> extends AbstractMap<K,V> {
        private TrackedSet<K> keys;
        private boolean valueSnapshotRequested;
        private final ConcurrentHashMap<K,V> values = new ConcurrentHashMap<>();
        private Map<K,V> snapshot = Map.of();
        private final Set<K> changed = new HashSet<>();
        private Map<K,V>[] shards = emptyMapShards();
        public int size() { return values.size(); }
        public V get(Object key) { return values.get(key); }
        public boolean containsKey(Object key) { return values.containsKey(key); }
        public synchronized V put(K key,V value) {
            V previous = values.put(key,value); if (previous == null && keys != null) keys.add(key); if (!Objects.equals(previous,value)) markChanged(key); return previous;
        }
        public synchronized V remove(Object key) { V previous = values.remove(key); if (previous != null) markRemoved(key); return previous; }
        public synchronized boolean remove(Object key,Object value) {
            boolean removed = values.remove(key,value); if (removed) markRemoved(key); return removed;
        }
        public synchronized void clear() { values.clear(); if (keys != null) keys.clear(); snapshot = Map.of(); changed.clear(); shards = emptyMapShards(); }
        public synchronized V putIfAbsent(K key,V value) {
            V previous = values.putIfAbsent(key,value); if (previous == null) { if (keys != null) keys.add(key); markChanged(key); } return previous;
        }
        public synchronized V replace(K key,V value) {
            V previous = values.replace(key,value); if (previous != null && !Objects.equals(previous,value)) markChanged(key); return previous;
        }
        public synchronized boolean replace(K key,V old,V value) {
            boolean result = values.replace(key,old,value); if (result && !Objects.equals(old,value)) markChanged(key); return result;
        }
        public synchronized V compute(K key,BiFunction<? super K,? super V,? extends V> fn) {
            V old = values.get(key), next = fn.apply(key,old);
            if (next == null) remove(key); else put(key,next);
            return next;
        }
        public synchronized V computeIfAbsent(K key,Function<? super K,? extends V> fn) {
            V old = values.get(key); return old != null ? old : compute(key,(k,v) -> fn.apply(k));
        }
        public synchronized V computeIfPresent(K key,BiFunction<? super K,? super V,? extends V> fn) {
            return values.containsKey(key) ? compute(key,fn) : null;
        }
        public synchronized V merge(K key,V value,BiFunction<? super V,? super V,? extends V> fn) {
            Objects.requireNonNull(value); return compute(key,(k,old) -> old == null ? value : fn.apply(old,value));
        }
        public synchronized void replaceAll(BiFunction<? super K,? super V,? extends V> fn) {
            for (var entry : values.entrySet()) put(entry.getKey(), Objects.requireNonNull(fn.apply(entry.getKey(),entry.getValue())));
        }
        public Set<Entry<K,V>> entrySet() {
            return new AbstractSet<>() {
                public int size() { return values.size(); }
                public void clear() { TrackedMap.this.clear(); }
                public Iterator<Entry<K,V>> iterator() {
                    var iterator = values.entrySet().iterator();
                    return new Iterator<>() {
                        private Entry<K,V> current;
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Entry<K,V> next() {
                            current = iterator.next();
                            return new SimpleEntry<>(current) {
                                public V setValue(V value) { V old = TrackedMap.this.put(getKey(),value); super.setValue(value); return old; }
                            };
                        }
                        public void remove() {
                            if (current == null) throw new IllegalStateException();
                            TrackedMap.this.remove(current.getKey()); current = null;
                        }
                    };
                }
            };
        }
        @SuppressWarnings("unchecked")
        private void markRemoved(Object key) { if (keys != null) keys.remove(key); markChanged((K) key); }
        synchronized Set<K> keySnapshot() {
            if (keys == null) { keys = new TrackedSet<>(); keys.addAll(values.keySet()); }
            return keys.snapshot();
        }
        private void markChanged(K key) { if (valueSnapshotRequested) changed.add(key); }
        synchronized Map<K,V> snapshot() {
            // Membership-only users never retain an unconsumed value-change history.
            if (!valueSnapshotRequested) { valueSnapshotRequested = true; changed.addAll(values.keySet()); }
            if (changed.isEmpty()) return snapshot;
            Map<K,V>[] next = shards.clone();
            boolean[] copied = new boolean[SHARDS];
            boolean updated = false;
            for (K key : changed) {
                int bucket = shard(key);
                V value = values.get(key);
                if (Objects.equals(shards[bucket].get(key), value)) continue;
                if (!copied[bucket]) { next[bucket] = new HashMap<>(shards[bucket]); copied[bucket] = true; }
                if (value == null) next[bucket].remove(key); else next[bucket].put(key,value);
                updated = true;
            }
            changed.clear();
            if (updated) {
                shards = next;
                snapshot = Collections.unmodifiableMap(new ShardMap<>(next, values.size()));
            }
            return snapshot;
        }
    }

    // Copy only buckets touched since the previous publication. Old buckets are
    // never mutated, so workers can retain any earlier snapshot without a delta chain.
    private static final int SHARDS = 64;
    static int shard(Object key) {
        // HashMap uses the low bits of hash ^ (hash >>> 16) inside each shard.
        // Select an independent bucket so those bits remain free to distribute entries.
        int hash = key.hashCode();
        hash ^= hash >>> 16; hash *= 0x85ebca6b; hash ^= hash >>> 13;
        return (hash * 0xc2b2ae35) >>> 26;
    }
    @SuppressWarnings("unchecked")
    static <T> Set<T>[] emptySetShards() {
        Set<T>[] buckets = (Set<T>[]) new Set<?>[SHARDS];
        java.util.Arrays.fill(buckets, Set.of()); return buckets;
    }
    @SuppressWarnings("unchecked")
    static <K,V> Map<K,V>[] emptyMapShards() {
        Map<K,V>[] buckets = (Map<K,V>[]) new Map<?,?>[SHARDS];
        java.util.Arrays.fill(buckets, Map.of()); return buckets;
    }
    private static <T> Iterator<T> flatten(int count, java.util.function.IntFunction<Iterator<T>> bucket) {
        return new Iterator<>() {
            int index;
            Iterator<T> current = Collections.emptyIterator();
            public boolean hasNext() {
                while (!current.hasNext() && index < count) current = bucket.apply(index++);
                return current.hasNext();
            }
            public T next() { if (!hasNext()) throw new NoSuchElementException(); return current.next(); }
        };
    }
    static final class ShardSet<T> extends AbstractSet<T> {
        private final Set<T>[] shards;
        private final int size;
        ShardSet(Set<T>[] shards, int size) { this.shards = shards; this.size = size; }
        public int size() { return size; }
        public boolean contains(Object key) { return key != null && shards[shard(key)].contains(key); }
        public Iterator<T> iterator() { return flatten(SHARDS, i -> Collections.unmodifiableSet(shards[i]).iterator()); }
    }
    static final class ShardMap<K,V> extends AbstractMap<K,V> {
        private final Map<K,V>[] shards;
        private final int size;
        ShardMap(Map<K,V>[] shards, int size) { this.shards = shards; this.size = size; }
        public int size() { return size; }
        public V get(Object key) { return key == null ? null : shards[shard(key)].get(key); }
        public boolean containsKey(Object key) { return key != null && shards[shard(key)].containsKey(key); }
        public Set<Entry<K,V>> entrySet() {
            return new AbstractSet<>() {
                public int size() { return size; }
                public Iterator<Entry<K,V>> iterator() { return flatten(SHARDS, i -> shards[i].entrySet().iterator()); }
            };
        }
    }

    @SuppressWarnings("unchecked")
    <T> Set<T> set(Set<T> source) {
        if (source instanceof SetSource<?> indexed) return ((SetSource<T>)indexed).snapshot();
        if (source instanceof TrackedSet<?> tracked) return ((TrackedSet<T>)tracked).snapshot();
        var previous = (Set<T>) copies.get(source);
        if (previous != null && previous.equals(source)) return previous;
        var next = Set.copyOf(source);
        copies.put(source, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    <K, V> Map<K, V> map(Map<K, V> source) {
        if (source instanceof MapSource<?,?> indexed) return ((MapSource<K,V>)indexed).snapshot();
        if (source instanceof TrackedMap<?,?> tracked) return ((TrackedMap<K,V>)tracked).snapshot();
        var previous = (Map<K, V>) copies.get(source);
        if (previous != null && previous.equals(source)) return previous;
        var next = Map.copyOf(source);
        copies.put(source, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    <K,V> Set<K> keys(Map<K,V> source) {
        if(source instanceof MapSource<?,?> indexed)return ((MapSource<K,V>)indexed).keySnapshot();
        if(source instanceof TrackedMap<?,?> tracked) return ((TrackedMap<K,V>)tracked).keySnapshot();
        return Set.copyOf(source.keySet());
    }

    /** Fixed three-source union. Unchanged buckets and their immutable identities survive publication. */
    static final class Union<T> {
        private Set<T>[] a=emptySetShards(),b=emptySetShards(),c=emptySetShards(),merged=emptySetShards();
        private Set<T> snapshot=Set.of();
        @SuppressWarnings("unchecked")
        private Set<T>[] buckets(Set<T> set) {
            if(set instanceof ShardSet<?> sharded)return ((ShardSet<T>)sharded).shards;
            if(set.isEmpty())return emptySetShards();
            throw new IllegalArgumentException("Union requires tracked snapshots");
        }
        Set<T> snapshot(Set<T> first,Set<T> second,Set<T> third) {
            var aa=buckets(first);var bb=buckets(second);var cc=buckets(third);
            Set<T>[] next=merged.clone();boolean updated=false;int size=0;
            for(int i=0;i<SHARDS;i++) {
                if(aa[i]!=a[i] || bb[i]!=b[i] || cc[i]!=c[i]) {
                    var values=new HashSet<T>(aa[i]);values.addAll(bb[i]);values.addAll(cc[i]);
                    if(!values.equals(merged[i])) {next[i]=Set.copyOf(values);updated=true;}
                }
                size+=next[i].size();
            }
            a=aa;b=bb;c=cc;
            if(updated) {merged=next;snapshot=new ShardSet<>(next,size);}
            return snapshot;
        }
        void clear() {a=emptySetShards();b=emptySetShards();c=emptySetShards();merged=emptySetShards();snapshot=Set.of();}
    }
    void clear() { copies.clear(); }
}

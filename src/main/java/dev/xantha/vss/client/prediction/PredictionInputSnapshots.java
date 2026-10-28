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
                snapshot = Collections.unmodifiableSet(new ShardSet<>(next, values.size()));
            }
            return snapshot;
        }
    }

    private static final class TrackedMap<K,V> extends AbstractMap<K,V> {
        private final ConcurrentHashMap<K,V> values = new ConcurrentHashMap<>();
        private Map<K,V> snapshot = Map.of();
        private final Set<K> changed = new HashSet<>();
        private Map<K,V>[] shards = emptyMapShards();
        public int size() { return values.size(); }
        public V get(Object key) { return values.get(key); }
        public boolean containsKey(Object key) { return values.containsKey(key); }
        public synchronized V put(K key,V value) {
            V previous = values.put(key,value); if (!Objects.equals(previous,value)) changed.add(key); return previous;
        }
        public synchronized V remove(Object key) { V previous = values.remove(key); if (previous != null) markRemoved(key); return previous; }
        public synchronized boolean remove(Object key,Object value) {
            boolean removed = values.remove(key,value); if (removed) markRemoved(key); return removed;
        }
        public synchronized void clear() { values.clear(); snapshot = Map.of(); changed.clear(); shards = emptyMapShards(); }
        public synchronized V putIfAbsent(K key,V value) {
            V previous = values.putIfAbsent(key,value); if (previous == null) changed.add(key); return previous;
        }
        public synchronized V replace(K key,V value) {
            V previous = values.replace(key,value); if (previous != null && !Objects.equals(previous,value)) changed.add(key); return previous;
        }
        public synchronized boolean replace(K key,V old,V value) {
            boolean result = values.replace(key,old,value); if (result && !Objects.equals(old,value)) changed.add(key); return result;
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
        private void markRemoved(Object key) { changed.add((K) key); }
        synchronized Map<K,V> snapshot() {
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
    private static int shard(Object key) {
        // HashMap uses the low bits of hash ^ (hash >>> 16) inside each shard.
        // Select an independent bucket so those bits remain free to distribute entries.
        int hash = key.hashCode();
        hash ^= hash >>> 16; hash *= 0x85ebca6b; hash ^= hash >>> 13;
        return (hash * 0xc2b2ae35) >>> 26;
    }
    @SuppressWarnings("unchecked")
    private static <T> Set<T>[] emptySetShards() {
        Set<T>[] buckets = (Set<T>[]) new Set<?>[SHARDS];
        java.util.Arrays.fill(buckets, Set.of()); return buckets;
    }
    @SuppressWarnings("unchecked")
    private static <K,V> Map<K,V>[] emptyMapShards() {
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
    private static final class ShardSet<T> extends AbstractSet<T> {
        private final Set<T>[] shards;
        private final int size;
        ShardSet(Set<T>[] shards, int size) { this.shards = shards; this.size = size; }
        public int size() { return size; }
        public boolean contains(Object key) { return key != null && shards[shard(key)].contains(key); }
        public Iterator<T> iterator() { return flatten(SHARDS, i -> shards[i].iterator()); }
    }
    private static final class ShardMap<K,V> extends AbstractMap<K,V> {
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
        if (source instanceof TrackedSet<?> tracked) return ((TrackedSet<T>)tracked).snapshot();
        var previous = (Set<T>) copies.get(source);
        if (previous != null && previous.equals(source)) return previous;
        var next = Set.copyOf(source);
        copies.put(source, next);
        return next;
    }

    @SuppressWarnings("unchecked")
    <K, V> Map<K, V> map(Map<K, V> source) {
        if (source instanceof TrackedMap<?,?> tracked) return ((TrackedMap<K,V>)tracked).snapshot();
        var previous = (Map<K, V>) copies.get(source);
        if (previous != null && previous.equals(source)) return previous;
        var next = Map.copyOf(source);
        copies.put(source, next);
        return next;
    }

    void clear() { copies.clear(); }
}

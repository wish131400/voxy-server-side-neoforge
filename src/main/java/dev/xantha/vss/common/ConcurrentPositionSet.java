package dev.xantha.vss.common;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Concurrent packed-coordinate set with a distributed hash and original coordinates on iteration. */
public final class ConcurrentPositionSet extends AbstractSet<Long> {
    private record Key(long packed) {
        @Override public int hashCode() {
            long hash = it.unimi.dsi.fastutil.HashCommon.mix(packed);
            return (int)(hash ^ hash >>> 32);
        }
    }
    private final Set<Key> keys = ConcurrentHashMap.newKeySet();
    @Override public int size() { return keys.size(); }
    @Override public boolean add(Long packed) { return keys.add(new Key(packed)); }
    @Override public boolean contains(Object value) { return value instanceof Long packed && keys.contains(new Key(packed)); }
    @Override public boolean remove(Object value) { return value instanceof Long packed && keys.remove(new Key(packed)); }
    @Override public void clear() { keys.clear(); }
    @Override public Iterator<Long> iterator() {
        var iterator = keys.iterator();
        return new Iterator<>() {
            public boolean hasNext() { return iterator.hasNext(); }
            public Long next() { return iterator.next().packed(); }
            public void remove() { iterator.remove(); }
        };
    }
}

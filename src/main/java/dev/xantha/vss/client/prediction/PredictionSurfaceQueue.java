package dev.xantha.vss.client.prediction;

import java.util.AbstractCollection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/** Client/commit monitor owns this queue. Priorities are immutable hints;
 * admission still checks live readiness and visibility. Duplicate refreshes do
 * no ordering work, and inserting a completion never scans existing hints. */
class PredictionSurfaceQueue<K> extends AbstractCollection<K> {
    record Priority(int band, double distance) { }
    private record Entry<K>(K key, Priority priority, long sequence) { }
    private final Function<K, Priority> rank;
    private final HashMap<K, Entry<K>> members = new HashMap<>();
    private final TreeSet<Entry<K>> ordered = new TreeSet<>((a, b) -> {
        int result = Integer.compare(a.priority.band, b.priority.band);
        if (result == 0) result = Double.compare(a.priority.distance, b.priority.distance);
        return result == 0 ? Long.compare(a.sequence, b.sequence) : result;
    });
    private long sequence;

    PredictionSurfaceQueue(Function<K, Priority> rank) { this.rank = rank; }

    @Override public boolean add(K key) {
        Objects.requireNonNull(key);
        if (members.containsKey(key)) return false;
        var entry = new Entry<>(key, rank.apply(key), sequence++);
        members.put(key, entry);
        ordered.add(entry);
        return true;
    }

    K peekFirst() { return ordered.isEmpty() ? null : ordered.first().key; }

    K removeFirst() {
        var entry = ordered.pollFirst();
        if (entry == null) throw new NoSuchElementException();
        members.remove(entry.key);
        return entry.key;
    }

    @Override public boolean contains(Object key) { return members.containsKey(key); }
    @Override public boolean remove(Object key) {
        var entry = members.remove(key);
        return entry != null && ordered.remove(entry);
    }
    @Override public int size() { return members.size(); }
    @Override public void clear() { members.clear(); ordered.clear(); sequence = 0; }

    @Override public Iterator<K> iterator() {
        var iterator = ordered.iterator();
        return new Iterator<>() {
            private Entry<K> current;
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public K next() { current = iterator.next(); return current.key; }
            @Override public void remove() {
                if (current == null) throw new IllegalStateException();
                iterator.remove(); members.remove(current.key); current = null;
            }
        };
    }
}

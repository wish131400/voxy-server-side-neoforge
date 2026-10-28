package dev.xantha.vss.compat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Version tokens with a horizontal region index. Reads of an existing token never take the index lock. */
final class VoxySectionVersions extends AbstractMap<Long, AtomicLong> {
    private volatile long additions;
    long additions() { return additions; }
    private final Map<Long, AtomicLong> tokens = new ConcurrentHashMap<>();
    // LOD 0..4 share 512-block buckets. Larger native sections use their own aligned bucket size.
    private final Map<Integer, Map<Long, Set<Long>>> regions = new HashMap<>();
    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
    private static int bucketLod(long pos) { return Math.max(4, VoxyPredictionBridge.level(pos)); }
    private static long bucket(long pos) {
        int shift = bucketLod(pos) - VoxyPredictionBridge.level(pos);
        return key(VoxyPredictionBridge.x(pos) >> shift, VoxyPredictionBridge.z(pos) >> shift);
    }
    private void index(long pos) {
        regions.computeIfAbsent(bucketLod(pos), ignored -> new HashMap<>())
                .computeIfAbsent(bucket(pos), ignored -> new HashSet<>()).add(pos);
    }
    @Override public AtomicLong get(Object pos) { return tokens.get(pos); }
    @Override public boolean containsKey(Object pos) { return tokens.containsKey(pos); }
    @Override public int size() { return tokens.size(); }
    @Override public AtomicLong computeIfAbsent(Long pos, Function<? super Long, ? extends AtomicLong> factory) {
        AtomicLong token = tokens.get(pos);
        if (token != null) return token;
        synchronized (this) {
            token = tokens.get(pos);
            if (token == null) {
                token = factory.apply(pos);
                if (token != null) { tokens.put(pos, token); index(pos); additions++; }
            }
            return token;
        }
    }
    @Override public synchronized AtomicLong put(Long pos, AtomicLong token) {
        AtomicLong previous = tokens.put(pos, Objects.requireNonNull(token));
        if (previous == null) { index(pos); additions++; }
        return previous;
    }
    @Override public synchronized AtomicLong remove(Object value) {
        AtomicLong token = tokens.remove(value);
        if (token == null) return null;
        long pos = (Long)value;
        var level = regions.get(bucketLod(pos)); var bucket = level.get(bucket(pos));
        bucket.remove(pos);
        if (bucket.isEmpty()) level.remove(bucket(pos));
        if (level.isEmpty()) regions.remove(bucketLod(pos));
        return token;
    }
    @Override public synchronized boolean remove(Object pos, Object token) {
        if (tokens.get(pos) != token) return false;
        return remove(pos) != null;
    }
    @Override public synchronized void clear() { tokens.clear(); regions.clear(); }
    @Override public Set<Entry<Long, AtomicLong>> entrySet() {
        return new AbstractSet<>() {
            public int size() { return tokens.size(); }
            public void clear() { VoxySectionVersions.this.clear(); }
            public Iterator<Entry<Long, AtomicLong>> iterator() {
                var iterator = tokens.entrySet().iterator();
                return new Iterator<>() {
                    Entry<Long, AtomicLong> current;
                    public boolean hasNext() { return iterator.hasNext(); }
                    public Entry<Long, AtomicLong> next() { return current = new SimpleImmutableEntry<>(iterator.next()); }
                    public void remove() {
                        if (current == null) throw new IllegalStateException();
                        VoxySectionVersions.this.remove(current.getKey(), current.getValue()); current = null;
                    }
                };
            }
        };
    }
    synchronized List<Entry<Long, AtomicLong>> intersect(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        var result = new ArrayList<Entry<Long, AtomicLong>>();
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) return result;
        for (var level : regions.entrySet()) {
            long span = 32L << level.getKey();
            int firstX = (int)Math.floorDiv(minX, span), firstZ = (int)Math.floorDiv(minZ, span);
            int lastX = (int)Math.floorDiv((long)maxX - 1, span), lastZ = (int)Math.floorDiv((long)maxZ - 1, span);
            var buckets = level.getValue();
            long count = ((long)lastX-firstX+1) * ((long)lastZ-firstZ+1);
            if (count < buckets.size()) {
                for (int z=firstZ; z<=lastZ; z++) for (int x=firstX; x<=lastX; x++)
                    collect(buckets.get(key(x,z)),minX,minY,minZ,maxX,maxY,maxZ,result);
            } else for (var entry : buckets.entrySet()) {
                int x=(int)(entry.getKey()>>32), z=(int)(long)entry.getKey();
                if (x>=firstX && x<=lastX && z>=firstZ && z<=lastZ)
                    collect(entry.getValue(),minX,minY,minZ,maxX,maxY,maxZ,result);
            }
        }
        return result;
    }
    private void collect(Set<Long> bucket, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                         List<Entry<Long, AtomicLong>> result) {
        if (bucket == null) return;
        for (long pos : bucket) {
            long span=32L<<VoxyPredictionBridge.level(pos);
            long x=(long)VoxyPredictionBridge.x(pos)*span, y=(long)VoxyPredictionBridge.y(pos)*span,
                    z=(long)VoxyPredictionBridge.z(pos)*span;
            if (x<maxX && y<maxY && z<maxZ && x+span>minX && y+span>minY && z+span>minZ)
                result.add(new SimpleImmutableEntry<>(pos,tokens.get(pos)));
        }
    }
}

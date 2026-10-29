package dev.xantha.vss.compat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native sections fit inside one aligned 512-block region at every supported LOD. */
final class VoxyPendingSections extends AbstractSet<Long> {
    private final Set<Long> positions = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Long>> regions = new LinkedHashMap<>();
    private final Set<Long> urgent = new LinkedHashSet<>();
    private final PriorityQueue<Long> order = new PriorityQueue<>(Comparator
            .comparingDouble((Long region) -> priority(region)).thenComparingLong(Long::longValue));
    private volatile View view = new View(0, 0, 0, 0);
    private View orderedView;
    private int turns;
    record View(int x, int z, int lookX, int lookZ) { }
    /** Publication is cheap on the render thread; only the consumer rebuilds region priorities. */
    void view(double x, double z, double lookX, double lookZ) {
        view = new View((int)Math.floor(x / 64), (int)Math.floor(z / 64),
                (int)Math.round(lookX * 4), (int)Math.round(lookZ * 4));
    }
    synchronized void promote(long pos) { if (positions.contains(pos)) urgent.add(pos); }
    private double priority(long region) {
        View v = orderedView;
        double dx = (int)(region >> 32) * 512.0 + 256 - v.x() * 64.0;
        double dz = (int)region * 512.0 + 256 - v.z() * 64.0;
        double distance = Math.max(0, Math.abs(dx) - 256) * Math.max(0, Math.abs(dx) - 256)
                + Math.max(0, Math.abs(dz) - 256) * Math.max(0, Math.abs(dz) - 256);
        return distance * (dx * v.lookX() + dz * v.lookZ() < 0 ? 2 : 1);
    }
    /** Current view first, with one FIFO turn in eight so distant updates still finish. */
    synchronized Long poll() { return poll(pos -> { }); }
    synchronized Long poll(java.util.function.LongConsumer claimed) {
        if (positions.isEmpty()) return null;
        if (++turns % 8 != 0 && !urgent.isEmpty()) {
            long pos = urgent.iterator().next(); claimed.accept(pos); remove(pos); return pos;
        }
        if (!view.equals(orderedView)) {
            orderedView = view; order.clear(); order.addAll(regions.keySet());
        }
        Long region = turns % 8 == 0 ? regions.keySet().iterator().next() : order.peek();
        if (region == null) return null;
        long pos = regions.get(region).iterator().next(); claimed.accept(pos); remove(pos);
        if (turns % 8 == 0) {
            Set<Long> remaining = regions.remove(region);
            if (remaining != null) regions.put(region, remaining);
        }
        return pos;
    }
    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
    private static long region(long position) {
        int shift = 4 - VoxyPredictionBridge.level(position);
        return key(VoxyPredictionBridge.x(position) >> shift, VoxyPredictionBridge.z(position) >> shift);
    }
    @Override public synchronized boolean add(Long position) {
        if (!positions.add(position)) return false;
        long region = region(position);
        Set<Long> bucket = regions.get(region);
        if (bucket == null) {
            bucket = new LinkedHashSet<>(); regions.put(region, bucket);
            if (orderedView != null) order.add(region);
        }
        bucket.add(position); return true;
    }
    @Override public synchronized boolean remove(Object value) {
        if (!(value instanceof Long position) || !positions.remove(position)) return false;
        long key = region(position); Set<Long> bucket = regions.get(key);
        urgent.remove(position);
        bucket.remove(position);
        if (bucket.isEmpty()) { regions.remove(key); order.remove(key); }
        return true;
    }
    @Override public synchronized void clear() { positions.clear(); regions.clear(); urgent.clear(); order.clear(); orderedView = null; turns = 0; }
    @Override public boolean contains(Object value) { return positions.contains(value); }
    @Override public int size() { return positions.size(); }
    @Override public Iterator<Long> iterator() {
        Iterator<Long> iterator = positions.iterator();
        return new Iterator<>() {
            Long current;
            public boolean hasNext() { return iterator.hasNext(); }
            public Long next() { return current = iterator.next(); }
            public void remove() { if (current == null) throw new IllegalStateException(); VoxyPendingSections.this.remove(current); current = null; }
        };
    }
    synchronized boolean intersects(int minX, int minZ, int maxX, int maxZ) {
        if (regions.isEmpty()) return false;
        int firstX = minX >> 5, firstZ = minZ >> 5, lastX = maxX >> 5, lastZ = maxZ >> 5;
        long count = ((long)lastX - firstX + 1) * ((long)lastZ - firstZ + 1);
        if (count < regions.size()) {
            for (int z = firstZ; z <= lastZ; z++) for (int x = firstX; x <= lastX; x++)
                if (intersects(regions.get(key(x,z)), minX,minZ,maxX,maxZ)) return true;
        } else for (var entry : regions.entrySet()) {
            int x = (int)(entry.getKey() >> 32), z = (int)(long)entry.getKey();
            if (x >= firstX && x <= lastX && z >= firstZ && z <= lastZ
                    && intersects(entry.getValue(),minX,minZ,maxX,maxZ)) return true;
        }
        return false;
    }
    private static boolean intersects(Set<Long> bucket, int minX, int minZ, int maxX, int maxZ) {
        if (bucket == null) return false;
        for (long position : bucket) {
            long span = 2L << VoxyPredictionBridge.level(position);
            long x = (long)VoxyPredictionBridge.x(position)*span, z = (long)VoxyPredictionBridge.z(position)*span;
            if (x <= maxX && z <= maxZ && x + span > minX && z + span > minZ) return true;
        }
        return false;
    }
}

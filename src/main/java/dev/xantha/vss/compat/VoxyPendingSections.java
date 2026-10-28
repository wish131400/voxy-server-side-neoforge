package dev.xantha.vss.compat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Native sections fit inside one aligned 512-block region at every supported LOD. */
final class VoxyPendingSections extends AbstractSet<Long> {
    private final Set<Long> positions = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Long>> regions = new HashMap<>();
    private static long key(int x, int z) { return (long)x << 32 | z & 0xffffffffL; }
    private static long region(long position) {
        int shift = 4 - VoxyPredictionBridge.level(position);
        return key(VoxyPredictionBridge.x(position) >> shift, VoxyPredictionBridge.z(position) >> shift);
    }
    @Override public synchronized boolean add(Long position) {
        if (!positions.add(position)) return false;
        regions.computeIfAbsent(region(position), ignored -> new HashSet<>()).add(position); return true;
    }
    @Override public synchronized boolean remove(Object value) {
        if (!(value instanceof Long position) || !positions.remove(position)) return false;
        long key = region(position); Set<Long> bucket = regions.get(key);
        bucket.remove(position); if (bucket.isEmpty()) regions.remove(key); return true;
    }
    @Override public synchronized void clear() { positions.clear(); regions.clear(); }
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

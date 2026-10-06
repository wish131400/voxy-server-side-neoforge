package dev.xantha.vss.compat;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Outstanding mesh work, scoped to one Voxy render service. No section or mesh data is retained. */
public final class StrictVoxyPipeline {
    private final Long2LongOpenHashMap pending = new Long2LongOpenHashMap();
    private long sequence;

    public synchronized void queued(long position) {
        pending.put(position, ++sequence);
        StrictLodVisibility.workChanged();
    }

    public synchronized Work started(long position) {
        return new Work(this, position, pending.get(position));
    }

    public synchronized void uploaded(Work work) {
        if (work.sequence() != 0 && pending.get(work.position()) == work.sequence()) {
            pending.remove(work.position());
            StrictLodVisibility.workChanged();
        }
    }

    public synchronized boolean idle(int cx, int sy, int cz) {
        if (pending.isEmpty()) return true;
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1;
            if (pending.containsKey(StrictVoxyNodeIndex.key(lod, cx >> shift, sy >> shift, cz >> shift))) return false;
        }
        return true;
    }

    /** Holds the pipeline lock once and visits each vertical ancestor only once. */
    public synchronized boolean idleRange(int cx, int minSectionY, int maxSectionY, int cz) {
        if (pending.isEmpty()) return true;
        int first = Math.min(minSectionY, maxSectionY), last = Math.max(minSectionY, maxSectionY);
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1;
            int minY = first >> shift, maxY = last >> shift;
            // Node keys retain eight Y bits, so longer spans repeat these keys.
            int count = (int) Math.min(256L, (long) maxY - minY + 1);
            for (int offset = 0; offset < count; offset++) {
                int y = minY + offset;
                if (pending.containsKey(StrictVoxyNodeIndex.key(lod, cx >> shift, y, cz >> shift))) return false;
            }
        }
        return true;
    }

    /** Checks each intersecting packed node once, under one lock for the whole box. */
    public synchronized boolean idleBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minZ > maxZ) return false;
        if (pending.isEmpty()) return true;
        int firstY = Math.min(minY, maxY), lastY = Math.max(minY, maxY);
        long nodes = 0;
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1;
            long countX = Math.min(1L << 24, (long) (maxX >> shift) - (minX >> shift) + 1);
            long countZ = Math.min(1L << 24, (long) (maxZ >> shift) - (minZ >> shift) + 1);
            long countY = Math.min(256L, (long) (lastY >> shift) - (firstY >> shift) + 1);
            nodes += countX * countY * countZ;
        }
        if (pending.size() < nodes) {
            var iterator = pending.keySet().iterator();
            while (iterator.hasNext()) {
                long position = iterator.nextLong();
                int lod = StrictVoxyNodeIndex.level(position);
                if (lod > 4) continue;
                int shift = lod + 1;
                if (containsWrapped(minX >> shift, maxX >> shift, StrictVoxyNodeIndex.x(position), 0xFFFFFF)
                        && containsWrapped(firstY >> shift, lastY >> shift, (byte) (position >>> 52), 255)
                        && containsWrapped(minZ >> shift, maxZ >> shift, StrictVoxyNodeIndex.z(position), 0xFFFFFF)) return false;
            }
            return true;
        }
        for (int lod = 0; lod <= 4; lod++) {
            int shift = lod + 1, startX = minX >> shift, startY = firstY >> shift, startZ = minZ >> shift;
            int countX = (int) Math.min(1L << 24, (long) (maxX >> shift) - startX + 1);
            int countZ = (int) Math.min(1L << 24, (long) (maxZ >> shift) - startZ + 1);
            int countY = (int) Math.min(256L, (long) (lastY >> shift) - startY + 1);
            for (int z = 0; z < countZ; z++) for (int x = 0; x < countX; x++) for (int y = 0; y < countY; y++)
                if (pending.containsKey(StrictVoxyNodeIndex.key(lod, startX + x, startY + y, startZ + z))) return false;
        }
        return true;
    }

    private static boolean containsWrapped(int min, int max, int value, int mask) {
        return (long) max - min >= mask || ((long) value - min & mask) <= (long) max - min;
    }

    public synchronized int size() { return pending.size(); }

    public record Work(StrictVoxyPipeline pipeline, long position, long sequence) {
        public void uploaded() { pipeline.uploaded(this); }
    }

    public interface Source { StrictVoxyPipeline vss$pipeline(); }
    public interface Mesh {
        Work vss$work();
        void vss$work(Work work);
    }
    public interface Batch {
        void vss$appendCompletedWork(java.util.Collection<Work> work);
        java.util.List<Work> vss$takeCompletedWork();
        void vss$restoreCompletedWork(java.util.Collection<Work> work);
    }
}

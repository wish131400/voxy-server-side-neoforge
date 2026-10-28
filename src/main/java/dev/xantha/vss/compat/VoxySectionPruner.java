package dev.xantha.vss.compat;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Bounded cache maintenance, independent of render FPS. Stable completed ranges need no new scan. */
final class VoxySectionPruner {
    private static final long INTERVAL = 50_000_000L;
    private Iterator<Map.Entry<Long, AtomicLong>> cursor;
    private long nextBatch, completedAdditions = -1, passAdditions;
    private int centerX, centerZ, range;
    private boolean started;

    void reset() { cursor = null; nextBatch = 0; completedAdditions = -1; started = false; }

    void prune(VoxySectionVersions requested, double x, double z, int radius, long now, LongConsumer removed) {
        if (started && now - nextBatch < 0) return;
        int cx = (int) Math.floor(x / 16), cz = (int) Math.floor(z / 16);
        if (cursor == null) {
            if (started && centerX == cx && centerZ == cz && range == radius
                    && completedAdditions == requested.additions()) return;
            centerX = cx; centerZ = cz; range = radius;
            passAdditions = requested.additions();
            cursor = requested.entrySet().iterator();
        }
        started = true;
        nextBatch = now + INTERVAL;
        for (int checked = 0; checked < 512 && cursor.hasNext(); checked++) {
            var entry = cursor.next(); long pos = entry.getKey();
            long span = 32L << VoxySectionPosition.level(pos);
            long bx = VoxySectionPosition.x(pos) * span, bz = VoxySectionPosition.z(pos) * span;
            // Use the current camera, including when it moves during a multi-batch pass.
            // Round retention outward to chunk boundaries so sub-chunk movement needs no rescan.
            long minX = (long) cx * 16 - radius, minZ = (long) cz * 16 - radius;
            long maxX = (long) cx * 16 + 16 + radius, maxZ = (long) cz * 16 + 16 + radius;
            if (bx > maxX || bz > maxZ || bx + span < minX || bz + span < minZ)
                if (requested.remove(pos, entry.getValue())) removed.accept(pos);
        }
        if (!cursor.hasNext()) {
            cursor = null;
            // Do not mark insertions concurrent with this pass as already visited.
            completedAdditions = passAdditions;
        }
    }
}

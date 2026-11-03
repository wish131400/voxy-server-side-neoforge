package dev.xantha.vss.client.prediction;

import java.util.concurrent.atomic.LongAdder;

/** Isolated benchmark only: exact final biome answers, owned by one sampler. */
final class PrototypeBiomeAnswerCache<T> {
    private static final int SLOTS = 16_384;
    private static final long ARRAY_PAYLOAD_BYTES = (long) SLOTS * (3 * Integer.BYTES + Long.BYTES);
    private final LongAdder tables = new LongAdder(), hits = new LongAdder(), misses = new LongAdder();
    private final LongAdder entries = new LongAdder(), replacements = new LongAdder(), nulls = new LongAdder();
    private final ThreadLocal<Table> local = ThreadLocal.withInitial(() -> {
        tables.increment();
        return new Table();
    });

    private static final class Table {
        final int[] xs = new int[SLOTS], ys = new int[SLOTS], zs = new int[SLOTS];
        final Object[] values = new Object[SLOTS];
    }

    @SuppressWarnings("unchecked")
    T get(int x, int y, int z) {
        var table = local.get();
        int slot = slot(x, y, z);
        Object value = table.values[slot];
        if (value != null && table.xs[slot] == x && table.ys[slot] == y && table.zs[slot] == z) {
            hits.increment();
            return (T) value;
        }
        misses.increment();
        return null;
    }

    void put(int x, int y, int z, T value) {
        // Missing answers must retain the original retry/fallback behavior.
        if (value == null) { nulls.increment(); return; }
        var table = local.get();
        int slot = slot(x, y, z);
        if (table.values[slot] == null) entries.increment();
        else replacements.increment();
        table.xs[slot] = x;
        table.ys[slot] = y;
        table.zs[slot] = z;
        table.values[slot] = value;
    }

    private static int slot(int x, int y, int z) {
        int hash = x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash & (SLOTS - 1);
    }

    String diagnostics() {
        long allocated = tables.sum();
        return "slotsPerThread=" + SLOTS + ",tables=" + allocated + ",entries=" + entries.sum()
                + ",hits=" + hits.sum() + ",misses=" + misses.sum() + ",replacements=" + replacements.sum()
                + ",nulls=" + nulls.sum() + ",arrayPayloadBytesUpperBound=" + allocated * ARRAY_PAYLOAD_BYTES;
    }
}

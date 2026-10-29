package dev.xantha.vss.compat;

/** Counts native 512-block root columns; no terrain, tile or node-set scan is required. */
public record VoxyCapacityPlan(float configuredSections, int serverChunks, int predictionBlocks,
                               int minY, int maxY, float sections, int radius, int layers, long roots) {
    public static final int BASE_CAPACITY = 200_000;

    public static VoxyCapacityPlan create(float configured, int server, int prediction, int minY, int maxY) {
        float sections = VoxyRenderAccess.renderDistance(configured, server, prediction);
        if (!Float.isFinite(sections) || sections < 0 || sections > 1_000_000 || maxY < minY)
            throw new IllegalArgumentException("Invalid Voxy horizon or dimension bounds");
        // Native setRenderDistance adds one top-level ring. Keep this margin in the capacity plan.
        int radius = (int) Math.ceil(sections + 1.0);
        int layers = Math.toIntExact((long) Math.floorDiv(maxY, 512) - Math.floorDiv(minY, 512) + 1);
        long columns = 0, square = (long) radius * radius;
        for (int x = -radius; x <= radius; x++) {
            long remaining = square - (long) x * x;
            long half = (long) Math.sqrt(remaining);
            while ((half + 1) * (half + 1) <= remaining) half++;
            while (half * half > remaining) half--;
            columns += half * 2 + 1;
        }
        return new VoxyCapacityPlan(configured, server, prediction, minY, maxY, sections, radius,
                layers, Math.multiplyExact(columns, layers));
    }

    /** Geometric growth also leaves room for native batches temporarily containing old and new roots. */
    public static int capacity(int current, long required) {
        if (required <= current) return current;
        long target = Math.max(required, current + Math.max(1024L, current / 2L));
        if (target > Integer.MAX_VALUE - 1031L)
            throw new IllegalStateException("Voxy node capacity exceeds addressable array size: " + required);
        target = (target + 1023) & ~1023L;
        return (int) target;
    }

    public int initialCapacity() { return capacity(BASE_CAPACITY, roots); }
}

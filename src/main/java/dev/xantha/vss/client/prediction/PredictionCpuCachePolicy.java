package dev.xantha.vss.client.prediction;

/** A byte target for reusable cold tiles, separate from active coverage and JVM safety headroom. */
final class PredictionCpuCachePolicy {
    static final long COLD_AGE_NANOS = 10_000_000_000L;
    final long highBytes, lowBytes;
    private boolean trimming;

    PredictionCpuCachePolicy(long maxHeap) {
        this(Math.max(128L << 20, Math.min(768L << 20, maxHeap / 12)), true);
    }
    PredictionCpuCachePolicy(long highBytes, boolean explicit) {
        this.highBytes = Math.max(0, highBytes); lowBytes = this.highBytes * 3 / 4;
    }
    long reclaim(long coldBytes) {
        if (coldBytes > highBytes) trimming = true;
        if (coldBytes <= lowBytes) trimming = false;
        return trimming ? Math.max(0, coldBytes - lowBytes) : 0;
    }
    static boolean safe(boolean desired, boolean near, boolean scoped, boolean pending,
                        boolean stored, boolean cpuFallback,
                        boolean features, boolean featureFallback, long ageNanos) {
        return !desired && !near && !scoped && !pending && stored && ageNanos >= COLD_AGE_NANOS
                && cpuFallback && (!features || featureFallback);
    }
}

package dev.xantha.vss.networking.client;

record RetryBackoffPolicy(
        long dirtyRefreshDelayNanos,
        long normalDelayNanos,
        long generationDelayNanos,
        long backpressureDelayNanos,
        int dirtyRefreshMaxShift,
        int normalMaxShift,
        int generationMaxShift) {

    RetryBackoffPolicy {
        dirtyRefreshDelayNanos = Math.max(0L, dirtyRefreshDelayNanos);
        normalDelayNanos = Math.max(0L, normalDelayNanos);
        generationDelayNanos = Math.max(0L, generationDelayNanos);
        backpressureDelayNanos = Math.max(0L, backpressureDelayNanos);
        dirtyRefreshMaxShift = Math.max(0, dirtyRefreshMaxShift);
        normalMaxShift = Math.max(0, normalMaxShift);
        generationMaxShift = Math.max(0, generationMaxShift);
    }

    long retryDelay(boolean dirtyRefresh, boolean generationCandidate, int attempt) {
        BackoffCurve curve = curve(dirtyRefresh, generationCandidate);
        int shift = Math.min(curve.maxShift(), Math.max(0, attempt - 1));
        return saturatingShift(curve.baseDelayNanos(), shift);
    }

    private BackoffCurve curve(boolean dirtyRefresh, boolean generationCandidate) {
        if (dirtyRefresh) {
            return new BackoffCurve(dirtyRefreshDelayNanos, dirtyRefreshMaxShift);
        }
        if (generationCandidate) {
            return new BackoffCurve(generationDelayNanos, generationMaxShift);
        }
        return new BackoffCurve(normalDelayNanos, normalMaxShift);
    }

    private static long saturatingShift(long value, int shift) {
        if (value <= 0L || shift <= 0) {
            return value;
        }
        if (shift >= Long.SIZE - 1 || value > (Long.MAX_VALUE >> shift)) {
            return Long.MAX_VALUE;
        }
        return value << shift;
    }

    private record BackoffCurve(long baseDelayNanos, int maxShift) {
    }
}

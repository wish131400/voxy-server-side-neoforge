package dev.xantha.vss.networking.server.sending;

final class QueuedPayloadExpiryPolicy {
    private static final long BASE_MAX_QUEUED_PAYLOAD_AGE_NANOS = 20_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long QUEUE_WAIT_GRACE_MULTIPLIER = 2L;

    private QueuedPayloadExpiryPolicy() {
    }

    static boolean isExpired(
            long queuedNanos,
            long nowNanos,
            int wireBytes,
            long queuedBytesAheadAtEnqueue,
            long effectiveBandwidthBytesPerSecond) {
        long ageNanos = Math.max(0L, nowNanos - queuedNanos);
        long allowedAgeNanos = allowedAgeNanos(
                wireBytes,
                queuedBytesAheadAtEnqueue,
                effectiveBandwidthBytesPerSecond);
        return ageNanos > allowedAgeNanos;
    }

    static long allowedAgeNanos(
            int wireBytes,
            long queuedBytesAheadAtEnqueue,
            long effectiveBandwidthBytesPerSecond) {
        long effectiveBandwidth = Math.max(1L, effectiveBandwidthBytesPerSecond);
        long queuedBytes = addSaturated(Math.max(0L, queuedBytesAheadAtEnqueue), Math.max(1L, wireBytes));
        long expectedWaitNanos = bytesToNanos(queuedBytes, effectiveBandwidth);
        long dynamicAgeNanos = multiplySaturated(expectedWaitNanos, QUEUE_WAIT_GRACE_MULTIPLIER);
        return Math.max(BASE_MAX_QUEUED_PAYLOAD_AGE_NANOS, dynamicAgeNanos);
    }

    private static long bytesToNanos(long bytes, long bytesPerSecond) {
        if (bytes > Long.MAX_VALUE / NANOS_PER_SECOND) {
            return Long.MAX_VALUE;
        }
        return bytes * NANOS_PER_SECOND / bytesPerSecond;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long multiplySaturated(long value, long factor) {
        if (factor > 0L && value > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return value * factor;
    }
}

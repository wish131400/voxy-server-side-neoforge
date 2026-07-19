package dev.xantha.vss.common;

/**
 * Token bucket used by the LOD sender.
 *
 * <p>The bucket may go negative after an oversized column is sent. That debt is intentional:
 * it keeps VSS from pushing another large payload into Netty until the configured wire-byte
 * budget has caught up.
 */
public final class BandwidthLimiter {
    private static final long MIN_REFILL_INTERVAL_NANOS = 1_000_000L;
    private static final long MAX_REFILL_WINDOW_NANOS = 1_000_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_PRIMED_SEND_CREDIT_BYTES = 128L * 1024L;

    @FunctionalInterface
    public interface NanoClock {
        long nanoTime();
    }

    private final NanoClock clock;

    private long availableBytes;
    private long lastRefillNanos;
    private long totalBytesSent;
    private long desiredBandwidth = Long.MAX_VALUE;

    public BandwidthLimiter(NanoClock clock) {
        this.clock = clock;
        this.lastRefillNanos = clock.nanoTime();
    }

    public void reset() {
        availableBytes = 0L;
        lastRefillNanos = clock.nanoTime();
        totalBytesSent = 0L;
    }

    public void setDesiredBandwidth(long desiredBandwidth) {
        this.desiredBandwidth = desiredBandwidth > 0L ? desiredBandwidth : Long.MAX_VALUE;
    }

    public long desiredBandwidth() {
        return desiredBandwidth;
    }

    public long totalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Current send credit. Negative values mean a previously sent oversized payload is still being
     * paid back by future refills.
     */
    public long availableBytes() {
        return availableBytes;
    }

    public boolean canSend(long configuredLimit) {
        refill(configuredLimit);
        return availableBytes > 0L;
    }

    public void recordSend(int bytes) {
        if (bytes <= 0) {
            return;
        }
        availableBytes = subtractSaturated(availableBytes, bytes);
        totalBytesSent = addSaturated(totalBytesSent, bytes);
    }

    public void primeSendCredit(long configuredLimit) {
        refill(configuredLimit);
        long primedCredit = Math.min(
                BandwidthProfile.sendBurstCapBytes(effectiveLimit(configuredLimit)),
                MAX_PRIMED_SEND_CREDIT_BYTES);
        availableBytes = primedCredit;
        lastRefillNanos = clock.nanoTime();
    }

    private void refill(long configuredLimit) {
        long limit = effectiveLimit(configuredLimit);
        long now = clock.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos < MIN_REFILL_INTERVAL_NANOS) {
            return;
        }

        lastRefillNanos = now;
        elapsedNanos = Math.min(elapsedNanos, MAX_REFILL_WINDOW_NANOS);
        long refill = refillBytes(elapsedNanos, limit);
        availableBytes = Math.min(addSaturated(availableBytes, refill), BandwidthProfile.sendBurstCapBytes(limit));
    }

    private long effectiveLimit(long configuredLimit) {
        long safeConfiguredLimit = Math.max(1L, configuredLimit);
        return Math.min(safeConfiguredLimit, desiredBandwidth);
    }

    private static long refillBytes(long elapsedNanos, long limit) {
        if (limit > Long.MAX_VALUE / elapsedNanos) {
            return Long.MAX_VALUE;
        }
        return elapsedNanos * limit / NANOS_PER_SECOND;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    private static long subtractSaturated(long left, long right) {
        if (right > 0L && left < Long.MIN_VALUE + right) {
            return Long.MIN_VALUE;
        }
        if (right < 0L && left > Long.MAX_VALUE + right) {
            return Long.MAX_VALUE;
        }
        return left - right;
    }
}

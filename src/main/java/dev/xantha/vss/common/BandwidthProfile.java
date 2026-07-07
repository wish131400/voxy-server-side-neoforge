package dev.xantha.vss.common;

/**
 * Shared bandwidth sizing rules for the LOD sender.
 *
 * <p>The payload splitter and the send token bucket must agree on their burst geometry:
 * a normal split payload should fit inside the credit the bucket can accumulate.
 */
public final class BandwidthProfile {
    private static final long BURST_DIVISOR = 4L;
    private static final long MIN_SEND_BURST_CAP_BYTES = 64L * 1024L;
    private static final int MIN_TARGET_WIRE_BYTES = 8 * 1024;
    private static final int MAX_TARGET_WIRE_BYTES = 256 * 1024;

    private BandwidthProfile() {
    }

    public static long sendBurstCapBytes(long bytesPerSecond) {
        return Math.max(MIN_SEND_BURST_CAP_BYTES, burstBytes(bytesPerSecond));
    }

    public static int targetWireBytes(long bytesPerSecond) {
        long target = Math.max(
                MIN_TARGET_WIRE_BYTES,
                Math.min(MAX_TARGET_WIRE_BYTES, burstBytes(bytesPerSecond)));
        return Math.toIntExact(Math.min(target, sendBurstCapBytes(bytesPerSecond)));
    }

    private static long burstBytes(long bytesPerSecond) {
        return Math.max(1L, bytesPerSecond) / BURST_DIVISOR;
    }
}

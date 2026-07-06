package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BandwidthLimiterTest {

    private static final long ONE_SEC = 1_000_000_000L;

    private static final class FakeClock implements BandwidthLimiter.NanoClock {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }

        void advanceNanos(long nanos) {
            now += nanos;
        }
    }

    @Test
    void newLimiterHasNoCreditUntilRefill() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);

        assertEquals(0L, limiter.availableBytes());
        assertFalse(limiter.canSend(1000L));
    }

    @Test
    void refillAccruesProportionalToElapsedTime() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);
        long limit = 4000L;

        clock.advanceNanos(ONE_SEC / 2);

        assertTrue(limiter.canSend(limit));
        assertEquals(1000L, limiter.availableBytes());
    }

    @Test
    void burstCapIsQuarterOfLimit() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);
        long limit = 8000L;

        clock.advanceNanos(ONE_SEC * 10);
        limiter.canSend(limit);

        assertEquals(2000L, limiter.availableBytes());
    }

    @Test
    void recordSendDrainsBucketAndCounts() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);
        long limit = 4000L;
        clock.advanceNanos(ONE_SEC);
        limiter.canSend(limit);

        limiter.recordSend(600);

        assertEquals(400L, limiter.availableBytes());
        assertEquals(600L, limiter.totalBytesSent());

        limiter.recordSend(1000);

        assertEquals(0L, limiter.availableBytes());
        assertEquals(1600L, limiter.totalBytesSent());
    }

    @Test
    void refillIgnoredBelowMinInterval() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);

        clock.advanceNanos(500_000L);

        assertFalse(limiter.canSend(1_000_000L));
        assertEquals(0L, limiter.availableBytes());
    }

    @Test
    void desiredBandwidthCapsEffectiveLimit() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);
        limiter.setDesiredBandwidth(2000L);

        clock.advanceNanos(ONE_SEC);
        limiter.canSend(1_000_000L);

        assertEquals(500L, limiter.availableBytes());
    }

    @Test
    void zeroOrNegativeDesiredBandwidthMeansUnlimited() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);

        limiter.setDesiredBandwidth(0L);

        assertEquals(Long.MAX_VALUE, limiter.desiredBandwidth());
    }

    @Test
    void primeSendCreditIsCappedByMaxPrimedCredit() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);

        limiter.primeSendCredit(1_000_000_000L);

        assertEquals(128L * 1024L, limiter.availableBytes());
    }

    @Test
    void primeSendCreditSmallLimitUsesBurstCap() {
        FakeClock clock = new FakeClock();
        BandwidthLimiter limiter = new BandwidthLimiter(clock);

        limiter.primeSendCredit(4000L);

        assertEquals(1000L, limiter.availableBytes());
    }
}

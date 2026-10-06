package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

class BandwidthOptimizerCompatTest {
    @Test
    void observesClosedEpochWithoutCreatingOrMutatingBoState() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertFalse(BandwidthOptimizerCompat.isWaitingForClient(channel));
            assertFalse(channel.hasAttr(BandwidthOptimizerCompat.SESSION));
            var session = new Session();
            channel.attr(BandwidthOptimizerCompat.SESSION).set(session);
            assertFalse(BandwidthOptimizerCompat.isWaitingForClient(channel));
            session.waiting = true;
            assertTrue(BandwidthOptimizerCompat.isWaitingForClient(channel));
            session.waiting = false;
            assertFalse(BandwidthOptimizerCompat.isWaitingForClient(channel));
            channel.attr(BandwidthOptimizerCompat.SESSION).set(new Object());
            assertFalse(BandwidthOptimizerCompat.isWaitingForClient(channel));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    public static final class Session {
        boolean waiting;
        public boolean isOutboundStreamingEpochClosed() { return waiting; }
    }
}

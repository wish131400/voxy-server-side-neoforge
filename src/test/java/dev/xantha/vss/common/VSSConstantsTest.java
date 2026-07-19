package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VSSConstantsTest {

    @Test
    void protocolVersionMatchesGenerationQueueProtocol() {
        assertEquals(41, VSSConstants.PROTOCOL_VERSION);
    }

    @Test
    void columnVersionIsStrictlyMonotonic() {
        long previous = VSSConstants.columnVersion();

        for (int i = 0; i < 1000; i++) {
            long next = VSSConstants.columnVersion();
            assertTrue(next > previous);
            previous = next;
        }
    }
}

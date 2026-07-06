package dev.xantha.vss.networking.server.request;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import org.junit.jupiter.api.Test;

class BatchResponseAccumulatorTest {

    @Test
    void emptyAccumulatorHasNoResponses() {
        BatchResponseAccumulator responses = new BatchResponseAccumulator(4);

        assertFalse(responses.hasResponses());
        assertEquals(0, responses.count());
    }

    @Test
    void payloadContainsResponsesInInsertionOrder() {
        BatchResponseAccumulator responses = new BatchResponseAccumulator(4);

        responses.add(VSSConstants.RESPONSE_RATE_LIMITED, 10);
        responses.add(VSSConstants.RESPONSE_UP_TO_DATE, 11);

        BatchResponseS2CPayload payload = responses.toPayload();
        assertTrue(responses.hasResponses());
        assertEquals(2, payload.count());
        assertArrayEquals(new byte[] {VSSConstants.RESPONSE_RATE_LIMITED, VSSConstants.RESPONSE_UP_TO_DATE}, payload.responseTypes());
        assertArrayEquals(new int[] {10, 11}, payload.requestIds());
    }

    @Test
    void payloadArraysAreTrimmedToResponseCount() {
        BatchResponseAccumulator responses = new BatchResponseAccumulator(8);

        responses.add(VSSConstants.RESPONSE_BACKPRESSURE, 42);

        BatchResponseS2CPayload payload = responses.toPayload();
        assertEquals(1, payload.count());
        assertEquals(1, payload.responseTypes().length);
        assertEquals(1, payload.requestIds().length);
    }

    @Test
    void extraResponsesBeyondCapacityAreIgnored() {
        BatchResponseAccumulator responses = new BatchResponseAccumulator(1);

        responses.add(VSSConstants.RESPONSE_BACKPRESSURE, 1);
        responses.add(VSSConstants.RESPONSE_UP_TO_DATE, 2);

        BatchResponseS2CPayload payload = responses.toPayload();
        assertEquals(1, payload.count());
        assertArrayEquals(new int[] {1}, payload.requestIds());
    }
}

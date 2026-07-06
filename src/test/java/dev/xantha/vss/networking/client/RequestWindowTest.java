package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.VSSConstants;
import org.junit.jupiter.api.Test;

class RequestWindowTest {

    @Test
    void emptyWindowHasNoCapacity() {
        RequestWindow window = new RequestWindow(0, 0, 0, 0, 0, 0);

        assertFalse(window.hasCapacity());
        assertFalse(window.hasAnySyncCapacity());
        assertEquals(0, window.remaining());
    }

    @Test
    void syncRingsConsumeTheirOwnBuckets() {
        RequestWindow window = new RequestWindow(1, 1, 1, 1, 0, 0);

        window.record(false, false, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS);
        window.record(false, false, VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS + 1);
        window.record(false, false, VSSConstants.SYNC_MID_DISTANCE_CHUNKS + 1);
        window.record(false, false, VSSConstants.SYNC_FAR_DISTANCE_CHUNKS + 1);

        assertEquals(4, window.syncSent());
        assertFalse(window.hasAnySyncCapacity());
        assertEquals(0, window.remaining());
    }

    @Test
    void dirtyAndGenerationUseIndependentBudgets() {
        RequestWindow window = new RequestWindow(1, 0, 0, 0, 1, 1);

        assertTrue(window.hasGenerationCapacity());
        assertTrue(window.hasAnyNormalCandidateCapacity());
        assertTrue(window.canSend(true, false, 0));
        assertTrue(window.canSend(false, true, 0));
        window.record(true, false, 0);
        window.record(false, true, 0);

        assertEquals(1, window.dirtySent());
        assertEquals(1, window.generationSent());
        assertFalse(window.hasGenerationCapacity());
        assertEquals(1, window.remaining());
        assertTrue(window.hasNearSyncCapacity());
    }

    @Test
    void generationCapacityCanDriveNormalCandidateScanningWithoutSyncBudget() {
        RequestWindow window = new RequestWindow(0, 0, 0, 0, 1, 0);

        assertFalse(window.hasAnySyncCapacity());
        assertTrue(window.hasAnyNormalCandidateCapacity());
        assertTrue(window.hasNormalCandidateCapacity(VSSConstants.SYNC_FAR_DISTANCE_CHUNKS + 1));
        assertFalse(window.canSend(false, false, VSSConstants.SYNC_FAR_DISTANCE_CHUNKS + 1));
        assertTrue(window.canSend(false, true, VSSConstants.SYNC_FAR_DISTANCE_CHUNKS + 1));

        window.record(false, true, VSSConstants.SYNC_FAR_DISTANCE_CHUNKS + 1);

        assertFalse(window.hasAnyNormalCandidateCapacity());
    }

    @Test
    void remainingNeverCountsNegativeBuckets() {
        RequestWindow window = new RequestWindow(0, 0, 0, 0, 0, 1);

        window.record(true, false, 0);
        window.record(true, false, 0);

        assertEquals(2, window.dirtySent());
        assertEquals(0, window.remaining());
        assertFalse(window.canSend(true, false, 0));
    }
}

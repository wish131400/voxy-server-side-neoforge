package dev.xantha.vss.networking.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScanBudgetTest {
    @Test void expiredBudgetNeverReopensForLaterPhases() {
        var budget = new LodRequestManager.ScanBudget(4096, 10, () -> 11L);
        for (int i = 0; i < 256; i++) assertFalse(budget.canScanMore());
    }

    @Test void phaseSliceChargesParentAndLeavesDiscoveryCapacity() {
        long[] now = {0};
        var budget = new LodRequestManager.ScanBudget(9, 900, () -> now[0]);
        var slice = budget.slice(3);
        for (int i = 0; i < 3; i++) {
            assertTrue(slice.canScanMore()); slice.recordCandidate();
        }
        assertFalse(slice.canScanMore());
        assertEquals(6, budget.remainingCandidates());
        assertTrue(budget.canScanMore());
        now[0] = 901;
        for (int i = 0; i < 64; i++) budget.canScanMore();
        assertFalse(budget.canScanMore());
        assertFalse(budget.slice(2).canScanMore());
    }
}

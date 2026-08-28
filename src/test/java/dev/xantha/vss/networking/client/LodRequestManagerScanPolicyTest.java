package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LodRequestManagerScanPolicyTest {
    @Test
    void firstScanDoesNotTrustClientRenderDistanceAsServerCoverage() {
        assertEquals(0, LodRequestManager.getVanillaProtectedSyncDistance());
    }

    @Test
    void outerScanWaitsForNearScanToComplete() {
        assertEquals(false, LodRequestManager.shouldRunOuterScan(false));
        assertEquals(true, LodRequestManager.shouldRunOuterScan(true));
    }
}

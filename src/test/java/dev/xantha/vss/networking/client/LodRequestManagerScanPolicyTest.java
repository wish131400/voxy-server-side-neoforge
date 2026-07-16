package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LodRequestManagerScanPolicyTest {
    @Test
    void firstScanDoesNotTrustClientRenderDistanceAsServerCoverage() {
        assertEquals(0, LodRequestManager.getVanillaProtectedSyncDistance());
    }
}

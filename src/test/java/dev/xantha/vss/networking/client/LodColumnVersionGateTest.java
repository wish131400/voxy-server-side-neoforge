package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LodColumnVersionGateTest {

    @Test
    void activeRequestAcceptsEqualCurrentVersionButLatePacketDoesNot() {
        assertTrue(LodRequestManager.isColumnVersionAllowed(100L, 0L, 100L, false));
        assertFalse(LodRequestManager.isColumnVersionAllowed(100L, 0L, 100L, true));
    }

    @Test
    void dirtyRequirementRejectsOlderColumn() {
        assertFalse(LodRequestManager.isColumnVersionAllowed(100L, 200L, 199L, false));
        assertTrue(LodRequestManager.isColumnVersionAllowed(100L, 200L, 200L, false));
    }

    @Test
    void firstColumnMustStillHavePositiveVersion() {
        assertFalse(LodRequestManager.isColumnVersionAllowed(-1L, 0L, 0L, false));
        assertTrue(LodRequestManager.isColumnVersionAllowed(-1L, 0L, 1L, true));
    }
}


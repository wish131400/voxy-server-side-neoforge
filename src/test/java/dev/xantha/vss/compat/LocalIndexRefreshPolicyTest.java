package dev.xantha.vss.compat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalIndexRefreshPolicyTest {
    @Test void stationaryQueriesDoNotArmTeleportRefresh() {
        var policy = new LocalIndexRefreshPolicy();
        policy.observePlayer(0, 0, 0);
        policy.startedBuild();
        for (int i = 1; i < 30; i++) {
            policy.observePlayer(0, 0, i * 1_000_000_000L);
            assertFalse(policy.shouldRefresh(true, 0, i * 1_000_000_000L));
        }
        assertTrue(policy.shouldRefresh(true, 0, 30_000_000_001L));
    }

    @Test void walkingAndActualTeleportRetainTheirRefreshCadences() {
        var policy = new LocalIndexRefreshPolicy();
        policy.observePlayer(0, 0, 0); policy.startedBuild();
        policy.observePlayer(8, 0, 1_000_000_000L);
        assertFalse(policy.shouldRefresh(true, 0, 1_000_000_000L));
        assertTrue(policy.shouldRefresh(true, 0, 5_000_000_001L));
        policy.startedBuild();
        policy.observePlayer(80, 0, 6_000_000_000L);
        assertTrue(policy.shouldRefresh(true, 5_000_000_001L, 6_000_000_000L));
        policy.startedBuild();
        assertFalse(policy.shouldRefresh(true, 6_000_000_000L, 6_400_000_000L));
        assertTrue(policy.shouldRefresh(true, 6_000_000_000L, 6_600_000_000L));
        assertFalse(policy.shouldRefresh(true, 35_000_000_000L, 37_000_000_000L));
        assertTrue(policy.shouldRefresh(false, 0, 0));
    }
}

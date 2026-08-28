package dev.xantha.vss.networking.server.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FarVehicleSyncPolicyTest {
    private static final long REFRESH_NANOS = 10_000_000_000L;

    @Test
    void fullDataFlagRequiresUsableInitializationData() {
        assertFalse(FarVehicleSyncPolicy.hasInitializationData(false, 0));
        assertTrue(FarVehicleSyncPolicy.hasInitializationData(true, 0));
        assertTrue(FarVehicleSyncPolicy.hasInitializationData(false, 1));
    }

    @Test
    void downgradedFinalPayloadDoesNotMarkFullDataDelivered() {
        FarVehicleSyncPolicy.Cache cache = new FarVehicleSyncPolicy.Cache();
        cache.observeChain(List.of(identity(41, "create:contraption", 1L)));
        long now = 100_000_000_000L;

        assertTrue(cache.shouldAttemptFullData(now, REFRESH_NANOS));
        cache.recordFinalPayload(true, false, now);

        assertEquals(0L, cache.lastFullDataNanos());
        assertEquals(1, cache.consecutiveFailures());
        assertFalse(cache.shouldAttemptFullData(now + FarVehicleSyncPolicy.RETRY_BASE_NANOS - 1L, REFRESH_NANOS));
        assertTrue(cache.shouldAttemptFullData(now + FarVehicleSyncPolicy.RETRY_BASE_NANOS, REFRESH_NANOS));
    }

    @Test
    void successfulFinalPayloadStartsNormalRefreshInterval() {
        FarVehicleSyncPolicy.Cache cache = new FarVehicleSyncPolicy.Cache();
        cache.observeChain(List.of(identity(41, "create:contraption", 1L)));
        long now = 100_000_000_000L;

        cache.recordFinalPayload(true, true, now);

        assertEquals(now, cache.lastFullDataNanos());
        assertEquals(0, cache.consecutiveFailures());
        assertFalse(cache.shouldAttemptFullData(now + REFRESH_NANOS - 1L, REFRESH_NANOS));
        assertTrue(cache.shouldAttemptFullData(now + REFRESH_NANOS, REFRESH_NANOS));
    }

    @Test
    void repeatedFailuresBackOffButRemainBounded() {
        FarVehicleSyncPolicy.Cache cache = new FarVehicleSyncPolicy.Cache();
        cache.observeChain(List.of(identity(41, "create:contraption", 1L)));
        long now = 100_000_000_000L;
        long delay = 0L;

        for (int i = 0; i < 8; i++) {
            cache.recordFinalPayload(true, false, now);
            delay = cache.nextFullDataAttemptNanos() - now;
            now = cache.nextFullDataAttemptNanos();
        }

        assertEquals(FarVehicleSyncPolicy.RETRY_MAX_NANOS, delay);
        assertEquals(4, cache.consecutiveFailures());
    }

    @Test
    void changingVehicleChainResetsSuccessAndBackoff() {
        FarVehicleSyncPolicy.Cache cache = new FarVehicleSyncPolicy.Cache();
        cache.observeChain(List.of(identity(41, "create:contraption", 1L)));
        long now = 100_000_000_000L;
        cache.recordFinalPayload(true, true, now);

        cache.observeChain(List.of(identity(42, "create:contraption", 2L)));

        assertEquals(0L, cache.lastFullDataNanos());
        assertEquals(0, cache.consecutiveFailures());
        assertTrue(cache.shouldAttemptFullData(now, REFRESH_NANOS));
    }

    @Test
    void reusedEntityIdWithDifferentUuidResetsCache() {
        FarVehicleSyncPolicy.Cache cache = new FarVehicleSyncPolicy.Cache();
        long now = 100_000_000_000L;
        cache.observeChain(List.of(identity(41, "create:contraption", 1L)));
        cache.recordFinalPayload(true, true, now);

        cache.observeChain(List.of(identity(41, "create:contraption", 2L)));

        assertEquals(0L, cache.lastFullDataNanos());
        assertTrue(cache.shouldAttemptFullData(now, REFRESH_NANOS));
    }

    private static FarVehicleSyncPolicy.VehicleIdentity identity(int id, String type, long uuidLeast) {
        return new FarVehicleSyncPolicy.VehicleIdentity(id, type, new UUID(0L, uuidLeast));
    }
}

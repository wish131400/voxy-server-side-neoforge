package dev.xantha.vss.networking.server.broadcast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FarVehicleSyncPolicy {
    static final long RETRY_BASE_NANOS = 10_000_000_000L;
    static final long RETRY_MAX_NANOS = 60_000_000_000L;

    private FarVehicleSyncPolicy() {
    }

    static boolean hasInitializationData(boolean hasEntityData, int spawnDataBytes) {
        return hasEntityData || spawnDataBytes > 0;
    }

    record VehicleIdentity(int entityId, String entityTypeId, UUID entityUuid) {
    }

    static final class Cache {
        private final List<VehicleIdentity> vehicleIdentities = new ArrayList<>();
        private long lastFullDataNanos;
        private long nextFullDataAttemptNanos;
        private int consecutiveFailures;

        void observeChain(List<VehicleIdentity> currentVehicleIdentities) {
            if (vehicleIdentities.equals(currentVehicleIdentities)) {
                return;
            }
            vehicleIdentities.clear();
            vehicleIdentities.addAll(currentVehicleIdentities);
            lastFullDataNanos = 0L;
            nextFullDataAttemptNanos = 0L;
            consecutiveFailures = 0;
        }

        boolean shouldAttemptFullData(long now, long refreshIntervalNanos) {
            return now >= nextFullDataAttemptNanos
                    && (lastFullDataNanos == 0L || now - lastFullDataNanos >= refreshIntervalNanos);
        }

        void recordFinalPayload(boolean attemptedFullData, boolean deliveredFullData, long now) {
            if (!attemptedFullData) {
                return;
            }
            if (deliveredFullData) {
                lastFullDataNanos = now;
                nextFullDataAttemptNanos = now + RETRY_BASE_NANOS;
                consecutiveFailures = 0;
                return;
            }

            consecutiveFailures = Math.min(consecutiveFailures + 1, 4);
            long delay = Math.min(RETRY_MAX_NANOS, RETRY_BASE_NANOS << (consecutiveFailures - 1));
            nextFullDataAttemptNanos = now + delay;
        }

        long lastFullDataNanos() {
            return lastFullDataNanos;
        }

        long nextFullDataAttemptNanos() {
            return nextFullDataAttemptNanos;
        }

        int consecutiveFailures() {
            return consecutiveFailures;
        }
    }
}

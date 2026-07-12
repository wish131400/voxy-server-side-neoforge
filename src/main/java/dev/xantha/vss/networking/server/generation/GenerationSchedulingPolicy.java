package dev.xantha.vss.networking.server.generation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class GenerationSchedulingPolicy {
    private GenerationSchedulingPolicy() {
    }

    static boolean hasPerPlayerCapacity(
            Map<UUID, Integer> activeCounts,
            Iterable<UUID> callbacks,
            int perPlayerLimit) {
        int limit = Math.max(0, perPlayerLimit);
        Map<UUID, Integer> required = new HashMap<>();
        for (UUID playerUuid : callbacks) {
            required.merge(playerUuid, 1, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> entry : required.entrySet()) {
            if (activeCounts.getOrDefault(entry.getKey(), 0) + entry.getValue() > limit) {
                return false;
            }
        }
        return true;
    }

    static void releaseSlots(Map<UUID, Integer> activeCounts, Iterable<UUID> callbacks) {
        for (UUID playerUuid : callbacks) {
            Integer count = activeCounts.get(playerUuid);
            if (count == null) {
                continue;
            }
            if (count <= 1) {
                activeCounts.remove(playerUuid);
            } else {
                activeCounts.put(playerUuid, count - 1);
            }
        }
    }

    static int compare(
            boolean leftPriority,
            int leftRing,
            long leftSequence,
            boolean rightPriority,
            int rightRing,
            long rightSequence) {
        int priorityCompare = Boolean.compare(rightPriority, leftPriority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        int ringCompare = Integer.compare(leftRing, rightRing);
        if (ringCompare != 0) {
            return ringCompare;
        }
        return Long.compare(leftSequence, rightSequence);
    }

    static boolean isCurrentRevision(long currentRevision, long queuedRevision) {
        return currentRevision == queuedRevision;
    }

    static <K, V> boolean removeIdentity(Map<K, V> values, K key, V expected) {
        if (values.get(key) != expected) {
            return false;
        }
        values.remove(key);
        return true;
    }
}

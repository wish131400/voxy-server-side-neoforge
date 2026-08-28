package dev.xantha.vss.networking.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Tracks dimensions that still need one cache-only replay after a Xaero reload. */
final class CacheOnlyReloadTracker {
    private final Set<String> pendingDimensions = new HashSet<>();
    private String activeDimension;

    void begin(Collection<String> dimensions, String currentDimension) {
        pendingDimensions.clear();
        if (dimensions != null) {
            pendingDimensions.addAll(dimensions);
        }
        if (currentDimension != null) {
            pendingDimensions.add(currentDimension);
        }
        enter(currentDimension);
    }

    void enter(String dimension) {
        activeDimension = dimension != null && pendingDimensions.contains(dimension)
                ? dimension
                : null;
    }

    boolean isActive() {
        return activeDimension != null;
    }

    boolean completeActive() {
        if (activeDimension == null) {
            return false;
        }
        pendingDimensions.remove(activeDimension);
        activeDimension = null;
        return true;
    }

    int pendingCount() {
        return pendingDimensions.size();
    }

    void clear() {
        pendingDimensions.clear();
        activeDimension = null;
    }
}

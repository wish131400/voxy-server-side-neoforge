package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSConstants;

final class RequestWindow {
    private int nearSyncRemaining;
    private int midSyncRemaining;
    private int farSyncRemaining;
    private int distantSyncRemaining;
    private int generationRemaining;
    private int dirtyRemaining;
    private int syncSent;
    private int generationSent;
    private int dirtySent;

    RequestWindow(
            int nearSyncRemaining,
            int midSyncRemaining,
            int farSyncRemaining,
            int distantSyncRemaining,
            int generationRemaining,
            int dirtyRemaining) {
        this.nearSyncRemaining = nearSyncRemaining;
        this.midSyncRemaining = midSyncRemaining;
        this.farSyncRemaining = farSyncRemaining;
        this.distantSyncRemaining = distantSyncRemaining;
        this.generationRemaining = generationRemaining;
        this.dirtyRemaining = dirtyRemaining;
    }

    boolean hasCapacity() {
        return hasAnySyncCapacity() || generationRemaining > 0 || dirtyRemaining > 0;
    }

    boolean hasAnySyncCapacity() {
        return nearSyncRemaining > 0 || midSyncRemaining > 0 || farSyncRemaining > 0 || distantSyncRemaining > 0;
    }

    boolean hasGenerationCapacity() {
        return generationRemaining > 0;
    }

    boolean hasAnyNormalCandidateCapacity() {
        return hasAnySyncCapacity() || hasGenerationCapacity();
    }

    boolean hasNormalCandidateCapacity(int ring) {
        return hasSyncCapacity(ring) || hasGenerationCapacity();
    }

    boolean hasNearSyncCapacity() {
        return nearSyncRemaining > 0;
    }

    boolean hasSyncCapacity(int ring) {
        return syncRemainingForRing(ring) > 0;
    }

    int remaining() {
        return Math.max(0, nearSyncRemaining)
                + Math.max(0, midSyncRemaining)
                + Math.max(0, farSyncRemaining)
                + Math.max(0, distantSyncRemaining)
                + Math.max(0, generationRemaining)
                + Math.max(0, dirtyRemaining);
    }

    boolean canSend(boolean dirtyRefresh, boolean generationCandidate, int ring) {
        if (dirtyRefresh) {
            return dirtyRemaining > 0;
        }
        return generationCandidate ? generationRemaining > 0 : hasSyncCapacity(ring);
    }

    void record(boolean dirtyRefresh, boolean generationCandidate, int ring) {
        if (dirtyRefresh) {
            dirtyRemaining--;
            dirtySent++;
        } else if (generationCandidate) {
            generationRemaining--;
            generationSent++;
        } else {
            decrementSyncRemaining(ring);
            syncSent++;
        }
    }

    int syncSent() {
        return syncSent;
    }

    int generationSent() {
        return generationSent;
    }

    int dirtySent() {
        return dirtySent;
    }

    private int syncRemainingForRing(int ring) {
        if (ring <= VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS) {
            return nearSyncRemaining;
        }
        if (ring <= VSSConstants.SYNC_MID_DISTANCE_CHUNKS) {
            return midSyncRemaining;
        }
        if (ring <= VSSConstants.SYNC_FAR_DISTANCE_CHUNKS) {
            return farSyncRemaining;
        }
        return distantSyncRemaining;
    }

    private void decrementSyncRemaining(int ring) {
        if (ring <= VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS) {
            nearSyncRemaining--;
        } else if (ring <= VSSConstants.SYNC_MID_DISTANCE_CHUNKS) {
            midSyncRemaining--;
        } else if (ring <= VSSConstants.SYNC_FAR_DISTANCE_CHUNKS) {
            farSyncRemaining--;
        } else {
            distantSyncRemaining--;
        }
    }
}

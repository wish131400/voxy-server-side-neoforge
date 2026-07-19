package dev.xantha.vss.networking.server.sending;

import java.util.List;
import java.util.UUID;

final class RoundRobinPlayerCursor {
    private UUID nextPlayerId;

    int startIndex(List<UUID> playerIds) {
        if (nextPlayerId == null) {
            return 0;
        }
        int index = playerIds.indexOf(nextPlayerId);
        return index >= 0 ? index : 0;
    }

    void advance(List<UUID> playerIds, int currentIndex) {
        if (playerIds.isEmpty()) {
            nextPlayerId = null;
            return;
        }
        nextPlayerId = playerIds.get((currentIndex + 1) % playerIds.size());
    }

    void reset() {
        nextPlayerId = null;
    }
}

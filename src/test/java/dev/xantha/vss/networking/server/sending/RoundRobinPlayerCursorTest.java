package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoundRobinPlayerCursorTest {
    private static final UUID FIRST = new UUID(0L, 1L);
    private static final UUID SECOND = new UUID(0L, 2L);
    private static final UUID THIRD = new UUID(0L, 3L);

    @Test
    void advancesTheFirstPlayerAcrossFlushes() {
        RoundRobinPlayerCursor cursor = new RoundRobinPlayerCursor();
        List<UUID> players = List.of(FIRST, SECOND, THIRD);

        assertEquals(0, cursor.startIndex(players));
        cursor.advance(players, 0);
        assertEquals(1, cursor.startIndex(players));
        cursor.advance(players, 1);
        assertEquals(2, cursor.startIndex(players));
        cursor.advance(players, 2);
        assertEquals(0, cursor.startIndex(players));
    }

    @Test
    void departedNextPlayerFallsBackToStartOfCurrentOrder() {
        RoundRobinPlayerCursor cursor = new RoundRobinPlayerCursor();
        cursor.advance(List.of(FIRST, SECOND, THIRD), 0);

        assertEquals(0, cursor.startIndex(List.of(FIRST, THIRD)));
    }
}

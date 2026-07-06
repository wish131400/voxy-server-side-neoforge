package dev.xantha.vss.networking.server.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerRequestRegistryTest {

    @Test
    void putGetAndIdentityChecksUseExactStateInstance() {
        PlayerRequestRegistry registry = new PlayerRequestRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerRequestState state = new PlayerRequestState();

        registry.put(playerId, state);

        assertTrue(registry.contains(playerId));
        assertSame(state, registry.get(playerId));
        assertTrue(registry.isCurrent(playerId, state));
        assertFalse(registry.isCurrent(playerId, new PlayerRequestState()));
    }

    @Test
    void removeReturnsStateAndClearsRegistration() {
        PlayerRequestRegistry registry = new PlayerRequestRegistry();
        UUID playerId = UUID.randomUUID();
        PlayerRequestState state = new PlayerRequestState();
        registry.put(playerId, state);

        assertSame(state, registry.remove(playerId));

        assertFalse(registry.contains(playerId));
        assertFalse(registry.isCurrent(playerId, state));
        assertTrue(registry.isEmpty());
    }

    @Test
    void viewsExposeRegisteredPlayersAndStates() {
        PlayerRequestRegistry registry = new PlayerRequestRegistry();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        PlayerRequestState first = new PlayerRequestState();
        PlayerRequestState second = new PlayerRequestState();

        registry.put(firstId, first);
        registry.put(secondId, second);

        assertEquals(2, registry.size());
        assertTrue(registry.playerIds().contains(firstId));
        assertTrue(registry.states().contains(first));
        assertEquals(2, registry.entries().size());
    }

    @Test
    void clearRemovesAllStates() {
        PlayerRequestRegistry registry = new PlayerRequestRegistry();
        registry.put(UUID.randomUUID(), new PlayerRequestState());

        registry.clear();

        assertEquals(0, registry.size());
        assertTrue(registry.isEmpty());
    }
}

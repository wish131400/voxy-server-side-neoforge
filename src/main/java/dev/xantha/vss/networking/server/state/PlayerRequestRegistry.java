package dev.xantha.vss.networking.server.state;

import java.util.Collection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerRequestRegistry {
    private final Map<UUID, PlayerRequestState> states = new HashMap<>();

    public synchronized boolean contains(UUID playerId) {
        return states.containsKey(playerId);
    }

    public synchronized boolean isEmpty() {
        return states.isEmpty();
    }

    public synchronized int size() {
        return states.size();
    }

    public synchronized PlayerRequestState get(UUID playerId) {
        return states.get(playerId);
    }

    public synchronized void put(UUID playerId, PlayerRequestState state) {
        states.put(playerId, state);
    }

    public synchronized PlayerRequestState remove(UUID playerId) {
        return states.remove(playerId);
    }

    public synchronized boolean isCurrent(UUID playerId, PlayerRequestState state) {
        return states.get(playerId) == state;
    }

    public synchronized Set<UUID> playerIds() {
        return Set.copyOf(states.keySet());
    }

    public synchronized List<Map.Entry<UUID, PlayerRequestState>> entries() {
        ArrayList<Map.Entry<UUID, PlayerRequestState>> snapshot = new ArrayList<>(states.size());
        for (Map.Entry<UUID, PlayerRequestState> entry : states.entrySet()) {
            snapshot.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return List.copyOf(snapshot);
    }

    public synchronized Collection<PlayerRequestState> states() {
        return List.copyOf(states.values());
    }

    public synchronized void clear() {
        states.clear();
    }
}

package dev.xantha.vss.client.prediction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;

/** Immutable numeric block mapping owned by one prediction cache session. */
final class PredictionBlockMappings {
    private final String[] names;
    private final Map<String, Integer> ids;

    private PredictionBlockMappings(String[] names, Map<String, Integer> ids) {
        this.names = names;
        this.ids = Map.copyOf(ids);
    }

    // Capture once while the owning world is bound. Deferred encoders and decoders
    // must never consult the next connection's mutable numeric registry.
    static PredictionBlockMappings capture() {
        var names = new ArrayList<String>();
        var ids = new HashMap<String, Integer>();
        for (var block : BuiltInRegistries.BLOCK) {
            int id = BuiltInRegistries.BLOCK.getId(block);
            if (id < 0) continue;
            while (names.size() <= id) names.add(null);
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            names.set(id, name);
            ids.put(name, id);
        }
        return new PredictionBlockMappings(names.toArray(String[]::new), ids);
    }

    String name(int id) throws IOException {
        if (id == ClientColumnSample.NO_BLOCK) return "";
        if (id < 0 || id >= names.length || names[id] == null)
            throw new IOException("Unregistered session block: " + id);
        return names[id];
    }

    int id(String name) throws IOException {
        if (name.isEmpty()) return ClientColumnSample.NO_BLOCK;
        Integer id = ids.get(name);
        if (id == null) throw new IOException("Block unavailable in session: " + name);
        return id;
    }
}

package dev.xantha.vss.client.prediction;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource content identity is computed once per reload, off the render thread.
 * Until ready, geometry caching is bypassed; loading never waits for this scan. */
final class PredictionMeshResources {
    private static volatile CompletableFuture<byte[]> current;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "vss-mesh-resource-identity");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });

    static synchronized void reset() { if (current != null) current.cancel(false); current = null; }
    static synchronized void prepare(ResourceManager resources) {
        if (current == null) current = CompletableFuture.supplyAsync(() -> fingerprint(resources), WORKER);
    }
    static byte[] ready() {
        var job = current;
        return job == null || !job.isDone() || job.isCompletedExceptionally() ? null : job.getNow(null);
    }

    /** True only while the current reload's identity is still being scanned. */
    static boolean pending() {
        var job = current;
        return job != null && !job.isDone();
    }
    private static byte[] fingerprint(ResourceManager resources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[32768];
            for (String root : List.of("models", "blockstates", "textures")) {
                var files = new TreeMap<>(resources.listResources(root, id -> id.getPath().endsWith(".json")
                        || id.getPath().endsWith(".png") || id.getPath().endsWith(".mcmeta")));
                for (var entry : files.entrySet()) {
                    digest.update(entry.getKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    try (var input = entry.getValue().open()) {
                        for (int n; (n = input.read(buffer)) != -1;) {
                            total += n; if (total > 512L * 1024 * 1024) return null;
                            digest.update(buffer, 0, n);
                        }
                    }
                    digest.update((byte) 0);
                }
            }
            // Numeric state ids occur in the input signature; refuse reuse after
            // registry reordering.  Keep the schema binary and bounded: the
            // old state.toString() path rebuilt a large String for every state
            // and dominated startup allocation on modded registries.
            digest.update((byte) 0x7f);
            updateInt(digest, net.minecraft.core.registries.BuiltInRegistries.BLOCK.size());
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                updateInt(digest, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(block));
                updateString(digest, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString());
                var properties = new ArrayList<>(block.getStateDefinition().getProperties());
                var choicesByProperty = new ArrayList<List<?>>(properties.size());
                updateInt(digest, properties.size());
                for (var property : properties) {
                    updateString(digest, property.getName());
                    var choices = List.copyOf(property.getPossibleValues());
                    choicesByProperty.add(choices);
                    updateInt(digest, choices.size());
                    for (Object choice : choices) updateString(digest, propertyName(property, choice));
                }
                var states = block.getStateDefinition().getPossibleStates();
                updateInt(digest, states.size());
                for (var state : states) {
                    updateInt(digest, net.minecraft.world.level.block.Block.getId(state));
                    for (int i = 0; i < properties.size(); i++) {
                        var property = properties.get(i);
                        var choices = choicesByProperty.get(i);
                        updateInt(digest, choices.indexOf(state.getValue(property)));
                    }
                }
            }
            return digest.digest();
        } catch (IOException | RuntimeException | NoSuchAlgorithmException unavailable) { return null; }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(net.minecraft.world.level.block.state.properties.Property property,
                                       Object value) {
        return property.getName((Comparable) value);
    }
}

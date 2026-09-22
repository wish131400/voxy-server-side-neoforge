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
            // Numeric state/biome ids occur in the input signature; refuse reuse after registry reordering.
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                digest.update(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (var state : block.getStateDefinition().getPossibleStates())
                    digest.update(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return digest.digest();
        } catch (IOException | RuntimeException | NoSuchAlgorithmException unavailable) { return null; }
    }
}

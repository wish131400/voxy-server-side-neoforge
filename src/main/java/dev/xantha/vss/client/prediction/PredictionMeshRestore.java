package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.concurrent.*;

/** Bounded upload staging. Render calls only inspect/enqueue; one worker performs cold restores. */
final class PredictionMeshRestore {
    static final long LIMIT_BYTES = 32L * 1024 * 1024;
    private static final LinkedHashMap<PredictionPackedMesh, int[]> READY = new LinkedHashMap<>();
    private static final HashSet<PredictionPackedMesh> PENDING = new HashSet<>();
    private static long readyBytes, pendingBytes, epoch;
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                var thread = new Thread(task, "vss-mesh-restore");
                thread.setDaemon(true); thread.setPriority(Thread.NORM_PRIORITY - 1); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static synchronized void stage(PredictionPackedMesh mesh, int[] words) {
        long size = words.length * 4L;
        if (size > LIMIT_BYTES) return;
        int[] old = READY.remove(mesh);
        if (old != null) readyBytes -= old.length * 4L;
        while (readyBytes + size > LIMIT_BYTES && !READY.isEmpty()) {
            var iterator = READY.entrySet().iterator();
            readyBytes -= iterator.next().getValue().length * 4L; iterator.remove();
        }
        READY.put(mesh, words); readyBytes += size;
    }
    static synchronized int[] peek(PredictionPackedMesh mesh) { return READY.get(mesh); }
    static synchronized void uploaded(PredictionPackedMesh mesh) {
        int[] old = READY.remove(mesh);
        if (old != null) readyBytes -= old.length * 4L;
    }
    static synchronized void request(PredictionPackedMesh mesh) {
        if (READY.containsKey(mesh) || PENDING.contains(mesh)) return;
        long bytes = mesh.quadBytes();
        if (bytes > LIMIT_BYTES - pendingBytes) return;
        long generation = epoch;
        PENDING.add(mesh); pendingBytes += bytes;
        try {
            WORKER.execute(() -> {
                try {
                    synchronized (PredictionMeshRestore.class) { if (generation != epoch) return; }
                    int[] words = mesh.restoreWords();
                    synchronized (PredictionMeshRestore.class) { if (generation == epoch) stage(mesh, words); }
                } catch (RuntimeException failure) {
                    dev.xantha.vss.common.VSSLogger.error("VSS mesh restore failed", failure);
                } finally {
                    synchronized (PredictionMeshRestore.class) { PENDING.remove(mesh); pendingBytes -= bytes; }
                }
            });
        } catch (RejectedExecutionException full) { PENDING.remove(mesh); pendingBytes -= bytes; }
    }
    static synchronized void clear() {
        epoch++;
        READY.clear(); readyBytes = 0;
        // Queued jobs observe the epoch and release their reservation in finally.
    }
    static synchronized long readyBytes() { return readyBytes; }
    static synchronized long pendingBytes() { return pendingBytes; }
    static synchronized String diagnostics() {
        return "stagedBytes=" + readyBytes + ",pendingBytes=" + pendingBytes + ",jobs=" + PENDING.size();
    }
}

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
        long size = mesh.stagingBytes();
        // A single maximum-size compressed tile may need more than 32 MiB
        // after its pass palettes are remapped. Permit one bounded oversized
        // restore so eviction can never leave it permanently unuploadable.
        if (size > PredictionMeshCompression.MAX_BYTES * 3L) return;
        int[] old = READY.remove(mesh);
        if (old != null) readyBytes -= mesh.stagingBytes();
        while (readyBytes + size > LIMIT_BYTES && !READY.isEmpty()) {
            var iterator = READY.entrySet().iterator();
            var retired = iterator.next().getKey();
            readyBytes -= retired.stagingBytes(); iterator.remove(); retired.releaseUploadStorage();
        }
        READY.put(mesh, words); readyBytes += size;
    }
    static synchronized int[] peek(PredictionPackedMesh mesh) { return READY.get(mesh); }
    static synchronized void uploaded(PredictionPackedMesh mesh) {
        int[] old = READY.remove(mesh);
        if (old != null) readyBytes -= mesh.stagingBytes();
    }
    static synchronized void request(PredictionPackedMesh mesh) {
        if (READY.containsKey(mesh) || PENDING.contains(mesh)) return;
        long bytes = mesh.maximumStagingBytes();
        if (bytes > PredictionMeshCompression.MAX_BYTES * 3L
                || bytes > LIMIT_BYTES - pendingBytes && pendingBytes != 0) return;
        long generation = epoch;
        long uploadGeneration = PredictionUploadStaging.SHARED.epoch();
        PENDING.add(mesh); pendingBytes += bytes;
        try {
            WORKER.execute(() -> {
                try {
                    synchronized (PredictionMeshRestore.class) { if (generation != epoch) return; }
                    int[] words = mesh.restoreUploadWords();
                    mesh.preparePassStorage(words);
                    synchronized (PredictionMeshRestore.class) {
                        if (generation == epoch) stage(mesh, words);
                        else {
                            PredictionUploadStaging.SHARED.discard(mesh);
                            mesh.releaseUploadStorage();
                        }
                    }
                    // Publish CPU restore first. An immediately available render
                    // may use it without waiting for native staging; it must not
                    // finish an upload before READY is published by this worker.
                    mesh.prepareUpload(uploadGeneration);
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
        PredictionUploadStaging.SHARED.clear();
        READY.keySet().forEach(PredictionPackedMesh::releaseUploadStorage);
        READY.clear(); readyBytes = 0;
        // Queued jobs observe the epoch and release their reservation in finally.
    }
    static synchronized long readyBytes() { return readyBytes; }
    static synchronized long pendingBytes() { return pendingBytes; }
    static synchronized String diagnostics() {
        return "stagedBytes=" + readyBytes + ",pendingBytes=" + pendingBytes + ",jobs=" + PENDING.size()
                + ",uploadStaging={" + PredictionUploadStaging.SHARED.diagnostics() + "}";
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Opt-in replay of exported live production words through the actual resident implementation. */
class PredictionMeshCompressionBenchmark {
    @Test void replayActualResidentStorageAndColdUploadRecovery() throws Exception {
        String corpus = System.getProperty("vss.meshCorpus");
        Assumptions.assumeTrue(corpus != null, "explicit live corpus required");
        var paths = Files.list(Path.of(corpus)).filter(p -> p.toString().endsWith(".bin")).sorted().toList();
        var meshes = new ArrayList<PredictionPackedMesh>(); var originals = new ArrayList<int[]>();
        long raw = 0, retained = 0, compression = 0; int zstd = 0;
        for (var path : paths) {
            byte[] bytes = Files.readAllBytes(path); int[] words = new int[bytes.length / 4];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
            var mesh = new PredictionPackedMesh(words, 64, words.length / 12, new int[5], new int[5], new int[5], new int[5], false, 0);
            long start = System.nanoTime();mesh.prepareStorage();compression += System.nanoTime() - start;
            assertTrue(mesh.compressed());raw += mesh.quadBytes();retained += mesh.retainedHeapBytes();
            meshes.add(mesh); originals.add(words);
            var field = PredictionPackedMesh.class.getDeclaredField("compressed");field.setAccessible(true);
            if (((PredictionMeshCompression.Blob)field.get(mesh)).zstd()) zstd++;
        }
        assertTrue(PredictionMeshRestore.readyBytes() <= PredictionMeshRestore.LIMIT_BYTES);
        PredictionMeshRestore.clear();
        long start = System.nanoTime(), callsNanos = 0; int polls = 0, uploaded = 0;
        while (uploaded < meshes.size()) {
            int requests = 0;
            for (int i = uploaded; i < meshes.size(); i++) {
                long callStart = System.nanoTime();int[] words = meshes.get(i).uploadWords();callsNanos += System.nanoTime() - callStart;polls++;
                if (words == null) { if (++requests == 4) break; continue; }
                // Ordered replay waits for the head before accounting completion.
                if (i != uploaded) continue;
                assertArrayEquals(originals.get(i), words);
                meshes.get(i).uploaded();uploaded++;
            }
            assertTrue(PredictionMeshRestore.pendingBytes() <= PredictionMeshRestore.LIMIT_BYTES);
            assertTrue(PredictionMeshRestore.readyBytes() <= PredictionMeshRestore.LIMIT_BYTES);
            assertTrue(System.nanoTime() - start < 30_000_000_000L);
            if (uploaded < meshes.size()) Thread.sleep(1);
        }
        String report = String.format(Locale.ROOT,
                "tiles=%d rawBytes=%d residentBytes=%d zstdTiles=%d prepareMs=%.3f coldBatchMs=%.3f renderPollTotalMs=%.3f polls=%d stagingEnd=%d%n",
                meshes.size(), raw, retained, zstd, compression / 1e6, (System.nanoTime()-start)/1e6, callsNanos/1e6, polls, PredictionMeshRestore.readyBytes());
        System.out.println(report);
        Files.writeString(Path.of(corpus).getParent().resolve("production-replay.txt"), report);
        PredictionMeshRestore.clear();
    }
}

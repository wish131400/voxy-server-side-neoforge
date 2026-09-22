package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class PredictionMeshCompressionTest {
    @AfterEach void releaseStaging() throws Exception {
        PredictionMeshRestore.clear();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (PredictionMeshRestore.pendingBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, PredictionMeshRestore.pendingBytes());
        PredictionMeshRestore.clear();
    }
    static PredictionPackedMesh payload(int words) {
        int[] records = new int[words / 12 * 12];
        for (int i = 0; i < records.length; i++) records[i] = (i % 12 == 7 ? 0xff123456 : i % 192);
        return PredictionPackedMesh.terrainRecords(records, 64);
    }
    @Test void exactWordsAndMetadataSurviveStagingEvictionAndColdRestore() throws Exception {
        var mesh = payload(128 * 1024); int[] original = mesh.quads().clone();
        int count = mesh.quadCount(); long bytes = mesh.uploadBytes();
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        mesh.prepareStorage(); assertTrue(mesh.compressed());
        assertTrue(mesh.retainedHeapBytes() < bytes * 0.75);
        assertArrayEquals(original, mesh.uploadWords());
        mesh.uploaded(); assertEquals(0, PredictionMeshRestore.readyBytes());
        assertEquals(count, mesh.quadCount()); assertEquals(bytes, mesh.uploadBytes());
        assertSame(ranges, mesh.drawRanges(false, VssLodFaceGroup.ALL));
        assertArrayEquals(original, mesh.quads(), "disk reads restore temporary words without pinning a raw copy");
        assertEquals(0, PredictionMeshRestore.readyBytes());
        int[] restored = awaitUpload(mesh);
        assertArrayEquals(original, restored);
        mesh.uploaded(); assertEquals(0, PredictionMeshRestore.readyBytes());
    }
    static int[] awaitUpload(PredictionPackedMesh mesh) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); int[] words;
        while ((words = mesh.uploadWords()) == null && System.nanoTime() < deadline) Thread.sleep(1);
        assertNotNull(words, "background restore timed out"); return words;
    }
    @Test void smallRandomAndOversizedInputsKeepRawStorage() {
        assertNull(PredictionMeshCompression.compress(new int[128]));
        assertNull(PredictionMeshCompression.compress(new int[PredictionMeshCompression.MAX_BYTES / 4 + 1]));
        int[] random = new Random(73).ints(65536).toArray();
        assertNull(PredictionMeshCompression.compress(random, false));
        for (boolean optionalNative : new boolean[]{false, true}) {
            int[] values = new int[65536];
            for (int i = 0; i < values.length; i++) values[i] = i % 12 == 3 ? Integer.MIN_VALUE : i % 12;
            var blob = PredictionMeshCompression.compress(values, optionalNative);
            assertNotNull(blob); assertArrayEquals(values, blob.restore());
            var truncated = new PredictionMeshCompression.Blob(Arrays.copyOf(blob.bytes(), blob.bytes().length / 2), blob.rawBytes(), blob.zstd());
            assertThrows(RuntimeException.class, truncated::restore);
        }
    }
    @Test void stagingAndRestoreQueuesStayBoundedAndResetRejectsOldWork() throws Exception {
        var meshes = new ArrayList<PredictionPackedMesh>();
        for (int i = 0; i < 12; i++) {
            var mesh = payload(1024 * 1024); mesh.prepareStorage(); meshes.add(mesh);
            assertTrue(PredictionMeshRestore.readyBytes() <= PredictionMeshRestore.LIMIT_BYTES);
        }
        assertNull(PredictionMeshRestore.peek(meshes.get(0)), "old initial-upload arrays must be evictable");
        PredictionMeshRestore.clear();
        var field = PredictionMeshRestore.class.getDeclaredField("WORKER"); field.setAccessible(true);
        var executor = (ThreadPoolExecutor)field.get(null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var gate = executor.submit(() -> { entered.countDown(); release.await(); return true; });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (var mesh : meshes) assertNull(mesh.uploadWords(), "render calls cannot wait for the blocked decoder");
            assertTrue(PredictionMeshRestore.pendingBytes() <= PredictionMeshRestore.LIMIT_BYTES);
            assertEquals(0, PredictionMeshRestore.readyBytes());
            PredictionMeshRestore.clear();
        } finally { release.countDown(); gate.get(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (PredictionMeshRestore.pendingBytes() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(0, PredictionMeshRestore.pendingBytes());
        assertEquals(0, PredictionMeshRestore.readyBytes(), "old-world jobs cannot repopulate upload staging");
        assertArrayEquals(meshes.get(0).quads(), awaitUpload(meshes.get(0)));
    }
    @Test void compressedMeshStillRoundTripsThroughFinishedDiskCache() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var mesh = PredictionMeshCodecTest.fixture(64);
        int[] original = mesh.gpuPayload().quads().clone();
        mesh.gpuPayload().prepareStorage();
        PredictionMeshRestore.clear();
        byte[] signature = new byte[32];
        var restored = PredictionMeshCodec.decode(PredictionMeshCodec.encode(mesh, signature), signature, mesh.cellAxis());
        assertNotNull(restored); assertArrayEquals(original, restored.gpuPayload().quads());
        for (int i = 0; i < mesh.cellCount(); i++) {
            assertEquals(mesh.seamMesh().topY(i), restored.seamMesh().topY(i));
            assertEquals(mesh.seamMesh().topColor(i), restored.seamMesh().topColor(i));
        }
    }
}

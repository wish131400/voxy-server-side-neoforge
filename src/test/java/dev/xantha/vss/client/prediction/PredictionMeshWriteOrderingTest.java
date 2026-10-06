package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Gates the actual material encoder to reverse call and submission order. */
class PredictionMeshWriteOrderingTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception {
        PredictionCacheTestFiles.awaitBackgroundClose();
        VssLodSpriteTable.close();
    }

    @Test void lateCoarseEncodingCannotOverwriteAlreadySavedFineMesh() throws Exception {
        reversedEncoding(8, 16, false);
    }

    @Test void sameAxisOlderEncodingCannotOverwriteTheNewestAcceptedResult() throws Exception {
        reversedEncoding(16, 16, false);
    }

    @Test void orderTableEvictionCannotForgetFineDetailWhileAnOldEncoderRetainsItsLease() throws Exception {
        reversedEncoding(8, 16, true);
    }

    @Test void sameAxisTerrainOnlyCannotReplaceCompleteSurfaceAndChangedIdentityStillCan() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var mesh = PredictionMeshCodecTest.fixture();
        byte[] base = new byte[32], complete = new byte[32], terrain = new byte[32];
        complete[0] = 1; terrain[0] = 2;
        for (int session = 0; session < 2; session++) {
            try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
                if (session == 0) {
                    cache.writeMeshLater(lease, complete, mesh, base, true, true);
                    cache.flushMeshes(); cache.flush();
                }
                var restored = cache.readMeshBaseRecord(lease, base, mesh.cellAxis());
                assertNotNull(restored); assertTrue(restored.surfaceCompleted());
                cache.writeMeshLater(lease, terrain, mesh, base, true, false);
                cache.flushMeshes(); cache.flush();
                assertNotNull(cache.readMesh(lease, complete, mesh.cellAxis()));
                assertNull(cache.readMesh(lease, terrain, mesh.cellAxis()));
            }
            PredictionCacheTestFiles.awaitBackgroundClose();
        }
        base[31] = 1;
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            cache.writeMeshLater(lease, terrain, mesh, base, true, false);
            cache.flushMeshes(); cache.flush();
            assertNotNull(cache.readMesh(lease, terrain, mesh.cellAxis()), "a changed settings/resource base must replace the old result");
            assertFalse(cache.readMeshBaseRecord(lease, base, mesh.cellAxis()).surfaceCompleted());
        }
    }

    private void reversedEncoding(int oldAxis, int newAxis, boolean fillOrderTable) throws Exception {
        VssLodSpriteTable.close();
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var coarse = PredictionMeshCodecTest.fixture(oldAxis);
        var fine = PredictionMeshCodecTest.fixture(newAxis);
        byte[] coarseIdentity = new byte[32], fineIdentity = new byte[32];
        coarseIdentity[0] = 8; fineIdentity[0] = 16;
        NativeImage image = new NativeImage(16, 16, false);
        image.fillRect(0, 0, 16, 16, 0xff808080);
        try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/ordering_fixture"),
                new FrameSize(16, 16), image, ResourceMetadata.EMPTY);
             var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            int row = VssLodSpriteTable.registerSprite(new Sprite(contents));
            var wordsField = PredictionPackedMesh.class.getDeclaredField("quads");
            wordsField.setAccessible(true);
            int[] coarseWords = (int[]) wordsField.get(coarse.gpuPayload());
            assertTrue(coarseWords.length >= PredictionPackedMesh.STRIDE_INTS);
            coarseWords[6] = (coarseWords[6] & 0xffff0000) | row;
            var entered = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread writer = new Thread(() -> {
                try {
                    entered.countDown();
                    cache.writeMeshLater(lease, coarseIdentity, coarse, coarseIdentity, true);
                } catch (Throwable throwable) { failure.set(throwable); }
            }, "vss-coarse-mesh-reproduction");
            try {
                synchronized (VssLodSpriteTable.class) {
                    writer.start();
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (writer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                    assertEquals(Thread.State.BLOCKED, writer.getState(), "coarse encode must reach the actual material-table lock");
                    assertTrue(java.util.Arrays.stream(writer.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals(VssLodSpriteTable.class.getName())
                                    && frame.getMethodName().equals("writeMaterial")));
                    cache.writeMeshLater(lease, fineIdentity, fine, fineIdentity, true);
                    cache.flushMeshes(); cache.flush();
                    assertNotNull(cache.readMeshBase(lease, fineIdentity, fine.cellAxis()));
                    if (fillOrderTable) {
                        var orders = orderTable(cache);
                        Object history = orders.get(PredictionDiskCache.Key.mesh(key));
                        // Populate completed bookkeeping only; avoid 32768 durable fixture writes.
                        for (int x = 1; x < PredictionDiskCache.MAX_MESH_WRITE_ORDERS; x++)
                            orders.put(PredictionDiskCache.Key.mesh(PredictionDiskCache.Key.terrain(x, 0, 0)), history);
                        try (var unrelated = cache.lease(PredictionDiskCache.Key.terrain(-1, 0, 0))) {
                            cache.writeMeshLater(unrelated, fineIdentity, fine);
                        }
                        cache.flushMeshes(); cache.flush();
                        assertEquals(PredictionDiskCache.MAX_MESH_WRITE_ORDERS, orders.size());
                        assertSame(history, orders.get(PredictionDiskCache.Key.mesh(key)),
                                "eviction must preserve the fine token needed to reject the blocked encoder");
                    }
                }
                writer.join(5000);
                assertFalse(writer.isAlive());
                assertNull(failure.get());
                cache.flushMeshes(); cache.flush();
                assertNotNull(cache.readMeshBase(lease, fineIdentity, fine.cellAxis()),
                        "a late older encode must preserve the accepted fine result");
                byte[] compressed = PredictionCacheTestFiles.read(cache, PredictionDiskCache.Key.mesh(key));
                try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
                    input.skipNBytes(32);
                    byte[] meshBytes = input.readNBytes(input.readInt());
                    assertEquals(fine.cellAxis(), ByteBuffer.wrap(meshBytes).getInt(69));
                }
            } finally {
                writer.join(5000);
                cache.flushMeshes(); cache.flush();
            }
        }
        try (var reopened = new PredictionDiskCache(directory, 77); var lease = reopened.lease(key)) {
            assertNotNull(reopened.readMeshBase(lease, fineIdentity, fine.cellAxis()),
                    "the latest accepted mesh must take the warm restore path after reopening");
        }
    }

    @Test void rejectedNewCandidateCannotRevokeThePreviouslyAcceptedMesh() throws Exception {
        var mesh = PredictionMeshCodecTest.fixture(16);
        byte[] identity = new byte[32], rejected = new byte[32]; rejected[0] = 9;
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            cache.writeMeshLater(lease, identity, mesh);
            cache.flushMeshes(); cache.flush();
            try (var gate = new MeshGate()) {
                for (int x = 1; x <= 2; x++) try (var other = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0))) {
                    cache.writeMeshLater(other, identity, mesh);
                }
                cache.writeMeshLater(lease, rejected, mesh);
                gate.release();
            }
            cache.flushMeshes(); cache.flush();
            assertNotNull(cache.readMeshBase(lease, identity, mesh.cellAxis()));
            assertNull(cache.readMeshBase(lease, rejected, mesh.cellAxis()));
        }
    }

    @Test void flushWaitsForFullBoundedMeshQueueInsteadOfRejectingItsBarrier() throws Exception {
        var mesh = PredictionMeshCodecTest.fixture(16);
        byte[] identity = new byte[32];
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new MeshGate()) {
            for (int x = 0; x < 2; x++) try (var lease = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0))) {
                cache.writeMeshLater(lease, identity, mesh);
            }
            Thread flusher = new Thread(() -> {
                entered.countDown();
                try { cache.flushMeshes(); }
                catch (Throwable throwable) { failure.set(throwable); }
                finally { completed.countDown(); }
            }, "vss-full-mesh-queue-flush");
            flusher.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(completed.await(50, TimeUnit.MILLISECONDS), "flush must wait for the bounded queue to drain");
                gate.release();
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertNull(failure.get());
                cache.flush();
                for (int x = 0; x < 2; x++) try (var lease = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0))) {
                    assertNotNull(cache.readMeshBase(lease, identity, mesh.cellAxis()));
                }
            } finally { gate.release(); flusher.join(5000); }
        }
    }

    @Test void acceptedNewerWriteCancelsAnOldMeshAlreadyWaitingForTheCommitWorker() throws Exception {
        var mesh = PredictionMeshCodecTest.fixture(16);
        byte[] oldIdentity = new byte[32], freshIdentity = new byte[32]; freshIdentity[0] = 9;
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var field = PredictionDiskCache.class.getDeclaredField("COMMITS"); field.setAccessible(true);
        var commits = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new PredictionDeferredCacheWriteTest.DiskGate();
             var lease = cache.lease(key)) {
            cache.writeMeshLater(lease, oldIdentity, mesh);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (commits.getQueue().isEmpty() && System.nanoTime() < deadline) Thread.sleep(1);
            assertFalse(commits.getQueue().isEmpty(), "old mesh must be staged and waiting for its actual region commit");
            cache.writeMeshLater(lease, freshIdentity, mesh);
            gate.release();
            cache.flushMeshes(); cache.flush();
            assertNotNull(cache.readMeshBase(lease, freshIdentity, mesh.cellAxis()));
            assertNull(cache.readMeshBase(lease, oldIdentity, mesh.cellAxis()));
            assertTrue(cache.diagnostics().contains("meshWrites=1"),
                    "the obsolete record must be rejected by the commit worker, not written and then overwritten");
        }
    }

    @Test void aFullTableOfActiveProducersRejectsNewKeysWithoutRevokingExistingOrders() throws Exception {
        var mesh = PredictionMeshCodecTest.fixture(16);
        var leases = new java.util.ArrayList<PredictionDiskCache.Lease>();
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        byte[] identity = new byte[32];
        try (var cache = new PredictionDiskCache(directory, 77); var lease = cache.lease(key)) {
            cache.writeMeshLater(lease, identity, mesh);
            cache.flushMeshes(); cache.flush();
            var orders = orderTable(cache);
            Object history = orders.get(PredictionDiskCache.Key.mesh(key));
            try {
                for (int x = 1; x < PredictionDiskCache.MAX_MESH_WRITE_ORDERS; x++) {
                    var held = cache.lease(PredictionDiskCache.Key.terrain(x, 0, 0));
                    leases.add(held);
                    orders.put(PredictionDiskCache.Key.mesh(held.key), history);
                }
                try (var rejected = cache.lease(PredictionDiskCache.Key.terrain(-1, 0, 0))) {
                    cache.writeMeshLater(rejected, identity, mesh);
                }
                cache.flushMeshes(); cache.flush();
                assertEquals(PredictionDiskCache.MAX_MESH_WRITE_ORDERS, orders.size());
                assertTrue(cache.diagnostics().contains("meshWrites=1"));
                assertNotNull(cache.readMeshBase(lease, identity, mesh.cellAxis()));
            } finally { for (var held : leases) held.close(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<PredictionDiskCache.Key, Object> orderTable(PredictionDiskCache cache) throws Exception {
        var field = PredictionDiskCache.class.getDeclaredField("meshWriteOrders"); field.setAccessible(true);
        return (java.util.Map<PredictionDiskCache.Key, Object>) field.get(cache);
    }

    @Test void invalidationResetsDetailOrderAndRejectsTheOldQueuedMesh() throws Exception {
        var fine = PredictionMeshCodecTest.fixture(16);
        var coarse = PredictionMeshCodecTest.fixture(8);
        byte[] oldIdentity = new byte[32], freshIdentity = new byte[32]; freshIdentity[0] = 9;
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var cache = new PredictionDiskCache(directory, 77); var gate = new MeshGate()) {
            try (var lease = cache.lease(key)) { cache.writeMeshLater(lease, oldIdentity, fine); }
            cache.invalidate(java.util.List.of(key));
            cache.flush();
            try (var lease = cache.lease(key)) { cache.writeMeshLater(lease, freshIdentity, coarse); }
            gate.release();
            cache.flushMeshes(); cache.flush();
            try (var lease = cache.lease(key)) {
                assertNotNull(cache.readMeshBase(lease, freshIdentity, coarse.cellAxis()));
                assertNull(cache.readMeshBase(lease, oldIdentity, fine.cellAxis()));
            }
        }
    }

    @Test void newSessionCannotReceiveThePreviousOwnersQueuedMesh() throws Exception {
        var fine = PredictionMeshCodecTest.fixture(16);
        var coarse = PredictionMeshCodecTest.fixture(8);
        byte[] oldIdentity = new byte[32], freshIdentity = new byte[32]; freshIdentity[0] = 9;
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        try (var previous = new PredictionDiskCache(directory, 77); var gate = new MeshGate()) {
            try (var lease = previous.lease(key)) { previous.writeMeshLater(lease, oldIdentity, fine); }
            try (var current = new PredictionDiskCache(directory, 77)) {
                try (var lease = current.lease(key)) { current.writeMeshLater(lease, freshIdentity, coarse); }
                gate.release();
                current.flushMeshes(); current.flush();
                try (var lease = current.lease(key)) {
                    assertNotNull(current.readMeshBase(lease, freshIdentity, coarse.cellAxis()));
                    assertNull(current.readMeshBase(lease, oldIdentity, fine.cellAxis()));
                }
            }
        }
    }

    private static class MeshGate implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final java.util.concurrent.Future<?> task;
        MeshGate() throws Exception {
            var field = PredictionDiskCache.class.getDeclaredField("MESH_WRITES"); field.setAccessible(true);
            var entered = new CountDownLatch(1);
            task = ((java.util.concurrent.ExecutorService) field.get(null)).submit(() -> {
                entered.countDown(); release.await(); return true;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        }
        void release() { release.countDown(); }
        @Override public void close() throws Exception { release(); task.get(5, TimeUnit.SECONDS); }
    }

    private static class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, 0, 0); }
    }
}

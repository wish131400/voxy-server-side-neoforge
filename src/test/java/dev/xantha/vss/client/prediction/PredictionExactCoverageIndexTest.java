package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionExactCoverageIndexTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void missingRemovalPreservesOtherCellsDimensionsAndReinsertedStartTime() {
        var index = new PredictionExactCoverageIndex();
        assertFalse(index.remove(Level.OVERWORLD, -1, -1));
        assertTrue(index.confirm(Level.OVERWORLD, -1, -1, 10));
        assertTrue(index.confirm(Level.OVERWORLD, -2, -1, 20));
        assertTrue(index.confirm(Level.NETHER, -1, -1, 30));
        assertFalse(index.remove(Level.OVERWORLD, -1000, 1000));
        assertTrue(index.remove(Level.OVERWORLD, -1, -1));
        assertFalse(index.remove(Level.OVERWORLD, -1, -1));
        assertTrue(index.contains(Level.OVERWORLD, -2, -1));
        assertTrue(index.contains(Level.NETHER, -1, -1));
        long reinserted = 1_000_000_000L;
        assertTrue(index.confirm(Level.OVERWORLD, -1, -1, reinserted));
        assertFalse(index.owns(Level.OVERWORLD, -1, -1, reinserted));
        assertTrue(index.owns(Level.OVERWORLD, -1, -1, reinserted + ExactCoverageGate.SETTLE_NANOS));
    }

    @Test void concurrentLastCellRemovalCannotDropAnotherCellInTheSamePage() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                var index = new PredictionExactCoverageIndex();
                index.confirm(Level.OVERWORLD, -1, -1, 0);
                var start = new CountDownLatch(1);
                var remove = workers.submit(() -> { start.await(); return index.remove(Level.OVERWORLD, -1, -1); });
                var insert = workers.submit(() -> { start.await(); return index.confirm(Level.OVERWORLD, -2, -1, 1); });
                start.countDown();
                assertTrue(remove.get(5, TimeUnit.SECONDS));
                assertTrue(insert.get(5, TimeUnit.SECONDS));
                assertFalse(index.contains(Level.OVERWORLD, -1, -1));
                assertTrue(index.contains(Level.OVERWORLD, -2, -1));
                assertEquals(1, index.pageCount());
            }
        } finally { workers.shutdownNow(); }
    }

    @Test void authoritativeRevocationClearsEditClaimAndAllowsReinsertion() throws Exception {
        try (var manager = manager()) {
            assertFalse(manager.revokeAuthoritative(-129, 258));
            manager.acceptExactColumn(-129, 258);
            edited(manager).add(pack(-129, 258));
            assertTrue(manager.isAuthoritative(-129, 258));
            assertTrue(manager.isRenderAuthoritative(-129, 258));
            assertTrue(manager.revokeAuthoritative(-129, 258));
            assertFalse(manager.isAuthoritative(-129, 258));
            assertFalse(edited(manager).contains(pack(-129, 258)));
            assertFalse(manager.revokeAuthoritative(-129, 258));
            manager.acceptExactColumn(-129, 258);
            assertTrue(manager.isAuthoritative(-129, 258));
            assertTrue(manager.revokeAuthoritative(-129, 258));
        }
    }

    @Test void reportsProductionNegativeSweepAllocations() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        var empty = new PredictionExactCoverageIndex();
        var populated = new PredictionExactCoverageIndex();
        populated.confirm(Level.OVERWORLD, -1, -1, 0);
        try (var manager = manager()) {
            for (int i = 0; i < 30_000; i++) {
                empty.remove(Level.OVERWORLD, i + 1000, -i - 1000);
                populated.remove(Level.OVERWORLD, i + 1000, -i - 1000);
                manager.revokeAuthoritative(i + 1000, -i - 1000);
            }
            long thread = Thread.currentThread().getId();
            long before = allocation.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 200_000; i++) empty.remove(Level.OVERWORLD, i + 1000, -i - 1000);
            long emptyBytes = allocation.getThreadAllocatedBytes(thread) - before;
            before = allocation.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 200_000; i++) populated.remove(Level.OVERWORLD, i + 1000, -i - 1000);
            long populatedBytes = allocation.getThreadAllocatedBytes(thread) - before;
            before = allocation.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 200_000; i++) manager.revokeAuthoritative(i + 1000, -i - 1000);
            long authoritativeBytes = allocation.getThreadAllocatedBytes(thread) - before;
            System.out.println("INDEX_ALLOCATION probes=200000 exactEmpty=" + emptyBytes
                    + " exactPopulatedMiss=" + populatedBytes + " authoritativeEmpty=" + authoritativeBytes);
            assertEquals(0, empty.pageCount());
            assertTrue(populated.contains(Level.OVERWORLD, -1, -1));
            assertFalse(manager.isAuthoritative(1000, -1000));
        }
    }

    private static PredictionTileManager manager() {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 1);
        return new PredictionTileManager(Level.OVERWORLD, ClientTerrainSampler.custom(42, profile, (x, z) -> 64),
                new PredictionMemoryBudget(256 * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null);
    }

    @SuppressWarnings("unchecked") private static Set<Long> edited(PredictionTileManager manager) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField("editedCells");
        field.setAccessible(true);
        return (Set<Long>) field.get(manager);
    }
    private static long pack(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}

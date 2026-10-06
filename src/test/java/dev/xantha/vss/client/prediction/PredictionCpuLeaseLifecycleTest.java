package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTask;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real queued tasks must release shared admission on every lifecycle path. */
class PredictionCpuLeaseLifecycleTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.tryParse("minecraft:overworld"), -64, 384,
            "noise", "minecraft:overworld", 123L);

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach void resetFrames() { PredictionFramePace.resetForTesting(); }

    @Test void completedTileReleasesTheSharedSlotForAnotherManager() throws Exception {
        exerciseCompletion(false);
    }

    @Test void backendFailureReleasesTheSharedSlotForAnotherManager() throws Exception {
        exerciseCompletion(true);
    }

    private static void exerciseCompletion(boolean fail) throws Exception {
        var cpu = PredictionCpuBudget.fixed(1);
        var firstGate = new Gate(fail);
        var secondCalls = new AtomicInteger();
        var firstMemory = memory(() -> Long.MAX_VALUE);
        var secondMemory = memory(() -> Long.MAX_VALUE);
        try (var first = manager(firstGate::sample, firstMemory, cpu);
             var second = manager((x, z) -> { secondCalls.incrementAndGet(); return 64; }, secondMemory, cpu)) {
            var a = plan(first, 0);
            var b = plan(second, 0);
            enqueue(first, a);
            assertTrue(firstGate.entered.await(10, TimeUnit.SECONDS));
            assertNull(cpu.tryAcquire(true), "a real running tile owns the shared slot");
            enqueue(second, b);
            await(second);
            assertEquals(0, secondCalls.get(), "a rejected worker must not start worldgen");
            assertEquals(0, second.failedTileCount(), "admission refusal is retryable");
            firstGate.release.countDown();
            await(first);
            assertEquals(fail ? 1 : 0, first.failedTileCount());
            assertEquals(fail ? 0 : 1, first.readyTiles().size());
            assertEquals(0, firstMemory.activeBuildCount());
            assertFree(cpu);
            enqueue(second, b);
            await(second);
            assertTrue(secondCalls.get() > 0);
            assertEquals(1, second.readyTiles().size());
            assertEquals(0, secondMemory.activeBuildCount());
            assertFree(cpu);
        } finally { firstGate.release.countDown(); }
    }

    @Test void pauseCancelsRunningAndQueuedTilesWithoutLeakingAdmission() throws Exception {
        var cpu = PredictionCpuBudget.fixed(1);
        var gate = new Gate(false);
        var memory = memory(() -> Long.MAX_VALUE);
        try (var manager = manager(gate::sample, memory, cpu)) {
            var a = plan(manager, 0);
            var b = plan(manager, 1);
            // Medium work is allowed to queue independently of the ordinary refinement cap.
            field("mediumCoverage").set(manager, new PredictionMediumCoverage(Set.of(a, b), Set.of(a, b)));
            field("mediumCoveragePending").setBoolean(manager, true);
            enqueue(manager, a);
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            enqueue(manager, b);
            assertEquals(2, manager.pendingCount());
            assertEquals(1, executor(manager).getQueue().size());
            manager.setPaused(true);
            await(manager);
            assertTrue(executor(manager).getQueue().isEmpty());
            assertTrue(manager.readyTiles().isEmpty(), "cancelled work cannot publish");
            assertEquals(0, manager.failedTileCount());
            assertEquals(0, memory.activeBuildCount());
            assertFree(cpu);
            manager.setPaused(false);
            gate.release.countDown();
            enqueue(manager, a);
            await(manager);
            assertEquals(1, manager.readyTiles().size(), "lifecycle cancellation is immediately retryable");
            assertFree(cpu);
        } finally { gate.release.countDown(); }
    }

    @Test void closeCancelsARunningTileAndReleasesItsLeaseAfterWorkerTermination() throws Exception {
        var cpu = PredictionCpuBudget.fixed(1);
        var gate = new Gate(false);
        var memory = memory(() -> Long.MAX_VALUE);
        try (var manager = manager(gate::sample, memory, cpu)) {
            var executor = executor(manager);
            enqueue(manager, plan(manager, 0));
            assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
            manager.close();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(manager.readyTiles().isEmpty());
            assertEquals(0, memory.activeBuildCount());
            assertFree(cpu);
        } finally { gate.release.countDown(); }
    }

    @Test void memoryRefusalAfterQueueingReleasesTheAlreadyAcquiredCpuLease() throws Exception {
        var cpu = PredictionCpuBudget.fixed(1);
        var free = new AtomicLong(Long.MAX_VALUE);
        var calls = new AtomicInteger();
        var blocker = new Gate(false);
        var memory = memory(free::get);
        try (var manager = manager((x, z) -> { calls.incrementAndGet(); return 64; }, memory, cpu)) {
            executor(manager).execute(new PredictionTask(null, Integer.MIN_VALUE, 0, () -> blocker.sample(0, 0)));
            assertTrue(blocker.entered.await(10, TimeUnit.SECONDS));
            enqueue(manager, plan(manager, 0));
            assertEquals(1, manager.pendingCount());
            free.set(0);
            blocker.release.countDown();
            await(manager);
            assertEquals(0, calls.get());
            assertEquals(0, manager.failedTileCount());
            assertEquals(0, memory.activeBuildCount());
            assertFree(cpu);
        } finally { blocker.release.countDown(); }
    }

    @Test void executorRejectionClearsPendingWithoutAcquiringCpuOrMemory() throws Exception {
        var cpu = PredictionCpuBudget.fixed(1);
        var calls = new AtomicInteger();
        var memory = memory(() -> Long.MAX_VALUE);
        try (var manager = manager((x, z) -> { calls.incrementAndGet(); return 64; }, memory, cpu)) {
            executor(manager).shutdown();
            enqueue(manager, plan(manager, 0));
            assertEquals(0, manager.pendingCount());
            assertEquals(0, calls.get());
            assertEquals(0, memory.activeBuildCount());
            assertFree(cpu);
        }
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean fail;
        Gate(boolean fail) { this.fail = fail; }
        int sample(int x, int z) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("test backend interrupted");
            }
            if (fail) throw new IllegalStateException("intentional backend failure");
            return 64;
        }
    }

    private static PredictionMemoryBudget memory(java.util.function.LongSupplier free) {
        return new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, free, System::nanoTime, 1);
    }

    private static PredictionTileManager manager(ClientTerrainSampler.TerrainFunction sample,
                                                   PredictionMemoryBudget memory, PredictionCpuBudget cpu) {
        return new PredictionTileManager(Level.OVERWORLD, ClientTerrainSampler.custom(42, PROFILE, sample),
                memory, null, cpu);
    }

    @SuppressWarnings("unchecked")
    private static PredictionTileKey plan(PredictionTileManager manager, int x) throws Exception {
        var key = new PredictionTileKey(Level.OVERWORLD, x, 0, manager.layout().levelCount() - 1);
        ((Set<PredictionTileKey>) field("desiredKeys").get(manager)).add(key);
        ((Set<PredictionTileKey>) field("terrainLeaves").get(manager)).add(key);
        var targets = new java.util.HashMap<>((Map<PredictionTileKey, Integer>) field("terrainTargets").get(manager));
        targets.put(key, 32);
        field("terrainTargets").set(manager, Map.copyOf(targets));
        field("ordinaryTargets").set(manager, Map.copyOf(targets));
        return key;
    }

    private static void enqueue(PredictionTileManager manager, PredictionTileKey key) throws Exception {
        var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileKey.class, int.class, int.class);
        enqueue.setAccessible(true);
        enqueue.invoke(manager, key, 0, 0);
    }

    private static ThreadPoolExecutor executor(PredictionTileManager manager) throws Exception {
        return (ThreadPoolExecutor) field("executor").get(manager);
    }

    private static Field field(String name) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(PredictionTileManager manager) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (manager.pendingCount() != 0 && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(0, manager.pendingCount(), manager.surfaceDiagnostics());
    }

    private static void assertFree(PredictionCpuBudget cpu) {
        try (var lease = cpu.tryAcquire(true)) { assertNotNull(lease, cpu.diagnostics()); }
        assertTrue(cpu.diagnostics().contains("active=0"), cpu.diagnostics());
    }
}

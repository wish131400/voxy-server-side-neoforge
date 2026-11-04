package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reproduce client/worker interleavings at the real scheduling transaction boundary. */
class PredictionSchedulingConcurrencyTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void farSurfaceRotationCannotRaceAWorkerDrainingTheSurfaceLane() throws Exception {
        exercise(false);
    }

    @Test void layoutPruningCannotRaceAWorkerDrainingTheDetailLane() throws Exception {
        exercise(true);
    }

    @SuppressWarnings("unchecked")
    private static void exercise(boolean shrink) throws Exception {
        var config = VSSClientConfig.CONFIG;
        int distance = config.predictionDistanceBlocks;
        boolean far = config.predictionFarDecorations;
        config.predictionDistanceBlocks = 65536;
        config.predictionFarDecorations = true;
        var profile = new DimensionProfile(ResourceLocation.tryParse("minecraft:overworld"),
                42L, -64, 384, "noise", "minecraft:overworld", 123L);
        var sampler = new ClientTerrainSampler(profile.seed(), profile) {
            @Override public ClientColumnSample sampleForLod(int x, int z, int step) {
                return PredictionVoxelFixtures.sample(64);
            }
            @Override public ClientColumnSample samplePreview(int x, int z, int step) {
                return sampleForLod(x, z, step);
            }
        };
        var probe = new PausingDeque(shrink);
        java.util.Collection<PredictionTileKey> queue = shrink ? probe : new PredictionSurfaceQueue<>(
                key -> new PredictionSurfaceQueue.Priority(0, key.tileX())) {
            @Override public boolean add(PredictionTileKey key) {
                probe.pauseClient(); return super.add(key);
            }
        };
        var entered = new AtomicBoolean();
        var cpu = PredictionCpuBudget.fixed(1);
        try (var held = cpu.tryAcquire(true);
             var manager = new PredictionTileManager(Level.OVERWORLD, sampler,
                     new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB,
                             0, () -> Long.MAX_VALUE, System::nanoTime, 1), null, cpu)) {
            assertNotNull(held);
            String lane = shrink ? "voxyDetailWork" : "visibleSurfaceWork";
            PredictionPlanningTestSupport.set(manager, lane, queue);
            var first = new PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            var second = new PredictionTileKey(Level.OVERWORLD, 1, 0, 0);
            queue.addAll(shrink ? List.of(first, second) : List.of(second));
            if (shrink) {
                config.predictionDistanceBlocks = 1024;
                assertNotEquals(manager.layout().levelCount(), VssLodLayout.of(1024, 6, true, false).levelCount());
            } else {
                int axis = 8, step = manager.layout().tileBlocks(0) / axis;
                var base = PredictionVoxelFixtures.flat(first, axis, step, 64).voxyTile;
                var approximate = new PredictionVoxySource.Tile(first, base.baseX(), base.baseZ(),
                        base.span(), axis, step, base.columns(), List.of(), base.terrainBytes(), true, 0, null, true);
                ((Map<PredictionTileKey, PredictionTile>) PredictionPlanningTestSupport.get(manager, "ready"))
                        .put(first, new PredictionTile(first, new int[0], new int[0], new ClientColumnSample[0],
                                PredictionVoxelData.forVoxy(approximate), new PredictionDepthBound(64, 64), 0, 1, axis, step));
            }
            // A completion worker already owns this monitor in production. Its
            // lane scan discards stale hints; it must not interleave with a tick's
            // remove/rotate/insert or layout-prune transaction.
            var worker = new FutureTask<Void>(() -> {
                synchronized (manager) {
                    entered.set(true);
                    invoke(manager, "serviceGenerationWork", new Class<?>[]{int.class, int.class, long.class},
                            0, 0, System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
                }
                return null;
            });
            var tick = new FutureTask<Void>(() -> { manager.tick(8, 80, 8, 1, null); return null; });
            var clientThread = new Thread(tick, "vss-test-client");
            var completionThread = new Thread(worker, "vss-test-completion");
            clientThread.start();
            try {
                assertTrue(probe.atMutation.await(10, TimeUnit.SECONDS), "tick must reach the affected queue mutation");
                completionThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!worker.isDone() && completionThread.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) Thread.yield();
                assertTrue(worker.isDone() || completionThread.getState() == Thread.State.BLOCKED,
                        "completion must attempt admission while the client mutation is paused");
                boolean interleaved = entered.get();
                probe.resume.countDown();
                tick.get(10, TimeUnit.SECONDS);
                worker.get(10, TimeUnit.SECONDS);
                assertFalse(interleaved, "worker admission must wait until the client scheduling transaction finishes");
                assertEquals(queue.size(), new HashSet<>(queue).size(), "hints must remain unique");
                assertFalse(queue.contains(null), "a lane cannot contain a null task");
                assertEquals(0, manager.failedTileCount());
            } finally {
                probe.resume.countDown();
                clientThread.join(10000);
                if (completionThread.getState() != Thread.State.NEW) completionThread.join(10000);
            }
        } finally {
            config.predictionDistanceBlocks = distance;
            config.predictionFarDecorations = far;
        }
    }

    private static void invoke(Object owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { method.invoke(owner, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static final class PausingDeque extends ArrayDeque<PredictionTileKey> {
        final CountDownLatch atMutation = new CountDownLatch(1), resume = new CountDownLatch(1);
        private final boolean pruning;
        private boolean paused;

        PausingDeque(boolean pruning) { this.pruning = pruning; }
        @Override public PredictionTileKey removeFirst() {
            if (!pruning) pauseClient();
            return super.removeFirst();
        }
        @Override public boolean removeIf(Predicate<? super PredictionTileKey> filter) {
            if (pruning) pauseClient();
            return super.removeIf(filter);
        }
        private void pauseClient() {
            if (paused || !Thread.currentThread().getName().equals("vss-test-client")) return;
            paused = true;
            atMutation.countDown();
            try { if (!resume.await(15, TimeUnit.SECONDS)) throw new AssertionError("client was not released"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}

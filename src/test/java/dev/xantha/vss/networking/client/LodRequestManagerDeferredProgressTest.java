package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

/** Exercises the actual drain, including queue mutations and outgoing request flags. */
class LodRequestManagerDeferredProgressTest {
    @BeforeAll
    static void initializeConfigDirectory() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Files.createTempDirectory("vss-deferred-test"));
        }
    }

    @Test
    void exhaustedProbeBudgetDoesNotBlockDistantGeneration() throws Exception {
        Fixture f = new Fixture();
        long probe = f.defer(2, false);
        long generation = f.defer(40, true);
        assertEquals(1, f.drain(window(2, 0), 96));
        assertEquals(generation, f.positions[0]);
        assertTrue(f.allowGeneration[0]);
        assertFalse(f.probes[0]);
        assertTrue(f.queue.contains(probe));
        assertFalse(f.queue.contains(generation));
    }

    @Test
    void coolingNearestCandidateDoesNotBlockReadyGeneration() throws Exception {
        Fixture f = new Fixture();
        long cooling = f.defer(2, true);
        long ready = f.defer(40, true);
        RetryBackoff backoff = (RetryBackoff) field("retryBackoff").get(f.manager);
        backoff.markBackoff(cooling, false, true);
        // A fixed time before the backoff deadline keeps this test independent of machine speed.
        f.now = 0L;
        assertEquals(1, f.drain(window(2, 0), 96));
        assertEquals(ready, f.positions[0]);
        assertTrue(f.queue.contains(cooling));
    }

    @Test
    void successfulNearProbeDoesNotPinGenerationToItsRing() throws Exception {
        Fixture f = new Fixture();
        long probe = f.defer(2, false);
        long generation = f.defer(40, true);
        assertEquals(2, f.drain(window(2, 1), 96));
        assertArrayEquals(new long[] {probe, generation}, java.util.Arrays.copyOf(f.positions, 2));
        assertTrue(f.probes[0]);
        assertFalse(f.allowGeneration[0]);
        assertTrue(f.allowGeneration[1]);
    }

    @Test
    void readyGenerationUsesNearestFirstOrderAndRespectsConcurrency() throws Exception {
        Fixture f = new Fixture();
        long far = f.defer(60, true);
        long near = f.defer(3, true);
        long middle = f.defer(30, true);
        RequestWindow window = window(2, 0);
        assertEquals(2, f.drain(window, 96));
        assertArrayEquals(new long[] {near, middle}, java.util.Arrays.copyOf(f.positions, 2));
        assertEquals(0, window.generationRemaining());
        assertEquals(2, f.tracker.generationSize());
        assertTrue(f.queue.contains(far));
    }

    @Test
    void batchLimitLeavesRemainingCandidatesQueuedForNextDrain() throws Exception {
        Fixture f = new Fixture();
        long first = f.defer(3, true);
        long second = f.defer(30, true);
        assertEquals(1, f.drain(window(2, 0), 1));
        assertEquals(first, f.positions[0]);
        assertTrue(f.queue.contains(second));
        assertEquals(1, f.drain(window(1, 0), 1));
        assertEquals(second, f.positions[0]);
        assertEquals(0, f.queue.queuedEntries());
    }

    @Test
    void outsideFrontierIsStillDeferred() throws Exception {
        Fixture f = new Fixture();
        field("softFrontierRadius").setInt(f.manager, 16);
        long far = f.defer(40, true);
        assertEquals(0, f.drain(window(2, 0), 96));
        assertTrue(f.queue.contains(far));
        assertEquals(0, f.tracker.size());
    }

    @Test
    void cacheOnlyPassNeverGrantsGenerationPermission() throws Exception {
        Fixture f = new Fixture();
        f.defer(40, true);
        ((CacheOnlyReloadTracker) field("cacheOnlyReload").get(f.manager))
                .begin(List.of("minecraft:overworld"), "minecraft:overworld");
        assertEquals(1, f.drain(window(2, 1), 96));
        assertFalse(f.allowGeneration[0]);
        assertTrue(f.probes[0]);
        assertEquals(0, f.tracker.generationSize());
    }

    @Test
    void disabledGenerationNeverGrantsGenerationPermission() throws Exception {
        Fixture f = new Fixture();
        field("sessionConfig").set(f.manager, config(false));
        f.defer(40, true);
        assertEquals(1, f.drain(window(2, 1), 96));
        assertFalse(f.allowGeneration[0]);
        assertTrue(f.probes[0]);
    }

    @Test
    void confirmedGenerationRunsBeforeInitialNearSweepFinishes() throws Exception {
        Fixture f = new Fixture();
        long ready = f.defer(3, true);
        field("orderedOffsetDistance").setInt(f.manager, 128);
        field("orderedOffsetCount").setInt(f.manager, 257 * 257);
        assertFalse(field("nearScanCompletedForCurrentOffsets").getBoolean(f.manager));
        assertEquals(1, f.collect(window(1, 0)));
        assertEquals(ready, f.positions[0]);
        assertTrue(f.allowGeneration[0]);
    }

    @Test
    void walkingPreservesOuterAndIncompleteNearProgressAndAcceptedWork() throws Exception {
        Fixture f = new Fixture();
        field("orderedOffsetDistance").setInt(f.manager, 128);
        field("orderedOffsetCount").setInt(f.manager, 257 * 257);
        field("scanOffsetIndex").setInt(f.manager, 30000);
        field("nearScanOffsetIndex").setInt(f.manager, 2000);
        field("presenceAuditOffsetIndex").setInt(f.manager, 18000);
        long ready = f.defer(70, true);
        int id = f.tracker.track(ready, true, false, false, 1_000_000_000L, f.now);
        Method move = LodRequestManager.class.getDeclaredMethod("rebaseScanCursorAfterMove", int.class);
        Method prune = LodRequestManager.class.getDeclaredMethod("pruneStaleGenerationWorkAround", int.class, int.class, int.class);
        move.setAccessible(true); prune.setAccessible(true);
        for (int x = 1; x <= 10; x++) {
            move.invoke(f.manager, 128);
            prune.invoke(f.manager, x, 0, 128);
        }
        assertEquals(30000, field("scanOffsetIndex").getInt(f.manager));
        assertEquals(2000, field("nearScanOffsetIndex").getInt(f.manager));
        assertEquals(18000, field("presenceAuditOffsetIndex").getInt(f.manager));
        assertTrue(f.tracker.matches(id, ready));
        assertTrue(f.queue.contains(ready));
        prune.invoke(f.manager, 300, 0, 128);
        assertFalse(f.tracker.contains(ready), "out-of-range work must still cancel");
        assertFalse(f.queue.contains(ready));
    }

    @Test
    void oneFreeSlotDoesNotDrainThousandsOfDeferredEntries() throws Exception {
        Fixture f = new Fixture();
        for (int x = -32; x < 32; x++) for (int z = -32; z < 32; z++) {
            long p = PositionUtil.packPosition(x, z);
            f.queue.defer(p); f.misses.add(p);
        }
        var budget = new LodRequestManager.ScanBudget(4096, Long.MAX_VALUE, () -> 0L);
        assertEquals(1, f.drain(window(1, 0), 96, budget));
        assertTrue(4096 - budget.remainingCandidates() <= 32);
        assertEquals(4095, f.queue.queuedEntries());
    }

    @Test
    void blockedFirstBatchDoesNotHideReadyWorkInLaterBatch() throws Exception {
        Fixture f = new Fixture();
        for (int i = 1; i <= 40; i++) f.defer(i, false);
        long ready = f.defer(60, true);
        assertEquals(1, f.drain(window(1, 0), 96));
        assertEquals(ready, f.positions[0]);
        assertEquals(40, f.queue.queuedEntries());
    }

    @Test
    void completedInitialNearPassContinuesAtFirstOuterRingWithoutRescanning() throws Exception {
        Fixture f = new Fixture();
        field("orderedOffsetDistance").setInt(f.manager, 128);
        field("orderedOffsetCount").setInt(f.manager, 257 * 257);
        var known = (it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap) field("columnTimestamps").get(f.manager);
        int radius = dev.xantha.vss.common.VSSConstants.SYNC_NEAR_DISTANCE_CHUNKS;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            known.put(PositionUtil.packPosition(x, z), 1L);
        assertEquals(1, f.collect(window(0, 1)));
        assertEquals(radius + 1, Math.max(Math.abs(PositionUtil.unpackX(f.positions[0])),
                Math.abs(PositionUtil.unpackZ(f.positions[0]))));
        assertTrue(field("nearScanCompletedOnce").getBoolean(f.manager));
    }

    @Test
    void periodicRetryStillRevisitsInnerHolesAfterInitialNearPass() throws Exception {
        Fixture f = new Fixture();
        field("orderedOffsetDistance").setInt(f.manager, 128);
        field("orderedOffsetCount").setInt(f.manager, 257 * 257);
        field("scanOffsetIndex").setInt(f.manager, 257 * 257);
        field("scanCompletedForCurrentOffsets").setBoolean(f.manager, true);
        field("nearScanCompletedForCurrentOffsets").setBoolean(f.manager, true);
        field("nearScanCompletedOnce").setBoolean(f.manager, true);
        assertEquals(1, f.collect(window(0, 1)));
        assertEquals(PositionUtil.packPosition(0, 0), f.positions[0]);
        assertFalse(f.allowGeneration[0], "unknown positions must still probe storage first");
        assertTrue(f.probes[0]);
    }

    private static RequestWindow window(int generation, int probes) {
        return new RequestWindow(0, 0, 0, 0, generation, probes, 0);
    }

    private static SessionConfigS2CPayload config(boolean generation) {
        return new SessionConfigS2CPayload(1, true, 128, 0, 0, 0, 0, 0, 128, generation, 0L, 1L);
    }

    @Test
    void predictionOptionControlsActualRequestWindowWithoutDroppingDeferredGeneration() throws Exception {
        Fixture f = new Fixture();
        Field capabilities = VSSClientNetworking.class.getDeclaredField("serverCapabilities");
        capabilities.setAccessible(true);
        int previousCapabilities = capabilities.getInt(null);
        boolean previousOption = dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction;
        Method create = LodRequestManager.class.getDeclaredMethod("createRequestWindow");
        create.setAccessible(true);
        try {
            capabilities.setInt(null, dev.xantha.vss.common.VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN);
            dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction = true;
            long waiting = f.defer(3, true);
            RequestWindow held = (RequestWindow) create.invoke(f.manager);
            assertEquals(0, held.generationRemaining());
            assertTrue(held.canSend(true, false, false, 3), "dirty refresh remains available");
            assertTrue(held.canSend(false, false, true, 3), "cache probes remain available");
            assertEquals(0, f.drain(held, 96));
            assertTrue(f.queue.contains(waiting));
            dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction = false;
            RequestWindow normal = (RequestWindow) create.invoke(f.manager);
            assertEquals(config(true).generationConcurrencyLimitPerPlayer(), normal.generationRemaining());
            assertEquals(1, f.drain(normal, 96));
            assertTrue(f.allowGeneration[0]);
            assertEquals(waiting, f.positions[0]);
            // A server without prediction support must use its normal generation quota.
            capabilities.setInt(null, 0);
            dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction = true;
            normal = (RequestWindow) create.invoke(f.manager);
            assertEquals(config(true).generationConcurrencyLimitPerPlayer() - 1, normal.generationRemaining());
        } finally {
            capabilities.setInt(null, previousCapabilities);
            dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction = previousOption;
        }
    }

    private static Field field(String name) throws Exception {
        Field field = LodRequestManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture {
        final ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {});
        final LodRequestManager manager = new LodRequestManager("deferred-progress-test", tracker);
        final DeferredColumnQueue queue;
        final LongOpenHashSet misses;
        final long[] positions = new long[96];
        final boolean[] allowGeneration = new boolean[96];
        final boolean[] probes = new boolean[96];
        long now = System.nanoTime();

        Fixture() throws Exception {
            field("sessionConfig").set(manager, config(true));
            field("softFrontierRadius").setInt(manager, 128);
            queue = (DeferredColumnQueue) field("deferredColumns").get(manager);
            queue.recenter(0, 0);
            misses = (LongOpenHashSet) field("diskMissedColumns").get(manager);
        }

        long defer(int ring, boolean generation) {
            long packed = PositionUtil.packPosition(ring, 0);
            queue.defer(packed);
            if (generation) misses.add(packed);
            return packed;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        int drain(RequestWindow window, int limit) throws Exception {
            Class<?> budgetClass = Class.forName(LodRequestManager.class.getName() + "$ScanBudget");
            Constructor<?> constructor = budgetClass.getDeclaredConstructor(int.class, long.class);
            constructor.setAccessible(true);
            Object budget = constructor.newInstance(4096, System.nanoTime() + 60_000_000_000L);
            return drain(window, limit, (LodRequestManager.ScanBudget) budget);
        }

        int collect(RequestWindow window) throws Exception {
            Method collect = LodRequestManager.class.getDeclaredMethod("collectRequests",
                    int.class, int.class, int.class, int.class, int[].class, long[].class, long[].class,
                    boolean[].class, boolean[].class, int.class, RequestWindow.class,
                    long.class, LodRequestManager.ScanBudget.class);
            collect.setAccessible(true);
            return (int) collect.invoke(manager, 0, 0, 128, 0, new int[96], positions, new long[96],
                    allowGeneration, probes, 96, window, now,
                    new LodRequestManager.ScanBudget(10000, Long.MAX_VALUE, () -> 0L));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        int drain(RequestWindow window, int limit, LodRequestManager.ScanBudget budget) throws Exception {
            Class<?> budgetClass = LodRequestManager.ScanBudget.class;
            Class<?> modeClass = Class.forName(LodRequestManager.class.getName() + "$DeferredDrainMode");
            Object mode = Enum.valueOf((Class) modeClass, "ALL");
            Method drain = LodRequestManager.class.getDeclaredMethod("drainDeferredColumns",
                    int.class, int.class, int.class, int.class, int[].class, long[].class, long[].class,
                    boolean[].class, boolean[].class, int.class, int.class, RequestWindow.class,
                    long.class, int.class, budgetClass, modeClass);
            drain.setAccessible(true);
            return (int) drain.invoke(manager, 0, 0, 128, 0, new int[96], positions, new long[96],
                    allowGeneration, probes, 0, limit, window, now, 128, budget, mode);
        }
    }
}

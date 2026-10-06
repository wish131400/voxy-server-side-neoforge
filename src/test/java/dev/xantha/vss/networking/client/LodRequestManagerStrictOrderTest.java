package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class LodRequestManagerStrictOrderTest {
    private LodRequestManager manager;
    private ClientRequestTracker tracker;
    private final int[] ids = new int[96];
    private final long[] positions = new long[96], timestamps = new long[96];
    private final boolean[] generation = new boolean[96], probes = new boolean[96];
    private boolean oldEnabled, oldVoxy, oldPrediction, oldPresenceLoaded;
    private boolean oldPresenceDirty;
    private int oldPresenceUpdates;
    private long oldPresenceSaveNanos;
    private int oldCapabilities;

    @BeforeAll static void initializeConfigDirectory() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createTempDirectory("vss-strict-test"));
        }
    }
    @BeforeEach void setup() throws Exception {
        oldPresenceLoaded = field(ClientLodPresenceCache.class, "loaded").getBoolean(null);
        oldPresenceDirty = field(ClientLodPresenceCache.class, "dirty").getBoolean(null);
        oldPresenceUpdates = field(ClientLodPresenceCache.class, "updatesSinceSave").getInt(null);
        oldPresenceSaveNanos = field(ClientLodPresenceCache.class, "lastSaveNanos").getLong(null);
        // Exercise presence in memory without reading or saving a game directory.
        field(ClientLodPresenceCache.class, "loaded").setBoolean(null, true);
        field(ClientLodPresenceCache.class, "updatesSinceSave").setInt(null, 0);
        field(ClientLodPresenceCache.class, "lastSaveNanos").setLong(null, System.nanoTime());
        ClientLodPresenceCache.clearScopeWithDimensions("strict-order");
        oldPrediction = VSSClientConfig.CONFIG.enablePrediction;
        VSSClientConfig.CONFIG.enablePrediction = true;
        oldEnabled = field(VSSClientNetworking.class,"serverEnabled").getBoolean(null);
        oldCapabilities = field(VSSClientNetworking.class,"serverCapabilities").getInt(null);
        oldVoxy = field(ModCompat.class,"voxyLoaded").getBoolean(null);
        field(VSSClientNetworking.class,"serverEnabled").setBoolean(null, true);
        field(VSSClientNetworking.class,"serverCapabilities").setInt(null, dev.xantha.vss.common.VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN);
        field(ModCompat.class,"voxyLoaded").setBoolean(null, true);
        frontier(-1);
        tracker = new ClientRequestTracker(ignored -> {});
        manager = new LodRequestManager("strict-order", tracker);
        field(LodRequestManager.class,"lastDimension").set(manager, Level.OVERWORLD);
        field(LodRequestManager.class,"sessionConfig").set(manager,
                new SessionConfigS2CPayload(1,true,128,0,0,0,0,0,128,true,0L,1L));
        queue().recenter(0,0);
        Method offsets = LodRequestManager.class.getDeclaredMethod("ensureOrderedOffsets", int.class);
        offsets.setAccessible(true);
        offsets.invoke(manager, 128);
    }
    @AfterEach void cleanup() throws Exception {
        ClientLodPresenceCache.clearScopeWithDimensions("strict-order");
        field(ClientLodPresenceCache.class, "loaded").setBoolean(null, oldPresenceLoaded);
        field(ClientLodPresenceCache.class, "dirty").setBoolean(null, oldPresenceDirty);
        field(ClientLodPresenceCache.class, "updatesSinceSave").setInt(null, oldPresenceUpdates);
        field(ClientLodPresenceCache.class, "lastSaveNanos").setLong(null, oldPresenceSaveNanos);
        VSSClientConfig.CONFIG.enablePrediction = oldPrediction;
        field(VSSClientNetworking.class,"serverEnabled").setBoolean(null,oldEnabled);
        field(VSSClientNetworking.class,"serverCapabilities").setInt(null,oldCapabilities);
        field(ModCompat.class,"voxyLoaded").setBoolean(null,oldVoxy);
        StrictLodVisibility.reset();
    }
    @Test void generationHoldStillAllowsDistantDirtyRefreshAndNearCacheProbes() throws Exception {
        long far = PositionUtil.packPosition(128, 0);
        queue().defer(far, true);
        ((LongOpenHashSet) field(LodRequestManager.class, "dirtyColumns").get(manager)).add(far);
        assertEquals(2, collect(new RequestWindow(0, 0, 0, 0, 0, 1, 1)));
        assertEquals(far, positions[0]);
        assertFalse(probes[0]);
        assertFalse(generation[0]);
        assertEquals(PositionUtil.packPosition(0, 0), positions[1]);
        assertTrue(probes[1]);
        assertFalse(generation[1]);
        assertFalse(queue().contains(far));
    }
    @Test void adjacentStrictColumnsRemainIndependentlyAddressable() throws Exception {
        var empty=new dev.xantha.vss.api.VoxelColumnData(null,1L);
        for(int x=-16;x<16;x++) for(int z=-16;z<16;z++) manager.recordStrictSections(x,z,empty);
        for(int x=-16;x<16;x++) for(int z=-16;z<16;z++) {
            assertTrue(manager.strictColumnReady(x,z));
            assertTrue(manager.strictColumnRenderReady(x,z,ignored -> false));
        }
        field(LodRequestManager.class,"lastDimension").set(manager,null);
        manager.reconcileMissingColumn(PositionUtil.packPosition(-7,11));
        assertFalse(manager.strictColumnReady(-7,11));
        assertTrue(manager.strictColumnReady(-7,10));
        assertTrue(manager.strictColumnReady(-8,11));
    }
    @Test void nearbyStrictColumnsDoNotCollapseIntoHashCollisionTrees() throws Exception {
        var empty = new dev.xantha.vss.api.VoxelColumnData(null, 1L);
        for (int x = -32; x < 32; x++) for (int z = -32; z < 32; z++) manager.recordStrictSections(x, z, empty);
        var sections = (java.util.Map<?, ?>) field(LodRequestManager.class, "strictSections").get(manager);
        int[] buckets = new int[4096];
        for (Object key : sections.keySet()) {
            int hash = key.hashCode();
            buckets[(hash ^ (hash >>> 16)) & (buckets.length - 1)]++;
        }
        assertEquals(4096, sections.size());
        assertTrue(java.util.Arrays.stream(buckets).max().orElseThrow() < 16,
                "packed X/Z have Long.hashCode = X xor Z; mix the full 64-bit coordinate reversibly before indexing");
    }
    @Test void capabilityRefreshWithUnchangedServerConfigPreservesRealLodRequests() throws Exception {
        long position = PositionUtil.packPosition(0,0);
        int id = tracker.track(position,true,false,false,60_000_000_000L,0L);
        long deferred = PositionUtil.packPosition(1,0);
        queue().defer(deferred);
        var config = (SessionConfigS2CPayload)field(LodRequestManager.class,"sessionConfig").get(manager);
        assertFalse(manager.onSessionConfig(config));
        assertTrue(tracker.matches(id,position));
        assertTrue(queue().contains(deferred));
    }
    @Test void confirmedNearMissUsesGenerationCapacityIndependentlyOfCacheProbes() throws Exception {
        long near = PositionUtil.packPosition(0, 0);
        ((LongOpenHashSet) field(LodRequestManager.class, "diskMissedColumns").get(manager)).add(near);
        queue().defer(near);
        assertEquals(1, collect(new RequestWindow(0, 0, 0, 0, 1, 0, 0)));
        assertEquals(near, positions[0]);
        assertTrue(generation[0]);
        assertFalse(probes[0]);
        assertEquals(0, collect(new RequestWindow(0, 0, 0, 0, 1, 0, 0)));
    }
    @Test void cacheProbeMissStillWaitsForNearGeneration() {
        int id=tracker.track(PositionUtil.packPosition(0,0),false,true,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        assertFalse(manager.strictColumnReady(0,0));
        assertFalse(manager.strictColumnRenderReady(0,0,ignored -> true));
    }
    @Test void responsesAndDirtyColumnsWakeScanningButGeometryChangesDoNot() throws Exception {
        assertTrue(manager.shouldScanRequests());
        assertFalse(manager.shouldScanRequests());
        assertTrue(manager.shouldScanRequests());
        int id = tracker.track(PositionUtil.packPosition(0, 0), true, false, false, 60_000_000_000L, 0L);
        manager.onColumnNotGenerated(id);
        assertTrue(manager.shouldScanRequests(), "a freed request slot must wake the scheduler");
        field(StrictLodVisibility.class, "snapshot").set(null,
                new StrictLodVisibility.Snapshot(Level.OVERWORLD, 0, 0, 0, 1, 2));
        assertFalse(manager.shouldScanRequests(), "GPU ring progress is independent from request scheduling");
        assertTrue(manager.shouldScanRequests());
        manager.onDirtyColumns(new long[]{PositionUtil.packPosition(0, 0)}, new long[]{10L});
        assertTrue(manager.shouldScanRequests());
    }
    @Test void unavailableGenerationRetainsRetryWithoutBlockingNearbyCacheDiscovery() throws Exception {
        long center = PositionUtil.packPosition(0, 0);
        int id = tracker.track(center, true, false, false, 60_000_000_000L, 0L);
        manager.onColumnNotGenerated(id);
        assertTrue(manager.strictColumnReady(0, 0));
        assertTrue(manager.strictColumnRenderReady(0, 0, ignored -> false));
        assertFalse(tracker.contains(center));
        assertTrue(queue().contains(center), "an unavailable column still needs a generation retry");
        StrictLodFrontier readiness = new StrictLodFrontier();
        readiness.center(0, 0, 128);
        assertTrue(readiness.advance(manager::strictColumnReady,
                (x, z) -> manager.strictColumnRenderReady(x, z, ignored -> false)));
        assertEquals(1, readiness.requestRing());
        frontier(readiness.visibleRing());
        assertEquals(4, collect(new RequestWindow(0, 0, 0, 0, 0, 4, 0)));
        for (int i = 0; i < 4; i++) {
            assertEquals(1, PositionUtil.chebyshevDistance(
                    PositionUtil.unpackX(positions[i]), PositionUtil.unpackZ(positions[i]), 0, 0));
            assertTrue(probes[i]);
        }
    }
    @Test void unavailableColumnWithoutGenerationDoesNotBlockTheRing() throws Exception {
        field(LodRequestManager.class,"sessionConfig").set(manager,
                new SessionConfigS2CPayload(1,true,128,0,0,0,0,0,128,false,0L,1L));
        int id=tracker.track(PositionUtil.packPosition(0,0),false,true,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        assertTrue(manager.strictColumnReady(0,0));
        assertTrue(manager.strictColumnRenderReady(0,0,ignored -> false));
    }
    @Test void temporaryCacheOnlyReplayDoesNotSettleUngeneratedHoles() throws Exception {
        var reload=(CacheOnlyReloadTracker)field(LodRequestManager.class,"cacheOnlyReload").get(manager);
        reload.begin(java.util.List.of(Level.OVERWORLD.location().toString()),Level.OVERWORLD.location().toString());
        int id=tracker.track(PositionUtil.packPosition(0,0),false,true,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        assertFalse(manager.strictColumnReady(0,0));
    }
    @Test void retryFailureDoesNotDiscardPreviouslyReceivedSections() throws Exception {
        strictSections().put(PositionUtil.packPosition(0,0),new int[]{4});
        int id=tracker.track(PositionUtil.packPosition(0,0),true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        assertTrue(manager.strictColumnReady(0,0));
        assertFalse(manager.strictColumnRenderReady(0,0,ignored -> false));
        assertTrue(manager.strictColumnRenderReady(0,0,y -> y==4));
    }
    @Test void actualSectionsTakePrecedenceDuringUnavailableReplacement() throws Exception {
        int id=tracker.track(PositionUtil.packPosition(0,0),true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        strictSections().put(PositionUtil.packPosition(0,0),new int[]{4});
        assertFalse(manager.strictColumnRenderReady(0,0,ignored -> false));
        assertTrue(manager.strictColumnRenderReady(0,0,y -> y==4));
    }
    @Test void successfulColumnReplacesUnavailableMarker() throws Exception {
        long center=PositionUtil.packPosition(0,0);
        int id=tracker.track(center,true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        manager.recordStrictSections(0,0,new dev.xantha.vss.api.VoxelColumnData(null,10L));
        assertFalse(unavailable().contains(center));
        assertTrue(manager.strictColumnReady(0,0),"a valid empty column is still complete");
    }
    @Test void disablingPredictionResetsDiscoveryWithoutRequeuingAllHistoricalMisses() throws Exception {
        long near = PositionUtil.packPosition(0, 0), far = PositionUtil.packPosition(128, 0);
        int failed = tracker.track(near, true, false, false, 60_000_000_000L, 0L);
        manager.onColumnNotGenerated(failed);
        var misses = (LongOpenHashSet) field(LodRequestManager.class, "diskMissedColumns").get(manager);
        misses.add(near);
        misses.add(far);
        manager.recordStrictSections(1, 0, emptyColumn(10L));
        manager.restoreKnownColumn(PositionUtil.packPosition(1, 0), 10L);
        int distant = tracker.track(far, true, false, false, 60_000_000_000L, 0L);
        queue().defer(PositionUtil.packPosition(100, 0));
        long dirty = PositionUtil.packPosition(90, 0);
        ((LongOpenHashSet) field(LodRequestManager.class, "dirtyColumns").get(manager)).add(dirty);
        int dirtyRequest = tracker.track(dirty, false, false, true, 60_000_000_000L, 0L);
        queue().defer(dirty, true);
        field(LodRequestManager.class, "scanOffsetIndex").setInt(manager, 10_000);
        field(LodRequestManager.class, "nearScanOffsetIndex").setInt(manager, 4_225);
        field(LodRequestManager.class, "nearScanCompletedOnce").setBoolean(manager, true);
        frontier(127);
        VSSClientConfig.CONFIG.enablePrediction = false;
        manager.restartPredictionOrder(false, Level.OVERWORLD, 0, 0);
        Method reset = LodRequestManager.class.getDeclaredMethod("resetSchedulerForPredictionDisable");
        reset.setAccessible(true);
        reset.invoke(manager);
        assertFalse(manager.strictColumnReady(0, 0));
        assertTrue(manager.strictColumnReady(1, 0));
        assertEquals(10L, manager.requestTimestampFor(PositionUtil.packPosition(1, 0)));
        assertEquals(0, field(LodRequestManager.class, "scanOffsetIndex").getInt(manager));
        assertEquals(0, field(LodRequestManager.class, "nearScanOffsetIndex").getInt(manager));
        assertFalse(field(LodRequestManager.class, "nearScanCompletedOnce").getBoolean(manager));
        assertTrue(queue().contains(dirty));
        assertTrue(tracker.matches(dirtyRequest, dirty));
        assertFalse(queue().contains(PositionUtil.packPosition(100, 0)));
        assertFalse(tracker.contains(far));
        assertTrue(misses.contains(near));
        assertTrue(misses.contains(far));
        assertEquals(LodRequestManager.ColumnProcessingResult.STALE, manager.processColumnIfCurrent(
                distant, Level.OVERWORLD, 128, 0, 10, new int[0],
                () -> { fail("A cancelled distant request reached Voxy"); return true; }));
        assertEquals(0, collect(new RequestWindow(0, 0, 0, 0, 1, 0, 0)));
        assertTrue(queue().contains(near));
        assertFalse(queue().contains(far));
        assertEquals(1, collect(new RequestWindow(0, 0, 0, 0, 1, 0, 0)));
        assertEquals(near, positions[0]);
        assertTrue(generation[0]);
        assertFalse(probes[0]);
    }
    @Test void enablingPredictionPreservesReadinessAndDisablingRecentersBookkeeping() throws Exception {
        int failed = tracker.track(PositionUtil.packPosition(0, 0), true, false, false, 60_000_000_000L, 0L);
        manager.onColumnNotGenerated(failed);
        frontier(31);
        var before = StrictLodVisibility.snapshot();
        manager.restartPredictionOrder(true, Level.OVERWORLD, 19, -41);
        assertEquals(before, StrictLodVisibility.snapshot());
        assertTrue(manager.strictColumnReady(0, 0));
        manager.restartPredictionOrder(false, Level.OVERWORLD, 19, -41);
        assertFalse(manager.strictColumnReady(0, 0));
        assertEquals(Level.OVERWORLD, StrictLodVisibility.snapshot().dimension());
        assertEquals(19, StrictLodVisibility.snapshot().x());
        assertEquals(-41, StrictLodVisibility.snapshot().z());
        assertEquals(-1, StrictLodVisibility.snapshot().visibleRing());
    }
    @Test void dirtyHoleAndSessionResetRequireFreshReadiness() throws Exception {
        long center=PositionUtil.packPosition(0,0);
        int id=tracker.track(center,true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        manager.onDirtyColumns(new long[]{center},new long[]{10L});
        assertFalse(manager.strictColumnReady(0,0));
        id=tracker.track(center,true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        assertTrue(manager.strictColumnReady(0,0));
        manager.forceResync();
        assertFalse(manager.strictColumnReady(0,0));
        assertTrue(unavailable().isEmpty());
    }
    @Test void pruningRemovesUnavailableColumnsWithoutTimestampEntries() throws Exception {
        long far=PositionUtil.packPosition(128,0);
        int id=tracker.track(far,true,false,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(id);
        Method prune=LodRequestManager.class.getDeclaredMethod("pruneAround",int.class,int.class,int.class);
        prune.setAccessible(true);prune.invoke(manager,0,0,32);
        assertFalse(manager.strictColumnReady(128,0));
        assertFalse(queue().contains(far));
    }
    @Test void staleNotGeneratedResponseCannotCompleteAnUnrequestedColumn() {
        manager.onColumnNotGenerated(-1);
        assertFalse(manager.strictColumnReady(0,0));
    }
    @SuppressWarnings("unchecked")
    private java.util.Map<Long,int[]> strictSections() throws Exception {
        return (java.util.Map<Long,int[]>)field(LodRequestManager.class,"strictSections").get(manager);
    }
    @SuppressWarnings("unchecked")
    private java.util.Set<Long> unavailable() throws Exception {
        return (java.util.Set<Long>)field(LodRequestManager.class,"strictUnavailableColumns").get(manager);
    }
    @Test void nearbyProbesUseDistanceOrderAndValidDistantResponsesRemainAccepted() throws Exception {
        frontier(0);
        assertEquals(12, collect(new RequestWindow(0, 0, 0, 0, 0, 12, 0)));
        int previous = -1;
        for (int i = 0; i < 12; i++) {
            int ring = PositionUtil.chebyshevDistance(
                    PositionUtil.unpackX(positions[i]), PositionUtil.unpackZ(positions[i]), 0, 0);
            assertTrue(ring >= previous);
            previous = ring;
        }
        assertEquals(2, previous, "discovery can continue beyond a completed ring");
        long far = PositionUtil.packPosition(128, 0);
        int request = tracker.track(far, false, false, false, 60_000_000_000L, 0L);
        assertEquals(LodRequestManager.ColumnProcessingResult.APPLIED, manager.processColumnIfCurrent(
                request, Level.OVERWORLD, 128, 0, 10, new int[0], () -> true));
        assertFalse(tracker.contains(far));
    }
    @Test void completedPregenNoticeReopensCacheMissEvenWithAutomaticGenerationDisabled() throws Exception {
        field(LodRequestManager.class,"sessionConfig").set(manager,
                new SessionConfigS2CPayload(1,true,128,0,0,0,0,0,128,false,0L,1L));
        long position = PositionUtil.packPosition(0,0);
        int miss = tracker.track(position,false,true,false,60_000_000_000L,0L);
        manager.onColumnNotGenerated(miss);
        assertTrue(manager.strictColumnReady(0,0));
        var notices = new dev.xantha.vss.networking.server.generation.ChunkyColumnAvailability(
                packet -> manager.onDirtyColumns(packet.dirtyPositions(),packet.dirtyTimestamps()));
        notices.available(0,0,10_000L); notices.flush();
        assertFalse(manager.strictColumnReady(0,0));
        assertTrue(queue().contains(position));
        assertEquals(1,collect(new RequestWindow(0,0,0,0,0,0,1)));
        assertEquals(position,positions[0]);
        assertFalse(generation[0],"persisted pregen data is fetched without another generation job");
        assertFalse(probes[0]);
        assertEquals(9_999L,timestamps[0],"request must fetch the saved version, not demand a newer one");
        int active = ids[0];
        notices.available(0,0,10_000L); notices.flush();
        assertTrue(tracker.contains(position),"repeated availability cannot cancel the active fetch");
        manager.onColumnNotGenerated(miss);
        assertTrue(tracker.contains(position),"an old miss response must not cancel the new fetch");
        assertTrue(active != miss);
    }
    @Test void transferWithoutAnActiveWorldCannotCancelANewerRequest() throws Exception {
        long position = PositionUtil.packPosition(1, 0);
        int old = tracker.track(position, true, false, false, 60_000_000_000L, 0L);
        assertFalse(manager.onColumnTransferPart(old, Level.OVERWORLD, 1, 0, 10).knownRequest());
        assertTrue(tracker.matches(old, position));
        tracker.cancel(position);
        int newer = tracker.track(position, true, false, false, 60_000_000_000L, 0L);
        manager.onColumnTransferPart(old, Level.OVERWORLD, 1, 0, 10);
        assertTrue(tracker.matches(newer, position));
    }
    private void frontier(int ring) throws Exception {
        field(StrictLodVisibility.class,"snapshot").set(null,new StrictLodVisibility.Snapshot(Level.OVERWORLD,0,0,ring,ring+1,1));
    }
    @Test void readinessCenterChangesDoNotBlockValidDistantResponses() throws Exception {
        long far = PositionUtil.packPosition(128, 0);
        long timestamp = 10L;
        for (int center : new int[]{0, 1, 8, 128, -64}) {
            field(StrictLodVisibility.class, "snapshot").set(null,
                    new StrictLodVisibility.Snapshot(Level.OVERWORLD, center, 0, -1, 0, 1));
            int request = tracker.track(far, false, false, false, 60_000_000_000L, 0L);
            assertEquals(LodRequestManager.ColumnProcessingResult.APPLIED, manager.processColumnIfCurrent(
                    request, Level.OVERWORLD, 128, 0, timestamp++, new int[0], () -> true));
        }
    }
    @Test void packagedMixinsCannotSuppressVoxyDrawCalls() throws Exception {
        try (var input = getClass().getResourceAsStream("/vss.strict.mixins.json")) {
            assertNotNull(input);
            var config = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            for (var mixin : config.getAsJsonArray("client"))
                assertNotEquals("voxy.StrictVoxyDrawMixin", mixin.getAsString());
        }
    }
    @Test void fullGenerationBudgetBoundsQueueChurnWithoutBlockingRingProbes() throws Exception {
        frontier(31);
        var misses = (LongOpenHashSet)field(LodRequestManager.class,"diskMissedColumns").get(manager);
        for (int x=-32;x<=32;x++) for(int z=-32;z<=32;z++) {
            if (Math.max(Math.abs(x),Math.abs(z)) != 32) continue;
            long p=PositionUtil.packPosition(x,z);
            queue().defer(p); misses.add(p);
        }
        long probe = PositionUtil.packPosition(32,32);
        misses.remove(probe);
        var budget=new LodRequestManager.ScanBudget(4096,Long.MAX_VALUE,()->0L);
        Method m=LodRequestManager.class.getDeclaredMethod("collectRequests",int.class,int.class,int.class,int.class,
                int[].class,long[].class,long[].class,boolean[].class,boolean[].class,int.class,RequestWindow.class,long.class,LodRequestManager.ScanBudget.class);
        m.setAccessible(true);
        assertEquals(1,m.invoke(manager,0,0,128,0,ids,positions,timestamps,generation,probes,96,
                new RequestWindow(0,0,0,0,0,1,0),0L,budget));
        assertEquals(probe,positions[0]); assertTrue(probes[0]); assertFalse(generation[0]);
        assertTrue(4096-budget.remainingCandidates()<=32+256,
                "at most one deferred batch plus the current ring should be examined");
        for (long blocked : misses) assertTrue(queue().contains(blocked));
        assertTrue(tracker.contains(probe));
    }
    private DeferredColumnQueue queue() throws Exception { return (DeferredColumnQueue)field(LodRequestManager.class,"deferredColumns").get(manager); }
    private int collect(RequestWindow window) throws Exception {
        Method m = LodRequestManager.class.getDeclaredMethod("collectRequests",int.class,int.class,int.class,int.class,
                int[].class,long[].class,long[].class,boolean[].class,boolean[].class,int.class,RequestWindow.class,long.class,LodRequestManager.ScanBudget.class);
        m.setAccessible(true);
        return (int)m.invoke(manager,0,0,128,0,ids,positions,timestamps,generation,probes,96,
                window,0L,new LodRequestManager.ScanBudget(4096,Long.MAX_VALUE,()->0L));
    }
    private static dev.xantha.vss.api.VoxelColumnData emptyColumn(long timestamp) {
        return new dev.xantha.vss.api.VoxelColumnData(
                new dev.xantha.vss.api.VoxelColumnData.SectionData[0], timestamp);
    }
    private static Field field(Class<?> type,String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
}

package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.StrictLodVisibility;
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
    private boolean oldEnabled, oldVoxy;

    @BeforeAll static void initializeConfigDirectory() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Files.createTempDirectory("vss-strict-test"));
        }
    }
    @BeforeEach void setup() throws Exception {
        oldEnabled = field(VSSClientNetworking.class,"serverEnabled").getBoolean(null);
        oldVoxy = field(ModCompat.class,"voxyLoaded").getBoolean(null);
        field(VSSClientNetworking.class,"serverEnabled").setBoolean(null, true);
        field(ModCompat.class,"voxyLoaded").setBoolean(null, true);
        frontier(-1);
        tracker = new ClientRequestTracker(ignored -> {});
        manager = new LodRequestManager("strict-order", tracker);
        field(LodRequestManager.class,"lastDimension").set(manager, Level.OVERWORLD);
        field(LodRequestManager.class,"sessionConfig").set(manager,
                new SessionConfigS2CPayload(1,true,128,0,0,0,0,0,128,true,0L,1L));
        queue().recenter(0,0);
    }
    @AfterEach void cleanup() throws Exception {
        field(VSSClientNetworking.class,"serverEnabled").setBoolean(null,oldEnabled);
        field(ModCompat.class,"voxyLoaded").setBoolean(null,oldVoxy);
        StrictLodVisibility.reset();
    }
    @Test void distantReadyAndDirtyCandidatesCannotStealRequestsFromTheMissingCenter() throws Exception {
        long far = PositionUtil.packPosition(128,0);
        queue().defer(far,true);
        ((LongOpenHashSet)field(LodRequestManager.class,"dirtyColumns").get(manager)).add(far);
        assertEquals(1, collect());
        assertEquals(PositionUtil.packPosition(0,0),positions[0]);
        assertTrue(probes[0]); assertFalse(generation[0]);
        assertTrue(queue().contains(far));
        assertEquals(0,collect(), "an outstanding center probe must not release the outer cache scan");
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
    @Test void confirmedNearMissGeneratesWithoutOpeningTheNextRing() throws Exception {
        long near = PositionUtil.packPosition(0,0);
        ((LongOpenHashSet)field(LodRequestManager.class,"diskMissedColumns").get(manager)).add(near);
        queue().defer(near);
        assertEquals(1,collect());
        assertEquals(near,positions[0]); assertTrue(generation[0]); assertFalse(probes[0]);
        assertEquals(0,collect());
    }
    @Test void advancingReadinessUnlocksExactlyOneRingAndStaleFarProcessingIsRejected() throws Exception {
        frontier(0);
        assertEquals(8,collect());
        for(int i=0;i<8;i++) assertEquals(1, PositionUtil.chebyshevDistance(
                PositionUtil.unpackX(positions[i]),PositionUtil.unpackZ(positions[i]),0,0));
        int request = tracker.track(PositionUtil.packPosition(128,0),false,false,false,60_000_000_000L,0L);
        assertEquals(LodRequestManager.ColumnProcessingResult.STALE,manager.processColumnIfCurrent(
                request,Level.OVERWORLD,128,0,10,new int[0],()->{fail("Far data reached the Voxy consumer");return true;}));
    }
    @Test void frontierRetractionReleasesRejectedTransferWithoutWaitingForTimeout() throws Exception {
        field(LodRequestManager.class,"lastPlayerChunkX").setInt(manager,0);
        field(LodRequestManager.class,"lastPlayerChunkZ").setInt(manager,0);
        long position = PositionUtil.packPosition(1,0);
        int old = tracker.track(position,true,false,false,60_000_000_000L,0L);
        manager.onColumnTransferPart(old,Level.OVERWORLD,1,0,10);
        assertFalse(tracker.contains(position));
        assertTrue(queue().contains(position));
        int newer = tracker.track(position,true,false,false,60_000_000_000L,0L);
        manager.onColumnTransferPart(old,Level.OVERWORLD,1,0,10);
        assertTrue(tracker.matches(newer,position), "late fragments must not cancel a new request");
        assertEquals(1,collect(), "center still has priority over the deferred outer response");
        assertEquals(PositionUtil.packPosition(0,0),positions[0]);
    }
    private void frontier(int ring) throws Exception {
        field(StrictLodVisibility.class,"snapshot").set(null,new StrictLodVisibility.Snapshot(Level.OVERWORLD,0,0,ring,ring+1,1));
    }
    @Test void missingCenterAndPlayerMovementNeverHideExistingVoxyTerrain() throws Exception {
        for (int center : new int[]{0, 1, 8, 128, -64}) {
            field(StrictLodVisibility.class,"snapshot").set(null,
                    new StrictLodVisibility.Snapshot(Level.OVERWORLD,center,0,-1,0,1));
            assertTrue(StrictLodVisibility.visible(Level.OVERWORLD,128,0));
            assertFalse(StrictLodVisibility.completed(Level.OVERWORLD,128,0));
            assertEquals(center == 128, StrictLodVisibility.requestable(Level.OVERWORLD,128,0));
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
                new RequestWindow(96,96,96,96,0,96,0),0L,budget));
        assertEquals(probe,positions[0]); assertTrue(probes[0]); assertFalse(generation[0]);
        assertTrue(4096-budget.remainingCandidates()<=32+256,
                "at most one deferred batch plus the current ring should be examined");
        for (long blocked : misses) assertTrue(queue().contains(blocked));
        assertTrue(tracker.contains(probe));
    }
    private DeferredColumnQueue queue() throws Exception { return (DeferredColumnQueue)field(LodRequestManager.class,"deferredColumns").get(manager); }
    private int collect() throws Exception {
        Method m = LodRequestManager.class.getDeclaredMethod("collectRequests",int.class,int.class,int.class,int.class,
                int[].class,long[].class,long[].class,boolean[].class,boolean[].class,int.class,RequestWindow.class,long.class,LodRequestManager.ScanBudget.class);
        m.setAccessible(true);
        return (int)m.invoke(manager,0,0,128,0,ids,positions,timestamps,generation,probes,96,
                new RequestWindow(96,96,96,96,8,96,96),0L,new LodRequestManager.ScanBudget(4096,Long.MAX_VALUE,()->0L));
    }
    private static Field field(Class<?> type,String name) throws Exception { Field f=type.getDeclaredField(name);f.setAccessible(true);return f; }
}

package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.processing.ColumnCoverage;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.common.VSSConstants;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColumnFilterSessionTest {
    @BeforeAll static void bootstrap() throws Exception {
        ColumnFilterFixtures.biomes();
        // Exercise the real in-memory presence store without a Minecraft client
        // or disk writer. Every class in this suite has its own JVM.
        var loaded = ClientLodPresenceCache.class.getDeclaredField("loaded");
        loaded.setAccessible(true); loaded.setBoolean(null, true);
        var saved = ClientLodPresenceCache.class.getDeclaredField("lastSaveNanos");
        saved.setAccessible(true); saved.setLong(null, System.nanoTime());
    }
    @Test void filteredSessionCannotRestoreOrAdvertiseFullCoverage() {
        var manager = new LodRequestManager("scoped-test", new ClientRequestTracker(), () -> { }, true);
        long packed = PositionUtil.packPosition(1, 2);
        assertFalse(manager.expectsAuthoritativeColumns());
        manager.restoreKnownColumn(packed, 100L);
        assertEquals(0L, manager.requestTimestampFor(packed));
        var data = new VoxelColumnData(new VoxelColumnData.SectionData[] {
                new VoxelColumnData.SectionData(0, ColumnFilterFixtures.stone(), null, null)
        }, 100L, false, new int[] {0}, true, new ColumnCoverage("a".repeat(64)));
        manager.recordStrictSections(1, 2, data);
        assertFalse(manager.strictColumnReady(1, 2));
        assertFalse(manager.strictColumnRenderReady(1, 2, y -> true));
        assertFalse(data.authoritative());
        assertThrows(IllegalArgumentException.class, () -> new VoxelColumnData(data.sections(), 100L, true, new int[] {0}, true, data.coverage()));
    }

    @Test void failedScopedIngestRevokesTheOldFullClaimAndCancelsItsRequest() throws Exception {
        String scope = "scoped-failed"; long packed = PositionUtil.packPosition(1, 2);
        var tracker = new ClientRequestTracker(ignored -> { });
        var manager = session(scope, tracker);
        ClientLodPresenceCache.recordColumn(scope, Level.OVERWORLD, packed, 100L, new int[] {0});
        assertNotNull(ClientLodPresenceCache.sectionManifest(scope, Level.OVERWORLD, packed));
        int requestId = tracker.track(packed, false, false, System.nanoTime(), 0L);
        assertEquals(LodRequestManager.ColumnProcessingResult.FAILED,
                manager.processColumnIfCurrent(requestId, Level.OVERWORLD, 1, 2, 200L, new int[] {0}, () -> false));
        assertNull(ClientLodPresenceCache.sectionManifest(scope, Level.OVERWORLD, packed));
        assertFalse(tracker.contains(packed));
    }

    @Test void successfulEmptyLocalUpdateIsAcknowledgedWithoutRestoringFullPresence() throws Exception {
        String scope = "scoped-local"; long packed = PositionUtil.packPosition(1, 2);
        var manager = session(scope, new ClientRequestTracker(ignored -> { }));
        ClientLodPresenceCache.recordColumn(scope, Level.OVERWORLD, packed, 100L, new int[] {0});
        assertTrue(manager.processLocalPregenColumn(Level.OVERWORLD, 1, 2, 200L, new int[0], () -> true));
        assertNull(ClientLodPresenceCache.sectionManifest(scope, Level.OVERWORLD, packed));
        assertTrue(manager.processLocalPregenColumn(Level.OVERWORLD, 1, 2, 200L, new int[0], () -> {
            fail("same scoped version must not ingest again"); return false;
        }));
        assertEquals(0L, manager.requestTimestampFor(packed));
        assertFalse(manager.strictColumnReady(1, 2));
    }

    private static LodRequestManager session(String scope, ClientRequestTracker tracker) throws Exception {
        var manager = new LodRequestManager(scope, tracker, () -> { }, true);
        manager.onSessionConfig(new SessionConfigS2CPayload(VSSConstants.PROTOCOL_VERSION, true,
                128, 0, 16, 16, 16, 16, 8, false, 0L, 1L));
        var dimension = LodRequestManager.class.getDeclaredField("lastDimension");
        dimension.setAccessible(true); dimension.set(manager, Level.OVERWORLD);
        return manager;
    }
}

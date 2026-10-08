package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LodRequestManagerCacheProbeMissTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeConfigDirectory() throws Exception {
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(
                    java.nio.file.Files.createTempDirectory("vss-cache-probe-miss-test"));
        }
    }

    @Test
    void confirmedCacheMissClearsStaleRuntimeVersion() throws Exception {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(12, -4);
        disableGeneration(manager);
        manager.restoreKnownColumn(packed, 123L);

        int requestId = tracker.track(packed, false, true, false, 1_000_000_000L, 0L);
        manager.onColumnNotGenerated(requestId);

        assertEquals(-1L, manager.requestTimestampFor(packed));
        assertFalse(tracker.contains(packed));
        assertEquals(0, tracker.generationSize());
    }

    @Test
    void disabledGenerationDoesNotQueueUnknownCacheMiss() throws Exception {
        ClientRequestTracker tracker = new ClientRequestTracker(ignored -> {
        });
        LodRequestManager manager = new LodRequestManager("test", tracker);
        long packed = PositionUtil.packPosition(-8, 17);
        disableGeneration(manager);

        int requestId = tracker.track(packed, false, true, false, 1_000_000_000L, 0L);
        manager.onColumnNotGenerated(requestId);

        assertEquals(-1L, manager.requestTimestampFor(packed));
        assertFalse(tracker.contains(packed));
        assertEquals(0, tracker.generationSize());
        Field deferred = LodRequestManager.class.getDeclaredField("deferredColumns");
        deferred.setAccessible(true);
        assertEquals(0, ((DeferredColumnQueue) deferred.get(manager)).queuedEntries());
    }

    private static void disableGeneration(LodRequestManager manager) throws Exception {
        Field field = LodRequestManager.class.getDeclaredField("sessionConfig");
        field.setAccessible(true);
        field.set(manager, new SessionConfigS2CPayload(1, true, 128, 0, 0, 0, 0, 0, 128, false, 0L, 1L));
    }
}

package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.api.VoxelColumnConsumer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class XaeroMapCompatQueueTest {
    @Test
    void sameCoordinateIsLatestWinsWithoutGrowingTheQueue() {
        var bridge = bridge(new AtomicBoolean(true), new ArrayList<>());
        bridge.offerPrepared("dimension", tile(4, 7));
        long firstBytes = bridge.queuedBytesForTest();
        bridge.offerPrepared("dimension", tile(4, 7));
        assertEquals(1, bridge.queuedForTest());
        assertEquals(firstBytes, bridge.queuedBytesForTest());
    }

    @Test
    void queueIsBoundedAndDropsTheOldestCoordinates() {
        var bridge = bridge(new AtomicBoolean(true), new ArrayList<>());
        for (int i = 0; i < XaeroMapCompat.MAX_QUEUE + 1; i++) {
            bridge.offerPrepared("dimension", tile(i, 0));
        }
        assertEquals(XaeroMapCompat.MAX_QUEUE, bridge.queuedForTest());
        assertFalse(bridge.hasQueuedForTest(0, 0));
        assertTrue(bridge.hasQueuedForTest(XaeroMapCompat.MAX_QUEUE, 0));
        assertEquals(1, bridge.counterForTest("dropped_overflow"));
    }

    @Test
    void intakePausesAtSoftWatermarkBeforeTheHardQueueCanDropColumns() {
        var bridge = bridge(new AtomicBoolean(true), new ArrayList<>());
        for (int i = 0; i < XaeroMapCompat.INTAKE_QUEUE_HIGH_WATERMARK; i++) {
            bridge.offerPrepared("dimension", tile(i, 0));
        }

        assertTrue(bridge.shouldBackpressureInputNow());
        assertEquals(0, bridge.counterForTest("dropped_overflow"));

        bridge.clearQueue();
        assertFalse(bridge.shouldBackpressureInputNow());
        assertFalse(bridge.hasPendingWorkNow());
    }

    @Test
    void disconnectClearsQueueAndDisabledBridgeDeregisters() {
        var enabled = new AtomicBoolean(true);
        var registered = new ArrayList<VoxelColumnConsumer>();
        var bridge = bridge(enabled, registered);
        bridge.maybeRegister();
        bridge.offerPrepared("dimension", tile(1, 2));
        assertEquals(1, registered.size());
        enabled.set(false);
        bridge.onSessionEnd();
        assertEquals(0, bridge.queuedForTest());
        assertEquals(0, registered.size());
        assertFalse(bridge.registeredForTest());
    }

    @Test
    void fullyLoadedThreeByThreeNeighbourhoodIsOwnedByXaero() {
        Set<Long> loaded = loadedThreeByThree(20, -30);

        assertTrue(bridgeForLoadedChunks(loaded).nativelyWritable(new Object(), 20, -30));
    }

    @Test
    void anyMissingNeighbourKeepsLoadedBoundaryChunkOwnedByBridge() {
        Set<Long> loaded = loadedThreeByThree(20, -30);
        XaeroMapCompat bridge = bridgeForLoadedChunks(loaded);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long neighbour = pack(20 + dx, -30 + dz);
                loaded.remove(neighbour);
                assertFalse(bridge.nativelyWritable(new Object(), 20, -30),
                        "missing neighbour " + dx + "," + dz + " must be bridge-written");
                loaded.add(neighbour);
            }
        }
    }

    private static Set<Long> loadedThreeByThree(int chunkX, int chunkZ) {
        Set<Long> loaded = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loaded.add(pack(chunkX + dx, chunkZ + dz));
            }
        }
        return loaded;
    }

    private static XaeroMapCompat bridgeForLoadedChunks(Set<Long> loaded) {
        return new XaeroMapCompat(null, new XaeroMapCompat.LevelOps() {
            @Override
            public Object dimension(Object world) {
                return world;
            }

            @Override
            public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                return loaded.contains(pack(chunkX, chunkZ));
            }
        }, () -> true, () -> true, ignored -> { }, ignored -> { });
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static XaeroMapCompat bridge(
            AtomicBoolean enabled, List<VoxelColumnConsumer> registered) {
        return new XaeroMapCompat(null, null, enabled::get, () -> true,
                registered::add, registered::remove);
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        var states = new net.minecraft.world.level.block.state.BlockState[256];
        return new XaeroTileExtractor.PreparedTile(
                chunkX, chunkZ, -64, states, new short[256], new short[256],
                (net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>[]) new net.minecraft.resources.ResourceKey<?>[256],
                new byte[256], new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }
}

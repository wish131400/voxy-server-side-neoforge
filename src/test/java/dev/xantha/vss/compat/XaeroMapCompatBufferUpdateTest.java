package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;

class XaeroMapCompatBufferUpdateTest {
    private MapProcessor processor;
    private XaeroMapCompat bridge;

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        Object world = new Object();
        this.processor.world = world;
        this.processor.mainWorld = world;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                new XaeroMapCompat.LevelOps() {
                    @Override
                    public Object dimension(Object ignored) {
                        return null;
                    }

                    @Override
                    public boolean isChunkLoaded(Object ignored, int chunkX, int chunkZ) {
                        return false;
                    }
                },
                () -> true,
                () -> true,
                ignored -> { },
                ignored -> { });
        this.bridge.pumpNanosBudget = Long.MAX_VALUE;
        this.bridge.updateNanosBudget = Long.MAX_VALUE;
        this.bridge.updateBorrowNanos = Long.MAX_VALUE;
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        XaeroStubEvents.clear();
    }

    @Test
    void commitsNeverUseUnsafeSweepFlagAndCoalescePerTileChunk() {
        this.bridge.updateIdlePumps = 2;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        this.bridge.offerPrepared(null, tile(65, 64));
        this.bridge.pump();

        MapTileChunk tileChunk = tileChunk();
        assertFalse(tileChunk.toUpdateBuffers);
        assertTrue(tileChunk.changed);
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));

        this.bridge.pump();
        this.bridge.pump();

        assertEquals(1, tileChunk.bufferUpdates);
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertFalse(tileChunk.changed);
        assertFalse(XaeroStubEvents.snapshot().stream()
                .anyMatch(event -> event.startsWith("tileChunk.setToUpdateBuffers")));
    }

    @Test
    void rebuildWaitsUntilSaverLeavesRegionResting() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        MapRegion region = region();
        MapTileChunk tileChunk = tileChunk();
        region.resting = false;

        this.bridge.pump();

        assertEquals(0, tileChunk.bufferUpdates);
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        region.resting = true;
        this.bridge.pump();

        assertEquals(1, tileChunk.bufferUpdates);
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void activeFrameSliceOwnsTheRebuildAndTickStandsDown() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        this.bridge.frameFlush();
        this.bridge.pump();

        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
        this.bridge.frameFlush();

        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(1, this.bridge.counterForTest("frame_flushes"));
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
    }

    @Test
    void completeFourByFourTileChunkRebuildsWithoutIdleDelay() {
        this.bridge.updateIdlePumps = 1000;
        for (int z = 64; z < 68; z++) {
            for (int x = 64; x < 68; x++) {
                this.bridge.offerPrepared(null, tile(x, z));
            }
        }

        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));

        this.bridge.frameFlush();

        assertEquals(1, this.bridge.counterForTest("buffer_updates"));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void disconnectDropsOwedRebuildWithoutTouchingOldXaeroObjects() {
        this.bridge.updateIdlePumps = 1000;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"));

        this.bridge.onSessionEnd();
        WorldMapSession.current = null;
        this.bridge.pump();

        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(1, this.bridge.counterForTest("dropped_updates"));
        assertEquals(0, this.bridge.counterForTest("buffer_updates"));
    }

    private MapRegion region() {
        return this.processor.regions.get((2L << 32) | 2L);
    }

    private MapTileChunk tileChunk() {
        return region().getChunk(0, 0);
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        return new XaeroTileExtractor.PreparedTile(
                chunkX, chunkZ, -64, new BlockState[256], new short[256], new short[256],
                (ResourceKey<Biome>[]) new ResourceKey<?>[256], new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }
}

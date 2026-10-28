package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
    void longSaveDoesNotDiscardAnOwedTextureRebuild() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        MapRegion region = region();
        MapTileChunk tileChunk = tileChunk();
        region.resting = false;

        // Pixel data exists, but the map stays black until its texture is built.
        // A busy saver must not erase that obligation after 1200 pumps.
        for (int i = 0; i < 1240; i++) this.bridge.pump();
        region.resting = true;
        this.bridge.pump();

        assertEquals(1, tileChunk.bufferUpdates);
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertEquals(0, this.bridge.counterForTest("dropped_updates"));
        assertFalse(tileChunk.changed);
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

    @Test
    void firstPredictionAfterProvenanceLoadCannotSaveAnUnpreparedTexture() throws Exception {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPredicted(null, tile(64, 64),
                new XaeroPredictionBridge.Quality(4, 4, false), () -> true);
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written"));
        assertFalse(tileChunk().hasHadTerrain);
        nativeCheckPrepared(region()); // Empty chunk is not yet included in cache.
        assertTrue(region().allCachePrepared);
        XaeroPredictionStore.awaitIoForTest();

        this.bridge.pump();

        assertEquals(1, this.bridge.counterForTest("written"));
        nativeCheckPrepared(region());
        assertFalse(assertDoesNotThrow(() -> nativeSaveCache(region())),
                "First terrain must revoke the earlier empty-region cache readiness");
        this.bridge.pump();
        nativeFinishUploads(region());
        assertTrue(nativeSaveCache(region()), "Coalesced redraw must restore native cache readiness");
    }

    @Test
    void firstRealCommitAfterPboWaitCannotSaveAnUnpreparedTexture() {
        MapRegion region = processor.getLeafMapRegion(Integer.MAX_VALUE, 2, 2, true);
        var chunk = new MapTileChunk(region, 16, 16);
        chunk.loadState = 2;
        chunk.leafTexture.downloadFromPBO = true;
        region.setChunk(0, 0, chunk);
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        nativeCheckPrepared(region);
        assertTrue(region.allCachePrepared);
        chunk.leafTexture.downloadFromPBO = false;

        this.bridge.pump();

        nativeCheckPrepared(region);
        assertFalse(assertDoesNotThrow(() -> nativeSaveCache(region)));
        assertTrue(chunk.hasHadTerrain, "Raw terrain data must remain eligible for native saving");
    }

    @Test
    void refiningPreparedTerrainCannotRecacheItsOldTexture() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        this.bridge.pump();
        nativeFinishUploads(region());
        assertTrue(nativeSaveCache(region()));

        this.bridge.offerPrepared(null, tile(65, 64));
        this.bridge.pump();

        assertFalse(tileChunk().leafTexture.isCachePrepared(),
                "Pixel changes must invalidate the previous texture before deferred rebuilding");
        nativeCheckPrepared(region());
        assertFalse(nativeSaveCache(region()));
        this.bridge.pump();
        nativeFinishUploads(region());
        assertTrue(nativeSaveCache(region()));
        assertEquals(2, tileChunk().bufferUpdates);
    }

    @Test
    void nativeSaveRefreshCanCompleteWhileBridgeRebuildIsDeferred() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        MapRegion region = region();
        MapTileChunk chunk = tileChunk();
        assertFalse(region.allCachePrepared);
        region.recacheRequested = true; // Native raw save requests refresh before caching.
        this.bridge.pump();
        assertEquals(0, chunk.bufferUpdates);
        assertEquals(1, this.bridge.counterForTest("pending_updates"));
        // Xaero's own refresh owns this redraw, even while isResting() is false.
        synchronized (region.writerThreadPauseSync) {
            synchronized (region) {
                chunk.updateBuffers(processor, processor.tintProvider, processor.overlayManager,
                        false, processor.shapeCache, new xaero.map.region.MapUpdateFastConfig(processor));
            }
        }
        nativeFinishUploads(region);
        assertTrue(nativeSaveCache(region));
        region.recacheRequested = false;
        this.bridge.pump();
        nativeFinishUploads(region);
        assertTrue(nativeSaveCache(region));
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
        assertFalse(chunk.changed);
    }

    @Test
    void pendingOldUploadCannotRevalidateNewPixelsBeforeTheirRebuild() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(null, tile(64, 64));
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(tileChunk().leafTexture.shouldUpload());
        this.bridge.offerPrepared(null, tile(65, 64));

        this.bridge.pump();

        assertEquals(1, this.bridge.queuedForTest(),
                "An old GPU upload can set cachePrepared true; keep the next pixels queued");
        assertEquals(1, this.bridge.counterForTest("written"));
        nativeFinishUploads(region());
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest());
        assertEquals(2, this.bridge.counterForTest("written"));
        nativeCheckPrepared(region());
        assertFalse(nativeSaveCache(region()));
        this.bridge.pump();
        nativeFinishUploads(region());
        assertTrue(nativeSaveCache(region()));
    }

    // The released onRenderProcess only sets allCachePrepared true; it does not
    // clear a previously true value when a newly included texture is unprepared.
    private static void nativeCheckPrepared(MapRegion region) {
        synchronized (region) {
            boolean ready = true;
            for (var row : region.chunks) for (var chunk : row) {
                if (chunk == null) continue;
                var texture = chunk.leafTexture;
                ready &= (!texture.shouldIncludeInCache() || texture.isCachePrepared())
                        && !texture.toUpload && !chunk.toUpdateBuffers;
            }
            if (ready) region.setAllCachePrepared(true);
        }
    }

    private static void nativeFinishUploads(MapRegion region) {
        for (var row : region.chunks) for (var chunk : row) {
            if (chunk == null || !chunk.leafTexture.toUpload) continue;
            chunk.leafTexture.toUpload = false;
            chunk.leafTexture.setCachePrepared(true);
        }
        nativeCheckPrepared(region);
    }

    private static boolean nativeSaveCache(MapRegion region) {
        synchronized (region.writerThreadPauseSync) {
            if (!region.allCachePrepared) return false;
            region.writingPaused = true;
        }
        try {
            for (var row : region.chunks) for (var chunk : row) {
                if (chunk != null && chunk.leafTexture.shouldIncludeInCache()
                        && !chunk.leafTexture.isCachePrepared()) {
                    throw new RuntimeException("Trying to save cache but texture is not prepared.");
                }
            }
            return true;
        } finally {
            synchronized (region.writerThreadPauseSync) { region.writingPaused = false; }
        }
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

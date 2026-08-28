package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;

class XaeroMapCompatStubContractTest {
    private MapProcessor processor;
    private XaeroMapCompat bridge;
    private boolean allChunksLoaded;

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        processor = new MapProcessor();
        Object world = new Object();
        processor.world = world;
        processor.mainWorld = world;
        var session = new WorldMapSession();
        session.processor = processor;
        WorldMapSession.current = session;
        bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                new XaeroMapCompat.LevelOps() {
                    @Override
                    public Object dimension(Object ignored) {
                        return null;
                    }

                    @Override
                    public boolean isChunkLoaded(Object ignored, int chunkX, int chunkZ) {
                        return allChunksLoaded;
                    }
                },
                () -> true,
                () -> true,
                ignored -> { },
                ignored -> { });
        bridge.pumpNanosBudget = Long.MAX_VALUE;
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        XaeroStubEvents.clear();
    }

    @Test
    void handleResolutionIsAllOrNothing() throws Exception {
        assertNotNull(XaeroMapCompat.Handles.resolve(Class::forName));
        assertThrows(ReflectiveOperationException.class,
                () -> XaeroMapCompat.Handles.resolve(name ->
                        name.equals("xaero.map.region.MapTile") ? Object.class : Class.forName(name)));
    }

    @Test
    void commitFollowsXaerosSaveAndBufferLifecycle() {
        bridge.offerPrepared(null, tile(64, 65));
        bridge.pump();
        assertEquals(1, bridge.counterForTest("written"));
        List<String> events = XaeroStubEvents.snapshot();
        assertTrue(events.contains("region.setBeingWritten true"));
        assertFalse(events.contains("region.setBeingWritten false"));
        int version = events.indexOf("tile.setWorldInterpretationVersion 1");
        int setTile = events.indexOf("tileChunk.setTile 0,1");
        int changed = events.lastIndexOf("tileChunk.setChanged true");
        assertTrue(version >= 0 && setTile > version, events.toString());
        assertTrue(changed >= 0 && changed < setTile, events.toString());
        assertFalse(events.stream().anyMatch(event ->
                event.startsWith("tileChunk.setToUpdateBuffers")), events.toString());
        assertEquals(1, bridge.counterForTest("pending_updates"));
        assertEquals(0, bridge.counterForTest("buffer_updates"));
    }

    @Test
    void unloadedRegionRequestsOnePacedLoadAndKeepsTheEntry() {
        MapRegion region = new MapRegion();
        region.loadState = 0;
        processor.regions.put((2L << 32) | 2L, region);
        bridge.offerPrepared(null, tile(64, 64));
        bridge.pump();
        assertEquals(1, processor.saveLoad.loadRequests.size());
        assertEquals(1, bridge.queuedForTest());
        List<String> events = XaeroStubEvents.snapshot();
        assertTrue(events.indexOf("region.setBeingWritten true")
                < events.indexOf("saveLoad.requestLoad vss-xaero-bridge"), events.toString());
    }

    @Test
    void loadedVanillaNeighbourhoodStillBridgeWritesMissingMapTile() {
        allChunksLoaded = true;
        bridge.offerPrepared(null, tile(64, 64));
        bridge.pump();

        assertEquals(1, bridge.counterForTest("written"));
        assertEquals(0, bridge.counterForTest("skipped_loaded"));
    }

    @Test
    void loadedVanillaNeighbourhoodSkipsOnlyAnActuallyWrittenMapTile() {
        allChunksLoaded = true;
        MapRegion region = processor.getLeafMapRegion(Integer.MAX_VALUE, 2, 2, true);
        var tileChunk = new xaero.map.region.MapTileChunk(region, 16, 16);
        tileChunk.loadState = 2;
        region.setChunk(0, 0, tileChunk);
        var mapTile = new xaero.map.region.MapTile(64, 64);
        mapTile.loaded = true;
        mapTile.writtenOnce = true;
        tileChunk.tiles[0][0] = mapTile;

        bridge.offerPrepared(null, tile(64, 64));
        bridge.pump();

        assertEquals(0, bridge.counterForTest("written"));
        assertEquals(1, bridge.counterForTest("skipped_loaded"));
        assertEquals(0, bridge.queuedForTest());
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        return new XaeroTileExtractor.PreparedTile(
                chunkX, chunkZ, -64, new BlockState[256], new short[256], new short[256],
                (ResourceKey<Biome>[]) new ResourceKey<?>[256], new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }
}

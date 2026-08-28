package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;

class XaeroMapCompatRegionLoadingTest {
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
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        XaeroStubEvents.clear();
    }

    @Test
    void fillsEightRegionLoadSlotsWithoutTakingXaerosViewingToken() {
        for (int regionX = 0; regionX < 12; regionX++) {
            addUnloadedRegion(regionX, 0, true);
            this.bridge.offerPrepared(null, tile(regionX << 5, 0));
        }

        this.bridge.pump();

        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS,
                this.processor.saveLoad.loadRequests.size());
        assertEquals(12, this.bridge.queuedForTest());
        assertNull(this.processor.saveLoad.nextToLoadByViewing);
        assertFalse(XaeroStubEvents.snapshot().contains("saveLoad.setNextToLoadByViewing"));
    }

    @Test
    void sameUnloadedRegionIsProbedOnceBeforeOneLoadGrant() {
        addUnloadedRegion(2, 2, true);
        for (int i = 0; i < 64; i++) {
            this.bridge.offerPrepared(null, tile(64 + i % 16, 64 + i / 16));
        }

        this.bridge.pump();

        long regionLookups = XaeroStubEvents.snapshot().stream()
                .filter(event -> event.startsWith("processor.getLeafMapRegion"))
                .count();
        assertEquals(2, regionLookups, "one bucket probe plus one load grant lookup");
        assertEquals(1, this.processor.saveLoad.loadRequests.size());
        assertEquals(64, this.bridge.queuedForTest());
    }

    @Test
    void existingInFlightRegionsReduceTheAvailableWindow() {
        for (int regionX = 0; regionX < 3; regionX++) {
            addUnloadedRegion(regionX, 0, false);
            this.bridge.offerPrepared(null, tile(regionX << 5, 0));
        }
        for (int regionX = 3; regionX < 13; regionX++) {
            addUnloadedRegion(regionX, 0, true);
            this.bridge.offerPrepared(null, tile(regionX << 5, 0));
        }

        this.bridge.pump();

        assertEquals(XaeroMapCompat.MAX_OUTSTANDING_LOADS - 3,
                this.processor.saveLoad.loadRequests.size());
    }

    private void addUnloadedRegion(int regionX, int regionZ, boolean requestable) {
        var region = new MapRegion();
        region.loadState = requestable ? (byte) 0 : (byte) 4;
        region.canRequestReload = requestable;
        this.processor.regions.put(pack(regionX, regionZ), region);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        return new XaeroTileExtractor.PreparedTile(
                chunkX, chunkZ, -64, new BlockState[256], new short[256], new short[256],
                (ResourceKey<Biome>[]) new ResourceKey<?>[256], new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }
}

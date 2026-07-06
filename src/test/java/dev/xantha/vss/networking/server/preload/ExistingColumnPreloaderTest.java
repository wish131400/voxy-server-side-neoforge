package dev.xantha.vss.networking.server.preload;


import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExistingColumnPreloaderTest {

    @Test
    void minimumDistanceIsZeroForChunkInsideRegion() {
        int regionSize = PersistentColumnLodStore.regionSize();

        assertEquals(0, ExistingColumnPreloader.minimumChunkDistanceToRegion(
                regionSize + 1,
                regionSize + 2,
                1,
                1));
    }

    @Test
    void minimumDistanceUsesChebyshevDistanceToNearestRegionEdge() {
        int regionSize = PersistentColumnLodStore.regionSize();

        assertEquals(3, ExistingColumnPreloader.minimumChunkDistanceToRegion(
                regionSize - 3,
                regionSize + 4,
                1,
                1));
    }
}

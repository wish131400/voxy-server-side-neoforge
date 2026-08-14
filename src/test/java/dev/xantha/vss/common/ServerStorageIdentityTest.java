package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerStorageIdentityTest {
    @Test
    void generatedIdentityUsesEightUnambiguousCharacters() {
        assertTrue(ServerStorageIdentity.generateIdentity().matches("[0-9A-HJKMNP-TV-Z]{8}"));
    }

    @Test
    void sharedWorldIgnoresTheNodeWhenBuildingItsCacheKey() {
        ServerStorageIdentity identity = ServerStorageIdentity.create("7k4m9pxa", true, "Node-A");

        assertEquals("7K4M9PXA", identity.cacheKey());
    }

    @Test
    void independentNodeProducesReadableCacheKeyWithoutDuplicatePrefix() {
        ServerStorageIdentity identity = ServerStorageIdentity.create("7K4M9PXA", false, "Node-A");

        assertEquals("7K4M9PXA-node-a", identity.cacheKey());
    }

    @Test
    void malformedServerOrMissingIndependentNodeIsRejected() {
        assertNull(ServerStorageIdentity.create("INVALID!", true, ""));
        assertNull(ServerStorageIdentity.create("7K4M9PXA", false, ""));
    }
}

package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ClientLodPresenceCacheManifestTest {
    @Test
    void manifestIsStoredInStableSignedSectionOrder() {
        assertArrayEquals(
                new byte[] {-4, -1, 0, 7},
                ClientLodPresenceCache.encodeSectionManifest(new int[] {7, -1, 0, -4}));
    }

    @Test
    void duplicateOrOutOfRangeSectionsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientLodPresenceCache.encodeSectionManifest(new int[] {2, 2}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClientLodPresenceCache.encodeSectionManifest(new int[] {128}));
    }

}

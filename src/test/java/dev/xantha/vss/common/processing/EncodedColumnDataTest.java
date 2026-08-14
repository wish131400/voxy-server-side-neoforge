package dev.xantha.vss.common.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EncodedColumnDataTest {
    @Test
    void encodedColumnCarriesImmutableManifestAndCompressedFrameCrc() throws Exception {
        byte[] raw = new byte[] {3, 3, 30, 31, -2, 20, 0, 10, 11, 12};
        int[] manifest = new int[] {3, -2, 0};
        LoadedColumnData loaded = new LoadedColumnData(
                4,
                8,
                raw,
                raw.length,
                true,
                manifest,
                new int[] {3, 2, 4});

        assertArrayEquals(new byte[] {3, -2, 20, 0, 10, 11, 12, 3, 30, 31}, loaded.sectionBytes());

        EncodedColumnData encoded = EncodedColumnData.encode(loaded, 123L);
        manifest[0] = 99;

        assertArrayEquals(new int[] {-2, 0, 3}, encoded.sectionYs());
        assertArrayEquals(new int[] {2, 4, 3}, encoded.sectionLengths());
        assertTrue(encoded.hasValidEncodedCrc32c());

        encoded.encodedBytes()[0] ^= 1;
        assertFalse(encoded.hasValidEncodedCrc32c());
    }
}

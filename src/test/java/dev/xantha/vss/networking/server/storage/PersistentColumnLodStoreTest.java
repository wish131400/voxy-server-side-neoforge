package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.xantha.vss.common.processing.LodByteCompression;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class PersistentColumnLodStoreTest {

    @Test
    void decodesValidStoredBody() throws IOException {
        byte[] raw = new byte[4096];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 31);
        }

        assertArrayEquals(
                raw,
                PersistentColumnLodStore.decodeStoredBody(
                        deflate(raw),
                        LodByteCompression.METHOD_DEFLATE,
                        raw.length));
    }

    @Test
    void rejectsCorruptStoredBodyBeforeItCanReachPayloadSplitting() {
        assertThrows(
                IOException.class,
                () -> PersistentColumnLodStore.decodeStoredBody(
                        new byte[] {1, 2, 3, 4},
                        LodByteCompression.METHOD_ZSTD,
                        1024));
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }
}

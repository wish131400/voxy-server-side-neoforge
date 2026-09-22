package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.jpountz.lz4.LZ4Factory;

/** Session-local, bit-exact storage of GPU words. No dependency on disk cache formats. */
final class PredictionMeshCompression {
    static final int MIN_BYTES = 64 * 1024, MAX_BYTES = 16 * 1024 * 1024;
    private static final LZ4Factory LZ4 = LZ4Factory.fastestJavaInstance();
    private static final ZstdBridge ZSTD = ZstdBridge.find();
    record Blob(byte[] bytes, int rawBytes, boolean zstd) {
        int[] restore() {
            if (rawBytes < 0 || rawBytes > MAX_BYTES || (rawBytes & 3) != 0)
                throw new IllegalStateException("Invalid mesh size");
            byte[] raw = new byte[rawBytes];
            if (zstd) {
                if (ZSTD == null) throw new IllegalStateException("Missing session mesh decoder");
                ZSTD.restore(raw, bytes);
            } else if (LZ4.safeDecompressor().decompress(bytes, 0, bytes.length, raw, 0, raw.length) != raw.length) {
                throw new IllegalStateException("Mesh length mismatch");
            }
            int[] words = new int[rawBytes / 4];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
            return words;
        }
    }
    static Blob compress(int[] words) { return compress(words, true); }
    static Blob compress(int[] words, boolean allowZstd) {
        long size = words.length * 4L;
        if (size < MIN_BYTES || size > MAX_BYTES) return null;
        byte[] raw = new byte[(int)size];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words);
        if (allowZstd && ZSTD != null) {
            try { return worthwhile(ZSTD.compress(raw), raw.length, true); }
            catch (RuntimeException unavailable) { /* The immutable fallback records retain their own decoder. */ }
        }
        return worthwhile(LZ4.fastCompressor().compress(raw), raw.length, false);
    }
    private static Blob worthwhile(byte[] data, int bytes, boolean zstd) {
        return data.length + 64 <= bytes * 3L / 4 ? new Blob(data, bytes, zstd) : null;
    }
    private record ZstdBridge(java.lang.reflect.Method compress, java.lang.reflect.Method decompress) {
        static ZstdBridge find() {
            try {
                Class<?> type = Class.forName("com.github.luben.zstd.Zstd");
                var bridge = new ZstdBridge(type.getMethod("compress", byte[].class, int.class),
                        type.getMethod("decompress", byte[].class, byte[].class));
                byte[] probe = {1, 2, 3, 4}, restored = new byte[4];
                bridge.restore(restored, bridge.compress(probe));
                return Arrays.equals(probe, restored) ? bridge : null;
            } catch (Throwable unavailable) { return null; }
        }
        byte[] compress(byte[] raw) {
            try { return (byte[])compress.invoke(null, raw, 1); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        void restore(byte[] raw, byte[] encoded) {
            try {
                long count = ((Number)decompress.invoke(null, raw, encoded)).longValue();
                if (count != raw.length) throw new IllegalStateException("Mesh length mismatch: " + count);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
}

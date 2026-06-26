package dev.xantha.vss.common.processing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class LodByteCompression {
    private static final int MIN_COMPRESS_BYTES = 128;
    private static final int MIN_SAVINGS_BYTES = 16;
    private static final int BUFFER_SIZE = 8192;
    private static final int ZSTD_NETWORK_LEVEL = 7;
    private static final int ZSTD_STORAGE_LEVEL = 7;
    public static final int METHOD_NONE = 0;
    public static final int METHOD_DEFLATE = 1;
    public static final int METHOD_ZSTD = 2;
    private static volatile ZstdBridge zstdBridge;
    private static volatile boolean zstdInitialized;

    private LodByteCompression() {
    }

    public static Result compressIfBeneficial(byte[] input) {
        return compressForStorage(input);
    }

    public static Result compressForStorage(byte[] input) {
        return compressIfBeneficial(input, true, ZSTD_STORAGE_LEVEL);
    }

    public static Result compressForNetwork(byte[] input, boolean allowZstd) {
        return compressIfBeneficial(input, allowZstd, ZSTD_NETWORK_LEVEL);
    }

    public static Result compressZstdRequired(byte[] input) throws IOException {
        if (input == null) {
            throw new IOException("Missing LOD data");
        }

        ZstdBridge bridge = zstdBridge();
        if (bridge == null) {
            throw new MissingCompressionMethodException("Zstd is not available");
        }

        try {
            return new Result(bridge.compress(input, ZSTD_STORAGE_LEVEL), METHOD_ZSTD, input.length);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to compress LOD data with Zstd", unwrapReflectiveCause(e));
        }
    }

    private static Result compressIfBeneficial(byte[] input, boolean allowZstd, int zstdLevel) {
        if (input == null || input.length < MIN_COMPRESS_BYTES) {
            return Result.raw(input);
        }

        ZstdBridge bridge = allowZstd ? zstdBridge() : null;
        if (bridge != null) {
            try {
                byte[] compressed = bridge.compress(input, zstdLevel);
                if (compressed.length + MIN_SAVINGS_BYTES < input.length) {
                    return new Result(compressed, METHOD_ZSTD, input.length);
                }
                return Result.raw(input);
            } catch (Throwable ignored) {
                zstdBridge = null;
                zstdInitialized = true;
            }
        }

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32, input.length / 2));
            byte[] buffer = new byte[Math.min(BUFFER_SIZE, input.length)];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    break;
                }
                output.write(buffer, 0, count);
                if (output.size() + MIN_SAVINGS_BYTES >= input.length) {
                    return Result.raw(input);
                }
            }

            byte[] compressed = output.toByteArray();
            if (compressed.length + MIN_SAVINGS_BYTES >= input.length) {
                return Result.raw(input);
            }
            return new Result(compressed, METHOD_DEFLATE, input.length);
        } finally {
            deflater.end();
        }
    }

    public static byte[] decompress(byte[] compressed, int method, int expectedLength, int maxLength) throws IOException {
        if (expectedLength < 0 || expectedLength > maxLength) {
            throw new IOException("Invalid decompressed LOD length: " + expectedLength);
        }
        if (method == METHOD_NONE) {
            if (compressed.length != expectedLength) {
                throw new IOException("Raw LOD length mismatch");
            }
            return compressed;
        }
        if (method == METHOD_ZSTD) {
            return decompressZstd(compressed, expectedLength);
        }
        if (method != METHOD_DEFLATE) {
            throw new IOException("Unknown LOD compression method: " + method);
        }
        if (expectedLength == 0) {
            return new byte[0];
        }

        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] output = new byte[expectedLength];
            int offset = 0;
            while (!inflater.finished() && offset < expectedLength) {
                int count = inflater.inflate(output, offset, expectedLength - offset);
                if (count <= 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) {
                        throw new IOException("Compressed LOD data ended early");
                    }
                    throw new IOException("Compressed LOD data made no progress");
                }
                offset += count;
            }

            if (!inflater.finished() || offset != expectedLength) {
                throw new IOException("Compressed LOD length mismatch");
            }
            return output;
        } catch (DataFormatException e) {
            throw new IOException("Invalid compressed LOD data", e);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decompressZstd(byte[] compressed, int expectedLength) throws IOException {
        ZstdBridge bridge = zstdBridge();
        if (bridge == null) {
            throw new MissingCompressionMethodException("Zstd is not available");
        }
        try {
            byte[] output = bridge.decompress(compressed, expectedLength);
            if (output.length != expectedLength) {
                throw new IOException("Zstd LOD length mismatch");
            }
            return output;
        } catch (Throwable e) {
            throw new IOException("Invalid Zstd LOD data", e);
        }
    }

    public static boolean isZstdAvailable() {
        return zstdBridge() != null;
    }

    private static ZstdBridge zstdBridge() {
        if (zstdInitialized) {
            return zstdBridge;
        }
        try {
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd");
            Method compress = zstdClass.getMethod("compress", byte[].class, int.class);
            Method decompress = zstdClass.getMethod("decompress", byte[].class, int.class);
            ZstdBridge bridge = new ZstdBridge(compress, decompress);
            bridge.compress(new byte[] {0}, ZSTD_NETWORK_LEVEL);
            zstdBridge = bridge;
        } catch (Throwable ignored) {
            zstdBridge = null;
        } finally {
            zstdInitialized = true;
        }
        return zstdBridge;
    }

    public record Result(byte[] bytes, int method, int originalLength) {
        public boolean compressed() {
            return method != METHOD_NONE;
        }

        public static Result raw(byte[] bytes) {
            return new Result(bytes, METHOD_NONE, bytes != null ? bytes.length : 0);
        }
    }

    private record ZstdBridge(Method compress, Method decompress) {
        private byte[] compress(byte[] input, int level) throws ReflectiveOperationException {
            return (byte[]) compress.invoke(null, input, level);
        }

        private byte[] decompress(byte[] input, int originalLength) throws ReflectiveOperationException {
            return (byte[]) decompress.invoke(null, input, originalLength);
        }
    }

    private static Throwable unwrapReflectiveCause(ReflectiveOperationException e) {
        if (e instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return e;
    }

    public static final class MissingCompressionMethodException extends IOException {
        public MissingCompressionMethodException(String message) {
            super(message);
        }
    }
}

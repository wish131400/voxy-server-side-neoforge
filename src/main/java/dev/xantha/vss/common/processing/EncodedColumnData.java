package dev.xantha.vss.common.processing;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32C;

public record EncodedColumnData(
        int chunkX,
        int chunkZ,
        int compression,
        int rawSize,
        byte[] encodedBytes,
        long columnStamp,
        int schemaVersion,
        boolean completeColumn,
        int[] sectionYs,
        int[] sectionLengths,
        int encodedCrc32c) {
    public static final int SCHEMA_VERSION = 4;

    public EncodedColumnData {
        sectionYs = immutableSectionYs(sectionYs);
        sectionLengths = immutableSectionLengths(sectionYs, sectionLengths);
        validateSectionManifestOrder(sectionYs);
    }

    public EncodedColumnData(
            int chunkX,
            int chunkZ,
            int compression,
            int rawSize,
            byte[] encodedBytes,
            long columnStamp,
            int schemaVersion,
            boolean completeColumn) {
        this(
                chunkX,
                chunkZ,
                compression,
                rawSize,
                encodedBytes,
                columnStamp,
                schemaVersion,
                completeColumn,
                new int[0],
                new int[0],
                crc32c(encodedBytes));
    }

    public static EncodedColumnData encode(LoadedColumnData rawColumn, long columnStamp) throws IOException {
        if (rawColumn == null || rawColumn.sectionBytes() == null) {
            throw new IOException("Missing raw LOD column data");
        }

        LodByteCompression.Result encoded = LodByteCompression.compressForStorage(rawColumn.sectionBytes());
        return new EncodedColumnData(
                rawColumn.chunkX(),
                rawColumn.chunkZ(),
                encoded.method(),
                encoded.originalLength(),
                encoded.bytes(),
                columnStamp,
                SCHEMA_VERSION,
                rawColumn.completeColumn(),
                rawColumn.sectionYs(),
                rawColumn.sectionLengths(),
                crc32c(encoded.bytes()));
    }

    public static EncodedColumnData encodeZstd(LoadedColumnData rawColumn, long columnStamp) throws IOException {
        return encode(rawColumn, columnStamp);
    }

    public EncodedColumnData withColumnStamp(long columnStamp) {
        if (this.columnStamp == columnStamp) {
            return this;
        }
        return new EncodedColumnData(
                chunkX,
                chunkZ,
                compression,
                rawSize,
                encodedBytes,
                columnStamp,
                schemaVersion,
                completeColumn,
                sectionYs,
                sectionLengths,
                encodedCrc32c);
    }

    public int encodedSize() {
        return encodedBytes != null ? encodedBytes.length : 0;
    }

    public boolean hasBody() {
        return encodedBytes != null && encodedBytes.length > 0 && rawSize > 0;
    }

    public boolean isCurrentZstdSchema() {
        return compression == LodByteCompression.METHOD_ZSTD && schemaVersion == SCHEMA_VERSION;
    }

    @Override
    public int[] sectionYs() {
        return Arrays.copyOf(sectionYs, sectionYs.length);
    }

    @Override
    public int[] sectionLengths() {
        return Arrays.copyOf(sectionLengths, sectionLengths.length);
    }

    public boolean hasValidEncodedCrc32c() {
        return encodedBytes != null && encodedCrc32c == crc32c(encodedBytes);
    }

    public static int crc32c(byte[] bytes) {
        if (bytes == null) {
            return 0;
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    private static int[] immutableSectionYs(int[] sectionYs) {
        if (sectionYs == null || sectionYs.length == 0) {
            return new int[0];
        }
        int[] copy = Arrays.copyOf(sectionYs, sectionYs.length);
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < Byte.MIN_VALUE || copy[i] > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Section Y is outside the wire range: " + copy[i]);
            }
        }
        return copy;
    }

    private static void validateSectionManifestOrder(int[] sectionYs) {
        for (int i = 1; i < sectionYs.length; i++) {
            if (sectionYs[i] <= sectionYs[i - 1]) {
                throw new IllegalArgumentException("Section Y manifest is not strictly ordered");
            }
        }
    }

    private static int[] immutableSectionLengths(int[] sectionYs, int[] sectionLengths) {
        if (sectionLengths == null || sectionLengths.length == 0) {
            return new int[0];
        }
        if (sectionLengths.length != sectionYs.length) {
            throw new IllegalArgumentException("Section length manifest does not match section Y manifest");
        }
        int[] copy = Arrays.copyOf(sectionLengths, sectionLengths.length);
        for (int length : copy) {
            if (length <= 0) {
                throw new IllegalArgumentException("Invalid serialized section length: " + length);
            }
        }
        return copy;
    }
}

package dev.xantha.vss.common.processing;

import java.io.IOException;

public record EncodedColumnData(
        int chunkX,
        int chunkZ,
        int compression,
        int rawSize,
        byte[] encodedBytes,
        long columnStamp,
        int schemaVersion,
        boolean completeColumn) {
    public static final int SCHEMA_VERSION = 2;

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
                rawColumn.completeColumn());
    }

    public static EncodedColumnData encodeZstd(LoadedColumnData rawColumn, long columnStamp) throws IOException {
        return encode(rawColumn, columnStamp);
    }

    public EncodedColumnData withColumnStamp(long columnStamp) {
        if (this.columnStamp == columnStamp) {
            return this;
        }
        return new EncodedColumnData(chunkX, chunkZ, compression, rawSize, encodedBytes, columnStamp, schemaVersion, completeColumn);
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
}

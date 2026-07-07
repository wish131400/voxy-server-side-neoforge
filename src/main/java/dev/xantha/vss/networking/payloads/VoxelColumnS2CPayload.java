package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class VoxelColumnS2CPayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VoxelColumnS2CPayload> TYPE = VSSPayloadCodecs.type("voxel_column");
    public static final StreamCodec<RegistryFriendlyByteBuf, VoxelColumnS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(VoxelColumnS2CPayload::encode, VoxelColumnS2CPayload::decode);

    private static final int MAX_SECTIONS_SIZE = 0x200000;
    private static final int MAX_ENCODED_SECTIONS_SIZE = MAX_SECTIONS_SIZE + 65536;
    private static final int MAX_DIMENSION_STRING_LENGTH = 256;
    private static final int MAX_REPLACEMENT_SECTION_COUNT = 64;

    private final int requestId;
    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;
    private final byte[] encodedSectionBytes;
    private final int encodedCompression;
    private final int encodedRawSize;
    private final boolean completeColumn;
    private final boolean completesRequest;
    private final int[] replacementSectionYs;
    private boolean allowZstdEncoding;
    private volatile boolean hasCachedEncodedForClient;
    private volatile boolean cachedNetworkCompressionEnabled;
    private volatile boolean cachedAllowZstdEncoding;
    private volatile LodByteCompression.Result cachedEncodedForClient;

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes) {
        this(requestId, chunkX, chunkZ, dimension, columnTimestamp, sectionBytes, true);
    }

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes, boolean completeColumn) {
        this(requestId, chunkX, chunkZ, dimension, columnTimestamp, sectionBytes, completeColumn, true, new int[0]);
    }

    public VoxelColumnS2CPayload(
            int requestId,
            int chunkX,
            int chunkZ,
            ResourceKey<Level> dimension,
            long columnTimestamp,
            byte[] sectionBytes,
            boolean completeColumn,
            boolean completesRequest,
            int[] replacementSectionYs) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
        this.encodedSectionBytes = null;
        this.encodedCompression = LodByteCompression.METHOD_NONE;
        this.encodedRawSize = sectionBytes != null ? sectionBytes.length : 0;
        this.completeColumn = completeColumn;
        this.completesRequest = completesRequest;
        this.replacementSectionYs = sanitizeReplacementSections(replacementSectionYs);
    }

    public VoxelColumnS2CPayload(int requestId, ResourceKey<Level> dimension, EncodedColumnData columnData) {
        this(requestId, dimension, columnData, true, new int[0]);
    }

    public VoxelColumnS2CPayload(
            int requestId,
            ResourceKey<Level> dimension,
            EncodedColumnData columnData,
            boolean completesRequest,
            int[] replacementSectionYs) {
        this.requestId = requestId;
        this.chunkX = columnData.chunkX();
        this.chunkZ = columnData.chunkZ();
        this.dimension = dimension;
        this.columnTimestamp = columnData.columnStamp();
        this.sectionBytes = null;
        this.encodedSectionBytes = columnData.encodedBytes();
        this.encodedCompression = columnData.compression();
        this.encodedRawSize = columnData.rawSize();
        this.completeColumn = columnData.completeColumn();
        this.completesRequest = completesRequest;
        this.replacementSectionYs = sanitizeReplacementSections(replacementSectionYs);
    }

    VoxelColumnS2CPayload(
            int requestId,
            int chunkX,
            int chunkZ,
            ResourceKey<Level> dimension,
            long columnTimestamp,
            byte[] encodedSectionBytes,
            int encodedCompression,
            int encodedRawSize,
            boolean completeColumn) {
        this(requestId, chunkX, chunkZ, dimension, columnTimestamp, encodedSectionBytes, encodedCompression, encodedRawSize, completeColumn, true, new int[0]);
    }

    VoxelColumnS2CPayload(
            int requestId,
            int chunkX,
            int chunkZ,
            ResourceKey<Level> dimension,
            long columnTimestamp,
            byte[] encodedSectionBytes,
            int encodedCompression,
            int encodedRawSize,
            boolean completeColumn,
            boolean completesRequest,
            int[] replacementSectionYs) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = null;
        this.encodedSectionBytes = encodedSectionBytes;
        this.encodedCompression = encodedCompression;
        this.encodedRawSize = encodedRawSize;
        this.completeColumn = completeColumn;
        this.completesRequest = completesRequest;
        this.replacementSectionYs = sanitizeReplacementSections(replacementSectionYs);
    }

    public int requestId() {
        return requestId;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public long columnTimestamp() {
        return columnTimestamp;
    }

    public byte[] decompressedSections() {
        if (sectionBytes != null) {
            return sectionBytes;
        }
        try {
            return encodedSectionBytes != null
                    ? LodByteCompression.decompress(encodedSectionBytes, encodedCompression, encodedRawSize, MAX_SECTIONS_SIZE)
                    : new byte[0];
        } catch (IOException e) {
            throw new IllegalStateException("Invalid encoded voxel column payload", e);
        }
    }

    public boolean completeColumn() {
        return completeColumn;
    }

    public boolean completesRequest() {
        return completesRequest;
    }

    public int[] replacementSectionYs() {
        return Arrays.copyOf(replacementSectionYs, replacementSectionYs.length);
    }

    public int rawSectionBytesLength() {
        return sectionBytes != null ? sectionBytes.length : encodedRawSize;
    }

    public int rawEstimatedBytes() {
        return rawSectionBytesLength() + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    public int encodedSectionBytesLength() {
        if (encodedSectionBytes != null) {
            return encodedSectionBytes.length;
        }
        return sectionBytes != null ? sectionBytes.length : 0;
    }

    public int encodedCompression() {
        return encodedCompression;
    }

    public int estimatedBytes() {
        return wireBodyLength() + metadataBytes() + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    public int estimatedWireBytes(boolean networkCompressionEnabled) {
        LodByteCompression.Result encoded = encodedForClient(this, networkCompressionEnabled);
        byte[] bytes = encoded.bytes();
        int bodyLength = bytes != null ? bytes.length : 0;
        return bodyLength + metadataBytes() + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    public void setAllowZstdEncoding(boolean allowZstdEncoding) {
        if (this.allowZstdEncoding != allowZstdEncoding) {
            hasCachedEncodedForClient = false;
            cachedEncodedForClient = null;
        }
        this.allowZstdEncoding = allowZstdEncoding;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(VoxelColumnS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.requestId);
        buf.writeInt(payload.chunkX);
        buf.writeInt(payload.chunkZ);
        int ordinal = dimensionToOrdinal(payload.dimension);
        buf.writeVarInt(ordinal);
        if (ordinal == -1) {
            buf.writeUtf(payload.dimension.location().toString());
        }
        buf.writeLong(payload.columnTimestamp);
        buf.writeBoolean(payload.completeColumn);
        buf.writeBoolean(payload.completesRequest);
        buf.writeVarInt(payload.replacementSectionYs.length);
        for (int sectionY : payload.replacementSectionYs) {
            buf.writeByte(sectionY);
        }
        LodByteCompression.Result encoded = encodedForClient(payload, VSSServerConfig.CONFIG.enableNetworkColumnCompression);
        if (encoded.method() == LodByteCompression.METHOD_ZSTD && !payload.allowZstdEncoding) {
            throw new IllegalStateException("Cannot send Zstd LOD column to a client without Zstd capability");
        }
        buf.writeVarInt(encoded.method());
        buf.writeVarInt(encoded.originalLength());
        buf.writeByteArray(encoded.bytes());
    }

    public static VoxelColumnS2CPayload decode(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int cx = buf.readInt();
        int cz = buf.readInt();
        int ordinal = buf.readVarInt();
        ResourceKey<Level> dim = switch (ordinal) {
            case 0 -> Level.OVERWORLD;
            case 1 -> Level.NETHER;
            case 2 -> Level.END;
            default -> ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf(MAX_DIMENSION_STRING_LENGTH)));
        };
        long timestamp = buf.readLong();
        boolean completeColumn = buf.readBoolean();
        boolean completesRequest = buf.readBoolean();
        int replacementCount = buf.readVarInt();
        if (replacementCount < 0 || replacementCount > MAX_REPLACEMENT_SECTION_COUNT) {
            throw new IllegalArgumentException("Invalid LOD replacement section count: " + replacementCount);
        }
        int[] replacementSectionYs = new int[replacementCount];
        for (int i = 0; i < replacementCount; i++) {
            replacementSectionYs[i] = buf.readByte();
        }
        int method = buf.readVarInt();
        int originalLength = buf.readVarInt();
        byte[] encodedSections = buf.readByteArray(MAX_ENCODED_SECTIONS_SIZE);
        validateEncodedSections(method, originalLength, encodedSections.length);
        return new VoxelColumnS2CPayload(
                requestId,
                cx,
                cz,
                dim,
                timestamp,
                encodedSections,
                method,
                originalLength,
                completeColumn,
                completesRequest,
                replacementSectionYs);
    }

    private static LodByteCompression.Result encodedForClient(VoxelColumnS2CPayload payload, boolean networkCompressionEnabled) {
        LodByteCompression.Result cached = payload.cachedEncodedForClient;
        if (payload.hasCachedEncodedForClient
                && payload.cachedNetworkCompressionEnabled == networkCompressionEnabled
                && payload.cachedAllowZstdEncoding == payload.allowZstdEncoding
                && cached != null) {
            return cached;
        }

        LodByteCompression.Result encoded = computeEncodedForClient(payload, networkCompressionEnabled);
        payload.cachedEncodedForClient = encoded;
        payload.cachedNetworkCompressionEnabled = networkCompressionEnabled;
        payload.cachedAllowZstdEncoding = payload.allowZstdEncoding;
        payload.hasCachedEncodedForClient = true;
        return encoded;
    }

    private static LodByteCompression.Result computeEncodedForClient(VoxelColumnS2CPayload payload, boolean networkCompressionEnabled) {
        if (payload.encodedSectionBytes == null) {
            return networkCompressionEnabled
                    ? LodByteCompression.compressForNetwork(payload.sectionBytes, payload.allowZstdEncoding)
                    : LodByteCompression.Result.raw(payload.sectionBytes);
        }

        LodByteCompression.Result encoded = new LodByteCompression.Result(
                payload.encodedSectionBytes,
                payload.encodedCompression,
                payload.encodedRawSize);
        if (encoded.method() != LodByteCompression.METHOD_ZSTD || payload.allowZstdEncoding) {
            return encoded;
        }

        byte[] sections = payload.decompressedSections();
        return networkCompressionEnabled
                ? LodByteCompression.compressForNetwork(sections, false)
                : LodByteCompression.Result.raw(sections);
    }

    private static int dimensionToOrdinal(ResourceKey<Level> dim) {
        if (dim == Level.OVERWORLD) {
            return 0;
        }
        if (dim == Level.NETHER) {
            return 1;
        }
        if (dim == Level.END) {
            return 2;
        }
        return -1;
    }

    private static void validateEncodedSections(int method, int originalLength, int encodedLength) {
        if (originalLength < 0 || originalLength > MAX_SECTIONS_SIZE) {
            throw new IllegalArgumentException("Invalid decompressed LOD length: " + originalLength);
        }
        if (method != LodByteCompression.METHOD_NONE
                && method != LodByteCompression.METHOD_DEFLATE
                && method != LodByteCompression.METHOD_ZSTD) {
            throw new IllegalArgumentException("Unknown LOD compression method: " + method);
        }
        if (method == LodByteCompression.METHOD_NONE && encodedLength != originalLength) {
            throw new IllegalArgumentException("Raw LOD length mismatch");
        }
    }

    private int wireBodyLength() {
        if (encodedSectionBytes != null) {
            return encodedSectionBytes.length;
        }
        return sectionBytes != null ? sectionBytes.length : 0;
    }

    private int metadataBytes() {
        return 2 + 1 + replacementSectionYs.length;
    }

    private static int[] sanitizeReplacementSections(int[] sectionYs) {
        if (sectionYs == null || sectionYs.length == 0) {
            return new int[0];
        }
        int count = Math.min(sectionYs.length, MAX_REPLACEMENT_SECTION_COUNT);
        return Arrays.copyOf(sectionYs, count);
    }
}

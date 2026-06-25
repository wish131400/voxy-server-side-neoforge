package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import java.io.IOException;
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
    private static final int MAX_DIMENSION_STRING_LENGTH = 256;

    private final int requestId;
    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;
    private final boolean completeColumn;
    private boolean allowZstdEncoding;

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes) {
        this(requestId, chunkX, chunkZ, dimension, columnTimestamp, sectionBytes, true);
    }

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes, boolean completeColumn) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
        this.completeColumn = completeColumn;
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
        return sectionBytes;
    }

    public boolean completeColumn() {
        return completeColumn;
    }

    public int estimatedBytes() {
        return sectionBytes.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
    }

    public void setAllowZstdEncoding(boolean allowZstdEncoding) {
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
        LodByteCompression.Result encoded = VSSServerConfig.CONFIG.enableNetworkColumnCompression
                ? LodByteCompression.compressForNetwork(payload.sectionBytes, payload.allowZstdEncoding)
                : LodByteCompression.Result.raw(payload.sectionBytes);
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
        int method = buf.readVarInt();
        int originalLength = buf.readVarInt();
        byte[] encodedSections = buf.readByteArray(MAX_SECTIONS_SIZE);
        byte[] sections;
        try {
            sections = LodByteCompression.decompress(encodedSections, method, originalLength, MAX_SECTIONS_SIZE);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid compressed voxel column payload", e);
        }
        return new VoxelColumnS2CPayload(requestId, cx, cz, dim, timestamp, sections, completeColumn);
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
}

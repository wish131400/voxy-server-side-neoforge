package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class VoxelColumnS2CPayload {
    private static final int MAX_SECTIONS_SIZE = 0x200000;
    private static final int MAX_DIMENSION_STRING_LENGTH = 256;

    private final int requestId;
    private final int chunkX;
    private final int chunkZ;
    private final ResourceKey<Level> dimension;
    private final long columnTimestamp;
    private final byte[] sectionBytes;

    public VoxelColumnS2CPayload(int requestId, int chunkX, int chunkZ, ResourceKey<Level> dimension, long columnTimestamp, byte[] sectionBytes) {
        this.requestId = requestId;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.dimension = dimension;
        this.columnTimestamp = columnTimestamp;
        this.sectionBytes = sectionBytes;
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

    public int estimatedBytes() {
        return sectionBytes.length + VSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
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
        buf.writeByteArray(payload.sectionBytes);
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
        byte[] sections = buf.readByteArray(MAX_SECTIONS_SIZE);
        return new VoxelColumnS2CPayload(requestId, cx, cz, dim, timestamp, sections);
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

package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.processing.LodByteCompression;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public final class RegionPresenceC2SPayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RegionPresenceC2SPayload> TYPE = VSSPayloadCodecs.type("region_presence");
    public static final StreamCodec<RegistryFriendlyByteBuf, RegionPresenceC2SPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(RegionPresenceC2SPayload::encode, RegionPresenceC2SPayload::decode);

    public static final int REGION_SIZE = 32;
    public static final int REGION_SLOT_COUNT = REGION_SIZE * REGION_SIZE;
    public static final int REGION_BITMAP_LONGS = REGION_SLOT_COUNT / Long.SIZE;

    private static final int MAX_DIMENSION_STRING_LENGTH = 256;

    private final ResourceKey<Level> dimension;
    private final boolean reset;
    private final int compression;
    private final int rawSize;
    private final byte[] encodedBytes;
    private List<RegionEntry> decodedEntries;

    public RegionPresenceC2SPayload(
            ResourceKey<Level> dimension,
            boolean reset,
            int compression,
            int rawSize,
            byte[] encodedBytes) {
        this.dimension = dimension;
        this.reset = reset;
        this.compression = compression;
        this.rawSize = rawSize;
        this.encodedBytes = encodedBytes;
    }

    public static RegionPresenceC2SPayload create(
            ResourceKey<Level> dimension,
            boolean reset,
            List<RegionEntry> entries,
            boolean allowZstd) {
        byte[] raw = encodeRaw(entries);
        LodByteCompression.Result encoded = LodByteCompression.compressForNetwork(raw, allowZstd);
        return new RegionPresenceC2SPayload(dimension, reset, encoded.method(), encoded.originalLength(), encoded.bytes());
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public boolean reset() {
        return reset;
    }

    public List<RegionEntry> entries() {
        List<RegionEntry> entries = decodedEntries;
        if (entries != null) {
            return entries;
        }
        try {
            byte[] raw = LodByteCompression.decompress(
                    encodedBytes,
                    compression,
                    rawSize,
                    VSSConstants.MAX_REGION_PRESENCE_RAW_BYTES);
            entries = decodeRaw(raw);
            decodedEntries = entries;
            return entries;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid compressed LOD presence summary", e);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(RegionPresenceC2SPayload payload, FriendlyByteBuf buf) {
        int ordinal = dimensionToOrdinal(payload.dimension);
        buf.writeVarInt(ordinal);
        if (ordinal == -1) {
            buf.writeUtf(payload.dimension.location().toString());
        }
        buf.writeBoolean(payload.reset);
        buf.writeVarInt(payload.compression);
        buf.writeVarInt(payload.rawSize);
        buf.writeByteArray(payload.encodedBytes);
    }

    public static RegionPresenceC2SPayload decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        ResourceKey<Level> dim = switch (ordinal) {
            case 0 -> Level.OVERWORLD;
            case 1 -> Level.NETHER;
            case 2 -> Level.END;
            default -> ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf(MAX_DIMENSION_STRING_LENGTH)));
        };
        boolean reset = buf.readBoolean();
        int compression = buf.readVarInt();
        int rawSize = buf.readVarInt();
        byte[] encoded = buf.readByteArray(VSSConstants.MAX_REGION_PRESENCE_ENCODED_BYTES);
        return new RegionPresenceC2SPayload(dim, reset, compression, rawSize, encoded);
    }

    private static byte[] encodeRaw(List<RegionEntry> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, entries.size() * 160));
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(entries.size());
            for (RegionEntry entry : entries) {
                out.writeInt(entry.regionX());
                out.writeInt(entry.regionZ());
                long[] bitmap = entry.bitmap();
                for (int i = 0; i < REGION_BITMAP_LONGS; i++) {
                    out.writeLong(i < bitmap.length ? bitmap[i] : 0L);
                }
                out.writeInt(entry.count());
                int[] slots = entry.slots();
                long[] timestamps = entry.timestamps();
                for (int i = 0; i < entry.count(); i++) {
                    out.writeShort(slots[i] & 0xFFFF);
                    out.writeLong(timestamps[i]);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode LOD presence summary", e);
        }
    }

    private static List<RegionEntry> decodeRaw(byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        int regionCount = in.readInt();
        if (regionCount < 0 || regionCount > VSSConstants.MAX_REGION_PRESENCE_REGIONS) {
            throw new IOException("LOD presence region count out of range: " + regionCount);
        }
        ArrayList<RegionEntry> entries = new ArrayList<>(regionCount);
        int totalColumns = 0;
        for (int r = 0; r < regionCount; r++) {
            int regionX = in.readInt();
            int regionZ = in.readInt();
            long[] bitmap = new long[REGION_BITMAP_LONGS];
            for (int i = 0; i < REGION_BITMAP_LONGS; i++) {
                bitmap[i] = in.readLong();
            }
            int count = in.readInt();
            if (count < 0 || count > REGION_SLOT_COUNT) {
                throw new IOException("LOD presence column count out of range: " + count);
            }
            totalColumns += count;
            if (totalColumns > VSSConstants.MAX_REGION_PRESENCE_COLUMNS) {
                throw new IOException("LOD presence summary contains too many columns: " + totalColumns);
            }
            int[] slots = new int[count];
            long[] timestamps = new long[count];
            for (int i = 0; i < count; i++) {
                slots[i] = in.readUnsignedShort();
                timestamps[i] = in.readLong();
            }
            entries.add(new RegionEntry(regionX, regionZ, bitmap, slots, timestamps, count));
        }
        return entries;
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

    public record RegionEntry(int regionX, int regionZ, long[] bitmap, int[] slots, long[] timestamps, int count) {
    }
}

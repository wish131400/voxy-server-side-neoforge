package dev.xantha.vss.networking.payloads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Complete VSS worldgen snapshot used to reconstruct client-side noise generators. */
public record WorldgenProfileS2CPayload(
        int formatVersion,
        long seed,
        long revision,
        int registriesCompression,
        int registriesRawSize,
        byte[] registries,
        List<DimensionProfile> dimensions) implements CustomPacketPayload {
    public static final int FORMAT_VERSION = 3;
    public static final int MAX_DIMENSIONS = 64;
    public static final int MAX_STRING_LENGTH = 8192;
    public static final int MAX_REGISTRIES_BYTES = 8 * 1024 * 1024;
    public static final int MAX_REGISTRIES_RAW_BYTES = 32 * 1024 * 1024;
    public static final int MAX_GENERATOR_BYTES = 2 * 1024 * 1024;
    public static final int MAX_GENERATOR_RAW_BYTES = 8 * 1024 * 1024;
    public static final CustomPacketPayload.Type<WorldgenProfileS2CPayload> TYPE =
            VSSPayloadCodecs.type("worldgen_profile");
    public static final StreamCodec<RegistryFriendlyByteBuf, WorldgenProfileS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(WorldgenProfileS2CPayload::encode, WorldgenProfileS2CPayload::decode);

    public WorldgenProfileS2CPayload {
        registries = registries == null ? new byte[0] : Arrays.copyOf(registries, registries.length);
        validateCompressedSize(registries, registriesRawSize, registriesCompression,
                MAX_REGISTRIES_BYTES, MAX_REGISTRIES_RAW_BYTES, "registry");
        dimensions = dimensions == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(dimensions));
    }

    public WorldgenProfileS2CPayload(
            int formatVersion,
            long seed,
            long revision,
            List<DimensionProfile> dimensions) {
        this(formatVersion, seed, revision, 0, 0, new byte[0], dimensions);
    }

    @Override
    public byte[] registries() {
        return Arrays.copyOf(registries, registries.length);
    }

    /** Session rates/generation toggles change revision, not worldgen identity. */
    public boolean sameWorldgen(WorldgenProfileS2CPayload other) {
        if (other == null || formatVersion != other.formatVersion || seed != other.seed
                || registriesCompression != other.registriesCompression || registriesRawSize != other.registriesRawSize
                || !Arrays.equals(registries, other.registries) || dimensions.size() != other.dimensions.size()) return false;
        for (int i = 0; i < dimensions.size(); i++) {
            DimensionProfile a = dimensions.get(i), b = other.dimensions.get(i);
            if (!a.dimension.equals(b.dimension) || a.seed != b.seed || a.minY != b.minY || a.height != b.height
                    || !a.generatorType.equals(b.generatorType) || !a.generatorSettings.equals(b.generatorSettings)
                    || a.fingerprint != b.fingerprint || a.generatorCompression != b.generatorCompression
                    || a.generatorRawSize != b.generatorRawSize || !Arrays.equals(a.generatorData, b.generatorData)) return false;
        }
        return true;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(WorldgenProfileS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.formatVersion());
        buf.writeLong(payload.seed());
        buf.writeVarLong(payload.revision());
        buf.writeByte(payload.registriesCompression());
        buf.writeVarInt(payload.registriesRawSize());
        buf.writeByteArray(payload.registries());
        int count = Math.min(payload.dimensions().size(), MAX_DIMENSIONS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            DimensionProfile profile = payload.dimensions().get(i);
            buf.writeResourceLocation(profile.dimension());
            buf.writeVarLong(profile.seed());
            buf.writeVarInt(profile.minY());
            buf.writeVarInt(profile.height());
            buf.writeUtf(profile.generatorType(), MAX_STRING_LENGTH);
            buf.writeUtf(profile.generatorSettings(), MAX_STRING_LENGTH);
            buf.writeLong(profile.fingerprint());
            buf.writeByte(profile.generatorCompression());
            buf.writeVarInt(profile.generatorRawSize());
            buf.writeByteArray(profile.generatorData());
        }
    }

    public static WorldgenProfileS2CPayload decode(FriendlyByteBuf buf) {
        int format = buf.readVarInt();
        long seed = buf.readLong();
        long revision = buf.readVarLong();
        int registryCompression = buf.readUnsignedByte();
        int registryRawSize = buf.readVarInt();
        byte[] registries = buf.readByteArray(MAX_REGISTRIES_BYTES);
        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_DIMENSIONS);
        List<DimensionProfile> dimensions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            dimensions.add(new DimensionProfile(
                    buf.readResourceLocation(),
                    buf.readVarLong(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(MAX_STRING_LENGTH),
                    buf.readUtf(MAX_STRING_LENGTH),
                    buf.readLong(),
                    buf.readUnsignedByte(),
                    buf.readVarInt(),
                    buf.readByteArray(MAX_GENERATOR_BYTES)));
        }
        return new WorldgenProfileS2CPayload(
                format, seed, revision, registryCompression, registryRawSize, registries, dimensions);
    }

    public record DimensionProfile(
            ResourceLocation dimension,
            long seed,
            int minY,
            int height,
            String generatorType,
            String generatorSettings,
            long fingerprint,
            int generatorCompression,
            int generatorRawSize,
            byte[] generatorData) {
        public DimensionProfile {
            if (dimension == null) {
                dimension = ResourceLocation.withDefaultNamespace("overworld");
            }
            generatorType = generatorType == null ? "unknown" : generatorType;
            generatorSettings = generatorSettings == null ? "unknown" : generatorSettings;
            generatorData = generatorData == null ? new byte[0] : Arrays.copyOf(generatorData, generatorData.length);
            validateCompressedSize(generatorData, generatorRawSize, generatorCompression,
                    MAX_GENERATOR_BYTES, MAX_GENERATOR_RAW_BYTES, "generator");
        }

        public DimensionProfile(ResourceLocation dimension, int minY, int height,
                                String generatorType, String generatorSettings, long fingerprint) {
            this(dimension, 0L, minY, height, generatorType, generatorSettings, fingerprint,
                    0, 0, new byte[0]);
        }

        public DimensionProfile(ResourceLocation dimension, long seed, int minY, int height,
                                String generatorType, String generatorSettings, long fingerprint) {
            this(dimension, seed, minY, height, generatorType, generatorSettings, fingerprint,
                    0, 0, new byte[0]);
        }

        public DimensionProfile(ResourceLocation dimension, int minY, int height,
                                String generatorType, String generatorSettings, long fingerprint,
                                int generatorCompression, int generatorRawSize, byte[] generatorData) {
            this(dimension, 0L, minY, height, generatorType, generatorSettings, fingerprint,
                    generatorCompression, generatorRawSize, generatorData);
        }

        @Override
        public byte[] generatorData() {
            return Arrays.copyOf(generatorData, generatorData.length);
        }

        public ResourceKey<Level> levelKey() {
            return ResourceKey.create(Registries.DIMENSION, dimension);
        }
    }

    private static void validateCompressedSize(byte[] bytes, int rawSize, int compression,
                                               int maxBytes, int maxRawBytes, String label) {
        if (bytes.length > maxBytes || rawSize < 0 || rawSize > maxRawBytes) {
            throw new IllegalArgumentException("VSS " + label + " snapshot exceeds size limits");
        }
        if (compression < 0 || compression > 2) {
            throw new IllegalArgumentException("Unknown VSS " + label + " compression method: " + compression);
        }
    }
}

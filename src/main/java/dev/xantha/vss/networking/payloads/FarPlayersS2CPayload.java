package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

public record FarPlayersS2CPayload(Entry[] entries) {
    private static final int MAX_NAME_LENGTH = 16;

    public static void encode(FarPlayersS2CPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.length);
        for (Entry entry : payload.entries) {
            buf.writeUUID(entry.uuid);
            buf.writeUtf(entry.name, MAX_NAME_LENGTH);
            buf.writeDouble(entry.x);
            buf.writeDouble(entry.y);
            buf.writeDouble(entry.z);
            buf.writeFloat(entry.yaw);
            buf.writeFloat(entry.pitch);
            buf.writeFloat(entry.headYaw);
            buf.writeBoolean(entry.crouching);
            buf.writeBoolean(entry.sprinting);
        }
    }

    public static FarPlayersS2CPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_FAR_PLAYER_ENTRIES) {
            throw new IllegalArgumentException("Far player entry count out of range: " + count);
        }

        Entry[] entries = new Entry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new Entry(
                    buf.readUUID(),
                    buf.readUtf(MAX_NAME_LENGTH),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean());
        }
        return new FarPlayersS2CPayload(entries);
    }

    public record Entry(
            UUID uuid,
            String name,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            boolean crouching,
            boolean sprinting) {
    }
}

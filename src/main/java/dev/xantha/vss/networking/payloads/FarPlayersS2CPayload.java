package dev.xantha.vss.networking.payloads;

import dev.xantha.vss.common.VSSConstants;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;

public record FarPlayersS2CPayload(Entry[] entries) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<FarPlayersS2CPayload> TYPE = VSSPayloadCodecs.type("far_players");
    public static final StreamCodec<RegistryFriendlyByteBuf, FarPlayersS2CPayload> STREAM_CODEC =
            VSSPayloadCodecs.codec(FarPlayersS2CPayload::encode, FarPlayersS2CPayload::decode);

    private static final int MAX_NAME_LENGTH = 16;
    private static final int MAX_USE_ITEM_REMAINING_TICKS = 72000;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void encode(FarPlayersS2CPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(payload.entries.length);
        for (Entry entry : payload.entries) {
            buf.writeUUID(entry.uuid());
            buf.writeUtf(entry.name(), MAX_NAME_LENGTH);
            buf.writeDouble(entry.x());
            buf.writeDouble(entry.y());
            buf.writeDouble(entry.z());
            buf.writeFloat(entry.yaw());
            buf.writeFloat(entry.pitch());
            buf.writeFloat(entry.headYaw());
            buf.writeFloat(entry.bodyYaw());
            buf.writeBoolean(entry.crouching());
            buf.writeBoolean(entry.sprinting());
            buf.writeEnum(orDefault(entry.pose(), Pose.STANDING));
            buf.writeEnum(orDefault(entry.mainArm(), HumanoidArm.RIGHT));
            buf.writeBoolean(entry.usingItem());
            buf.writeEnum(orDefault(entry.usedItemHand(), InteractionHand.MAIN_HAND));
            buf.writeVarInt(clampUseItemTicks(entry.useItemRemainingTicks()));
            buf.writeBoolean(entry.swinging());
            buf.writeEnum(orDefault(entry.swingingArm(), InteractionHand.MAIN_HAND));
            buf.writeBoolean(entry.swimming());
            buf.writeBoolean(entry.invisible());
            buf.writeBoolean(entry.glowing());
            buf.writeBoolean(entry.onGround());
            buf.writeBoolean(entry.onFire());
            buf.writeByte(entry.modelParts());
            buf.writeNbt(entry.curiosData());
            writeEquipment(buf, entry);
            writeVehicles(buf, entry.vehicles());
        }
    }

    public static FarPlayersS2CPayload decode(RegistryFriendlyByteBuf buf) {
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
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readEnum(Pose.class),
                    buf.readEnum(HumanoidArm.class),
                    buf.readBoolean(),
                    buf.readEnum(InteractionHand.class),
                    clampUseItemTicks(buf.readVarInt()),
                    buf.readBoolean(),
                    buf.readEnum(InteractionHand.class),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readUnsignedByte(),
                    buf.readNbt(),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                    readVehicles(buf));
        }
        return new FarPlayersS2CPayload(entries);
    }

    private static void writeEquipment(RegistryFriendlyByteBuf buf, Entry entry) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.mainHand()));
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.offHand()));
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.head()));
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.chest()));
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.legs()));
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, orEmpty(entry.feet()));
    }

    private static void writeVehicles(FriendlyByteBuf buf, VehicleSnapshot[] vehicles) {
        if (vehicles == null || vehicles.length == 0) {
            buf.writeVarInt(0);
            return;
        }

        int count = 0;
        for (VehicleSnapshot vehicle : vehicles) {
            if (vehicle != null && vehicle.entityTypeId() != null) {
                count++;
                if (count >= VSSConstants.MAX_FAR_VEHICLE_PARENT_DEPTH) {
                    break;
                }
            }
        }
        buf.writeVarInt(count);
        int written = 0;
        for (VehicleSnapshot vehicle : vehicles) {
            if (vehicle == null || vehicle.entityTypeId() == null) {
                continue;
            }
            writeVehicle(buf, vehicle);
            written++;
            if (written >= count) {
                break;
            }
        }
    }

    private static void writeVehicle(FriendlyByteBuf buf, VehicleSnapshot vehicle) {
        ResourceLocation entityTypeId = vehicle.entityTypeId();
        buf.writeVarInt(vehicle.sourceEntityId());
        buf.writeResourceLocation(entityTypeId != null ? entityTypeId : BuiltInRegistries.ENTITY_TYPE.getDefaultKey());
        buf.writeDouble(vehicle.x());
        buf.writeDouble(vehicle.y());
        buf.writeDouble(vehicle.z());
        buf.writeFloat(vehicle.yaw());
        buf.writeFloat(vehicle.pitch());
        buf.writeFloat(vehicle.headYaw());
        buf.writeFloat(vehicle.bodyYaw());
        buf.writeBoolean(vehicle.onGround());
        buf.writeBoolean(vehicle.onFire());
        buf.writeBoolean(vehicle.invisible());
        buf.writeBoolean(vehicle.glowing());
        buf.writeFloat(vehicle.rocketFinalLiftVelocity());
        buf.writeBoolean(vehicle.fullData());
        if (vehicle.fullData()) {
            buf.writeNbt(vehicle.entityData());
            buf.writeByteArray(orEmpty(vehicle.spawnData()));
        }
    }

    private static VehicleSnapshot[] readVehicles(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > VSSConstants.MAX_FAR_VEHICLE_PARENT_DEPTH) {
            throw new IllegalArgumentException("Far vehicle chain count out of range: " + count);
        }

        VehicleSnapshot[] vehicles = new VehicleSnapshot[count];
        for (int i = 0; i < count; i++) {
            vehicles[i] = readVehicle(buf);
        }
        return vehicles;
    }

    private static VehicleSnapshot readVehicle(FriendlyByteBuf buf) {
        boolean fullData;
        CompoundTag entityData = null;
        byte[] spawnData = new byte[0];
        int sourceEntityId = buf.readVarInt();
        ResourceLocation entityTypeId = buf.readResourceLocation();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        float headYaw = buf.readFloat();
        float bodyYaw = buf.readFloat();
        boolean onGround = buf.readBoolean();
        boolean onFire = buf.readBoolean();
        boolean invisible = buf.readBoolean();
        boolean glowing = buf.readBoolean();
        float rocketFinalLiftVelocity = buf.readFloat();
        fullData = buf.readBoolean();
        if (fullData) {
            entityData = buf.readNbt();
            spawnData = buf.readByteArray(VSSConstants.MAX_FAR_VEHICLE_DATA_BYTES);
        }
        return new VehicleSnapshot(
                sourceEntityId,
                entityTypeId,
                x,
                y,
                z,
                yaw,
                pitch,
                headYaw,
                bodyYaw,
                onGround,
                onFire,
                invisible,
                glowing,
                rocketFinalLiftVelocity,
                fullData,
                entityData,
                spawnData);
    }

    private static int clampUseItemTicks(int ticks) {
        return Math.max(0, Math.min(MAX_USE_ITEM_REMAINING_TICKS, ticks));
    }

    private static <E extends Enum<E>> E orDefault(E value, E fallback) {
        return value != null ? value : fallback;
    }

    private static ItemStack orEmpty(ItemStack stack) {
        return stack != null ? stack : ItemStack.EMPTY;
    }

    private static byte[] orEmpty(byte[] bytes) {
        return bytes != null ? bytes : new byte[0];
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
            float bodyYaw,
            boolean crouching,
            boolean sprinting,
            Pose pose,
            HumanoidArm mainArm,
            boolean usingItem,
            InteractionHand usedItemHand,
            int useItemRemainingTicks,
            boolean swinging,
            InteractionHand swingingArm,
            boolean swimming,
            boolean invisible,
            boolean glowing,
            boolean onGround,
            boolean onFire,
            int modelParts,
            CompoundTag curiosData,
            ItemStack mainHand,
            ItemStack offHand,
            ItemStack head,
            ItemStack chest,
            ItemStack legs,
            ItemStack feet,
            VehicleSnapshot[] vehicles) {
        public ItemStack itemBySlot(EquipmentSlot slot) {
            return switch (slot) {
                case MAINHAND -> mainHand;
                case OFFHAND -> offHand;
                case HEAD -> head;
                case CHEST -> chest;
                case LEGS -> legs;
                case FEET -> feet;
                default -> ItemStack.EMPTY;
            };
        }
    }

    public record VehicleSnapshot(
            int sourceEntityId,
            ResourceLocation entityTypeId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float headYaw,
            float bodyYaw,
            boolean onGround,
            boolean onFire,
            boolean invisible,
            boolean glowing,
            float rocketFinalLiftVelocity,
            boolean fullData,
            CompoundTag entityData,
            byte[] spawnData) {
    }
}

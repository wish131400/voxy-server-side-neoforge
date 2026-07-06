package dev.xantha.vss.networking.server.broadcast;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.networking.server.compat.NorthstarRocketCompat;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class FarPlayerBroadcaster {
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final long FULL_VEHICLE_DATA_INTERVAL_NANOS = 10_000_000_000L;
    private static final int MAX_VEHICLE_SPAWN_DATA_BYTES = VSSConstants.MAX_FAR_VEHICLE_DATA_BYTES;
    private static final int MAX_VEHICLE_NBT_BYTES = VSSConstants.MAX_FAR_VEHICLE_DATA_BYTES;
    private static final int MAX_FAR_PLAYERS_PACKET_BYTES = VSSConstants.MAX_FAR_PLAYERS_PACKET_BYTES;
    private static final int FAR_PLAYER_BUCKET_CHUNKS = 32;
    private static final Map<UUID, Map<UUID, VehicleSyncCache>> VEHICLE_SYNC_CACHES = new HashMap<>();
    private static int tickCounter;
    private static long nextDiagnosticNanos;

    private FarPlayerBroadcaster() {
    }

    @SubscribeEvent
    public static synchronized void onServerTick(ServerTickEvent.Post event) {
        VSSServerConfig config = VSSServerConfig.CONFIG;
        if (!config.enabled || !config.farPlayerSyncEnabled || !VSSServerNetworking.hasRegisteredPlayers()) {
            NorthstarRocketCompat.removeAll();
            return;
        }
        if (++tickCounter < config.farPlayerSyncIntervalTicks) {
            return;
        }
        tickCounter = 0;
        broadcast(event.getServer(), config);
    }

    @SubscribeEvent
    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        tickCounter = 0;
        VEHICLE_SYNC_CACHES.clear();
        NorthstarRocketCompat.removeAll();
    }

    private static synchronized void broadcast(MinecraftServer server, VSSServerConfig config) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() < 2) {
            VEHICLE_SYNC_CACHES.clear();
            NorthstarRocketCompat.removeAll();
            return;
        }
        pruneVehicleCaches(players);
        NorthstarRocketCompat.pruneViewers(players);

        double maxHorizontalDistanceSqr = square(config.lodDistanceChunks * 16.0D);
        PlayerSpatialIndex spatialIndex = PlayerSpatialIndex.build(players);
        for (ServerPlayer viewer : players) {
            if (!VSSServerNetworking.isRegistered(viewer)) {
                NorthstarRocketCompat.clear(viewer);
                continue;
            }

            NorthstarRocketCompat.beginViewer(viewer);
            List<FarPlayersS2CPayload.Entry> entries = new ArrayList<>();
            Set<Integer> sentVehicleIds = new HashSet<>();
            int skippedUnavailable = 0;
            int skippedDistance = 0;
            int vehicleSnapshotsSent = 0;
            try {
                for (ServerPlayer target : spatialIndex.candidates(viewer, config.lodDistanceChunks)) {
                    if (target == viewer) {
                        continue;
                    }
                    if (target.isSpectator() || !target.serverLevel().dimension().equals(viewer.serverLevel().dimension())) {
                        skippedUnavailable++;
                        continue;
                    }

                    double horizontalDistanceSqr = horizontalDistanceSqr(target, viewer);
                    if (horizontalDistanceSqr > maxHorizontalDistanceSqr) {
                        skippedDistance++;
                        continue;
                    }

                    FarPlayersS2CPayload.VehicleSnapshot[] vehicles = vehicleSnapshots(viewer, target, sentVehicleIds);
                    vehicleSnapshotsSent += vehicles.length;
                    entries.add(new FarPlayersS2CPayload.Entry(
                            target.getUUID(),
                            target.getGameProfile().getName(),
                            target.getX(),
                            target.getY(),
                            target.getZ(),
                            target.getYRot(),
                            target.getXRot(),
                            target.getYHeadRot(),
                            target.yBodyRot,
                            target.isCrouching(),
                            target.isSprinting(),
                            syncedPose(target),
                            orDefault(target.getMainArm(), HumanoidArm.RIGHT),
                            target.isUsingItem(),
                            target.isUsingItem() ? orDefault(target.getUsedItemHand(), InteractionHand.MAIN_HAND) : InteractionHand.MAIN_HAND,
                            target.isUsingItem() ? target.getUseItemRemainingTicks() : 0,
                            target.swinging,
                            orDefault(target.swingingArm, InteractionHand.MAIN_HAND),
                            target.isSwimming(),
                            target.isInvisible(),
                            target.isCurrentlyGlowing(),
                            target.onGround(),
                            target.isOnFire(),
                            copyItem(target, EquipmentSlot.MAINHAND),
                            copyItem(target, EquipmentSlot.OFFHAND),
                            copyItem(target, EquipmentSlot.HEAD),
                            copyItem(target, EquipmentSlot.CHEST),
                            copyItem(target, EquipmentSlot.LEGS),
                            copyItem(target, EquipmentSlot.FEET),
                            vehicles));
                    if (entries.size() >= VSSConstants.MAX_FAR_PLAYER_ENTRIES) {
                        break;
                    }
                }

                VSSNetworking.sendToPlayer(viewer, safePayload(viewer, entries));
                maybeLogBroadcast(viewer, players.size(), entries.size(), vehicleSnapshotsSent, skippedUnavailable, skippedDistance);
            } finally {
                NorthstarRocketCompat.finishViewer(viewer);
            }
        }
    }

    private record PlayerSpatialIndex(Map<ResourceLocation, Map<Long, List<ServerPlayer>>> buckets) {
        static PlayerSpatialIndex build(List<ServerPlayer> players) {
            Map<ResourceLocation, Map<Long, List<ServerPlayer>>> buckets = new HashMap<>();
            for (ServerPlayer player : players) {
                if (player.isSpectator()) {
                    continue;
                }
                ResourceLocation dimension = player.serverLevel().dimension().location();
                int chunkX = player.getBlockX() >> 4;
                int chunkZ = player.getBlockZ() >> 4;
                long bucketKey = bucketKey(
                        Math.floorDiv(chunkX, FAR_PLAYER_BUCKET_CHUNKS),
                        Math.floorDiv(chunkZ, FAR_PLAYER_BUCKET_CHUNKS));
                buckets.computeIfAbsent(dimension, ignored -> new HashMap<>())
                        .computeIfAbsent(bucketKey, ignored -> new ArrayList<>())
                        .add(player);
            }
            return new PlayerSpatialIndex(buckets);
        }

        List<ServerPlayer> candidates(ServerPlayer viewer, int lodDistanceChunks) {
            Map<Long, List<ServerPlayer>> dimensionBuckets = buckets.get(viewer.serverLevel().dimension().location());
            if (dimensionBuckets == null || dimensionBuckets.isEmpty()) {
                return List.of();
            }

            int viewerCx = viewer.getBlockX() >> 4;
            int viewerCz = viewer.getBlockZ() >> 4;
            int minBucketX = Math.floorDiv(viewerCx - lodDistanceChunks, FAR_PLAYER_BUCKET_CHUNKS);
            int maxBucketX = Math.floorDiv(viewerCx + lodDistanceChunks, FAR_PLAYER_BUCKET_CHUNKS);
            int minBucketZ = Math.floorDiv(viewerCz - lodDistanceChunks, FAR_PLAYER_BUCKET_CHUNKS);
            int maxBucketZ = Math.floorDiv(viewerCz + lodDistanceChunks, FAR_PLAYER_BUCKET_CHUNKS);
            ArrayList<ServerPlayer> candidates = new ArrayList<>();
            for (int bucketX = minBucketX; bucketX <= maxBucketX; bucketX++) {
                for (int bucketZ = minBucketZ; bucketZ <= maxBucketZ; bucketZ++) {
                    List<ServerPlayer> bucket = dimensionBuckets.get(bucketKey(bucketX, bucketZ));
                    if (bucket != null) {
                        candidates.addAll(bucket);
                    }
                }
            }
            return candidates;
        }

        private static long bucketKey(int bucketX, int bucketZ) {
            return ((long) bucketX << 32) ^ (bucketZ & 0xFFFF_FFFFL);
        }
    }

    private static FarPlayersS2CPayload safePayload(ServerPlayer viewer, List<FarPlayersS2CPayload.Entry> entries) {
        FarPlayersS2CPayload payload = new FarPlayersS2CPayload(entries.toArray(FarPlayersS2CPayload.Entry[]::new));
        if (!hasFullVehicleData(payload)) {
            return payload;
        }

        int size = encodedSize(viewer, payload);
        if (size >= 0 && size <= MAX_FAR_PLAYERS_PACKET_BYTES) {
            return payload;
        }

        FarPlayersS2CPayload poseOnlyPayload = copyPayloadWithPoseOnlyVehicles(payload);
        int poseOnlySize = encodedSize(viewer, poseOnlyPayload);
        if (poseOnlySize >= 0 && poseOnlySize <= MAX_FAR_PLAYERS_PACKET_BYTES) {
            VSSLogger.warn("Far player vehicle data exceeded packet budget (" + size
                    + " bytes); sending pose-only vehicle data instead");
            return poseOnlyPayload;
        }

        FarPlayersS2CPayload playerOnlyPayload = copyPayloadWithoutVehicles(payload);
        VSSLogger.warn("Far player vehicle data could not be encoded safely; sending player-only far sync");
        return playerOnlyPayload;
    }

    private static boolean hasFullVehicleData(FarPlayersS2CPayload payload) {
        for (FarPlayersS2CPayload.Entry entry : payload.entries()) {
            FarPlayersS2CPayload.VehicleSnapshot[] vehicles = entry.vehicles();
            if (vehicles == null) {
                continue;
            }
            for (FarPlayersS2CPayload.VehicleSnapshot vehicle : vehicles) {
                if (vehicle != null && vehicle.fullData()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int encodedSize(ServerPlayer viewer, FarPlayersS2CPayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), viewer.registryAccess());
        try {
            FarPlayersS2CPayload.encode(payload, buf);
            return buf.readableBytes();
        } catch (RuntimeException e) {
            VSSLogger.warn("Failed to encode far player payload for validation", e);
            return -1;
        } finally {
            buf.release();
        }
    }

    private static FarPlayersS2CPayload copyPayloadWithPoseOnlyVehicles(FarPlayersS2CPayload payload) {
        FarPlayersS2CPayload.Entry[] entries = payload.entries();
        FarPlayersS2CPayload.Entry[] copy = new FarPlayersS2CPayload.Entry[entries.length];
        for (int i = 0; i < entries.length; i++) {
            FarPlayersS2CPayload.Entry entry = entries[i];
            copy[i] = copyEntry(entry, poseOnlyVehicles(entry.vehicles()));
        }
        return new FarPlayersS2CPayload(copy);
    }

    private static FarPlayersS2CPayload copyPayloadWithoutVehicles(FarPlayersS2CPayload payload) {
        FarPlayersS2CPayload.Entry[] entries = payload.entries();
        FarPlayersS2CPayload.Entry[] copy = new FarPlayersS2CPayload.Entry[entries.length];
        for (int i = 0; i < entries.length; i++) {
            copy[i] = copyEntry(entries[i], new FarPlayersS2CPayload.VehicleSnapshot[0]);
        }
        return new FarPlayersS2CPayload(copy);
    }

    private static FarPlayersS2CPayload.VehicleSnapshot[] poseOnlyVehicles(FarPlayersS2CPayload.VehicleSnapshot[] vehicles) {
        if (vehicles == null || vehicles.length == 0) {
            return new FarPlayersS2CPayload.VehicleSnapshot[0];
        }
        List<FarPlayersS2CPayload.VehicleSnapshot> copy = new ArrayList<>();
        for (FarPlayersS2CPayload.VehicleSnapshot vehicle : vehicles) {
            if (vehicle == null || vehicle.entityTypeId() == null) {
                continue;
            }
            copy.add(new FarPlayersS2CPayload.VehicleSnapshot(
                    vehicle.sourceEntityId(),
                    vehicle.entityTypeId(),
                    vehicle.x(),
                    vehicle.y(),
                    vehicle.z(),
                    vehicle.yaw(),
                    vehicle.pitch(),
                    vehicle.headYaw(),
                    vehicle.bodyYaw(),
                    vehicle.onGround(),
                    vehicle.onFire(),
                    vehicle.invisible(),
                    vehicle.glowing(),
                    vehicle.rocketFinalLiftVelocity(),
                    false,
                    null,
                    new byte[0]));
            if (copy.size() >= VSSConstants.MAX_FAR_VEHICLE_PARENT_DEPTH) {
                break;
            }
        }
        return copy.toArray(FarPlayersS2CPayload.VehicleSnapshot[]::new);
    }

    private static FarPlayersS2CPayload.Entry copyEntry(
            FarPlayersS2CPayload.Entry entry,
            FarPlayersS2CPayload.VehicleSnapshot[] vehicles) {
        return new FarPlayersS2CPayload.Entry(
                entry.uuid(),
                entry.name(),
                entry.x(),
                entry.y(),
                entry.z(),
                entry.yaw(),
                entry.pitch(),
                entry.headYaw(),
                entry.bodyYaw(),
                entry.crouching(),
                entry.sprinting(),
                entry.pose(),
                entry.mainArm(),
                entry.usingItem(),
                entry.usedItemHand(),
                entry.useItemRemainingTicks(),
                entry.swinging(),
                entry.swingingArm(),
                entry.swimming(),
                entry.invisible(),
                entry.glowing(),
                entry.onGround(),
                entry.onFire(),
                entry.mainHand(),
                entry.offHand(),
                entry.head(),
                entry.chest(),
                entry.legs(),
                entry.feet(),
                vehicles);
    }

    private static void maybeLogBroadcast(
            ServerPlayer viewer,
            int onlinePlayers,
            int entries,
            int vehicleSnapshots,
            int skippedUnavailable,
            int skippedDistance) {
        long now = System.nanoTime();
        if (now < nextDiagnosticNanos) {
            return;
        }
        nextDiagnosticNanos = now + DIAGNOSTIC_INTERVAL_NANOS;
        VSSLogger.debug("Far players sent to " + viewer.getGameProfile().getName()
                + ": entries=" + entries
                + ", vehicleSnapshots=" + vehicleSnapshots
                + ", online=" + onlinePlayers
                + ", skippedUnavailable=" + skippedUnavailable
                + ", skippedDistance=" + skippedDistance);
    }

    private static double square(double value) {
        return value * value;
    }

    private static double horizontalDistanceSqr(ServerPlayer target, ServerPlayer viewer) {
        double dx = target.getX() - viewer.getX();
        double dz = target.getZ() - viewer.getZ();
        return dx * dx + dz * dz;
    }

    private static <E extends Enum<E>> E orDefault(E value, E fallback) {
        return value != null ? value : fallback;
    }

    private static Pose syncedPose(ServerPlayer player) {
        Pose pose = orDefault(player.getPose(), Pose.STANDING);
        if (pose == Pose.FALL_FLYING && !player.isFallFlying()) {
            return Pose.STANDING;
        }
        return pose;
    }

    private static ItemStack copyItem(ServerPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        return stack != null ? stack.copy() : ItemStack.EMPTY;
    }

    private static FarPlayersS2CPayload.VehicleSnapshot[] vehicleSnapshots(ServerPlayer viewer, ServerPlayer target, Set<Integer> sentVehicleIds) {
        List<Entity> chain = vehicleChain(target);
        if (chain.isEmpty()) {
            clearVehicleCache(viewer, target);
            return new FarPlayersS2CPayload.VehicleSnapshot[0];
        }

        NorthstarRocketCompat.sync(viewer, target, chain);

        VehicleSyncCache cache = vehicleCache(viewer, target);
        long now = System.nanoTime();
        List<FarPlayersS2CPayload.VehicleSnapshot> snapshots = new ArrayList<>();
        boolean sentFullData = false;
        for (int i = 0; i < chain.size(); i++) {
            Entity vehicle = chain.get(i);
            boolean fullData = !sentVehicleIds.contains(vehicle.getId())
                    && cache.shouldSendFullData(vehicle, i, now);
            sentFullData |= fullData;
            FarPlayersS2CPayload.VehicleSnapshot snapshot = vehicleSnapshot(vehicle, fullData);
            if (snapshot == null) {
                break;
            }
            snapshots.add(snapshot);
        }
        for (FarPlayersS2CPayload.VehicleSnapshot snapshot : snapshots) {
            sentVehicleIds.add(snapshot.sourceEntityId());
        }
        cache.remember(chain, now, sentFullData);
        return snapshots.toArray(FarPlayersS2CPayload.VehicleSnapshot[]::new);
    }

    private static List<Entity> vehicleChain(ServerPlayer target) {
        List<Entity> chain = new ArrayList<>();
        Set<Entity> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Entity vehicle = target.getVehicle();
        while (vehicle != null
                && !vehicle.isRemoved()
                && chain.size() < VSSConstants.MAX_FAR_VEHICLE_PARENT_DEPTH
                && seen.add(vehicle)) {
            chain.add(vehicle);
            vehicle = vehicle.getVehicle();
        }
        return chain;
    }

    private static VehicleSyncCache vehicleCache(ServerPlayer viewer, ServerPlayer target) {
        return VEHICLE_SYNC_CACHES
                .computeIfAbsent(viewer.getUUID(), ignored -> new HashMap<>())
                .computeIfAbsent(target.getUUID(), ignored -> new VehicleSyncCache());
    }

    private static void clearVehicleCache(ServerPlayer viewer, ServerPlayer target) {
        Map<UUID, VehicleSyncCache> viewerCache = VEHICLE_SYNC_CACHES.get(viewer.getUUID());
        if (viewerCache != null) {
            viewerCache.remove(target.getUUID());
            if (viewerCache.isEmpty()) {
                VEHICLE_SYNC_CACHES.remove(viewer.getUUID());
            }
        }
    }

    private static FarPlayersS2CPayload.VehicleSnapshot vehicleSnapshot(Entity vehicle, boolean fullData) {
        if (vehicle == null || vehicle.isRemoved()) {
            return null;
        }

        ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        if (entityTypeId == null) {
            return null;
        }

        return new FarPlayersS2CPayload.VehicleSnapshot(
                vehicle.getId(),
                entityTypeId,
                vehicle.getX(),
                vehicle.getY(),
                vehicle.getZ(),
                vehicle.getYRot(),
                vehicle.getXRot(),
                vehicle.getYHeadRot(),
                vehicleBodyYaw(vehicle),
                vehicle.onGround(),
                vehicle.isOnFire(),
                vehicle.isInvisible(),
                vehicle.isCurrentlyGlowing(),
                NorthstarRocketCompat.finalLiftVelocity(vehicle),
                fullData,
                fullData ? captureEntityData(vehicle) : null,
                fullData ? captureSpawnData(vehicle) : new byte[0]);
    }

    private static float vehicleBodyYaw(Entity vehicle) {
        return vehicle instanceof LivingEntity livingEntity ? livingEntity.yBodyRot : vehicle.getYRot();
    }

    private static CompoundTag captureEntityData(Entity vehicle) {
        try {
            CompoundTag tag = vehicle.saveWithoutId(new CompoundTag());
            tag.remove("Passengers");
            tag.remove("UUID");
            tag.remove("UUIDMost");
            tag.remove("UUIDLeast");
            if (estimatedNbtSize(tag) > MAX_VEHICLE_NBT_BYTES) {
                VSSLogger.warn("Skipped far vehicle NBT for " + vehicle.getType() + " because it exceeded "
                        + MAX_VEHICLE_NBT_BYTES + " bytes");
                return null;
            }
            return tag;
        } catch (RuntimeException e) {
            VSSLogger.warn("Failed to capture far vehicle NBT for " + vehicle.getType(), e);
            return null;
        }
    }

    private static byte[] captureSpawnData(Entity vehicle) {
        if (!(vehicle instanceof IEntityWithComplexSpawn spawnDataEntity)) {
            return new byte[0];
        }

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), vehicle.registryAccess());
        try {
            spawnDataEntity.writeSpawnData(buf);
            int readable = buf.readableBytes();
            if (readable > MAX_VEHICLE_SPAWN_DATA_BYTES) {
                VSSLogger.warn("Skipped far vehicle spawn data for " + vehicle.getType() + " because it exceeded "
                        + MAX_VEHICLE_SPAWN_DATA_BYTES + " bytes");
                return new byte[0];
            }
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return bytes;
        } catch (RuntimeException e) {
            VSSLogger.warn("Failed to capture far vehicle spawn data for " + vehicle.getType(), e);
            return new byte[0];
        } finally {
            buf.release();
        }
    }

    private static int estimatedNbtSize(CompoundTag tag) {
        if (tag == null) {
            return 0;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeNbt(tag);
            return buf.readableBytes();
        } catch (RuntimeException e) {
            return MAX_VEHICLE_NBT_BYTES + 1;
        } finally {
            buf.release();
        }
    }

    private static void pruneVehicleCaches(List<ServerPlayer> players) {
        Set<UUID> online = new java.util.HashSet<>();
        for (ServerPlayer player : players) {
            online.add(player.getUUID());
        }

        VEHICLE_SYNC_CACHES.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
        for (Map<UUID, VehicleSyncCache> viewerCache : VEHICLE_SYNC_CACHES.values()) {
            viewerCache.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
        }
    }

    private static final class VehicleSyncCache {
        private final List<Integer> entityIds = new ArrayList<>();
        private long lastFullDataNanos;

        private boolean shouldSendFullData(Entity entity, int index, long now) {
            return entityIds.size() <= index
                    || entityIds.get(index) != entity.getId()
                    || now - lastFullDataNanos >= FULL_VEHICLE_DATA_INTERVAL_NANOS;
        }

        private void remember(List<Entity> chain, long now, boolean sentFullData) {
            entityIds.clear();
            for (Entity entity : chain) {
                entityIds.add(entity.getId());
            }
            if (sentFullData) {
                lastFullDataNanos = now;
            }
        }
    }
}

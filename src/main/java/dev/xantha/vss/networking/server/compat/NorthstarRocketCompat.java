package dev.xantha.vss.networking.server.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.mixin.minecraft.ChunkMapEntityTrackingAccessor;
import dev.xantha.vss.mixin.minecraft.TrackedEntitySeenByAccessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class NorthstarRocketCompat {
    private static final String NORTHSTAR_NAMESPACE = "northstar";
    private static final String ROCKET_ENTITY_PATH = "rocket_contraption";
    private static final String ROCKET_ENTITY_CLASS = "com.lightning.northstar.contraption.rocket.RocketContraptionEntity";
    private static final String PACKETS_CLASS = "com.lightning.northstar.content.NorthstarPackets";
    private static final String ROCKET_SYNC_PACKET_CLASS = "com.lightning.northstar.contraption.rocket.packet.RocketContraptionSyncPacket";
    private static final String ENTITY_LOCK_PACKET_CLASS = "com.lightning.northstar.contraption.rocket.packet.EntityLockPacket";
    private static final String ENTITY_LOCK_INFO_CLASS = "com.lightning.northstar.contraption.rocket.packet.EntityLockPacket$LockInfo";
    private static final String MUTABLE_INT_CLASS = "org.apache.commons.lang3.mutable.MutableInt";
    private static final long FULL_SYNC_INTERVAL_NANOS = 2_000_000_000L;
    private static final int MIRRORED_LOCK_TICKS = 40;
    private static final Map<UUID, Map<Integer, RocketViewState>> TRACKED = new HashMap<>();
    private static final Map<UUID, Set<Integer>> ACTIVE_THIS_BROADCAST = new HashMap<>();
    private static volatile Reflection reflection;
    private static volatile LockReflection lockReflection;
    private static volatile boolean reflectionUnavailable;
    private static volatile boolean lockReflectionUnavailable;

    private NorthstarRocketCompat() {
    }

    public static boolean isRocketEntity(ResourceLocation entityTypeId, Entity entity) {
        if (entityTypeId != null
                && NORTHSTAR_NAMESPACE.equals(entityTypeId.getNamespace())
                && ROCKET_ENTITY_PATH.equals(entityTypeId.getPath())) {
            return true;
        }
        return entity != null && ROCKET_ENTITY_CLASS.equals(entity.getClass().getName());
    }

    public static float finalLiftVelocity(Entity entity) {
        if (entity == null || !isRocketEntity(null, entity)) {
            return 0.0F;
        }
        Reflection resolved = reflection();
        if (resolved == null) {
            return 0.0F;
        }
        try {
            return resolved.finalLiftVelocityField.getFloat(entity);
        } catch (IllegalAccessException | RuntimeException e) {
            VSSLogger.warn("Failed to read Northstar rocket final lift velocity", e);
            return 0.0F;
        }
    }

    public static synchronized void beginViewer(ServerPlayer viewer) {
        if (viewer != null) {
            ACTIVE_THIS_BROADCAST.put(viewer.getUUID(), new HashSet<>());
        }
    }

    public static synchronized void finishViewer(ServerPlayer viewer) {
        if (viewer == null) {
            return;
        }
        Set<Integer> activeIds = ACTIVE_THIS_BROADCAST.remove(viewer.getUUID());
        prune(viewer, activeIds != null ? activeIds : Set.of());
    }

    public static synchronized void pruneViewers(List<ServerPlayer> players) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : players) {
            if (player != null) {
                online.add(player.getUUID());
            }
        }
        TRACKED.keySet().removeIf(uuid -> !online.contains(uuid));
        ACTIVE_THIS_BROADCAST.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    public static synchronized void sync(ServerPlayer viewer, ServerPlayer target, List<Entity> chain) {
        if (viewer == null || chain == null || chain.isEmpty()) {
            return;
        }

        Set<Integer> activeIds = ACTIVE_THIS_BROADCAST.computeIfAbsent(viewer.getUUID(), ignored -> new HashSet<>());
        for (Entity vehicle : chain) {
            if (!isRocketEntity(null, vehicle) || vehicle.isRemoved()) {
                continue;
            }
            activeIds.add(vehicle.getId());
            syncRocket(viewer, target, vehicle);
        }
    }

    public static synchronized void clear(ServerPlayer viewer) {
        if (viewer == null) {
            return;
        }
        Map<Integer, RocketViewState> states = TRACKED.remove(viewer.getUUID());
        if (states == null || states.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, RocketViewState> entry : states.entrySet()) {
            removeIfVssOwned(viewer, entry.getKey(), entry.getValue());
        }
    }

    public static synchronized void clearAll() {
        ACTIVE_THIS_BROADCAST.clear();
        TRACKED.clear();
    }

    public static synchronized void removeAll() {
        if (TRACKED.isEmpty() && ACTIVE_THIS_BROADCAST.isEmpty()) {
            return;
        }
        ACTIVE_THIS_BROADCAST.clear();
        for (Map.Entry<UUID, Map<Integer, RocketViewState>> entry : TRACKED.entrySet()) {
            ServerPlayer player = findPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            for (Map.Entry<Integer, RocketViewState> rocketEntry : entry.getValue().entrySet()) {
                removeIfVssOwned(player, rocketEntry.getKey(), rocketEntry.getValue());
            }
        }
        TRACKED.clear();
    }

    private static void syncRocket(ServerPlayer viewer, ServerPlayer target, Entity rocket) {
        if (isVanillaTracking(viewer, rocket)) {
            Map<Integer, RocketViewState> viewerStates = TRACKED.get(viewer.getUUID());
            if (viewerStates != null && viewerStates.remove(rocket.getId()) != null && viewerStates.isEmpty()) {
                TRACKED.remove(viewer.getUUID());
            }
            return;
        }

        Map<Integer, RocketViewState> viewerStates = TRACKED.computeIfAbsent(viewer.getUUID(), ignored -> new HashMap<>());
        RocketViewState state = viewerStates.computeIfAbsent(rocket.getId(), ignored -> new RocketViewState());
        long now = System.nanoTime();
        state.entity = rocket;
        boolean syncStateChanged = state.updateSyncState(captureRocketSyncState(rocket));

        if (!state.spawned) {
            if (!sendPairingData(viewer, rocket)) {
                return;
            }
            state.spawned = true;
            state.lastFullSyncNanos = 0L;
        } else {
            try {
                viewer.connection.send(new ClientboundTeleportEntityPacket(rocket));
            } catch (RuntimeException e) {
                VSSLogger.warn("Failed to teleport Northstar rocket for far player viewer", e);
            }
        }

        if (syncStateChanged || now - state.lastFullSyncNanos >= FULL_SYNC_INTERVAL_NANOS) {
            sendNorthstarSync(viewer, rocket);
            state.lastFullSyncNanos = now;
        }
        sendNorthstarLock(viewer, target, rocket);
    }

    private static void prune(ServerPlayer viewer, Set<Integer> activeIds) {
        Map<Integer, RocketViewState> states = TRACKED.get(viewer.getUUID());
        if (states == null || states.isEmpty()) {
            return;
        }
        Iterator<Integer> iterator = states.keySet().iterator();
        while (iterator.hasNext()) {
            Integer entityId = iterator.next();
            if (!activeIds.contains(entityId)) {
                removeIfVssOwned(viewer, entityId, states.get(entityId));
                iterator.remove();
            }
        }
        if (states.isEmpty()) {
            TRACKED.remove(viewer.getUUID());
        }
    }

    private static boolean isVanillaTracking(ServerPlayer viewer, Entity rocket) {
        if (viewer == null || rocket == null || !(rocket.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return false;
        }
        try {
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object trackedEntity = ((ChunkMapEntityTrackingAccessor) chunkMap).vss$getEntityMap().get(rocket.getId());
            if (!(trackedEntity instanceof TrackedEntitySeenByAccessor accessor)) {
                return false;
            }
            Set<ServerPlayerConnection> seenBy = accessor.vss$getSeenBy();
            return seenBy != null && seenBy.contains(viewer.connection);
        } catch (RuntimeException e) {
            VSSLogger.warn("Failed to inspect vanilla tracking state for Northstar rocket", e);
            return false;
        }
    }

    private static boolean sendPairingData(ServerPlayer viewer, Entity rocket) {
        java.util.List<Packet<? super ClientGamePacketListener>> packets = new java.util.ArrayList<>();
        try {
            ServerEntity serverEntity = new ServerEntity(
                    viewer.serverLevel(),
                    rocket,
                    rocket.getType().updateInterval(),
                    rocket.getType().trackDeltas(),
                    ignored -> {
                    });
            serverEntity.sendPairingData(viewer, new PacketAndPayloadAcceptor<>(packets::add));
            viewer.connection.send(new ClientboundBundlePacket(packets));
            return true;
        } catch (RuntimeException e) {
            VSSLogger.warn("Failed to send Northstar rocket pairing data", e);
            return false;
        }
    }

    private static ServerPlayer findPlayer(UUID uuid) {
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.getPlayerList().getPlayer(uuid) : null;
    }

    private static void removeIfVssOwned(ServerPlayer viewer, int entityId, RocketViewState state) {
        if (viewer == null) {
            return;
        }
        Entity entity = state != null ? state.entity : null;
        if (entity != null && isVanillaTracking(viewer, entity)) {
            return;
        }
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(entityId));
    }

    private static void sendNorthstarSync(ServerPlayer viewer, Entity rocket) {
        Reflection resolved = reflection();
        if (resolved == null || resolved.syncPacketConstructor == null) {
            return;
        }
        try {
            Object packet = resolved.syncPacketConstructor.newInstance(
                    rocket.getId(),
                    rocket.position(),
                    resolved.liftVelocityField.getFloat(rocket),
                    ((Integer) resolved.getLaunchTimeMethod.invoke(rocket)).intValue(),
                    resolved.launchingModeField.getBoolean(rocket),
                    resolved.landingModeField.getBoolean(rocket),
                    resolved.blastingField.getBoolean(rocket),
                    resolved.slowingField.getBoolean(rocket),
                    ((Boolean) resolved.isActiveLaunchMethod.invoke(rocket)).booleanValue(),
                    ((Boolean) resolved.isInFlightMethod.invoke(rocket)).booleanValue());
            sendNorthstarPacket(viewer, packet, resolved);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to send Northstar rocket sync packet", e);
        }
    }

    private static void sendNorthstarLock(ServerPlayer viewer, ServerPlayer target, Entity rocket) {
        if (target == null) {
            return;
        }
        Reflection resolved = reflection();
        LockReflection lockResolved = lockReflection();
        if (resolved == null || lockResolved == null) {
            return;
        }
        try {
            Object lockMapValue = lockResolved.entityLockMapField.get(rocket);
            if (!(lockMapValue instanceof Map<?, ?> lockMap)) {
                return;
            }
            Object sourceLockInfo = lockMap.get(target.getUUID());
            if (sourceLockInfo == null) {
                return;
            }
            Vec3 offset = (Vec3) lockResolved.lockInfoOffsetMethod.invoke(sourceLockInfo);
            Object sourceTicks = lockResolved.lockInfoTicksMethod.invoke(sourceLockInfo);
            int ticks = ((Integer) lockResolved.mutableIntIntValueMethod.invoke(sourceTicks)).intValue();
            Object mirroredTicks = lockResolved.mutableIntConstructor.newInstance(Math.max(ticks, MIRRORED_LOCK_TICKS));
            Object mirroredLockInfo = lockResolved.lockInfoConstructor.newInstance(offset, mirroredTicks);
            Object packet = lockResolved.entityLockPacketConstructor.newInstance(
                    farPlayerSyntheticUuid(target.getUUID()),
                    rocket.getId(),
                    mirroredLockInfo);
            sendNorthstarPacket(viewer, packet, resolved);
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to mirror Northstar rocket passenger lock", e);
        }
    }

    private static void sendNorthstarPacket(ServerPlayer viewer, Object packet, Reflection resolved)
            throws ReflectiveOperationException {
        if (packet instanceof net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
            PacketDistributor.sendToPlayer(viewer, payload);
            return;
        }
        if (resolved.channelSendToPlayerMethod != null) {
            resolved.channelSendToPlayerMethod.invoke(resolved.getChannelMethod.invoke(null), viewer, packet);
        }
    }

    private static UUID farPlayerSyntheticUuid(UUID uuid) {
        return UUID.nameUUIDFromBytes(("vss:far-player:" + uuid).getBytes(StandardCharsets.UTF_8));
    }

    private static RocketSyncState captureRocketSyncState(Entity rocket) {
        Reflection resolved = reflection();
        if (resolved == null) {
            return null;
        }
        try {
            return new RocketSyncState(
                    quantizeVelocity(resolved.liftVelocityField.getFloat(rocket)),
                    quantizeVelocity(resolved.finalLiftVelocityField.getFloat(rocket)),
                    ((Integer) resolved.getLaunchTimeMethod.invoke(rocket)).intValue(),
                    resolved.launchingModeField.getBoolean(rocket),
                    resolved.landingModeField.getBoolean(rocket),
                    resolved.blastingField.getBoolean(rocket),
                    resolved.slowingField.getBoolean(rocket),
                    ((Boolean) resolved.isActiveLaunchMethod.invoke(rocket)).booleanValue(),
                    ((Boolean) resolved.isInFlightMethod.invoke(rocket)).booleanValue());
        } catch (ReflectiveOperationException | RuntimeException e) {
            VSSLogger.warn("Failed to read Northstar rocket sync state", e);
            return null;
        }
    }

    private static int quantizeVelocity(float velocity) {
        return Math.round(velocity * 1000.0F);
    }

    private static Reflection reflection() {
        if (reflectionUnavailable) {
            return null;
        }
        Reflection cached = reflection;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> rocketClass = Class.forName(ROCKET_ENTITY_CLASS);
            Field liftVelocityField = rocketClass.getField("lift_vel");
            Field finalLiftVelocityField = rocketClass.getField("final_lift_vel");
            Field launchingModeField = rocketClass.getField("launchingMode");
            Field landingModeField = rocketClass.getField("landingMode");
            Field blastingField = rocketClass.getField("blasting");
            Field slowingField = rocketClass.getField("slowing");
            Method getLaunchTimeMethod = rocketClass.getMethod("getLaunchTime");
            Method isActiveLaunchMethod = rocketClass.getMethod("isActiveLaunch");
            Method isInFlightMethod = rocketClass.getMethod("isInFlight");
            Constructor<?> syncPacketConstructor = null;
            Method getChannelMethod = null;
            Method channelSendToPlayerMethod = null;
            try {
                Class<?> packetsClass = Class.forName(PACKETS_CLASS);
                Class<?> syncPacketClass = Class.forName(ROCKET_SYNC_PACKET_CLASS);
                syncPacketConstructor = syncPacketClass.getConstructor(
                        int.class,
                        Vec3.class,
                        float.class,
                        int.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        boolean.class);
                getChannelMethod = packetsClass.getMethod("getChannel");
                Object channel = getChannelMethod.invoke(null);
                channelSendToPlayerMethod = findSendToPlayerMethod(channel.getClass(), syncPacketClass);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 1.21 Northstar ports may use NeoForge custom payloads or rename their channel.
            }
            Reflection resolved = new Reflection(
                    syncPacketConstructor,
                    liftVelocityField,
                    finalLiftVelocityField,
                    launchingModeField,
                    landingModeField,
                    blastingField,
                    slowingField,
                    getLaunchTimeMethod,
                    isActiveLaunchMethod,
                    isInFlightMethod,
                    getChannelMethod,
                    channelSendToPlayerMethod);
            reflection = resolved;
            return resolved;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionUnavailable = true;
            VSSLogger.warn("Northstar rocket compat is unavailable", e);
            return null;
        }
    }

    private static Method findSendToPlayerMethod(Class<?> channelClass, Class<?> packetClass) {
        for (Method method : channelClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2
                    && ServerPlayer.class.isAssignableFrom(parameters[0])
                    && parameters[1].isAssignableFrom(packetClass)) {
                return method;
            }
        }
        return null;
    }

    private static LockReflection lockReflection() {
        if (lockReflectionUnavailable) {
            return null;
        }
        LockReflection cached = lockReflection;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> rocketClass = Class.forName(ROCKET_ENTITY_CLASS);
            Class<?> entityLockPacketClass = Class.forName(ENTITY_LOCK_PACKET_CLASS);
            Class<?> lockInfoClass = Class.forName(ENTITY_LOCK_INFO_CLASS);
            Class<?> mutableIntClass = Class.forName(MUTABLE_INT_CLASS);
            Constructor<?> entityLockPacketConstructor = entityLockPacketClass.getConstructor(
                    UUID.class,
                    int.class,
                    lockInfoClass);
            Constructor<?> lockInfoConstructor = lockInfoClass.getConstructor(
                    Vec3.class,
                    mutableIntClass);
            Constructor<?> mutableIntConstructor = mutableIntClass.getConstructor(int.class);
            Field entityLockMapField = rocketClass.getField("entityLockMap");
            Method lockInfoOffsetMethod = lockInfoClass.getMethod("offset");
            Method lockInfoTicksMethod = lockInfoClass.getMethod("ticks");
            Method mutableIntIntValueMethod = mutableIntClass.getMethod("intValue");
            LockReflection resolved = new LockReflection(
                    entityLockPacketConstructor,
                    lockInfoConstructor,
                    mutableIntConstructor,
                    entityLockMapField,
                    lockInfoOffsetMethod,
                    lockInfoTicksMethod,
                    mutableIntIntValueMethod);
            lockReflection = resolved;
            return resolved;
        } catch (ReflectiveOperationException | RuntimeException e) {
            lockReflectionUnavailable = true;
            VSSLogger.warn("Northstar rocket passenger lock mirroring is unavailable", e);
            return null;
        }
    }

    private static final class RocketViewState {
        private boolean spawned;
        private long lastFullSyncNanos;
        private Entity entity;
        private RocketSyncState lastSyncState;

        private boolean updateSyncState(RocketSyncState next) {
            if (next == null) {
                return false;
            }
            if (!next.equals(lastSyncState)) {
                lastSyncState = next;
                return true;
            }
            return false;
        }
    }

    private record RocketSyncState(
            int liftVelocity,
            int finalLiftVelocity,
            int launchTime,
            boolean launchingMode,
            boolean landingMode,
            boolean blasting,
            boolean slowing,
            boolean activeLaunch,
            boolean inFlight) {
    }

    private record Reflection(
            Constructor<?> syncPacketConstructor,
            Field liftVelocityField,
            Field finalLiftVelocityField,
            Field launchingModeField,
            Field landingModeField,
            Field blastingField,
            Field slowingField,
            Method getLaunchTimeMethod,
            Method isActiveLaunchMethod,
            Method isInFlightMethod,
            Method getChannelMethod,
            Method channelSendToPlayerMethod) {
    }

    private record LockReflection(
            Constructor<?> entityLockPacketConstructor,
            Constructor<?> lockInfoConstructor,
            Constructor<?> mutableIntConstructor,
            Field entityLockMapField,
            Method lockInfoOffsetMethod,
            Method lockInfoTicksMethod,
            Method mutableIntIntValueMethod) {
    }
}

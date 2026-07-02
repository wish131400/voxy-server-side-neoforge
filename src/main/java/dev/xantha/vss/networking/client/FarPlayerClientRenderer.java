package dev.xantha.vss.networking.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.lwjgl.opengl.GL11;

public final class FarPlayerClientRenderer {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long STALE_AFTER_NANOS = 3_000L * NANOS_PER_MILLI;
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000L * NANOS_PER_MILLI;
    private static final long MIN_INTERPOLATION_NANOS = 50L * NANOS_PER_MILLI;
    private static final long MAX_INTERPOLATION_NANOS = 1_000L * NANOS_PER_MILLI;
    private static final double TELEPORT_DISTANCE_SQR = 32.0D * 32.0D;
    private static final double VANILLA_HANDOFF_DISTANCE_BLOCKS = VSSConstants.FAR_PLAYER_SYNC_START_BLOCKS;
    private static final double VANILLA_HANDOFF_DISTANCE_SQR = VANILLA_HANDOFF_DISTANCE_BLOCKS * VANILLA_HANDOFF_DISTANCE_BLOCKS;
    private static final double VANILLA_HANDOFF_RELEASE_DISTANCE_BLOCKS = Math.max(8.0D, VANILLA_HANDOFF_DISTANCE_BLOCKS - 8.0D);
    private static final double VANILLA_HANDOFF_RELEASE_DISTANCE_SQR = VANILLA_HANDOFF_RELEASE_DISTANCE_BLOCKS * VANILLA_HANDOFF_RELEASE_DISTANCE_BLOCKS;
    private static final double VANILLA_HANDOFF_VERTICAL_BLOCKS = VSSConstants.FAR_PLAYER_VERTICAL_HANDOFF_BLOCKS;
    private static final double VANILLA_HANDOFF_RELEASE_VERTICAL_BLOCKS = Math.max(4.0D, VANILLA_HANDOFF_VERTICAL_BLOCKS - 4.0D);
    private static final int APPROXIMATE_SWING_DURATION_TICKS = 6;
    private static final int ENTITY_ID_BASE = -2_000_000_000;
    private static final int VEHICLE_ENTITY_ID_BASE = -1_500_000_000;
    private static final ResourceLocation NORTHSTAR_ROCKET_CONTRAPTION = ResourceLocation.fromNamespaceAndPath("northstar", "rocket_contraption");
    private static final Map<UUID, FarPlayerState> FAR_PLAYERS = new HashMap<>();
    private static final Map<VehicleKey, FarVehicleState> FAR_VEHICLES = new HashMap<>();
    private static final Set<ResourceLocation> FAILED_VEHICLE_TYPES = new HashSet<>();
    private static long nextClientDiagnosticNanos;
    private static boolean manualFarPlayerRender;

    private FarPlayerClientRenderer() {
    }

    public static void handleFarPlayers(FarPlayersS2CPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer localPlayer = mc.player;
        if (level == null || localPlayer == null) {
            return;
        }

        long now = System.nanoTime();
        Set<UUID> seen = new HashSet<>();
        for (FarPlayersS2CPayload.Entry entry : payload.entries()) {
            if (entry.uuid().equals(localPlayer.getUUID())) {
                FarPlayerState selfState = FAR_PLAYERS.remove(entry.uuid());
                if (selfState != null) {
                    selfState.removeAll();
                }
                continue;
            }
            seen.add(entry.uuid());
            FAR_PLAYERS.computeIfAbsent(entry.uuid(), uuid -> new FarPlayerState(entry))
                    .update(level, entry, now);
        }

        Iterator<Map.Entry<UUID, FarPlayerState>> iterator = FAR_PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FarPlayerState> entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                FarPlayerState state = entry.getValue();
                if (state.shouldRemoveAfterMissing(level, localPlayer)) {
                    state.removeAll();
                    iterator.remove();
                } else {
                    state.markMissing(now);
                }
            }
        }
        maybeLogClientDiagnostics(payload.entries(), now);
    }

    public static void clear() {
        for (FarPlayerState state : FAR_PLAYERS.values()) {
            state.removeAll();
        }
        FAR_PLAYERS.clear();
        clearSharedVehicles();
    }

    public static boolean isSyntheticFarPlayer(Entity entity) {
        return entity instanceof VSSRemotePlayer;
    }

    public static boolean hasActiveFarPlayerAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || pos == null || FAR_PLAYERS.isEmpty()) {
            return false;
        }

        for (FarPlayerState state : FAR_PLAYERS.values()) {
            if (state.level == level && state.shouldAllowRenderingAt(pos)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (event.getEntity() instanceof VSSRemotePlayer) {
            if (!manualFarPlayerRender) {
                event.setCanceled(true);
            }
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        FarPlayerState state = FAR_PLAYERS.get(event.getEntity().getUUID());
        if (state != null) {
            if (state.hasRenderablePlayer(level)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }

        long now = System.nanoTime();
        Set<FarVehicleState> tickedVehicles = new HashSet<>();
        Iterator<Map.Entry<UUID, FarPlayerState>> iterator = FAR_PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            FarPlayerState state = iterator.next().getValue();
            if (now - state.lastSeenNanos > STALE_AFTER_NANOS) {
                state.removeAll();
                iterator.remove();
                continue;
            }

            if (state.level == level) {
                state.ensureEntityState(level);
                state.tickAnimation(now, tickedVehicles);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            renderFarPlayers(event);
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || FAR_PLAYERS.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        Set<FarVehicleState> appliedVehicles = new HashSet<>();
        for (FarPlayerState state : FAR_PLAYERS.values()) {
            if (state.level == level && state.hasRenderableObjects(level)) {
                state.apply(now, appliedVehicles);
            }
        }
    }

    private static void renderFarPlayers(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || FAR_PLAYERS.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        Vec3 cameraPosition = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean renderedAny = false;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        try {
            Set<FarVehicleState> appliedVehicles = new HashSet<>();
            Set<FarVehicleState> renderedVehicles = new HashSet<>();
            for (FarPlayerState state : FAR_PLAYERS.values()) {
                if (state.level == level && state.hasRenderableObjects(level)) {
                    state.apply(now, appliedVehicles);
                    state.renderManually(event.getPoseStack(), buffers, event.getPartialTick().getGameTimeDeltaPartialTick(false), cameraPosition, renderedVehicles);
                    renderedAny = true;
                }
            }
            if (renderedAny) {
                buffers.endBatch();
            }
        } finally {
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(depthMask);
            if (!depthTest) {
                RenderSystem.disableDepthTest();
            }
        }
    }

    private static boolean hasVanillaPlayer(ClientLevel level, UUID uuid, int vssEntityId) {
        for (AbstractClientPlayer player : level.players()) {
            if (uuid.equals(player.getUUID()) && player.getId() != vssEntityId) {
                return true;
            }
        }
        return false;
    }

    private static int farPlayerEntityId(UUID uuid) {
        return ENTITY_ID_BASE + (uuid.hashCode() & 0x0FFFFFFF);
    }

    private static UUID farPlayerSyntheticUuid(UUID uuid) {
        return UUID.nameUUIDFromBytes(("vss:far-player:" + uuid).getBytes(StandardCharsets.UTF_8));
    }

    private static int farVehicleEntityId(int sourceEntityId, int index) {
        int hash = 31 * sourceEntityId + index;
        return VEHICLE_ENTITY_ID_BASE + (hash & 0x0FFFFFFF);
    }

    private static UUID farVehicleSyntheticUuid(int sourceEntityId, int index, ResourceLocation entityTypeId) {
        return UUID.nameUUIDFromBytes(("vss:far-player-vehicle:" + sourceEntityId + ":" + index + ":" + entityTypeId).getBytes(StandardCharsets.UTF_8));
    }

    private static FarVehicleState acquireVehicle(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot, int index) {
        VehicleKey key = new VehicleKey(snapshot.sourceEntityId(), snapshot.entityTypeId(), index);
        FarVehicleState state = FAR_VEHICLES.get(key);
        if (state != null && !state.canReuse(level)) {
            FAR_VEHICLES.remove(key);
            state.remove();
            state = null;
        }
        if (state == null) {
            state = new FarVehicleState(key);
            FAR_VEHICLES.put(key, state);
        }
        state.retain();
        return state;
    }

    private static void releaseVehicle(FarVehicleState state) {
        if (state == null) {
            return;
        }
        if (state.release()) {
            FAR_VEHICLES.remove(state.key(), state);
            state.remove();
        }
    }

    private static void clearSharedVehicles() {
        for (FarVehicleState vehicle : FAR_VEHICLES.values()) {
            vehicle.remove();
        }
        FAR_VEHICLES.clear();
    }

    private static boolean isNorthstarRocket(ResourceLocation entityTypeId) {
        return NORTHSTAR_ROCKET_CONTRAPTION.equals(entityTypeId);
    }

    private static void setFallFlyingFlag(Entity entity, boolean fallFlying) {
        if ((Object) entity instanceof FallFlyingFlagAccess fallFlyingFlags) {
            fallFlyingFlags.vss$setFallFlyingFlag(fallFlying);
        }
    }

    private static int entityLight(Entity entity) {
        if (entity == null || entity.level() == null) {
            return LightTexture.FULL_BRIGHT;
        }
        int light = LevelRenderer.getLightColor(entity.level(), entity.blockPosition());
        if (light == 0 && isCreateContraptionPassenger(entity)) {
            return LightTexture.pack(4, 15);
        }
        return light;
    }

    private static boolean isCreateContraptionPassenger(Entity entity) {
        for (Entity vehicle = entity.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle()) {
            ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
            if ("create".equals(entityTypeId.getNamespace()) || vehicle.getClass().getName().toLowerCase().contains("contraption")) {
                return true;
            }
        }
        return false;
    }

    private static void maybeLogClientDiagnostics(FarPlayersS2CPayload.Entry[] entries, long now) {
        int receivedEntries = entries != null ? entries.length : 0;
        if (receivedEntries <= 0 || now < nextClientDiagnosticNanos) {
            return;
        }
        int receivedVehicles = 0;
        for (FarPlayersS2CPayload.Entry entry : entries) {
            FarPlayersS2CPayload.VehicleSnapshot[] vehicles = entry.vehicles();
            if (vehicles != null) {
                receivedVehicles += vehicles.length;
            }
        }
        int activeEntities = 0;
        int activeVehicles = 0;
        for (FarPlayerState state : FAR_PLAYERS.values()) {
            if (state.hasRenderablePlayer(Minecraft.getInstance().level)) {
                activeEntities++;
            }
        }
        for (FarVehicleState vehicle : FAR_VEHICLES.values()) {
            if (vehicle.isRenderable()) {
                activeVehicles++;
            }
        }
        nextClientDiagnosticNanos = now + DIAGNOSTIC_INTERVAL_NANOS;
        VSSLogger.debug("Far players received: entries=" + receivedEntries
                + ", receivedVehicles=" + receivedVehicles
                + ", tracked=" + FAR_PLAYERS.size()
                + ", activeEntities=" + activeEntities
                + ", activeVehicles=" + activeVehicles);
    }

    private static final class FarPlayerState {
        private final UUID uuid;
        private final String name;
        private final int entityId;
        private ClientLevel level;
        private VSSRemotePlayer player;
        private final List<FarVehicleState> vehicles = new ArrayList<>();
        private double previousX;
        private double previousY;
        private double previousZ;
        private double targetX;
        private double targetY;
        private double targetZ;
        private float previousYaw;
        private float previousPitch;
        private float previousHeadYaw;
        private float previousBodyYaw;
        private float targetYaw;
        private float targetPitch;
        private float targetHeadYaw;
        private float targetBodyYaw;
        private long lastSeenNanos;
        private long interpolationStartNanos;
        private long interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
        private double animationX;
        private double animationY;
        private double animationZ;
        private boolean hasAnimationPosition;
        private boolean wasSwinging;
        private boolean usingItem;
        private InteractionHand usingItemHand = InteractionHand.MAIN_HAND;
        private FarPlayersS2CPayload.Entry lastEntry;

        private FarPlayerState(FarPlayersS2CPayload.Entry entry) {
            this.uuid = entry.uuid();
            this.name = entry.name();
            this.entityId = farPlayerEntityId(entry.uuid());
        }

        private void update(ClientLevel newLevel, FarPlayersS2CPayload.Entry entry, long now) {
            if (level != newLevel) {
                removeAll();
                level = newLevel;
                player = null;
                snapTo(entry);
            } else if (lastEntry == null) {
                snapTo(entry);
            } else {
                PoseSample current = sample(now);
                if (distanceSqr(current.x, current.y, current.z, entry.x(), entry.y(), entry.z()) > TELEPORT_DISTANCE_SQR) {
                    snapTo(entry);
                } else {
                    previousX = current.x;
                    previousY = current.y;
                    previousZ = current.z;
                    previousYaw = current.yaw;
                    previousPitch = current.pitch;
                    previousHeadYaw = current.headYaw;
                    previousBodyYaw = current.bodyYaw;
                }
                targetX = entry.x();
                targetY = entry.y();
                targetZ = entry.z();
                targetYaw = entry.yaw();
                targetPitch = entry.pitch();
                targetHeadYaw = entry.headYaw();
                targetBodyYaw = entry.bodyYaw();
                long packetInterval = lastSeenNanos > 0L ? now - lastSeenNanos : MIN_INTERPOLATION_NANOS;
                interpolationStartNanos = now;
                interpolationDurationNanos = clamp(packetInterval, MIN_INTERPOLATION_NANOS, MAX_INTERPOLATION_NANOS);
            }

            lastEntry = entry;
            if (isInsideVanillaHandoffRange(newLevel)) {
                removeAll();
                lastSeenNanos = now;
                return;
            }
            ensureEntityState(newLevel);
            updateVehicles(newLevel, entry.vehicles(), now);
            applyStateFlags(entry);
            lastSeenNanos = now;
        }

        private void markMissing(long now) {
            if (now - lastSeenNanos > STALE_AFTER_NANOS / 2L) {
                lastSeenNanos = Math.min(lastSeenNanos, now - STALE_AFTER_NANOS);
            }
        }

        private boolean shouldRemoveAfterMissing(ClientLevel currentLevel, LocalPlayer localPlayer) {
            if (level != currentLevel) {
                return true;
            }
            return hasVanillaPlayer(currentLevel, uuid, entityId)
                    && isInsideVanillaHandoffRange(localPlayer);
        }

        private void ensureEntityState(ClientLevel currentLevel) {
            if (level != currentLevel) {
                removeAll();
                level = currentLevel;
                player = null;
            }

            if (isInsideVanillaHandoffRange(currentLevel)) {
                removePlayerEntity();
                return;
            }

            if (player == null || player.isRemoved()) {
                createEntity(currentLevel);
            }
        }

        private boolean isVssEntityActive(ClientLevel currentLevel) {
            return player != null && !player.isRemoved() && currentLevel.getEntity(entityId) == player;
        }

        private boolean hasRenderablePlayer(ClientLevel currentLevel) {
            return level == currentLevel && player != null && !player.isRemoved();
        }

        private boolean hasRenderableObjects(ClientLevel currentLevel) {
            return hasRenderablePlayer(currentLevel) || activeVehicleCount() > 0;
        }

        private int activeVehicleCount() {
            int count = 0;
            for (FarVehicleState vehicle : vehicles) {
                if (vehicle.isRenderable()) {
                    count++;
                }
            }
            return count;
        }

        private boolean isAtBlockPos(BlockPos pos) {
            return player != null
                    && player.getBlockX() == pos.getX()
                    && player.getBlockY() == pos.getY()
                    && player.getBlockZ() == pos.getZ();
        }

        private boolean shouldAllowRenderingAt(BlockPos pos) {
            if (hasRenderablePlayer(level) && isAtBlockPos(pos)) {
                return true;
            }
            for (FarVehicleState vehicle : vehicles) {
                if (vehicle.isAtBlockPos(pos)) {
                    return true;
                }
            }
            return lastEntry != null
                    && Math.abs(Mth.floor(targetX) - pos.getX()) <= 2
                    && Math.abs(Mth.floor(targetY) - pos.getY()) <= 3
                    && Math.abs(Mth.floor(targetZ) - pos.getZ()) <= 2;
        }

        private boolean isInsideVanillaHandoffRange(ClientLevel currentLevel) {
            LocalPlayer localPlayer = Minecraft.getInstance().player;
            if (localPlayer == null || !hasVanillaPlayer(currentLevel, uuid, entityId)) {
                return false;
            }
            return isInsideVanillaHandoffRange(localPlayer);
        }

        private boolean isInsideVanillaHandoffRange(LocalPlayer localPlayer) {
            return horizontalDistanceSqr(localPlayer.getX(), localPlayer.getZ(), targetX, targetZ) <= VANILLA_HANDOFF_RELEASE_DISTANCE_SQR
                    && Math.abs(localPlayer.getY() - targetY) <= VANILLA_HANDOFF_RELEASE_VERTICAL_BLOCKS;
        }

        private void createEntity(ClientLevel currentLevel) {
            if (lastEntry == null) {
                return;
            }

            UUID syntheticUuid = farPlayerSyntheticUuid(uuid);
            player = new VSSRemotePlayer(currentLevel, new GameProfile(syntheticUuid, name), uuid);
            player.setId(entityId);
            player.setUUID(syntheticUuid);
            player.setNoGravity(true);
            player.noCulling = true;
            player.setCustomName(Component.literal(name));
            player.setCustomNameVisible(true);
            applyImmediately(sample(System.nanoTime()));
            applyStateFlags(lastEntry);
            currentLevel.addEntity(player);
            VSSLogger.debug("Far player entity created: " + name + " at "
                    + player.getBlockX() + "," + player.getBlockY() + "," + player.getBlockZ());
        }

        private void renderManually(
                PoseStack poseStack,
                MultiBufferSource.BufferSource buffers,
                float partialTick,
                Vec3 cameraPosition,
                Set<FarVehicleState> renderedVehicles) {
            manualFarPlayerRender = true;
            try {
                for (int i = vehicles.size() - 1; i >= 0; i--) {
                    FarVehicleState vehicle = vehicles.get(i);
                    if (renderedVehicles.add(vehicle)) {
                        vehicle.renderManually(poseStack, buffers, partialTick, cameraPosition);
                    }
                }
                if (player == null || player.isRemoved()) {
                    return;
                }
                Minecraft.getInstance().getEntityRenderDispatcher().render(
                        player,
                        player.getX() - cameraPosition.x(),
                        player.getY() - cameraPosition.y(),
                        player.getZ() - cameraPosition.z(),
                        player.getYRot(),
                        partialTick,
                        poseStack,
                        buffers,
                        entityLight(player));
            } finally {
                manualFarPlayerRender = false;
            }
        }

        private void removeAll() {
            removeVehicles();
            removePlayerEntity();
        }

        private void removePlayerEntity() {
            if (level != null && player != null) {
                Entity entity = level.getEntity(entityId);
                if (entity == player) {
                    level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
                } else if (!player.isRemoved()) {
                    player.setRemoved(Entity.RemovalReason.DISCARDED);
                    player.onClientRemoval();
                }
            }
            player = null;
            usingItem = false;
            usingItemHand = InteractionHand.MAIN_HAND;
            wasSwinging = false;
        }

        private void removeVehicles() {
            if (player != null && player.isPassenger()) {
                player.stopRiding();
            }
            for (FarVehicleState vehicle : vehicles) {
                releaseVehicle(vehicle);
            }
            vehicles.clear();
        }

        private void tickAnimation(long now, Set<FarVehicleState> tickedVehicles) {
            for (FarVehicleState vehicle : vehicles) {
                if (tickedVehicles.add(vehicle)) {
                    vehicle.tick();
                }
            }
            if (player == null || player.isRemoved()) {
                return;
            }

            PoseSample sample = sample(now);
            double oldX = hasAnimationPosition ? animationX : sample.x;
            double oldY = hasAnimationPosition ? animationY : sample.y;
            double oldZ = hasAnimationPosition ? animationZ : sample.z;
            double dx = sample.x - oldX;
            double dz = sample.z - oldZ;
            float movement = (float) Math.sqrt(dx * dx + dz * dz);

            player.walkAnimation.update(Math.min(movement * 4.0F, 1.0F), 0.4F);
            player.setDeltaMovement(dx, sample.y - oldY, dz);
            player.tickCount++;
            tickSyncedActionState();
            stabilizeMovementState();
            stabilizeCloakState();
            stabilizePoseState();

            animationX = sample.x;
            animationY = sample.y;
            animationZ = sample.z;
            hasAnimationPosition = true;
        }

        private void apply(long now, Set<FarVehicleState> appliedVehicles) {
            for (FarVehicleState vehicle : vehicles) {
                if (appliedVehicles.add(vehicle)) {
                    vehicle.apply(now);
                }
            }
            if (player == null || player.isRemoved()) {
                return;
            }
            applyImmediately(sample(now));
            syncVehiclePassengerState();
        }

        private void applyImmediately(PoseSample sample) {
            if (player == null) {
                return;
            }
            player.xo = sample.x;
            player.yo = sample.y;
            player.zo = sample.z;
            player.xOld = sample.x;
            player.yOld = sample.y;
            player.zOld = sample.z;
            player.yRotO = sample.yaw;
            player.xRotO = sample.pitch;
            player.yBodyRotO = sample.bodyYaw;
            player.yHeadRotO = sample.headYaw;
            player.syncPacketPositionCodec(sample.x, sample.y, sample.z);
            player.setPos(sample.x, sample.y, sample.z);
            player.setYRot(sample.yaw);
            player.setXRot(sample.pitch);
            player.setYBodyRot(sample.bodyYaw);
            player.setYHeadRot(sample.headYaw);
            stabilizeMovementState();
            stabilizeCloakState();
            stabilizePoseState();
        }

        private PoseSample sample(long now) {
            float progress = interpolationProgress(now);
            return new PoseSample(
                    Mth.lerp(progress, previousX, targetX),
                    Mth.lerp(progress, previousY, targetY),
                    Mth.lerp(progress, previousZ, targetZ),
                    Mth.rotLerp(progress, previousYaw, targetYaw),
                    Mth.lerp(progress, previousPitch, targetPitch),
                    Mth.rotLerp(progress, previousHeadYaw, targetHeadYaw),
                    Mth.rotLerp(progress, previousBodyYaw, targetBodyYaw));
        }

        private float interpolationProgress(long now) {
            if (interpolationDurationNanos <= 0L) {
                return 1.0F;
            }
            long elapsed = now - interpolationStartNanos;
            return Mth.clamp(elapsed / (float) interpolationDurationNanos, 0.0F, 1.0F);
        }

        private void snapTo(FarPlayersS2CPayload.Entry entry) {
            previousX = entry.x();
            previousY = entry.y();
            previousZ = entry.z();
            targetX = entry.x();
            targetY = entry.y();
            targetZ = entry.z();
            previousYaw = entry.yaw();
            previousPitch = entry.pitch();
            previousHeadYaw = entry.headYaw();
            previousBodyYaw = entry.bodyYaw();
            targetYaw = entry.yaw();
            targetPitch = entry.pitch();
            targetHeadYaw = entry.headYaw();
            targetBodyYaw = entry.bodyYaw();
            animationX = entry.x();
            animationY = entry.y();
            animationZ = entry.z();
            hasAnimationPosition = true;
            interpolationStartNanos = System.nanoTime();
            interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
            if (player != null) {
                applyImmediately(sample(interpolationStartNanos));
                applyStateFlags(entry);
            }
        }

        private void applyStateFlags(FarPlayersS2CPayload.Entry entry) {
            if (player == null) {
                return;
            }
            applyEquipment(entry);
            player.setMainArm(entry.mainArm());
            player.setOnGround(entry.onGround());
            player.setSharedFlagOnFire(entry.onFire());
            player.setInvisible(entry.invisible());
            player.setGlowingTag(entry.glowing());
            player.setShiftKeyDown(entry.crouching() || entry.pose() == Pose.CROUCHING);
            player.setSprinting(entry.sprinting());
            player.setSwimming(entry.swimming());
            setFallFlyingFlag(player, entry.pose() == Pose.FALL_FLYING);
            player.setPose(entry.pose());
            applyUseItemState(entry);
            applySwingState(entry);
            player.setNoGravity(true);
            player.noCulling = true;
        }

        private void updateVehicles(ClientLevel currentLevel, FarPlayersS2CPayload.VehicleSnapshot[] snapshots, long now) {
            if (snapshots == null || snapshots.length == 0) {
                removeVehicles();
                return;
            }

            while (vehicles.size() > snapshots.length) {
                releaseVehicle(vehicles.remove(vehicles.size() - 1));
            }

            for (int i = 0; i < snapshots.length; i++) {
                FarPlayersS2CPayload.VehicleSnapshot snapshot = snapshots[i];
                if (snapshot == null || snapshot.entityTypeId() == null) {
                    while (vehicles.size() > i) {
                        releaseVehicle(vehicles.remove(vehicles.size() - 1));
                    }
                    break;
                }

                FarVehicleState vehicleState = acquireVehicle(currentLevel, snapshot, i);
                if (vehicles.size() <= i) {
                    vehicles.add(vehicleState);
                } else if (vehicles.get(i) != vehicleState) {
                    releaseVehicle(vehicles.set(i, vehicleState));
                } else {
                    releaseVehicle(vehicleState);
                }
                vehicleState.update(currentLevel, snapshot, now, lastSeenNanos);
            }
            syncVehiclePassengerState();
        }

        private void syncVehiclePassengerState() {
            if (player == null) {
                return;
            }
            Entity passenger = player;
            for (FarVehicleState vehicleState : vehicles) {
                Entity vehicle = vehicleState.entity();
                if (vehicle == null || vehicle.isRemoved()) {
                    break;
                }
                if (passenger.getVehicle() != vehicle) {
                    passenger.startRiding(vehicle, true);
                }
                passenger = vehicle;
            }
            if (vehicles.isEmpty() && player.isPassenger()) {
                player.stopRiding();
            }
        }

        private void applyEquipment(FarPlayersS2CPayload.Entry entry) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack next = entry.itemBySlot(slot);
                if (!ItemStack.matches(player.getItemBySlot(slot), next)) {
                    player.setItemSlot(slot, next.copy());
                }
            }
        }

        private void applyUseItemState(FarPlayersS2CPayload.Entry entry) {
            InteractionHand hand = entry.usedItemHand();
            if (entry.usingItem()) {
                if (!usingItem || usingItemHand != hand) {
                    player.startUsingItem(hand);
                    usingItem = true;
                    usingItemHand = hand;
                }
            } else if (usingItem) {
                player.stopUsingItem();
                usingItem = false;
                usingItemHand = InteractionHand.MAIN_HAND;
            }
        }

        private void applySwingState(FarPlayersS2CPayload.Entry entry) {
            if (entry.swinging() && !wasSwinging) {
                startLocalSwing(entry.swingingArm());
            }
            wasSwinging = entry.swinging();
        }

        private void startLocalSwing(InteractionHand hand) {
            player.swinging = true;
            player.swingingArm = hand;
            player.swingTime = -1;
            player.oAttackAnim = 0.0F;
            player.attackAnim = 0.0F;
        }

        private void tickSyncedActionState() {
            player.oAttackAnim = player.attackAnim;
            if (player.swinging) {
                player.swingTime++;
                if (player.swingTime >= APPROXIMATE_SWING_DURATION_TICKS) {
                    player.swingTime = 0;
                    player.swinging = false;
                }
            } else {
                player.swingTime = 0;
            }
            player.attackAnim = player.swingTime / (float) APPROXIMATE_SWING_DURATION_TICKS;
        }

        private void stabilizeMovementState() {
            player.oBob = 0.0F;
            player.bob = 0.0F;
            player.walkDistO = player.walkDist;
        }

        private void stabilizeCloakState() {
            if (player == null) {
                return;
            }
            player.xCloakO = player.getX();
            player.yCloakO = player.getY();
            player.zCloakO = player.getZ();
            player.xCloak = player.getX();
            player.yCloak = player.getY();
            player.zCloak = player.getZ();
        }

        private void stabilizePoseState() {
            if (player == null || lastEntry == null) {
                return;
            }
            setFallFlyingFlag(player, lastEntry.pose() == Pose.FALL_FLYING);
            player.setPose(lastEntry.pose());
        }

        private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx;
            double dy = ay - by;
            double dz = az - bz;
            return dx * dx + dy * dy + dz * dz;
        }

        private static double horizontalDistanceSqr(double ax, double az, double bx, double bz) {
            double dx = ax - bx;
            double dz = az - bz;
            return dx * dx + dz * dz;
        }

        private static long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class FarVehicleState {
        private final VehicleKey key;
        private int references;
        private ClientLevel level;
        private Entity vehicle;
        private ResourceLocation vehicleTypeId;
        private boolean externalEntity;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double targetX;
        private double targetY;
        private double targetZ;
        private float previousYaw;
        private float previousPitch;
        private float previousHeadYaw;
        private float previousBodyYaw;
        private float targetYaw;
        private float targetPitch;
        private float targetHeadYaw;
        private float targetBodyYaw;
        private long interpolationStartNanos;
        private long interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
        private float rocketFinalLiftVelocity;
        private int rocketParticleTicks;

        private FarVehicleState(VehicleKey key) {
            this.key = key;
        }

        private VehicleKey key() {
            return key;
        }

        private boolean canReuse(ClientLevel currentLevel) {
            return level == null || level == currentLevel;
        }

        private void retain() {
            references++;
        }

        private boolean release() {
            if (references > 0) {
                references--;
            }
            return references == 0;
        }

        private Entity entity() {
            return vehicle;
        }

        private boolean isRenderable() {
            return vehicle != null && !vehicle.isRemoved();
        }

        private void update(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot, long now, long lastSeenNanos) {
            boolean needsCreate = this.level != level
                    || vehicle == null
                    || vehicle.isRemoved()
                    || !snapshot.entityTypeId().equals(vehicleTypeId)
                    || (externalEntity && vehicle.getId() != snapshot.sourceEntityId());
            if (needsCreate) {
                remove();
                this.level = level;
                create(level, snapshot);
                snapTo(snapshot);
            } else {
                VehiclePoseSample current = sample(now);
                if (current == null
                        || FarPlayerState.distanceSqr(current.x, current.y, current.z, snapshot.x(), snapshot.y(), snapshot.z()) > TELEPORT_DISTANCE_SQR) {
                    snapTo(snapshot);
                } else {
                    previousX = current.x;
                    previousY = current.y;
                    previousZ = current.z;
                    previousYaw = current.yaw;
                    previousPitch = current.pitch;
                    previousHeadYaw = current.headYaw;
                    previousBodyYaw = current.bodyYaw;
                    targetX = snapshot.x();
                    targetY = snapshot.y();
                    targetZ = snapshot.z();
                    targetYaw = snapshot.yaw();
                    targetPitch = snapshot.pitch();
                    targetHeadYaw = snapshot.headYaw();
                    targetBodyYaw = snapshot.bodyYaw();
                    long packetInterval = lastSeenNanos > 0L ? now - lastSeenNanos : MIN_INTERPOLATION_NANOS;
                    interpolationStartNanos = now;
                    interpolationDurationNanos = FarPlayerState.clamp(packetInterval, MIN_INTERPOLATION_NANOS, MAX_INTERPOLATION_NANOS);
                }
            }

            applyFullData(level, snapshot);
            applyState(snapshot);
            rocketFinalLiftVelocity = snapshot.rocketFinalLiftVelocity();
            apply(now);
            NorthstarRocketClientCompat.apply(vehicle, snapshot);
        }

        private void create(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            rocketParticleTicks = 0;
            Entity existingVehicle = level.getEntity(snapshot.sourceEntityId());
            ResourceLocation existingTypeId = existingVehicle == null
                    ? null
                    : BuiltInRegistries.ENTITY_TYPE.getKey(existingVehicle.getType());
            if (existingVehicle != null
                    && !existingVehicle.isRemoved()
                    && snapshot.entityTypeId().equals(existingTypeId)) {
                vehicle = existingVehicle;
                vehicleTypeId = snapshot.entityTypeId();
                externalEntity = true;
                vehicle.noCulling = true;
                return;
            }
            vehicle = null;
            vehicleTypeId = null;
            externalEntity = false;

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(snapshot.entityTypeId()).orElse(null);
            if (type == null) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle; unknown entity type " + snapshot.entityTypeId());
                }
                return;
            }

            try {
                vehicle = type.create(level);
            } catch (RuntimeException e) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle " + snapshot.entityTypeId(), e);
                }
                vehicle = null;
                return;
            }

            if (vehicle == null) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle " + snapshot.entityTypeId());
                }
                return;
            }

            vehicleTypeId = snapshot.entityTypeId();
            externalEntity = false;
            applySyntheticIdentity(snapshot);
        }

        private void applyFullData(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            if (vehicle == null || vehicle.isRemoved() || !snapshot.fullData()) {
                return;
            }
            if (externalEntity) {
                return;
            }

            CompoundTag entityData = snapshot.entityData();
            if (entityData != null) {
                try {
                    vehicle.load(entityData.copy());
                } catch (RuntimeException e) {
                    VSSLogger.warn("Failed to load far vehicle NBT for " + snapshot.entityTypeId(), e);
                }
                applySyntheticIdentity(snapshot);
            }

            byte[] spawnData = snapshot.spawnData();
            if (spawnData != null && spawnData.length > 0 && vehicle instanceof IEntityWithComplexSpawn spawnDataEntity) {
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(spawnData), level.registryAccess());
                try {
                    spawnDataEntity.readSpawnData(buf);
                } catch (RuntimeException e) {
                    VSSLogger.warn("Failed to apply far vehicle spawn data for " + snapshot.entityTypeId(), e);
                } finally {
                    buf.release();
                }
                applySyntheticIdentity(snapshot);
            }
        }

        private void applySyntheticIdentity(FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            if (vehicle == null) {
                return;
            }
            if (externalEntity) {
                return;
            }
            vehicle.setId(farVehicleEntityId(snapshot.sourceEntityId(), key.index()));
            vehicle.setUUID(farVehicleSyntheticUuid(snapshot.sourceEntityId(), key.index(), snapshot.entityTypeId()));
            vehicle.setNoGravity(true);
            vehicle.noCulling = true;
        }

        private void applyState(FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            if (vehicle == null || vehicle.isRemoved()) {
                return;
            }
            if (externalEntity) {
                return;
            }
            vehicle.setOnGround(snapshot.onGround());
            vehicle.setSharedFlagOnFire(snapshot.onFire());
            vehicle.setInvisible(snapshot.invisible());
            vehicle.setGlowingTag(snapshot.glowing());
            vehicle.setNoGravity(true);
            vehicle.noCulling = true;
        }

        private void apply(long now) {
            VehiclePoseSample sample = sample(now);
            if (vehicle == null || vehicle.isRemoved() || sample == null) {
                return;
            }
            if (externalEntity) {
                vehicle.noCulling = true;
                return;
            }
            double oldX = vehicle.getX();
            double oldY = vehicle.getY();
            double oldZ = vehicle.getZ();
            vehicle.xo = sample.x;
            vehicle.yo = sample.y;
            vehicle.zo = sample.z;
            vehicle.xOld = sample.x;
            vehicle.yOld = sample.y;
            vehicle.zOld = sample.z;
            vehicle.yRotO = sample.yaw;
            vehicle.xRotO = sample.pitch;
            if (vehicle instanceof LivingEntity livingEntity) {
                livingEntity.yBodyRotO = sample.bodyYaw;
                livingEntity.yHeadRotO = sample.headYaw;
            }
            vehicle.syncPacketPositionCodec(sample.x, sample.y, sample.z);
            vehicle.setPos(sample.x, sample.y, sample.z);
            vehicle.setYRot(sample.yaw);
            vehicle.setXRot(sample.pitch);
            vehicle.setYHeadRot(sample.headYaw);
            if (vehicle instanceof LivingEntity livingEntity) {
                livingEntity.setYBodyRot(sample.bodyYaw);
                livingEntity.setYHeadRot(sample.headYaw);
            }
        }

        private VehiclePoseSample sample(long now) {
            if (vehicle == null || vehicle.isRemoved()) {
                return null;
            }
            float progress = interpolationProgress(now);
            return new VehiclePoseSample(
                    Mth.lerp(progress, previousX, targetX),
                    Mth.lerp(progress, previousY, targetY),
                    Mth.lerp(progress, previousZ, targetZ),
                    Mth.rotLerp(progress, previousYaw, targetYaw),
                    Mth.lerp(progress, previousPitch, targetPitch),
                    Mth.rotLerp(progress, previousHeadYaw, targetHeadYaw),
                    Mth.rotLerp(progress, previousBodyYaw, targetBodyYaw));
        }

        private float interpolationProgress(long now) {
            if (interpolationDurationNanos <= 0L) {
                return 1.0F;
            }
            long elapsed = now - interpolationStartNanos;
            return Mth.clamp(elapsed / (float) interpolationDurationNanos, 0.0F, 1.0F);
        }

        private void snapTo(FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            previousX = snapshot.x();
            previousY = snapshot.y();
            previousZ = snapshot.z();
            targetX = snapshot.x();
            targetY = snapshot.y();
            targetZ = snapshot.z();
            previousYaw = snapshot.yaw();
            previousPitch = snapshot.pitch();
            previousHeadYaw = snapshot.headYaw();
            previousBodyYaw = snapshot.bodyYaw();
            targetYaw = snapshot.yaw();
            targetPitch = snapshot.pitch();
            targetHeadYaw = snapshot.headYaw();
            targetBodyYaw = snapshot.bodyYaw();
            interpolationStartNanos = System.nanoTime();
            interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
        }

        private void tick() {
            if (vehicle != null && !vehicle.isRemoved()) {
                if (isNorthstarRocket(vehicleTypeId)) {
                    rocketParticleTicks++;
                    NorthstarRocketClientCompat.tickParticles(vehicle, rocketFinalLiftVelocity, rocketParticleTicks);
                }
                if (externalEntity) {
                    vehicle.noCulling = true;
                } else {
                    vehicle.tickCount++;
                }
            }
        }

        private void renderManually(PoseStack poseStack, MultiBufferSource.BufferSource buffers, float partialTick, Vec3 cameraPosition) {
            if (vehicle == null || vehicle.isRemoved() || externalEntity) {
                return;
            }
            Minecraft.getInstance().getEntityRenderDispatcher().render(
                    vehicle,
                    vehicle.getX() - cameraPosition.x(),
                    vehicle.getY() - cameraPosition.y(),
                    vehicle.getZ() - cameraPosition.z(),
                    vehicle.getYRot(),
                    partialTick,
                    poseStack,
                    buffers,
                    entityLight(vehicle));
        }

        private boolean isAtBlockPos(BlockPos pos) {
            return vehicle != null
                    && !vehicle.isRemoved()
                    && vehicle.getBlockX() == pos.getX()
                    && vehicle.getBlockY() == pos.getY()
                    && vehicle.getBlockZ() == pos.getZ();
        }

        private void remove() {
            if (vehicle != null && !vehicle.isRemoved()) {
                if (!externalEntity) {
                    vehicle.ejectPassengers();
                    vehicle.setRemoved(Entity.RemovalReason.DISCARDED);
                    vehicle.onClientRemoval();
                }
            }
            vehicle = null;
            level = null;
            vehicleTypeId = null;
            externalEntity = false;
            rocketFinalLiftVelocity = 0.0F;
            rocketParticleTicks = 0;
        }
    }

    private static final class VSSRemotePlayer extends RemotePlayer {
        private final UUID sourceUuid;

        private VSSRemotePlayer(ClientLevel level, GameProfile profile, UUID sourceUuid) {
            super(level, profile);
            this.sourceUuid = sourceUuid;
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSqr) {
            return true;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            return getSourcePlayerInfo();
        }

        @Override
        public PlayerSkin getSkin() {
            PlayerInfo playerInfo = getSourcePlayerInfo();
            return playerInfo != null ? playerInfo.getSkin() : DefaultPlayerSkin.get(sourceUuid);
        }

        @Override
        public boolean isModelPartShown(PlayerModelPart part) {
            return true;
        }

        private PlayerInfo getSourcePlayerInfo() {
            if (Minecraft.getInstance().getConnection() == null) {
                return null;
            }
            return Minecraft.getInstance().getConnection().getPlayerInfo(sourceUuid);
        }
    }

    private record PoseSample(double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw) {
    }

    private record VehiclePoseSample(double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw) {
    }

    private record VehicleKey(int sourceEntityId, ResourceLocation entityTypeId, int index) {
    }
}

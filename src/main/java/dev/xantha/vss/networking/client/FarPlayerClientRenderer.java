package dev.xantha.vss.networking.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.xantha.vss.compat.CuriosCompat;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.phys.AABB;
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
    private static final long MAX_INTERPOLATION_NANOS = 500L * NANOS_PER_MILLI;
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
    private static final Set<FarVehicleState> TICKED_VEHICLES = new HashSet<>();
    private static final Set<FarVehicleState> APPLIED_VEHICLES = new HashSet<>();
    private static final Set<FarVehicleState> RENDERED_VEHICLES = new HashSet<>();
    private static final LongOpenHashSet ACTIVE_RENDER_BLOCKS = new LongOpenHashSet();
    private static long nextClientDiagnosticNanos;
    private static ClientLevel activeRenderBlockLevel;
    private static boolean activeRenderBlocksDirty = true;
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
                FarPlayerState selfState = FAR_PLAYERS.get(entry.uuid());
                if (selfState != null) {
                    selfState.requestRemoval();
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
                    state.requestRemoval();
                } else {
                    state.markMissing(now);
                }
            }
        }
        markRenderBlockIndexDirty();
        maybeLogClientDiagnostics(payload.entries(), now);
    }

    public static void clear() {
        for (FarPlayerState state : FAR_PLAYERS.values()) {
            state.removeAll();
        }
        FAR_PLAYERS.clear();
        clearSharedVehicles();
        clearRenderBlockIndex();
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

        ensureRenderBlockIndex(level);
        return ACTIVE_RENDER_BLOCKS.contains(pos.asLong());
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
        List<FarPlayerState> activeStates = new ArrayList<>(FAR_PLAYERS.size());
        TICKED_VEHICLES.clear();
        APPLIED_VEHICLES.clear();
        try {
            Iterator<Map.Entry<UUID, FarPlayerState>> iterator = FAR_PLAYERS.entrySet().iterator();
            while (iterator.hasNext()) {
                FarPlayerState state = iterator.next().getValue();
                if (state.removalRequested || now - state.lastSeenNanos > STALE_AFTER_NANOS) {
                    state.removeAll();
                    iterator.remove();
                    markRenderBlockIndexDirty();
                    continue;
                }

                if (state.requestedLevel == level) {
                    if (state.prepareClientTick(level, now)) {
                        activeStates.add(state);
                    }
                }
            }
            for (FarPlayerState state : activeStates) {
                state.applyClientTick(now, TICKED_VEHICLES, APPLIED_VEHICLES);
            }
        } finally {
            TICKED_VEHICLES.clear();
            APPLIED_VEHICLES.clear();
        }
        markRenderBlockIndexDirty();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            renderFarPlayers(event);
        }
    }

    private static void renderFarPlayers(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || FAR_PLAYERS.isEmpty()) {
            return;
        }

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
            RENDERED_VEHICLES.clear();
            for (FarPlayerState state : FAR_PLAYERS.values()) {
                if (state.level == level && state.hasRenderableObjects(level)) {
                    state.renderManually(event.getPoseStack(), buffers, event.getPartialTick().getGameTimeDeltaPartialTick(false), cameraPosition, RENDERED_VEHICLES);
                    renderedAny = true;
                }
            }
            if (renderedAny) {
                buffers.endBatch();
            }
        } finally {
            RENDERED_VEHICLES.clear();
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

    private static int farVehicleEntityId(int sourceEntityId) {
        return VEHICLE_ENTITY_ID_BASE + (sourceEntityId & 0x0FFFFFFF);
    }

    private static UUID farVehicleSyntheticUuid(int sourceEntityId, ResourceLocation entityTypeId) {
        return UUID.nameUUIDFromBytes(("vss:far-player-vehicle:" + sourceEntityId + ":" + entityTypeId).getBytes(StandardCharsets.UTF_8));
    }

    private static FarVehicleState acquireVehicle(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
        VehicleKey key = new VehicleKey(snapshot.sourceEntityId(), snapshot.entityTypeId());
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

    private static void markRenderBlockIndexDirty() {
        activeRenderBlocksDirty = true;
    }

    private static void clearRenderBlockIndex() {
        ACTIVE_RENDER_BLOCKS.clear();
        activeRenderBlockLevel = null;
        activeRenderBlocksDirty = true;
    }

    private static void ensureRenderBlockIndex(ClientLevel level) {
        if (!activeRenderBlocksDirty && activeRenderBlockLevel == level) {
            return;
        }
        rebuildRenderBlockIndex(level);
    }

    private static void rebuildRenderBlockIndex(ClientLevel level) {
        ACTIVE_RENDER_BLOCKS.clear();
        activeRenderBlockLevel = level;
        activeRenderBlocksDirty = false;
        if (level == null) {
            return;
        }
        for (FarPlayerState state : FAR_PLAYERS.values()) {
            state.addRenderBlocks(level, ACTIVE_RENDER_BLOCKS);
        }
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
        private ClientLevel requestedLevel;
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
        private CompoundTag appliedCuriosData;
        private FarPlayersS2CPayload.Entry lastEntry;
        private FarPlayersS2CPayload.Entry appliedEntry;
        private boolean levelResetRequested;
        private boolean removalRequested;

        private FarPlayerState(FarPlayersS2CPayload.Entry entry) {
            this.uuid = entry.uuid();
            this.name = entry.name();
            this.entityId = farPlayerEntityId(entry.uuid());
        }

        private void update(ClientLevel newLevel, FarPlayersS2CPayload.Entry entry, long now) {
            removalRequested = false;
            if (requestedLevel != newLevel) {
                requestedLevel = newLevel;
                levelResetRequested = true;
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
                interpolationDurationNanos = clamp(packetInterval + (packetInterval / 4), MIN_INTERPOLATION_NANOS, MAX_INTERPOLATION_NANOS);
            }

            lastEntry = entry;
            lastSeenNanos = now;
        }

        private boolean prepareClientTick(ClientLevel currentLevel, long now) {
            if (levelResetRequested || level != currentLevel) {
                removeAll();
                level = currentLevel;
                levelResetRequested = false;
            }
            if (lastEntry == null) {
                return false;
            }
            if (isInsideVanillaHandoffRange(currentLevel) && !hasNorthstarRocket(lastEntry.vehicles())) {
                removeAll();
                return false;
            }

            ensureEntityState(currentLevel);
            if (appliedEntry != lastEntry) {
                updateVehicles(currentLevel, lastEntry.vehicles(), now);
            }
            return player != null && !player.isRemoved();
        }

        private void requestRemoval() {
            removalRequested = true;
        }

        private void applyClientTick(
                long now,
                Set<FarVehicleState> tickedVehicles,
                Set<FarVehicleState> appliedVehicles) {
            apply(now, appliedVehicles);
            tickAnimation(now, tickedVehicles);
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

        private void addRenderBlocks(ClientLevel currentLevel, LongOpenHashSet output) {
            if (level != currentLevel) {
                return;
            }
            if (player != null && !player.isRemoved()) {
                output.add(BlockPos.asLong(player.getBlockX(), player.getBlockY(), player.getBlockZ()));
            }
            for (FarVehicleState vehicle : vehicles) {
                vehicle.addRenderBlock(output);
            }
            if (lastEntry != null) {
                addRenderBox(output, Mth.floor(targetX), Mth.floor(targetY), Mth.floor(targetZ));
            }
        }

        private static void addRenderBox(LongOpenHashSet output, int centerX, int centerY, int centerZ) {
            for (int y = centerY - 3; y <= centerY + 3; y++) {
                for (int z = centerZ - 2; z <= centerZ + 2; z++) {
                    for (int x = centerX - 2; x <= centerX + 2; x++) {
                        output.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
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

        private boolean hasNorthstarRocket(FarPlayersS2CPayload.VehicleSnapshot[] snapshots) {
            if (snapshots == null) {
                return false;
            }
            for (FarPlayersS2CPayload.VehicleSnapshot snapshot : snapshots) {
                if (snapshot != null && isNorthstarRocket(snapshot.entityTypeId())) {
                    return true;
                }
            }
            return false;
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
            player.noPhysics = true;
            player.noCulling = true;
            player.setCustomName(Component.literal(name));
            player.setCustomNameVisible(true);
            applyImmediately(sample(System.nanoTime()));
            player.xo = player.getX();
            player.yo = player.getY();
            player.zo = player.getZ();
            player.xOld = player.getX();
            player.yOld = player.getY();
            player.zOld = player.getZ();
            applyStateFlags(lastEntry);
            currentLevel.addEntity(player);
            applyCuriosData(lastEntry.curiosData());
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
                if (player == null || player.isRemoved() || !hasSafeRenderPassengerChain()) {
                    return;
                }
                for (int i = vehicles.size() - 1; i >= 0; i--) {
                    FarVehicleState vehicle = vehicles.get(i);
                    if (renderedVehicles.add(vehicle)) {
                        vehicle.renderManually(poseStack, buffers, partialTick, cameraPosition);
                    }
                }
                double renderX = Mth.lerp(partialTick, player.xOld, player.getX());
                double renderY = Mth.lerp(partialTick, player.yOld, player.getY());
                double renderZ = Mth.lerp(partialTick, player.zOld, player.getZ());
                Minecraft.getInstance().getEntityRenderDispatcher().render(
                        player,
                        renderX - cameraPosition.x(),
                        renderY - cameraPosition.y(),
                        renderZ - cameraPosition.z(),
                        Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
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
            appliedCuriosData = null;
            appliedEntry = null;
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
            syncVehiclePassengerState(false);
            if (appliedEntry != lastEntry) {
                applyStateFlags(lastEntry);
                appliedEntry = lastEntry;
            }
            applyImmediately(sample(now));
            syncVehiclePassengerState(true);
        }

        private void applyImmediately(PoseSample sample) {
            if (player == null) {
                return;
            }
            player.xo = player.getX();
            player.yo = player.getY();
            player.zo = player.getZ();
            player.xOld = player.getX();
            player.yOld = player.getY();
            player.zOld = player.getZ();
            player.yRotO = player.getYRot();
            player.xRotO = player.getXRot();
            player.yBodyRotO = player.yBodyRot;
            player.yHeadRotO = player.yHeadRot;
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
        }

        private void applyStateFlags(FarPlayersS2CPayload.Entry entry) {
            if (player == null) {
                return;
            }
            applyEquipment(entry);
            applyCuriosData(entry.curiosData());
            player.setModelParts(entry.modelParts());
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
            player.noPhysics = true;
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

                FarVehicleState vehicleState = acquireVehicle(currentLevel, snapshot);
                if (vehicles.size() <= i) {
                    vehicles.add(vehicleState);
                } else if (vehicles.get(i) != vehicleState) {
                    releaseVehicle(vehicles.set(i, vehicleState));
                } else {
                    releaseVehicle(vehicleState);
                }
                vehicleState.update(currentLevel, snapshot, now, lastSeenNanos);
            }
        }

        private void syncVehiclePassengerState(boolean allowAttach) {
            if (player == null) {
                return;
            }
            Entity passenger = player;
            boolean ownsPassenger = true;
            for (FarVehicleState vehicleState : vehicles) {
                Entity vehicle = vehicleState.entity();
                if (!vehicleState.isPassengerReady()) {
                    if (ownsPassenger && passenger.isPassenger()) {
                        passenger.stopRiding();
                    }
                    return;
                }
                if (passenger.getVehicle() != vehicle) {
                    if (!ownsPassenger) {
                        return;
                    }
                    if (passenger.isPassenger()) {
                        passenger.stopRiding();
                    }
                    if (!allowAttach || !passenger.startRiding(vehicle, true)) {
                        return;
                    }
                }
                passenger = vehicle;
                ownsPassenger = vehicleState.ownsSyntheticEntity();
            }
            if (ownsPassenger && passenger.isPassenger()) {
                passenger.stopRiding();
            }
        }

        private boolean hasSafeRenderPassengerChain() {
            if (player == null || player.isRemoved()) {
                return false;
            }
            Set<Entity> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            int expectedIndex = 0;
            for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle()) {
                if (!seen.add(vehicle) || !CreateContraptionClientCompat.isSafeToUse(vehicle)) {
                    return false;
                }
                if (expectedIndex < vehicles.size()) {
                    FarVehicleState expected = vehicles.get(expectedIndex++);
                    if (expected.entity() != vehicle || !expected.isPassengerReady()) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void applyEquipment(FarPlayersS2CPayload.Entry entry) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack next = entry.itemBySlot(slot);
                if (!ItemStack.matches(player.getItemBySlot(slot), next)) {
                    player.setItemSlot(slot, next.copy());
                }
            }
        }

        private void applyCuriosData(CompoundTag curiosData) {
            if (player == null || curiosData == null || curiosData.equals(appliedCuriosData)) {
                return;
            }
            if (CuriosCompat.apply(player, curiosData)) {
                appliedCuriosData = curiosData.copy();
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
        private final FarVehicleInitializationGate initialization = new FarVehicleInitializationGate();
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
            return isPassengerReady();
        }

        private boolean isPassengerReady() {
            return initialization.isReady()
                    && vehicle != null
                    && !vehicle.isRemoved()
                    && CreateContraptionClientCompat.isSafeToUse(vehicle);
        }

        private boolean ownsSyntheticEntity() {
            return vehicle != null && !externalEntity;
        }

        private void update(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot, long now, long lastSeenNanos) {
            boolean identityChanged = this.level != level
                    || (vehicleTypeId != null && !snapshot.entityTypeId().equals(vehicleTypeId));
            if (identityChanged) {
                remove();
                this.level = level;
                snapTo(snapshot);
            } else if (interpolationStartNanos == 0L) {
                snapTo(snapshot);
            } else {
                VehiclePoseSample current = sample(now);
                if (FarPlayerState.distanceSqr(current.x, current.y, current.z, snapshot.x(), snapshot.y(), snapshot.z()) > TELEPORT_DISTANCE_SQR) {
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

            this.level = level;
            vehicleTypeId = snapshot.entityTypeId();
            Entity external = externalVehicle(level, snapshot);
            if (external != null && CreateContraptionClientCompat.isSafeToUse(external)) {
                attachExternalVehicle(external);
            } else if (externalEntity && (vehicle == null
                    || vehicle.isRemoved()
                    || vehicle.getId() != snapshot.sourceEntityId()
                    || !CreateContraptionClientCompat.isSafeToUse(vehicle))) {
                vehicle = null;
                externalEntity = false;
                initialization.reset();
            }

            boolean hasEntityData = snapshot.entityData() != null;
            boolean hasSpawnData = snapshot.spawnData() != null && snapshot.spawnData().length > 0;
            if (!externalEntity && initialization.shouldInitialize(snapshot.fullData(), hasEntityData, hasSpawnData)) {
                initializeSyntheticVehicle(level, snapshot);
            }
            applyState(snapshot);
            rocketFinalLiftVelocity = snapshot.rocketFinalLiftVelocity();
            if (isPassengerReady()) {
                NorthstarRocketClientCompat.apply(vehicle, snapshot);
            }
        }

        private void initializeSyntheticVehicle(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            boolean retainExisting = !externalEntity && isPassengerReady();
            initialization.beginInitialization();
            rocketParticleTicks = 0;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(snapshot.entityTypeId()).orElse(null);
            if (type == null) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle; unknown entity type " + snapshot.entityTypeId());
                }
                initialization.completeInitialization(retainExisting);
                return;
            }

            Entity candidate;
            try {
                candidate = type.create(level);
            } catch (RuntimeException e) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle " + snapshot.entityTypeId(), e);
                }
                initialization.completeInitialization(retainExisting);
                return;
            }

            if (candidate == null) {
                if (FAILED_VEHICLE_TYPES.add(snapshot.entityTypeId())) {
                    VSSLogger.warn("Unable to create far player vehicle " + snapshot.entityTypeId());
                }
                initialization.completeInitialization(retainExisting);
                return;
            }

            boolean initialized = applyFullData(candidate, level, snapshot);
            if (initialized) {
                applySyntheticIdentity(candidate, snapshot);
                applyState(candidate, snapshot);
                initialized = CreateContraptionClientCompat.isSafeToUse(candidate);
            }
            if (!initialized) {
                discardSyntheticVehicle(candidate);
                initialization.completeInitialization(retainExisting);
                return;
            }

            Entity previousVehicle = vehicle;
            boolean previousExternal = externalEntity;
            vehicle = candidate;
            externalEntity = false;
            initialization.completeInitialization(true);
            if (previousVehicle != null && !previousExternal && previousVehicle != candidate) {
                discardSyntheticVehicle(previousVehicle);
            }
        }

        private Entity externalVehicle(ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            if (level == null || snapshot == null) {
                return null;
            }
            Entity existingVehicle = level.getEntity(snapshot.sourceEntityId());
            ResourceLocation existingTypeId = existingVehicle == null
                    ? null
                    : BuiltInRegistries.ENTITY_TYPE.getKey(existingVehicle.getType());
            if (existingVehicle == null
                    || existingVehicle.isRemoved()
                    || !snapshot.entityTypeId().equals(existingTypeId)) {
                return null;
            }
            return existingVehicle;
        }

        private void attachExternalVehicle(Entity externalVehicle) {
            if (vehicle == externalVehicle && externalEntity) {
                initialization.markExternalReady();
                return;
            }
            if (vehicle != null && !externalEntity) {
                discardSyntheticVehicle(vehicle);
            }
            vehicle = externalVehicle;
            externalEntity = true;
            vehicle.noCulling = true;
            initialization.markExternalReady();
        }

        private static void discardSyntheticVehicle(Entity entity) {
            if (entity != null && !entity.isRemoved()) {
                entity.ejectPassengers();
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
                entity.onClientRemoval();
            }
        }

        private boolean applyFullData(Entity candidate, ClientLevel level, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            boolean appliedAny = false;
            CompoundTag entityData = snapshot.entityData();
            if (entityData != null) {
                try {
                    candidate.load(entityData.copy());
                    appliedAny = true;
                } catch (RuntimeException e) {
                    VSSLogger.warn("Failed to load far vehicle NBT for " + snapshot.entityTypeId(), e);
                    return false;
                }
            }

            byte[] spawnData = snapshot.spawnData();
            if (spawnData != null && spawnData.length > 0) {
                if (!(candidate instanceof IEntityWithComplexSpawn spawnDataEntity)) {
                    VSSLogger.warn("Far vehicle supplied spawn data for an unsupported entity " + snapshot.entityTypeId());
                    return false;
                }
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(spawnData), level.registryAccess());
                try {
                    spawnDataEntity.readSpawnData(buf);
                    appliedAny = true;
                } catch (RuntimeException e) {
                    VSSLogger.warn("Failed to apply far vehicle spawn data for " + snapshot.entityTypeId(), e);
                    return false;
                } finally {
                    buf.release();
                }
            }
            return appliedAny;
        }

        private void applySyntheticIdentity(Entity entity, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            entity.setId(farVehicleEntityId(snapshot.sourceEntityId()));
            entity.setUUID(farVehicleSyntheticUuid(snapshot.sourceEntityId(), snapshot.entityTypeId()));
            entity.setNoGravity(true);
            entity.noPhysics = true;
            entity.noCulling = true;
        }

        private void applyState(FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            if (!isPassengerReady() || externalEntity) {
                return;
            }
            applyState(vehicle, snapshot);
        }

        private static void applyState(Entity entity, FarPlayersS2CPayload.VehicleSnapshot snapshot) {
            entity.setOnGround(snapshot.onGround());
            entity.setSharedFlagOnFire(snapshot.onFire());
            entity.setInvisible(snapshot.invisible());
            entity.setGlowingTag(snapshot.glowing());
            entity.setNoGravity(true);
            entity.noPhysics = true;
            entity.noCulling = true;
        }

        private void apply(long now) {
            VehiclePoseSample sample = sample(now);
            if (!isPassengerReady()) {
                return;
            }
            if (externalEntity && !isNorthstarRocket(vehicleTypeId)) {
                vehicle.noCulling = true;
                return;
            }
            double oldX = vehicle.getX();
            double oldY = vehicle.getY();
            double oldZ = vehicle.getZ();
            vehicle.xo = vehicle.getX();
            vehicle.yo = vehicle.getY();
            vehicle.zo = vehicle.getZ();
            vehicle.xOld = vehicle.getX();
            vehicle.yOld = vehicle.getY();
            vehicle.zOld = vehicle.getZ();
            vehicle.yRotO = vehicle.getYRot();
            vehicle.xRotO = vehicle.getXRot();
            if (vehicle instanceof LivingEntity livingEntity) {
                livingEntity.yBodyRotO = livingEntity.yBodyRot;
                livingEntity.yHeadRotO = livingEntity.yHeadRot;
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
            if (externalEntity) {
                vehicle.noCulling = true;
                vehicle.setDeltaMovement(sample.x - oldX, sample.y - oldY, sample.z - oldZ);
            }
        }

        private VehiclePoseSample sample(long now) {
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
            if (isPassengerReady()) {
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
            if (!isRenderable()) {
                return;
            }
            if (externalEntity && !isNorthstarRocket(vehicleTypeId)) {
                return;
            }
            double renderX = Mth.lerp(partialTick, vehicle.xOld, vehicle.getX());
            double renderY = Mth.lerp(partialTick, vehicle.yOld, vehicle.getY());
            double renderZ = Mth.lerp(partialTick, vehicle.zOld, vehicle.getZ());
            Minecraft.getInstance().getEntityRenderDispatcher().render(
                    vehicle,
                    renderX - cameraPosition.x(),
                    renderY - cameraPosition.y(),
                    renderZ - cameraPosition.z(),
                    Mth.rotLerp(partialTick, vehicle.yRotO, vehicle.getYRot()),
                    partialTick,
                    poseStack,
                    buffers,
                    entityLight(vehicle));
        }

        private boolean isAtBlockPos(BlockPos pos) {
            return isRenderable()
                    && vehicle.getBlockX() == pos.getX()
                    && vehicle.getBlockY() == pos.getY()
                    && vehicle.getBlockZ() == pos.getZ();
        }

        private void addRenderBlock(LongOpenHashSet output) {
            if (isRenderable()) {
                output.add(BlockPos.asLong(vehicle.getBlockX(), vehicle.getBlockY(), vehicle.getBlockZ()));
            }
        }

        private void remove() {
            if (!externalEntity) {
                discardSyntheticVehicle(vehicle);
            }
            vehicle = null;
            level = null;
            vehicleTypeId = null;
            externalEntity = false;
            rocketFinalLiftVelocity = 0.0F;
            rocketParticleTicks = 0;
            initialization.reset();
        }
    }

    private static final class VSSRemotePlayer extends RemotePlayer {
        private final UUID sourceUuid;
        private int modelParts = 0x7F;

        private VSSRemotePlayer(ClientLevel level, GameProfile profile, UUID sourceUuid) {
            super(level, profile);
            this.sourceUuid = sourceUuid;
            this.noPhysics = true;
        }

        @Override
        protected AABB makeBoundingBox() {
            double halfWidth = getBbWidth() / 2.0D;
            return new AABB(
                    getX() - halfWidth,
                    getY(),
                    getZ() - halfWidth,
                    getX() + halfWidth,
                    getY() + getBbHeight(),
                    getZ() + halfWidth);
        }

        @Override
        public UUID getUUID() {
            // UUID-keyed render integrations must see the real player's cached cosmetic state.
            return manualFarPlayerRender ? sourceUuid : super.getUUID();
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSqr) {
            return true;
        }

        private void setModelParts(int modelParts) {
            this.modelParts = modelParts;
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
            return (modelParts & part.getMask()) != 0;
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

    private record VehicleKey(int sourceEntityId, ResourceLocation entityTypeId) {
    }
}

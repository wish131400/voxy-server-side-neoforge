package dev.xantha.vss.networking.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;

public final class FarPlayerClientRenderer {
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long STALE_AFTER_NANOS = 3_000L * NANOS_PER_MILLI;
    private static final long MIN_INTERPOLATION_NANOS = 50L * NANOS_PER_MILLI;
    private static final long MAX_INTERPOLATION_NANOS = 1_000L * NANOS_PER_MILLI;
    private static final double TELEPORT_DISTANCE_SQR = 32.0D * 32.0D;
    private static final Map<UUID, FarPlayerState> FAR_PLAYERS = new HashMap<>();

    private FarPlayerClientRenderer() {
    }

    public static void handleFarPlayers(FarPlayersS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
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
                continue;
            }
            seen.add(entry.uuid());
            FAR_PLAYERS.computeIfAbsent(entry.uuid(), uuid -> new FarPlayerState(level, entry))
                    .update(level, entry, now);
        }

        Iterator<Map.Entry<UUID, FarPlayerState>> iterator = FAR_PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FarPlayerState> entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                entry.getValue().markMissing(now);
            }
        }
    }

    public static void clear() {
        FAR_PLAYERS.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            FAR_PLAYERS.clear();
            return;
        }

        long now = System.nanoTime();
        ClientLevel level = Minecraft.getInstance().level;
        Iterator<Map.Entry<UUID, FarPlayerState>> iterator = FAR_PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            FarPlayerState state = iterator.next().getValue();
            if (now - state.lastSeenNanos > STALE_AFTER_NANOS) {
                iterator.remove();
                continue;
            }
            if (state.level == level && !isTrackedByVanilla(level, state.uuid)) {
                state.tickAnimation(now);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer localPlayer = mc.player;
        if (level == null || localPlayer == null || FAR_PLAYERS.isEmpty()) {
            return;
        }

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        double cameraX = event.getCamera().getPosition().x;
        double cameraY = event.getCamera().getPosition().y;
        double cameraZ = event.getCamera().getPosition().z;
        float partialTick = event.getPartialTick();
        boolean renderedAny = false;

        for (FarPlayerState state : FAR_PLAYERS.values()) {
            if (state.level != level || isTrackedByVanilla(level, state.uuid)) {
                continue;
            }
            state.apply();
            RemotePlayer player = state.player;
            if (player.isRemoved()) {
                continue;
            }

            try {
                dispatcher.render(
                        player,
                        player.getX() - cameraX,
                        player.getY() - cameraY,
                        player.getZ() - cameraZ,
                        player.getYRot(),
                        partialTick,
                        poseStack,
                        bufferSource,
                        LightTexture.FULL_BRIGHT);
                renderNameTag(mc, dispatcher, bufferSource, poseStack, state, cameraX, cameraY, cameraZ);
                renderedAny = true;
            } catch (RuntimeException e) {
                VSSLogger.debug("Far player render failed for " + state.name + ": " + e.getMessage());
            }
        }

        if (renderedAny) {
            bufferSource.endBatch();
        }
    }

    private static void renderNameTag(
            Minecraft mc,
            EntityRenderDispatcher dispatcher,
            MultiBufferSource bufferSource,
            PoseStack poseStack,
            FarPlayerState state,
            double cameraX,
            double cameraY,
            double cameraZ) {
        RemotePlayer player = state.player;
        poseStack.pushPose();
        poseStack.translate(
                player.getX() - cameraX,
                player.getY() - cameraY + player.getBbHeight() + 0.5F,
                player.getZ() - cameraZ);
        poseStack.mulPose(dispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        Font font = mc.font;
        float textX = -font.width(state.name) / 2.0F;
        int background = (int) (mc.options.getBackgroundOpacity(0.25F) * 255.0F) << 24;
        font.drawInBatch(
                state.name,
                textX,
                0.0F,
                0xFFFFFFFF,
                false,
                poseStack.last().pose(),
                bufferSource,
                Font.DisplayMode.NORMAL,
                background,
                LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static boolean isTrackedByVanilla(ClientLevel level, UUID uuid) {
        for (AbstractClientPlayer player : level.players()) {
            if (uuid.equals(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    private static final class FarPlayerState {
        private final UUID uuid;
        private final String name;
        private ClientLevel level;
        private RemotePlayer player;
        private double previousX;
        private double previousY;
        private double previousZ;
        private double targetX;
        private double targetY;
        private double targetZ;
        private float previousYaw;
        private float previousPitch;
        private float previousHeadYaw;
        private float targetYaw;
        private float targetPitch;
        private float targetHeadYaw;
        private long lastSeenNanos;
        private long interpolationStartNanos;
        private long interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
        private double animationX;
        private double animationY;
        private double animationZ;
        private boolean hasAnimationPosition;

        private FarPlayerState(ClientLevel level, FarPlayersS2CPayload.Entry entry) {
            this.uuid = entry.uuid();
            this.name = entry.name();
            this.level = level;
            this.player = createPlayer(level, entry);
            snapTo(entry);
            this.lastSeenNanos = 0L;
        }

        private void update(ClientLevel newLevel, FarPlayersS2CPayload.Entry entry, long now) {
            if (level != newLevel) {
                level = newLevel;
                player = createPlayer(newLevel, entry);
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
                }
                targetX = entry.x();
                targetY = entry.y();
                targetZ = entry.z();
                targetYaw = entry.yaw();
                targetPitch = entry.pitch();
                targetHeadYaw = entry.headYaw();
                long packetInterval = lastSeenNanos > 0L ? now - lastSeenNanos : MIN_INTERPOLATION_NANOS;
                interpolationStartNanos = now;
                interpolationDurationNanos = clamp(packetInterval, MIN_INTERPOLATION_NANOS, MAX_INTERPOLATION_NANOS);
            }

            applyStateFlags(entry);
            lastSeenNanos = now;
        }

        private void markMissing(long now) {
            if (now - lastSeenNanos > STALE_AFTER_NANOS / 2L) {
                lastSeenNanos = Math.min(lastSeenNanos, now - STALE_AFTER_NANOS);
            }
        }

        private void tickAnimation(long now) {
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

            animationX = sample.x;
            animationY = sample.y;
            animationZ = sample.z;
            hasAnimationPosition = true;
        }

        private void apply() {
            PoseSample sample = sample(System.nanoTime());
            player.xo = sample.x;
            player.yo = sample.y;
            player.zo = sample.z;
            player.yRotO = sample.yaw;
            player.xRotO = sample.pitch;
            player.yBodyRotO = sample.yaw;
            player.yHeadRotO = sample.headYaw;
            player.setPos(sample.x, sample.y, sample.z);
            player.setYRot(sample.yaw);
            player.setXRot(sample.pitch);
            player.setYBodyRot(sample.yaw);
            player.setYHeadRot(sample.headYaw);
        }

        private PoseSample sample(long now) {
            float progress = interpolationProgress(now);
            return new PoseSample(
                    Mth.lerp(progress, previousX, targetX),
                    Mth.lerp(progress, previousY, targetY),
                    Mth.lerp(progress, previousZ, targetZ),
                    Mth.rotLerp(progress, previousYaw, targetYaw),
                    Mth.lerp(progress, previousPitch, targetPitch),
                    Mth.rotLerp(progress, previousHeadYaw, targetHeadYaw));
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
            targetYaw = entry.yaw();
            targetPitch = entry.pitch();
            targetHeadYaw = entry.headYaw();
            player.setPos(entry.x(), entry.y(), entry.z());
            player.setYRot(entry.yaw());
            player.setXRot(entry.pitch());
            player.setYBodyRot(entry.yaw());
            player.setYHeadRot(entry.headYaw());
            player.yRotO = entry.yaw();
            player.xRotO = entry.pitch();
            player.yBodyRotO = entry.yaw();
            player.yHeadRotO = entry.headYaw();
            animationX = entry.x();
            animationY = entry.y();
            animationZ = entry.z();
            hasAnimationPosition = true;
            interpolationStartNanos = System.nanoTime();
            interpolationDurationNanos = MIN_INTERPOLATION_NANOS;
            applyStateFlags(entry);
        }

        private void applyStateFlags(FarPlayersS2CPayload.Entry entry) {
            player.setShiftKeyDown(entry.crouching());
            player.setSprinting(entry.sprinting());
            player.setPose(entry.crouching() ? Pose.CROUCHING : Pose.STANDING);
            player.setNoGravity(true);
            player.noCulling = true;
        }

        private static RemotePlayer createPlayer(ClientLevel level, FarPlayersS2CPayload.Entry entry) {
            RemotePlayer player = new RemotePlayer(level, new GameProfile(entry.uuid(), entry.name()));
            player.setUUID(entry.uuid());
            player.setId(-Math.abs(entry.uuid().hashCode()));
            player.setNoGravity(true);
            player.noCulling = true;
            return player;
        }

        private static double distanceSqr(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx;
            double dy = ay - by;
            double dz = az - bz;
            return dx * dx + dy * dy + dz * dz;
        }

        private static long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private record PoseSample(double x, double y, double z, float yaw, float pitch, float headYaw) {
    }
}

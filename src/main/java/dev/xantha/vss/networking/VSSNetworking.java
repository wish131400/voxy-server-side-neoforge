package dev.xantha.vss.networking;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class VSSNetworking {
    private static final String PROTOCOL = Integer.toString(VSSConstants.PROTOCOL_VERSION);
    private static final String CLIENT_PACKET_HANDLERS_CLASS = "dev.xantha.vss.networking.client.VSSClientPacketHandlers";

    private VSSNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.MAIN);

        registrar.playToServer(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.STREAM_CODEC, VSSServerNetworking::handleHandshake);
        registrar.playToServer(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBatchRequest);
        registrar.playToServer(CancelRequestC2SPayload.TYPE, CancelRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleCancel);
        registrar.playToServer(BandwidthUpdateC2SPayload.TYPE, BandwidthUpdateC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBandwidthUpdate);
        registrar.playToServer(RegionPresenceC2SPayload.TYPE, RegionPresenceC2SPayload.STREAM_CODEC, VSSServerNetworking::handleRegionPresence);

        registrar.playToClient(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.STREAM_CODEC, VSSNetworking::handleSessionConfig);
        registrar.playToClient(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.STREAM_CODEC, VSSNetworking::handleBatchResponse);
        registrar.playToClient(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.STREAM_CODEC, VSSNetworking::handleDirtyColumns);
        registrar.playToClient(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.STREAM_CODEC, VSSNetworking::handleVoxelColumn);
        registrar.playToClient(FarPlayersS2CPayload.TYPE, FarPlayersS2CPayload.STREAM_CODEC, VSSNetworking::handleFarPlayers);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (trySendToIntegratedHost(player, payload)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static boolean trySendToIntegratedHost(ServerPlayer player, CustomPacketPayload payload) {
        if (!FMLEnvironment.dist.isClient()) {
            return false;
        }
        Object handled = invokeClientHandler(
                "tryHandleIntegratedHostPayload",
                new Class<?>[] {ServerPlayer.class, CustomPacketPayload.class},
                player,
                payload);
        return Boolean.TRUE.equals(handled);
    }

    private static void handleSessionConfig(SessionConfigS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleSessionConfig", new Class<?>[] {SessionConfigS2CPayload.class}, payload);
    }

    private static void handleBatchResponse(BatchResponseS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleBatchResponse", new Class<?>[] {BatchResponseS2CPayload.class}, payload);
    }

    private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleDirtyColumns", new Class<?>[] {DirtyColumnsS2CPayload.class}, payload);
    }

    private static void handleVoxelColumn(VoxelColumnS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleVoxelColumn", new Class<?>[] {VoxelColumnS2CPayload.class}, payload);
    }

    private static void handleFarPlayers(FarPlayersS2CPayload payload, IPayloadContext context) {
        invokeClientHandler("handleFarPlayers", new Class<?>[] {FarPlayersS2CPayload.class}, payload);
    }

    private static Object invokeClientHandler(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (!FMLEnvironment.dist.isClient()) {
            return null;
        }
        try {
            Class<?> handlersClass = Class.forName(CLIENT_PACKET_HANDLERS_CLASS);
            Method method = handlersClass.getMethod(methodName, parameterTypes);
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("VSS client packet handler failed: " + methodName, cause);
        } catch (ReflectiveOperationException e) {
            VSSLogger.error("Unable to invoke VSS client packet handler: " + methodName, e);
            return null;
        }
    }
}

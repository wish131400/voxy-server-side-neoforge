package dev.xantha.vss.networking;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.ClientDirtyColumnsC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.function.Supplier;

public final class VSSNetworking {
    private static final String PROTOCOL = Integer.toString(VSSConstants.PROTOCOL_VERSION);
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(VSSConstants.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private VSSNetworking() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(HandshakeC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(HandshakeC2SPayload::encode)
                .decoder(HandshakeC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleHandshake)
                .add();
        CHANNEL.messageBuilder(BatchChunkRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BatchChunkRequestC2SPayload::encode)
                .decoder(BatchChunkRequestC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleBatchRequest)
                .add();
        CHANNEL.messageBuilder(CancelRequestC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CancelRequestC2SPayload::encode)
                .decoder(CancelRequestC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleCancel)
                .add();
        CHANNEL.messageBuilder(BandwidthUpdateC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BandwidthUpdateC2SPayload::encode)
                .decoder(BandwidthUpdateC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleBandwidthUpdate)
                .add();
        CHANNEL.messageBuilder(ClientDirtyColumnsC2SPayload.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClientDirtyColumnsC2SPayload::encode)
                .decoder(ClientDirtyColumnsC2SPayload::decode)
                .consumerMainThread(VSSServerNetworking::handleClientDirtyColumns)
                .add();
        CHANNEL.messageBuilder(SessionConfigS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SessionConfigS2CPayload::encode)
                .decoder(SessionConfigS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleSessionConfig)
                .add();
        CHANNEL.messageBuilder(BatchResponseS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(BatchResponseS2CPayload::encode)
                .decoder(BatchResponseS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleBatchResponse)
                .add();
        CHANNEL.messageBuilder(DirtyColumnsS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DirtyColumnsS2CPayload::encode)
                .decoder(DirtyColumnsS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleDirtyColumns)
                .add();
        CHANNEL.messageBuilder(VoxelColumnS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VoxelColumnS2CPayload::encode)
                .decoder(VoxelColumnS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleVoxelColumn)
                .add();
        CHANNEL.messageBuilder(FarPlayersS2CPayload.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FarPlayersS2CPayload::encode)
                .decoder(FarPlayersS2CPayload::decode)
                .consumerMainThread(VSSNetworking::handleFarPlayers)
                .add();
    }

    public static void sendToServer(Object payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void handleSessionConfig(SessionConfigS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleSessionConfig(payload, contextSupplier));
    }

    private static void handleBatchResponse(BatchResponseS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleBatchResponse(payload, contextSupplier));
    }

    private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleDirtyColumns(payload, contextSupplier));
    }

    private static void handleVoxelColumn(VoxelColumnS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleVoxelColumn(payload, contextSupplier));
    }

    private static void handleFarPlayers(FarPlayersS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> ClientPacketHandlers.handleFarPlayers(payload, contextSupplier));
    }

    private static final class ClientPacketHandlers {
        private static void handleSessionConfig(SessionConfigS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleSessionConfig(payload, contextSupplier);
        }

        private static void handleBatchResponse(BatchResponseS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleBatchResponse(payload, contextSupplier);
        }

        private static void handleDirtyColumns(DirtyColumnsS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleDirtyColumns(payload, contextSupplier);
        }

        private static void handleVoxelColumn(VoxelColumnS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.VSSClientNetworking.handleVoxelColumn(payload, contextSupplier);
        }

        private static void handleFarPlayers(FarPlayersS2CPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
            dev.xantha.vss.networking.client.FarPlayerClientRenderer.handleFarPlayers(payload, contextSupplier);
        }
    }
}

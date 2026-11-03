package dev.xantha.vss.networking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.Test;

class OptionalClientNetworkingTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();
        if (net.neoforged.fml.loading.FMLPaths.GAMEDIR.get() == null) {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "optional-client-tests"));
        }
    }
    @Test
    void everyPayloadIsOptionalWhileItsProtocolVersionStaysExact() {
        try (var registry = mockStatic(net.neoforged.neoforge.network.registration.NetworkRegistry.class)) {
            VSSNetworking.register(new net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent());
            registry.verify(() -> net.neoforged.neoforge.network.registration.NetworkRegistry.register(
                    any(), any(), any(), anyList(), any(),
                    eq(Integer.toString(VSSConstants.PROTOCOL_VERSION)), eq(true)), times(18));
            registry.verifyNoMoreInteractions();
        }
    }

    @Test
    void configurationIdentityIsOnlyScheduledForAClientWithItsChannel() {
        var listener = mock(net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener.class);
        var absent = new net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent(listener);
        VSSNetworking.registerConfigurationTasks(absent);
        assertTrue(absent.getConfigurationTasks().isEmpty());

        when(listener.hasChannel(ServerIdentityS2CPayload.TYPE)).thenReturn(true);
        var present = new net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent(listener);
        VSSNetworking.registerConfigurationTasks(present);
        assertEquals(1, present.getConfigurationTasks().size());
        assertInstanceOf(dev.xantha.vss.networking.server.ServerIdentityConfigurationTask.class,
                present.getConfigurationTasks().element());
    }

    @Test
    void playersWithoutVssReceiveNoPlayHandshakeAndInstalledPlayersStillDo() throws Exception {
        var player = mock(ServerPlayer.class);
        var listener = mock(ServerGamePacketListenerImpl.class);
        ServerPlayer.class.getField("connection").set(player, listener);
        var event = new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(player);
        try (var sending = mockStatic(VSSNetworking.class)) {
            VSSServerNetworking.onPlayerLogin(event);
            sending.verifyNoInteractions();

            when(listener.hasChannel(HandshakeRequestS2CPayload.TYPE)).thenReturn(true);
            VSSServerNetworking.onPlayerLogin(event);
            sending.verify(() -> VSSNetworking.sendToPlayer(eq(player), any(HandshakeRequestS2CPayload.class)));
        }
    }
}

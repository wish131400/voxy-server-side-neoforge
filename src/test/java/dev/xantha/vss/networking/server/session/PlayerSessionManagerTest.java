package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.VSSConstants;
import org.junit.jupiter.api.Test;

class PlayerSessionManagerTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {dev.xantha.vss.testutil.MinecraftTestBootstrap.initialize();}
    @Test void advertisedConcurrencyMatchesTheHostingServer() throws Exception {
        var config=dev.xantha.vss.config.VSSServerConfig.CONFIG;
        int local=config.singleplayerGenerationConcurrencyLimit, dedicated=config.generationConcurrencyLimitPerPlayer;
        try {
            config.singleplayerGenerationConcurrencyLimit=2;config.generationConcurrencyLimitPerPlayer=4;
            var manager=new PlayerSessionManager(null,null,()->{},new dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard());
            var server=org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
            var create=PlayerSessionManager.class.getDeclaredMethod("createSessionConfig",net.minecraft.server.MinecraftServer.class,boolean.class);
            create.setAccessible(true);
            org.mockito.Mockito.when(server.isDedicatedServer()).thenReturn(false);
            var payload=(dev.xantha.vss.networking.payloads.SessionConfigS2CPayload)create.invoke(manager,server,true);
            org.junit.jupiter.api.Assertions.assertEquals(2,payload.generationConcurrencyLimitPerPlayer());
            org.mockito.Mockito.when(server.isDedicatedServer()).thenReturn(true);
            payload=(dev.xantha.vss.networking.payloads.SessionConfigS2CPayload)create.invoke(manager,server,true);
            org.junit.jupiter.api.Assertions.assertEquals(4,payload.generationConcurrencyLimitPerPlayer());
        } finally {config.singleplayerGenerationConcurrencyLimit=local;config.generationConcurrencyLimitPerPlayer=dedicated;}
    }


    @Test
    void voxelColumnClientWithCurrentProtocolIsCompatible() {
        assertTrue(PlayerSessionManager.isCompatibleClient(
                VSSConstants.PROTOCOL_VERSION,
                VSSConstants.CAPABILITY_VOXEL_COLUMNS));
    }

    @Test
    void extraCapabilitiesRemainCompatible() {
        assertTrue(PlayerSessionManager.isCompatibleClient(
                VSSConstants.PROTOCOL_VERSION,
                VSSConstants.CAPABILITY_VOXEL_COLUMNS | VSSConstants.CAPABILITY_ZSTD_COLUMNS));
    }

    @Test
    void mismatchedProtocolIsRejected() {
        assertFalse(PlayerSessionManager.isCompatibleClient(
                VSSConstants.PROTOCOL_VERSION + 1,
                VSSConstants.CAPABILITY_VOXEL_COLUMNS));
    }

    @Test
    void missingVoxelColumnCapabilityIsRejected() {
        assertFalse(PlayerSessionManager.isCompatibleClient(
                VSSConstants.PROTOCOL_VERSION,
                VSSConstants.CAPABILITY_ZSTD_COLUMNS));
    }
}

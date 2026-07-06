package dev.xantha.vss.networking.server.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.VSSConstants;
import org.junit.jupiter.api.Test;

class PlayerSessionManagerTest {

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

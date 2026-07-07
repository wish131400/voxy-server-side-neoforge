package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import org.junit.jupiter.api.Test;

class VSSClientNetworkingTest {

    @Test
    void splitReplacementPartsDoNotClearMissingSectionsUntilRequestCompletes() {
        LodRequestManager.ColumnReceiveResult replacingKnownColumn =
                new LodRequestManager.ColumnReceiveResult(true, false, true, 42L);
        VoxelColumnS2CPayload middlePart = payload(false);
        VoxelColumnS2CPayload finalPart = payload(true);

        assertFalse(VSSClientNetworking.shouldReplaceMissingSections(replacingKnownColumn, middlePart));
        assertTrue(VSSClientNetworking.shouldReplaceMissingSections(replacingKnownColumn, finalPart));
    }

    @Test
    void freshColumnsNeverClearMissingSections() {
        LodRequestManager.ColumnReceiveResult freshColumn =
                new LodRequestManager.ColumnReceiveResult(true, false, false, 42L);

        assertFalse(VSSClientNetworking.shouldReplaceMissingSections(freshColumn, payload(true)));
    }

    private static VoxelColumnS2CPayload payload(boolean completesRequest) {
        return new VoxelColumnS2CPayload(
                7,
                0,
                0,
                null,
                123L,
                new byte[] {0},
                true,
                completesRequest,
                new int[] {-4, -3, -2});
    }
}

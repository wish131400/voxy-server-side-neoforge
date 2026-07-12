package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import org.junit.jupiter.api.Test;

class VSSClientNetworkingTest {

    @Test
    void allReplacementPartsKeepLogicalReplacementSemantics() {
        LodRequestManager.ColumnReceiveResult replacingKnownColumn =
                new LodRequestManager.ColumnReceiveResult(true, false, true, 42L);
        VoxelColumnS2CPayload middlePart = emptyPayload(false);
        VoxelColumnS2CPayload finalPart = emptyPayload(true);

        assertTrue(VSSClientNetworking.shouldReplaceMissingSections(replacingKnownColumn, middlePart));
        assertTrue(VSSClientNetworking.shouldReplaceMissingSections(replacingKnownColumn, finalPart));
    }

    @Test
    void nonEmptyReplacementClearsSectionsOutsideCompleteManifest() {
        LodRequestManager.ColumnReceiveResult replacingKnownColumn =
                new LodRequestManager.ColumnReceiveResult(true, false, true, 42L);

        assertTrue(VSSClientNetworking.shouldReplaceMissingSections(
                replacingKnownColumn,
                payload(true, new byte[] {1, 2})));
    }

    @Test
    void freshColumnsNeverClearMissingSections() {
        LodRequestManager.ColumnReceiveResult freshColumn =
                new LodRequestManager.ColumnReceiveResult(true, false, false, 42L);

        assertFalse(VSSClientNetworking.shouldReplaceMissingSections(freshColumn, emptyPayload(true)));
    }

    private static VoxelColumnS2CPayload emptyPayload(boolean completesRequest) {
        return payload(completesRequest, new byte[] {0});
    }

    private static VoxelColumnS2CPayload payload(boolean completesRequest, byte[] sectionBytes) {
        return new VoxelColumnS2CPayload(
                7,
                0,
                0,
                null,
                123L,
                sectionBytes,
                true,
                101L,
                completesRequest ? 1 : 0,
                2,
                completesRequest ? new int[] {-4, -3, -2} : new int[0]);
    }
}

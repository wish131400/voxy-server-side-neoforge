package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ColumnPayloadSplitterTest {

    @Test
    void targetWireBytesShrinksBelowOldTwentyFourKilobyteFloorAtLowBandwidth() {
        int target = ColumnPayloadSplitter.targetWireBytes(64L * 1024L);

        assertTrue(target < 24 * 1024);
        assertEquals(16 * 1024, target);
    }

    @Test
    void targetWireBytesKeepsSmallFloorForVeryLowBandwidth() {
        assertEquals(8 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L));
    }

    @Test
    void targetWireBytesStillCapsLargeBandwidthPayloads() {
        assertEquals(256 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L * 1024L));
    }

    @Test
    void oversizedEncodedColumnUsesLengthManifestWithoutParsingSections() {
        byte[] raw = new byte[1 + 5000 + 5000];
        raw[0] = 2;
        Arrays.fill(raw, 1, raw.length, (byte) 0x7F);
        EncodedColumnData encoded = new EncodedColumnData(
                2,
                3,
                LodByteCompression.METHOD_NONE,
                raw.length,
                raw,
                9L,
                EncodedColumnData.SCHEMA_VERSION,
                true,
                new int[] {-1, 0},
                new int[] {5000, 5000},
                EncodedColumnData.crc32c(raw));
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(5, Level.OVERWORLD, encoded)
                .withTransferMetadata(7L, 0, 1, encoded.sectionYs());

        List<VoxelColumnS2CPayload> parts = ColumnPayloadSplitter.splitForBandwidth(
                null,
                payload,
                4L * 1024L,
                false);

        assertEquals(2, parts.size());
        assertEquals(1, parts.get(0).partCount() - 1);
        assertArrayEquals(new int[] {-1, 0}, parts.get(1).replacementSectionYs());
    }
}

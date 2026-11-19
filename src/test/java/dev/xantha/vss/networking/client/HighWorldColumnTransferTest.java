package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.sending.ColumnPayloadSplitter;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import dev.xantha.vss.testutil.HighWorldColumnFixtures;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HighWorldColumnTransferTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ColumnFilterFixtures.biomes(); }
    @Test void tallColumnsRoundTripSplitReassembleAndDecodeWithLights() {
        for (int count : new int[] {65, 80, 256}) {
            var data = HighWorldColumnFixtures.column(count);
            var source = new VoxelColumnS2CPayload(7, Level.OVERWORLD, data)
                    .withTransferMetadata(42L, 0, 1, data.sectionYs());
            // First exercise a highly compressed, unsplit tall column.
            var whole = roundTrip(source);
            assertArrayEquals(data.sectionYs(), whole.replacementSectionYs());
            byte[] raw = dataBytes(data);
            var uncompressed = new EncodedColumnData(0, 7, LodByteCompression.METHOD_NONE, raw.length, raw,
                    123L, EncodedColumnData.SCHEMA_VERSION, true, data.sectionYs(), data.sectionLengths(), EncodedColumnData.crc32c(raw));
            var splitSource = new VoxelColumnS2CPayload(7, Level.OVERWORLD, uncompressed).withTransferMetadata(42L, 0, 1, data.sectionYs());
            var parts = ColumnPayloadSplitter.splitForBandwidth(null, splitSource, 4 * 1024L, false);
            assertTrue(parts.size() > 1);
            var assembler = new ClientColumnTransferAssembler(4, 16 * 1024 * 1024L);
            ClientColumnTransferAssembler.OfferResult complete = null;
            for (int i = parts.size() - 1; i >= 0; i--) {
                complete = assembler.offer(roundTrip(parts.get(i)), false, true, 0, 0L, parts.size() - i);
            }
            assertEquals(ClientColumnTransferAssembler.OfferStatus.COMPLETED, complete.status());
            var decoded = ClientColumnProcessor.decodeColumn(complete.column(), ColumnFilterFixtures.biomes());
            assertEquals(count, decoded.sections().length);
            assertArrayEquals(data.sectionYs(), decoded.replacementSectionYs());
            for (var section : decoded.sections()) {
                assertFalse(section.section().hasOnlyAir());
                assertArrayEquals(ColumnFilterFixtures.light(0x12), section.blockLight().getData());
                assertArrayEquals(ColumnFilterFixtures.light(0xEF), section.skyLight().getData());
            }
            assertEquals(0L, assembler.activeBytes());
        }
    }

    @Test void compressedWholeColumnOverTwoMiBStillSplitsBeforeWireEncoding() {
        int[] ys = HighWorldColumnFixtures.heights(80);
        int[] lengths = new int[80];
        Arrays.fill(lengths, 40000);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        byte[] raw;
        try {
            buffer.writeVarInt(80).writeZero(80 * 40000);
            raw = new byte[buffer.readableBytes()]; buffer.readBytes(raw);
        } finally { buffer.release(); }
        var compressed = LodByteCompression.compressForNetwork(raw, false);
        var data = new EncodedColumnData(0, 7, compressed.method(), raw.length, compressed.bytes(), 123L,
                EncodedColumnData.SCHEMA_VERSION, true, ys, lengths, EncodedColumnData.crc32c(compressed.bytes()));
        var source = new VoxelColumnS2CPayload(7, Level.OVERWORLD, data).withTransferMetadata(99L, 0, 1, ys);
        assertArrayEquals(raw, source.decompressedSections());
        var parts = ColumnPayloadSplitter.splitForBandwidth(null, source, 4 * 1024 * 1024L, true);
        assertTrue(parts.size() > 1, "compressed size alone cannot bypass the per-fragment raw limit");
        for (var part : parts) assertTrue(roundTrip(part).rawSectionBytesLength() <= VSSConstants.MAX_COLUMN_PART_BYTES);
    }

    @Test void all256LargeSectionsCanUseMoreThan64Fragments() {
        int[] ys = HighWorldColumnFixtures.heights(256);
        int[] lengths = new int[256]; Arrays.fill(lengths, 10000);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        byte[] raw;
        try {
            buffer.writeVarInt(256).writeZero(256 * 10000);
            raw = new byte[buffer.readableBytes()]; buffer.readBytes(raw);
        } finally { buffer.release(); }
        var data = new EncodedColumnData(0, 7, LodByteCompression.METHOD_NONE, raw.length, raw, 123L,
                EncodedColumnData.SCHEMA_VERSION, true, ys, lengths, EncodedColumnData.crc32c(raw));
        var source = new VoxelColumnS2CPayload(7, Level.OVERWORLD, data).withTransferMetadata(101L, 0, 1, ys);
        var parts = ColumnPayloadSplitter.splitForBandwidth(null, source, 4 * 1024L, false);
        assertEquals(256, parts.size());
        assertEquals(256, roundTrip(parts.get(255)).replacementSectionYs().length);
    }

    @Test void invalidHeightDuplicatesAndOversizedManifestsStillFail() {
        for (int[] ys : new int[][] {{128}, {-129}, {0, 0}, new int[257]}) {
            assertThrows(IllegalArgumentException.class, () -> new VoxelColumnS2CPayload(
                    7, 0, 7, Level.OVERWORLD, 123L, new byte[] {0}, true, 42L, 0, 1, ys));
        }
    }

    private static byte[] dataBytes(EncodedColumnData data) {
        try { return LodByteCompression.decompress(data.encodedBytes(), data.compression(), data.rawSize(), VSSConstants.MAX_COLUMN_BYTES); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static VoxelColumnS2CPayload roundTrip(VoxelColumnS2CPayload source) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { VoxelColumnS2CPayload.encode(source, buffer); return VoxelColumnS2CPayload.decode(buffer); }
        finally { buffer.release(); }
    }
}

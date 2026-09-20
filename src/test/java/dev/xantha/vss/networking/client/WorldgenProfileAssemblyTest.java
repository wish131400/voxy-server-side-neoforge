package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.WorldgenProfileTransfer;
import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class WorldgenProfileAssemblyTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 128, 524288, 2930360, 8388608})
    void productionSplitterRoundTripsWithoutDependingOnCompression(int registryBytes) {
        var original = profile(registryBytes, 42);
        var parts = split(original);
        var assembly = new WorldgenProfileAssembly();
        byte[] encoded = encode(original);
        assertEquals(encoded.length, parts.get(0).total());
        assertEquals((encoded.length + 524287) / 524288, parts.size());
        if (registryBytes == 2930360) {
            assertEquals(2930380, encoded.length, "exact reported packet-size regression");
            assertEquals(6, parts.size());
        }
        WorldgenProfileS2CPayload restored = null;
        int offset = 0;
        for (var part : parts) {
            assertEquals(offset, part.offset());
            assertEquals(parts.get(0).transfer(), part.transfer());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                WorldgenProfileFragmentS2CPayload.encode(part, buf);
                assertTrue(buf.readableBytes() <= 524288 + 16);
                assertTrue(buf.readableBytes() + 128 < 1024 * 1024,
                        "leave ample space for custom-payload id and compression framing");
                restored = assembly.accept(WorldgenProfileFragmentS2CPayload.decode(buf));
                assertFalse(buf.isReadable());
                offset += part.data().length;
                if (offset < encoded.length) assertNull(restored, "never publish a partial profile");
            } finally { buf.release(); }
        }
        assertNotNull(restored);
        assertTrue(original.sameWorldgen(restored));
        assertEquals(original.revision(), restored.revision());
        assertArrayEquals(encoded, encode(restored));
    }

    @Test
    void exactChunkBoundaryDoesNotSendEmptyTrailingFragment() {
        // With a three-byte registry length and raw length, metadata is 18 bytes.
        var parts = split(profile(524288 - 18, 1));
        assertEquals(1, parts.size());
        assertEquals(524288, parts.get(0).data().length);
    }

    @Test
    void vanillaFrameEncoderRejectsReportedSizeButAcceptsFragmentsWithAndWithoutCompression() {
        var original = profile(2930360, 42);
        for (boolean compressed : new boolean[]{false, true}) {
            EmbeddedChannel oversized = frameEncoder(compressed);
            try {
                assertThrows(EncoderException.class,
                        () -> oversized.writeOutbound(Unpooled.wrappedBuffer(encode(original))));
            } finally { oversized.finishAndReleaseAll(); }
            EmbeddedChannel fragmented = frameEncoder(compressed);
            try {
                for (var part : split(original)) {
                    FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
                    WorldgenProfileFragmentS2CPayload.encode(part, payload);
                    assertTrue(fragmented.writeOutbound(payload));
                    FriendlyByteBuf frame = new FriendlyByteBuf(fragmented.readOutbound());
                    try {
                        int frameSize = frame.readVarInt();
                        assertEquals(frameSize, frame.readableBytes());
                        assertTrue(frameSize < 1024 * 1024);
                    } finally { frame.release(); }
                }
            } finally { fragmented.finishAndReleaseAll(); }
        }
    }

    private static EmbeddedChannel frameEncoder(boolean compressed) {
        return compressed
                ? new EmbeddedChannel(new Varint21LengthFieldPrepender(), new CompressionEncoder(256))
                : new EmbeddedChannel(new Varint21LengthFieldPrepender());
    }

    @Test
    void newTransferReplacesIncompleteOldWorld() {
        var oldParts = split(profile(600000, 1));
        var newProfile = profile(600000, 2);
        var newParts = split(newProfile);
        assertNotEquals(oldParts.get(0).transfer(), newParts.get(0).transfer());
        var assembly = new WorldgenProfileAssembly();
        assertNull(assembly.accept(oldParts.get(0)));
        assertNull(assembly.accept(newParts.get(0)));
        assertTrue(newProfile.sameWorldgen(assembly.accept(newParts.get(1))));
    }

    @Test
    void missingDuplicateMismatchedAndDisconnectedFragmentsCannotPublish() {
        var parts = split(profile(1200000, 1));
        var assembly = new WorldgenProfileAssembly();
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(parts.get(1)));
        assertNull(assembly.accept(parts.get(0)));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(parts.get(2)));
        assertNull(assembly.accept(parts.get(0)));
        assertNull(assembly.accept(parts.get(1)));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(parts.get(1)));
        assertNull(assembly.accept(parts.get(0)));
        var second = parts.get(1);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(new WorldgenProfileFragmentS2CPayload(
                second.transfer() + 1, second.total(), second.offset(), second.data())));
        assertNull(assembly.accept(parts.get(0)));
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(new WorldgenProfileFragmentS2CPayload(
                second.transfer(), second.total() + 1, second.offset(), second.data())));
        assertNull(assembly.accept(parts.get(0)));
        assembly.clear(); // Same cleanup used at logout/login and session reconfiguration.
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(parts.get(1)));
    }

    @Test
    void idleTimeoutReleasesIncompleteTransferAndProgressRenewsDeadline() {
        AtomicLong now = new AtomicLong();
        var assembly = new WorldgenProfileAssembly(now::get);
        var parts = split(profile(1200000, 1));
        assertNull(assembly.accept(parts.get(0)));
        now.set(WorldgenProfileAssembly.IDLE_TIMEOUT_NANOS - 1);
        assertFalse(assembly.expire());
        assertNull(assembly.accept(parts.get(1)));
        now.addAndGet(WorldgenProfileAssembly.IDLE_TIMEOUT_NANOS - 1);
        assertFalse(assembly.expire());
        now.incrementAndGet();
        assertTrue(assembly.expire());
        assertFalse(assembly.expire());
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(parts.get(2)));
        WorldgenProfileS2CPayload recovered = null;
        for (var part : parts) recovered = assembly.accept(part);
        assertNotNull(recovered);
    }

    @Test
    void invalidLengthsAreRejectedBeforeAssemblyAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, 0, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, Integer.MAX_VALUE, 0, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, 10, -1, new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, 10, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, 10, 9, new byte[2]));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileFragmentS2CPayload(1, 600000, 0, new byte[524289]));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(1);
            buf.writeVarInt(600000);
            buf.writeVarInt(0);
            buf.writeByteArray(new byte[524289]);
            assertThrows(RuntimeException.class, () -> WorldgenProfileFragmentS2CPayload.decode(buf));
        } finally { buf.release(); }
    }

    @Test
    void malformedOrTrailingProfileDataIsNotInstalledAndNextTransferCanRecover() {
        var assembly = new WorldgenProfileAssembly();
        assertThrows(RuntimeException.class, () -> assembly.accept(new WorldgenProfileFragmentS2CPayload(1, 1, 0, new byte[1])));
        byte[] encoded = encode(profile(128, 1));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> assembly.accept(
                new WorldgenProfileFragmentS2CPayload(2, trailing.length, 0, trailing)));
        assertNotNull(assembly.accept(split(profile(128, 1)).get(0)));
    }

    @Test
    void encodingFailureDoesNotSendPartialTransfer() {
        var invalid = new WorldgenProfileS2CPayload(3, 1, 1, List.of(
                new WorldgenProfileS2CPayload.DimensionProfile(null, -64, 384,
                        "x".repeat(WorldgenProfileS2CPayload.MAX_STRING_LENGTH + 1), "test", 1)));
        var sent = new ArrayList<WorldgenProfileFragmentS2CPayload>();
        assertThrows(RuntimeException.class, () -> WorldgenProfileTransfer.send(invalid, sent::add));
        assertTrue(sent.isEmpty());
    }

    private static WorldgenProfileS2CPayload profile(int size, long seed) {
        byte[] registries = new byte[size];
        new Random(seed).nextBytes(registries);
        return new WorldgenProfileS2CPayload(3, seed, 7, 0, size, registries, List.of());
    }

    private static List<WorldgenProfileFragmentS2CPayload> split(WorldgenProfileS2CPayload profile) {
        var result = new ArrayList<WorldgenProfileFragmentS2CPayload>();
        WorldgenProfileTransfer.send(profile, result::add);
        return result;
    }

    private static byte[] encode(WorldgenProfileS2CPayload profile) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WorldgenProfileS2CPayload.encode(profile, buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally { buf.release(); }
    }
}

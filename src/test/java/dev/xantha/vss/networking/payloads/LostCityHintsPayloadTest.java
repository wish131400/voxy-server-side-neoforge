package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class LostCityHintsPayloadTest {
    @Test void infrastructureRoundTripsSignedHeightWaterVariantsAndBottomFaces() {
        var bottom = new LostCityPreview.Model(new int[]{LostCityPreview.origin(0, 0, 0),
                LostCityPreview.extent(16, 0, 16, 5), 1});
        var value = LostCityPreview.EMPTY.withOverlays(List.of(new LostCityPreview.Overlay(-20, model(), bottom, bottom, bottom)));
        var region = java.util.Collections.nCopies(64, value);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LostCityPreview.write(buffer, region);
            assertEquals(region, LostCityPreview.read(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new LostCityPreview.Overlay(0, bottom, bottom, bottom, null));
        assertThrows(IllegalArgumentException.class, () -> new LostCityPreview.Model(new int[]{
                LostCityPreview.origin(0, 0, 0), LostCityPreview.extent(16, 1, 16, 5), 1}));
    }
    private static LostCityPreview.Model model() {
        return new LostCityPreview.Model(new int[] {
                LostCityPreview.origin(0, 0, 0), LostCityPreview.extent(16, 24, 0, 1), 1
        });
    }

    private static List<LostCityPreview.Chunk> entries() {
        var result = new ArrayList<LostCityPreview.Chunk>(LostCityHintsS2CPayload.ENTRY_COUNT);
        var building = new LostCityPreview.Chunk(LostCityPreview.BUILDING, -4, true,
                1, 1, 0, 0,
                List.of(new LostCityPreview.Placement(0, model())), model());
        var publicModel = new LostCityPreview.Model(new int[]{
                LostCityPreview.origin(0, 1, 0), LostCityPreview.extent(16, 0, 16, 0), 1,
                LostCityPreview.origin(2, 3, 2), LostCityPreview.extent(12, 0, 1, 0), 2});
        var road = new LostCityPreview.Chunk(LostCityPreview.ROAD, 64, true, 1, 1, 8, 15,
                List.of(new LostCityPreview.Placement(0, publicModel)), publicModel);
        var park = new LostCityPreview.Chunk(LostCityPreview.PARK, 65, true, 1, 1, 0, 0,
                List.of(new LostCityPreview.Placement(0, publicModel)), publicModel);
        for (int i = 0; i < LostCityHintsS2CPayload.ENTRY_COUNT; i++)
            result.add(i == 63 ? building : i == 0 ? road : i == 1 ? park : LostCityPreview.EMPTY);
        return result;
    }

    @Test
    void boundedMessageRoundTripsAndOwnsItsList() {
        var entries = entries();
        var payload = new LostCityHintsS2CPayload(ResourceLocation.parse("lostcities:lostcity"),
                -1, 2, 17, true, entries);
        entries.set(63, LostCityPreview.EMPTY);
        var buffer = new RegistryFriendlyByteBuf(new FriendlyByteBuf(Unpooled.buffer()), RegistryAccess.EMPTY);
        LostCityHintsS2CPayload.STREAM_CODEC.encode(buffer, payload);
        assertTrue(buffer.readableBytes() < 8192);
        var decoded = LostCityHintsS2CPayload.STREAM_CODEC.decode(buffer);
        try {
            assertEquals(payload.dimension(), decoded.dimension());
            assertEquals(-1, decoded.regionX());
            assertEquals(2, decoded.regionZ());
            assertEquals(17, decoded.session());
            assertEquals(payload.chunks(), decoded.chunks());
            assertThrows(UnsupportedOperationException.class,
                    () -> decoded.chunks().set(0, LostCityPreview.EMPTY));
            assertEquals(LostCityPreview.BUILDING, decoded.chunks().get(63).kind());
            assertEquals(LostCityPreview.ROAD, decoded.chunks().get(0).kind());
            assertEquals(LostCityPreview.PARK, decoded.chunks().get(1).kind());
            assertSame(decoded.chunks().get(0).floors().get(0).model(), decoded.chunks().get(1).floors().get(0).model());
        } finally { buffer.release(); }
    }

    @Test
    void rejectsInvalidEntryCounts() {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        assertThrows(IllegalArgumentException.class, () ->
                new LostCityHintsS2CPayload(dimension, 0, 0, 1, true, List.of(LostCityPreview.EMPTY)));
        assertThrows(IllegalArgumentException.class, () ->
                new LostCityHintsS2CPayload(dimension, 0, 0, 1, false,
                        List.of(LostCityPreview.EMPTY)));
    }

    @Test
    void repeatedFloorsShareOneWireModelAndStayBelowThePacketLimit() {
        var shape = model();
        var floors = new ArrayList<LostCityPreview.Placement>();
        for (int f = 0; f < 32; f++) floors.add(new LostCityPreview.Placement(f * 24, shape));
        var chunk = new LostCityPreview.Chunk(LostCityPreview.BUILDING, 64, true,
                1, 1, 0, 0, floors, shape);
        var region = java.util.Collections.nCopies(64, chunk);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            LostCityPreview.write(buffer, region);
            assertTrue(buffer.readableBytes() < 16_384);
            assertEquals(1, buffer.getByte(0), "the shared model is encoded once");
            var decoded = LostCityPreview.read(buffer);
            assertEquals(region, decoded);
            assertSame(decoded.get(0).floors().get(0).model(), decoded.get(63).floors().get(31).model());
        } finally { buffer.release(); }
    }

    @Test
    void malformedModelIndexAndMissingTableAreRejected() {
        for (int count : new int[] {0, 1}) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeVarInt(count);
                if (count != 0) buffer.writeVarInt(0);
                buffer.writeByte(2).writeShort(64).writeBoolean(true);
                buffer.writeVarInt(1).writeVarInt(1).writeByte(0).writeByte(0);
                buffer.writeVarInt(0).writeVarInt(1).writeVarInt(0).writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> LostCityPreview.read(buffer));
            } finally { buffer.release(); }
        }
    }
}

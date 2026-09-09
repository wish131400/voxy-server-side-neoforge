package dev.xantha.vss.networking.payloads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WorldgenProfileS2CPayloadTest {
    @Test
    void generationToggleRevisionDoesNotInvalidateThePredictionWorld() {
        var a = new WorldgenProfileS2CPayload(3, 42L, 1L, 0, 2, new byte[]{1, 2}, List.of());
        var b = new WorldgenProfileS2CPayload(3, 42L, 999L, 0, 2, new byte[]{1, 2}, List.of());
        assertTrue(a.sameWorldgen(b), "ordinary settings changes must keep meshes and pending decode");
        org.junit.jupiter.api.Assertions.assertFalse(a.sameWorldgen(new WorldgenProfileS2CPayload(
                3, 42L, 999L, 0, 2, new byte[]{1, 3}, List.of())), "data reload must invalidate");
        org.junit.jupiter.api.Assertions.assertFalse(a.sameWorldgen(new WorldgenProfileS2CPayload(
                3, 43L, 999L, 0, 2, new byte[]{1, 2}, List.of())), "another seed must invalidate");
    }
    @Test
    void roundTripPreservesSeedAndDimensionMetadata() {
        WorldgenProfileS2CPayload original = new WorldgenProfileS2CPayload(
                WorldgenProfileS2CPayload.FORMAT_VERSION,
                123456789L,
                17L,
                List.of(new WorldgenProfileS2CPayload.DimensionProfile(
                        ResourceLocation.withDefaultNamespace("overworld"),
                        987654321L,
                        -64,
                        384,
                        "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
                        "minecraft:overworld",
                        99L)));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            WorldgenProfileS2CPayload.encode(original, buffer);
            WorldgenProfileS2CPayload decoded = WorldgenProfileS2CPayload.decode(buffer);
            assertEquals(original.formatVersion(), decoded.formatVersion());
            assertEquals(original.seed(), decoded.seed());
            assertEquals(original.revision(), decoded.revision());
            assertEquals(original.registriesCompression(), decoded.registriesCompression());
            assertEquals(original.registriesRawSize(), decoded.registriesRawSize());
            assertArrayEquals(original.registries(), decoded.registries());
            assertEquals(original.dimensions().size(), decoded.dimensions().size());
            assertEquals(original.dimensions().get(0).dimension(), decoded.dimensions().get(0).dimension());
            assertEquals(original.dimensions().get(0).seed(), decoded.dimensions().get(0).seed());
            assertEquals(original.dimensions().get(0).minY(), decoded.dimensions().get(0).minY());
            assertEquals(original.dimensions().get(0).height(), decoded.dimensions().get(0).height());
            assertEquals(original.dimensions().get(0).generatorType(), decoded.dimensions().get(0).generatorType());
            assertEquals(original.dimensions().get(0).generatorSettings(), decoded.dimensions().get(0).generatorSettings());
            assertEquals(original.dimensions().get(0).fingerprint(), decoded.dimensions().get(0).fingerprint());
            assertArrayEquals(original.dimensions().get(0).generatorData(), decoded.dimensions().get(0).generatorData());
            assertTrue(decoded.dimensions().get(0).levelKey().location().toString().equals("minecraft:overworld"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedSnapshotMetadataBeforeItCanReachTheDecoder() {
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileS2CPayload(
                WorldgenProfileS2CPayload.FORMAT_VERSION,
                1L,
                1L,
                0,
                WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES + 1,
                new byte[0],
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "noise", "minecraft:overworld", 1L,
                0, WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES + 1, new byte[0]));
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import org.junit.jupiter.api.Test;

class PredictionInitializationTest {
    @Test void encodedStatesMatchVanillaCodecForEntireRegistry() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        for (var block : BuiltInRegistries.BLOCK) {
            for (var state : block.getStateDefinition().getPossibleStates()) {
                assertEquals(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow(),
                        RustWorldgenDocument.encodeState(state));
            }
        }
    }

    @Test void preferredDimensionComesFirstWithoutMutatingPayloadOrder() {
        var overworld = profile("overworld");
        var nether = profile("the_nether");
        var end = profile("the_end");
        var original = List.of(overworld, nether, end);
        assertEquals(List.of(end, overworld, nether),
                ClientWorldgenProfileDecoder.orderedDimensions(original, Level.END));
        assertEquals(List.of(overworld, nether, end), original);
        assertEquals(original, ClientWorldgenProfileDecoder.orderedDimensions(original, null));
    }

    @Test void sharedInputsRebuildAfterResourceInvalidation() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var shared = new RustWorldgenDocument.SharedInputs();
        shared.prepare();
        var first = shared.stateLookup();
        shared.prepare();
        assertSame(first, shared.stateLookup());
        RustWorldgenDocument.invalidateSharedInputs();
        shared.prepare();
        assertNotSame(first, shared.stateLookup());
        assertEquals(first, shared.stateLookup());
    }

    @Test void nativePaletteMappingMatchesCodecWithoutAssumingStateOrder() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        var shared = new RustWorldgenDocument.SharedInputs();
        shared.prepare();
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        var profile = profile("overworld");
        var context = new ClientTerrainSampler(1, profile);
        try (var original = new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()),
                profile, context);
             var mapped = new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()),
                profile, context, shared.stateLookup())) {
            assertArrayEquals(original.states(), mapped.states());
            for (int i = 0; i < mapped.states().length; i++) {
                assertEquals(i, mapped.stateId(mapped.states()[i]));
            }
            assertEquals(original.sample(17, -19), mapped.sample(17, -19));
        }
    }

    private static DimensionProfile profile(String dimension) {
        return new DimensionProfile(ResourceLocation.withDefaultNamespace(dimension), -64, 384,
                "noise", "minecraft:" + dimension, 1L);
    }
}

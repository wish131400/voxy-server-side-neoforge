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

    @Test void compactPalettePreservesEveryStateAndOutlivesOwnerHandle() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(RustTerrainSampler.available());
        try (var shared = new RustWorldgenDocument.SharedInputs()) {
            long palette = shared.palette();
            assertEquals(palette, shared.palette());
            BlockState[] canonical = shared.canonicalStates();
            var doc = LithostitchedNativeTest.document();
            doc.remove("block_definitions"); doc.remove("input_states");
            doc.addProperty("vss_shared_palette", palette);
            doc.add("possible_biomes", new com.google.gson.JsonArray());
            var legacy = doc.deepCopy(); legacy.remove("vss_shared_palette");
            legacy.add("block_definitions", shared.compactPalette().get("block_definitions"));
            var fullStates = new com.google.gson.JsonArray();
            for (BlockState state : canonical) fullStates.add(RustWorldgenDocument.encodeState(state));
            legacy.add("input_states", fullStates);
            var profile = profile("overworld");
            var context = new ClientTerrainSampler(1, profile);
            try (var original = new RustTerrainSampler(RustWorldgenBackend.create(1, 0, legacy.toString()), profile, context);
                 var compact = new RustTerrainSampler(RustWorldgenBackend.create(1, 0, doc.toString()), profile, context, canonical)) {
                assertArrayEquals(original.states(), compact.states());
                for (BlockState state : canonical) assertTrue(compact.stateId(state) >= 0, state::toString);
                // Worlds must own the palette snapshot, not borrow the handle's lifetime.
                shared.close();
                assertThrows(IllegalArgumentException.class, () -> RustWorldgenBackend.create(1, 0, doc.toString()));
                assertEquals(original.sample(17, -19), compact.sample(17, -19));
                assertEquals(original.sample(-17, 19), compact.sample(-17, 19));
            }
        }
    }

    @Test void compactGroupsEncodeCanonicalOrderAndAreSmallerThanStateJson() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var shared = new RustWorldgenDocument.SharedInputs()) {
            var compact = shared.compactPalette();
            int index = 0, legacyChars = 0;
            for (var element : compact.getAsJsonArray("groups")) {
                var group = element.getAsJsonObject();
                var keys = group.getAsJsonArray("properties");
                var values = group.getAsJsonArray("values");
                for (var encoded : group.getAsJsonArray("codes")) {
                    long code = encoded.getAsLong();
                    var expected = RustWorldgenDocument.encodeState(shared.canonicalStates()[index++]);
                    assertEquals(expected.get("Name"), group.get("name"));
                    for (int i = 0; i < keys.size(); i++) {
                        var choices = values.get(i).getAsJsonArray();
                        assertEquals(expected.getAsJsonObject("Properties").get(keys.get(i).getAsString()),
                                choices.get((int) (code % choices.size())));
                        code /= choices.size();
                    }
                    assertEquals(0, code);
                    legacyChars += expected.toString().length();
                }
            }
            assertEquals(shared.canonicalStates().length, index);
            assertTrue(compact.getAsJsonArray("groups").toString().length() < legacyChars / 3);
        }
    }

    @Test void lazySessionOpensOnlyRequestedDimensionAndHonorsCancellation() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        var opened = new java.util.ArrayList<net.minecraft.resources.ResourceLocation>();
        PredictionTerrainBackends.register(new PredictionTerrainBackend() {
            public String id() { return "vss-test:lazy-session"; }
            public int priority() { return Integer.MAX_VALUE; }
            public java.util.Optional<ClientTerrainSampler> open(DimensionProfile profile, long seed,
                    net.minecraft.core.RegistryAccess access) {
                if (!enabled.get() || !profile.generatorType().equals(id())) return java.util.Optional.empty();
                opened.add(profile.dimension());
                return java.util.Optional.of(new ClientTerrainSampler(seed, profile));
            }
        });
        var a = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), -64, 384,
                "vss-test:lazy-session", "test", 1L);
        var b = new DimensionProfile(ResourceLocation.withDefaultNamespace("the_nether"), 0, 256,
                "vss-test:lazy-session", "test", 2L);
        var payload = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload(3, 42, 1, List.of(a, b));
        try (var session = new ClientWorldgenProfileDecoder.Session(payload, net.minecraft.core.RegistryAccess.EMPTY,
                new com.google.gson.JsonObject(), null, null)) {
            assertTrue(opened.isEmpty());
            var published = new java.util.ArrayList<net.minecraft.resources.ResourceKey<Level>>();
            session.decode(Level.OVERWORLD, () -> true, (key, sampler) -> published.add(key));
            assertEquals(List.of(a.dimension()), opened);
            assertEquals(List.of(Level.OVERWORLD), published);
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> session.decode(Level.NETHER, () -> false, (key, sampler) -> fail("stale publish")));
            assertEquals(1, opened.size());
            session.decode(Level.NETHER, () -> true, (key, sampler) -> published.add(key));
            assertEquals(List.of(a.dimension(), b.dimension()), opened);
            assertEquals(List.of(Level.OVERWORLD, Level.NETHER), published);
            session.close();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> session.decode(Level.OVERWORLD, () -> true, (key, sampler) -> fail("closed publish")));
        } finally { enabled.set(false); }
    }

    private static DimensionProfile profile(String dimension) {
        return new DimensionProfile(ResourceLocation.withDefaultNamespace(dimension), -64, 384,
                "noise", "minecraft:" + dimension, 1L);
    }
}

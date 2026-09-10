package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.DensityFunctionSnapshot;
import dev.xantha.vss.common.worldgen.DensityFunctionReferences;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DensityFunctionSnapshotTest {
    @BeforeAll
    static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void nestedDirectHoldersRoundTripWithoutChangingDensity() {
        DensityFunction slope = DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0);
        DensityFunction nested = new DensityFunctions.HolderHolder(Holder.direct(
                new DensityFunctions.HolderHolder(Holder.direct(slope))));
        DensityFunction graph = DensityFunctions.add(nested, DensityFunctions.constant(0.25));
        assertThrows(UnsupportedOperationException.class,
                () -> DensityFunction.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow());
        assertRoundTrip(graph);
    }

    @Test
    void appliedModifierGraphIsCapturedInsteadOfOriginalCodec() {
        DensityFunction original = DensityFunctions.constant(-0.5);
        DensityFunction modified = DensityFunctions.add(
                DensityFunctions.yClampedGradient(-64, 320, 2, -2), original);
        DensityFunction wrapper = new AppliedModifier(original, modified);
        assertRoundTrip(wrapper);
    }

    @Test
    void appliedNoiseSettingsPreserveSeededDensityAndRemainBounded() {
        var lookup = VanillaRegistries.createLookup();
        var original = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,
                DensityFunctionSnapshot.applied(original)).getOrThrow();
        assertTrue(encoded.toString().length() < 2_000_000, "expanded router must fit the network budget");
        var decoded = NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
        var noises = lookup.lookupOrThrow(Registries.NOISE);
        var expected = RandomState.create(original, noises, 123456789L).router();
        var actual = RandomState.create(decoded, noises, 123456789L).router();
        for (int i = 0; i < 64; i++) {
            var position = new DensityFunction.SinglePointContext(i * 71 - 2048, i * 6 - 64, i * -37);
            assertEquals(expected.finalDensity().compute(position), actual.finalDensity().compute(position), 0.0);
            assertEquals(expected.initialDensityWithoutJaggedness().compute(position),
                    actual.initialDensityWithoutJaggedness().compute(position), 0.0);
        }
        assertEquals(original.surfaceRule(), decoded.surfaceRule());
    }

    @Test
    void repeatedAppliedGraphUsesBoundedSharedRegistryReferences() {
        var base = DensityFunctions.yClampedGradient(-64, 320, 0.5, -0.5);
        DensityFunction graph = base;
        for (int i = 0; i < 16; i++) graph = DensityFunctions.add(graph, graph);
        graph = graph.clamp(-0.75, 0.75);
        var expanded = DensityFunction.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, graph).getOrThrow();
        assertTrue(expanded.toString().length() > 8 * 1024 * 1024,
                "fixture must reproduce the generator snapshot raw-size failure");
        var references = new DensityFunctionReferences();
        var compact = references.compact(expanded);
        assertTrue(compact.toString().length() + references.definitions().toString().length() < 16_384);
        assertTrue(compact.getAsJsonObject().get("input").isJsonObject(),
                "Clamp.input requires DIRECT_CODEC rather than a holder reference");
        var root = new com.google.gson.JsonObject();
        root.add("noises", new com.google.gson.JsonObject());
        root.add("density_functions", references.definitions());
        var registries = ClientWorldgenRegistries.decode(root, net.minecraft.core.RegistryAccess.EMPTY);
        var decoded = DensityFunction.DIRECT_CODEC.parse(registries.ops(), compact).getOrThrow();
        for (int y : new int[]{-64, 127, 128, 129, 320}) {
            var position = new DensityFunction.SinglePointContext(-16, y, 31);
            assertEquals(graph.compute(position), decoded.compute(position), 0.0);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
    void installedLithostitchedAppliedGraphRoundTrips() throws Exception {
        var url = java.nio.file.Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            Class<?> type;
            try {
                type = loader.loadClass("dev.worldgen.lithostitched.impl.worldgen.densityfunction.marker.MergedDensityFunction");
            } catch (ClassNotFoundException legacyRelease) {
                type = loader.loadClass("dev.worldgen.lithostitched.worldgen.densityfunction.MergedDensityFunction");
            }
            var original = DensityFunctions.constant(-0.5);
            DensityFunction nested = new DensityFunctions.HolderHolder(Holder.direct(
                    new DensityFunctions.HolderHolder(Holder.direct(original))));
            var full = DensityFunctions.add(nested, DensityFunctions.yClampedGradient(-64, 320, 2, -2));
            var graph = (DensityFunction) type.getConstructor(DensityFunction.class, DensityFunction.class,
                    DensityFunction.class).newInstance(original, original, full);
            assertRoundTrip(graph);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
    void installedTectonicDefaultConfigFunctionsRoundTrip() throws Exception {
        var url = java.nio.file.Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            String prefix = "dev.worldgen.tectonic.worldgen.densityfunction.";
            Class<?> constantType = loader.loadClass(prefix + "ConfigConstant");
            DensityFunction constant;
            try {
                constant = (DensityFunction) constantType.getConstructor(double.class).newInstance(0.375);
            } catch (NoSuchMethodException legacyRelease) {
                constant = (DensityFunction) constantType.getConstructor(double.class, double.class, double.class)
                        .newInstance(0.375, 0.375, 0.375);
            }
            assertRoundTrip(constant);
            try {
                var clamp = (DensityFunction) loader.loadClass(prefix + "ConfigClamp")
                        .getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class)
                        .newInstance(DensityFunctions.yClampedGradient(-64, 320, 2, -2),
                                DensityFunctions.constant(-0.5), constant);
                assertRoundTrip(clamp);
            } catch (ClassNotFoundException legacyRelease) {
                assertRoundTrip(DensityFunctions.add(constant, DensityFunctions.yClampedGradient(-64, 320, 2, -2)));
            }
            var noise = new DensityFunction.NoiseHolder(Holder.direct(
                    new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-3, 1.0)));
            Class<?> noiseType = loader.loadClass(prefix + "ConfigNoise");
            DensityFunction configured;
            try {
                configured = (DensityFunction) noiseType
                        .getConstructor(DensityFunction.NoiseHolder.class, DensityFunction.class,
                                DensityFunction.class, double.class, double.class, double.class, boolean.class)
                        .newInstance(noise, constant, DensityFunctions.zero(), 0.13, 1.25, -0.8, false);
            } catch (NoSuchMethodException legacyRelease) {
                configured = (DensityFunction) noiseType
                        .getConstructor(DensityFunction.NoiseHolder.class, DensityFunction.class,
                                DensityFunction.class, double.class, double.class, double.class)
                        .newInstance(noise, constant, DensityFunctions.zero(), 0.13, 1.25, -0.8);
            }
            assertRoundTrip(configured);
            var seedNoise = new DensityFunction.Visitor() {
                public DensityFunction apply(DensityFunction value) { return value; }
                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) {
                    return new DensityFunction.NoiseHolder(value.noiseData(),
                            net.minecraft.world.level.levelgen.synth.NormalNoise.create(
                                    new net.minecraft.world.level.levelgen.XoroshiroRandomSource(78123L), value.noiseData().value()));
                }
            };
            var seeded = configured.mapAll(seedNoise);
            var applied = DensityFunctionSnapshot.applied(seeded);
            var encoded = DensityFunction.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,
                    DensityFunctionSnapshot.applied(configured)).getOrThrow();
            var restored = DensityFunction.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().mapAll(seedNoise);
            for (int i = -16; i <= 16; i++) {
                var point = new DensityFunction.SinglePointContext(i * 137, i * 11, -i * 79);
                assertEquals(seeded.compute(point), applied.compute(point), 0.0, "retain initialized noise");
                assertEquals(seeded.compute(point), restored.compute(point), 0.0, "restore noise after snapshot decoding");
            }
        }
    }

    private static void assertRoundTrip(DensityFunction graph) {
        var encoded = DensityFunction.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,
                DensityFunctionSnapshot.applied(graph)).getOrThrow();
        var decoded = DensityFunction.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        for (int y = -64; y <= 320; y += 7) {
            var position = new DensityFunction.SinglePointContext(-131, y, 257);
            assertEquals(graph.compute(position), decoded.compute(position), 0.0);
        }
    }

    private record AppliedModifier(DensityFunction original, DensityFunction full)
            implements DensityFunction {
        public double compute(FunctionContext context) { return full.compute(context); }
        public void fillArray(double[] values, ContextProvider context) { full.fillArray(values, context); }
        public DensityFunction mapAll(Visitor visitor) { return full.mapAll(visitor); }
        public double minValue() { return full.minValue(); }
        public double maxValue() { return full.maxValue(); }
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new AssertionError("The source-only codec must not be used for an applied snapshot");
        }
    }
}

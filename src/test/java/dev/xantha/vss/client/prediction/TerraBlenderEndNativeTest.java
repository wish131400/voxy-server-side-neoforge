package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.worldgen.TerraBlenderEndRouting;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real Java registry/End sampler -> serialized routing contract -> JNI -> Rust. */
class TerraBlenderEndNativeTest {
    private static final String[] AREAS = {"highlands", "midlands", "edge", "islands"};
    private static final String[] PRIMARY = {"minecraft:end_highlands", "minecraft:end_midlands",
            "minecraft:end_barrens", "minecraft:small_end_islands"};
    private static final String[] SECONDARY = {"minecraft:forest", "minecraft:taiga",
            "minecraft:desert", "minecraft:plains"};
    private static NoiseGeneratorSettings endSettings;
    private static HolderLookup.Provider vanillaLookup;
    private static JsonObject encodedSettings;
    private static ClientWorldgenRegistries registries;

    @BeforeAll static void bootstrap() {
        TerrainPredictionFixtures.bootstrap();
        String library = System.getProperty("vss.testNativeLibrary");
        assertNotNull(library, "This integration test requires an explicit, rebuilt native library");
        assertTrue(Files.isRegularFile(Path.of(library)), library);
        RustWorldgenBackend.load(Path.of(library));
        assertEquals(6, RustWorldgenBackend.abi());
        var access = TerrainPredictionFixtures.access();
        vanillaLookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        endSettings = vanillaLookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.END).value();
        encodedSettings = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(
                vanillaLookup.createSerializationContext(JsonOps.INSTANCE), endSettings).getOrThrow().getAsJsonObject();
        assertEquals(0, encodedSettings.getAsJsonObject("noise").get("min_y").getAsInt());
        assertEquals(128, encodedSettings.getAsJsonObject("noise").get("height").getAsInt());
        assertEquals("minecraft:end_stone", encodedSettings.getAsJsonObject("default_block").get("Name").getAsString());
        registries = ClientWorldgenRegistries.decode(TerrainPredictionFixtures.emptyRegistries(), access);
    }

    private static JsonObject route(long seed) {
        var route = new JsonObject();
        route.addProperty("revision", 1);
        route.addProperty("seed", seed);
        route.addProperty("density_coordinates", "block");
        for (int i = 0; i < AREAS.length; i++) {
            var area = new JsonObject();
            area.addProperty("size", i + 1);
            area.addProperty("default_biome", "minecraft:ocean");
            var entries = new JsonArray();
            // Duplicate biome identity must retain its original weighted entry order.
            for (int n = 0; n < 3; n++) {
                var entry = new JsonObject();
                entry.addProperty("biome", n == 1 ? SECONDARY[i] : PRIMARY[i]);
                entry.addProperty("weight", new int[]{10, 100, 100}[n]);
                entries.add(entry);
            }
            area.add("biomes", entries);
            route.add(AREAS[i], area);
        }
        return route;
    }

    private static JsonObject document(JsonObject route) throws Exception {
        Path root = Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        JsonObject doc = JsonParser.parseString(Files.readString(root.resolve("end.json"))).getAsJsonObject();
        // Export the running 1.21.1 registry settings; do not rely on a fabricated router.
        doc.add("settings", encodedSettings.deepCopy());
        for (var entry : Map.of("block_definitions", "blocks", "biomes", "biomes",
                "grass_colormap", "grass", "foliage_colormap", "foliage").entrySet())
            doc.add(entry.getKey(), JsonParser.parseString(Files.readString(root.resolve(entry.getValue() + ".json"))));
        doc.add(TerraBlenderEndRouting.FIELD, route);
        var possible = new JsonArray();
        new TerraBlenderEndRouting(route, route.get("seed").getAsLong()).possibleBiomes().stream().sorted().forEach(possible::add);
        doc.add("possible_biomes", possible);
        return doc;
    }

    private static List<int[]> points() {
        var result = new ArrayList<int[]>();
        for (int[] p : new int[][]{{0,0}, {255,0}, {256,0}, {257,0}, {260,0}, {-255,0}, {-256,0}, {-257,0},
                {0,256}, {0,257}, {0,-256}, {0,-257}, {181,181}, {184,184}, {-181,-181}, {-184,-184},
                {256,-1}, {-256,-1}, {7_499_999,-7_499_999}, {-7_499_999,7_499_999}})
            result.add(new int[]{p[0], -17, p[1]});
        for (int x = -1536; x <= 1536; x += 137)
            for (int z = -1536; z <= 1536; z += 151)
                result.add(new int[]{x, (x + z) % 47, z});
        return result;
    }

    private static int compare(long seed, JsonObject doc, TerraBlenderEndBiomeSource javaSource,
            Climate.Sampler sampler, List<int[]> points) {
        long world = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), doc.toString());
        assertNotEquals(0, world);
        try {
            var description = JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
            assertTrue(description.get("terrablender_routing").getAsBoolean(),
                    "ABI6 alone must not admit an old native that silently uses vanilla End routing");
            var compact = JsonParser.parseString(RustWorldgenBackend.describeCompact(world)).getAsJsonObject();
            assertTrue(compact.get("terrablender_routing").getAsBoolean());
            var names = description.getAsJsonArray("biome_names");
            assertEquals(names, compact.getAsJsonArray("biome_names"));
            int comparisons = 0;
            for (int start = 0; start < points.size(); start += 256) {
                int count = Math.min(256, points.size() - start);
                var input = ByteBuffer.allocateDirect(count * 12).order(ByteOrder.LITTLE_ENDIAN);
                var output = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < count; i++) for (int v : points.get(start + i)) input.putInt(v);
                assertEquals(count, RustWorldgenBackend.biomeIndices(world, input, output, count));
                for (int i = 0; i < count; i++) {
                    int[] p = points.get(start + i);
                    String expected = javaSource.getNoiseBiome(p[0], p[1], p[2], sampler)
                            .unwrapKey().orElseThrow().location().toString();
                    int index = output.getInt(i * 4);
                    assertTrue(index >= 0 && index < names.size(), "invalid native biome palette index");
                    assertEquals(expected, names.get(index).getAsString(),
                            "seed=" + seed + ", quart=" + Arrays.toString(p));
                    comparisons++;
                }
            }
            return comparisons;
        } finally { assertEquals(1, RustWorldgenBackend.close(world)); }
    }

    @Test void realEndNoiseSettingsAndWeightedRoutesMatchAcrossJniForThreeSeeds() throws Exception {
        var points = points();
        int comparisons = 0, exactCoordinateDifferences = 0;
        var observed = new HashSet<String>();
        for (long seed : new long[]{0, 42, -918273645L}) {
            var route = route(seed);
            var routing = new TerraBlenderEndRouting(route, seed);
            var source = new TerraBlenderEndBiomeSource(route, seed, registries);
            var state = RandomState.create(endSettings,
                    vanillaLookup.lookupOrThrow(Registries.NOISE), seed);
            comparisons += compare(seed, document(route), source, state.sampler(), points);
            for (int[] p : points) {
                observed.add(source.getNoiseBiome(p[0], p[1], p[2], state.sampler())
                        .unwrapKey().orElseThrow().location().toString());
                if (TerraBlenderEndRouting.central(p[0], p[2])) continue;
                int bx = p[0] << 2, bz = p[2] << 2;
                var exact = new DensityFunction.SinglePointContext(bx, p[1] << 2, bz);
                var snapped = new DensityFunction.SinglePointContext(((bx >> 4) * 2 + 1) * 8, p[1] << 2,
                        ((bz >> 4) * 2 + 1) * 8);
                if (!routing.sample(p[0], p[2], state.sampler().erosion().compute(exact)).equals(
                        routing.sample(p[0], p[2], state.sampler().erosion().compute(snapped)))) exactCoordinateDifferences++;
            }
        }
        assertTrue(observed.contains("minecraft:the_end"));
        for (String id : PRIMARY) assertTrue(observed.contains(id), "unsampled End area " + id);
        for (String id : SECONDARY) assertTrue(observed.contains(id), "weighted alternative never selected " + id);
        assertTrue(exactCoordinateDifferences > 0, "sample set must detect a regression to chunk-centered erosion");
        System.out.println("End JNI real-noise comparisons=" + comparisons + ", biomes=" + observed.size()
                + ", exact-vs-snapped differences=" + exactCoordinateDifferences);
    }

    @Test void thresholdAndCentralBoundaryCasesMatchJavaWithoutMockingNative() throws Exception {
        long seed = 42;
        var route = route(seed);
        var source = new TerraBlenderEndBiomeSource(route, seed, registries);
        var zero = DensityFunctions.zero();
        int comparisons = 0;
        for (double value : new double[]{Math.nextUp(.25), .25, -.0625, Math.nextDown(-.0625), -.21875, Math.nextDown(-.21875)}) {
            var doc = document(route);
            doc.getAsJsonObject("settings").getAsJsonObject("noise_router").addProperty("erosion", value);
            var sampler = new Climate.Sampler(zero, zero, zero, DensityFunctions.constant(value), zero, zero, List.of());
            comparisons += compare(seed, doc, source, sampler, points());
        }
        System.out.println("End JNI exact-threshold comparisons=" + comparisons);
    }

    @Test void malformedRequiredRouteCannotSilentlyFallBackThroughJni() throws Exception {
        var route = route(42);
        var doc = document(route);
        route.addProperty("density_coordinates", "chunk_center");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> RustWorldgenBackend.create(42, 0, doc.toString()));
        assertTrue(failure.getMessage().contains("required TerraBlender End routing contract"));
    }
}

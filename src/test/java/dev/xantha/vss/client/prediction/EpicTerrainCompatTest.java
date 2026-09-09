package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real released data packs, evaluated by both Minecraft and the packaged native library. */
@EnabledIfSystemProperty(named = "vss.epicTerrainJars", matches = ".+")
class EpicTerrainCompatTest {
    private static final Map<TagKey<Block>, List<Holder<Block>>> previousTags = new HashMap<>();

    @BeforeAll
    static void bootstrap() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String developmentLibrary = System.getProperty("vss.testNativeLibrary");
        if (developmentLibrary != null) RustWorldgenBackend.load(Path.of(developmentLibrary));
        else assertTrue(RustTerrainSampler.available(), "The shipped native backend must load");
        BuiltInRegistries.BLOCK.getTags().forEach(p -> previousTags.put(p.getFirst(), p.getSecond().stream().toList()));
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        for (var field : net.minecraft.tags.BlockTags.class.getFields()) {
            if (TagKey.class.isAssignableFrom(field.getType())) {
                var key = (TagKey<?>) field.get(null);
                blockTag(key.location(), tags);
            }
        }
        BuiltInRegistries.BLOCK.bindTags(tags);
    }

    @AfterAll
    static void restoreTags() {
        BuiltInRegistries.BLOCK.bindTags(previousTags);
    }

    private static List<Holder<Block>> blockTag(ResourceLocation id, Map<TagKey<Block>, List<Holder<Block>>> tags) throws Exception {
        var key = TagKey.create(Registries.BLOCK, id);
        if (tags.containsKey(key)) return tags.get(key);
        var values = new LinkedHashSet<Holder<Block>>();
        try (var input = EpicTerrainCompatTest.class.getResourceAsStream("/data/" + id.getNamespace() + "/tags/block/" + id.getPath() + ".json")) {
            if (input != null) for (var entry : JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("values")) {
                String value = entry.isJsonPrimitive() ? entry.getAsString() : entry.getAsJsonObject().get("id").getAsString();
                if (value.startsWith("#")) values.addAll(blockTag(ResourceLocation.parse(value.substring(1)), tags));
                else values.add(BuiltInRegistries.BLOCK.getHolderOrThrow(ResourceKey.create(Registries.BLOCK, ResourceLocation.parse(value))));
            }
        }
        var result = List.copyOf(values);
        tags.put(key, result);
        return result;
    }

    static List<String> releases() {
        return List.of(System.getProperty("vss.epicTerrainJars").split(";"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("releases")
    void releasedPackSelectsReproducibleBackendAndPreservesExtendedHeight(String jar) throws Exception {
        var vanilla = VanillaRegistries.createLookup();
        var root = new JsonObject();
        export(root, "noises", vanilla, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC);
        export(root, "density_functions", vanilla, Registries.DENSITY_FUNCTION, DensityFunction.DIRECT_CODEC);
        export(root, "configured_carvers", vanilla, Registries.CONFIGURED_CARVER, ConfiguredWorldCarver.DIRECT_CODEC);
        export(root, "configured_features", vanilla, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC);
        export(root, "placed_features", vanilla, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC);
        export(root, "biomes", vanilla, Registries.BIOME, Biome.DIRECT_CODEC);
        var groups = Map.of("noise", "noises", "density_function", "density_functions",
                "configured_carver", "configured_carvers", "configured_feature", "configured_features",
                "placed_feature", "placed_features", "biome", "biomes");
        JsonObject generator;
        JsonObject dimension;
        try (var zip = new ZipFile(jar)) {
            for (var entry : Collections.list(zip.entries())) {
                var parts = entry.getName().split("/", 5);
                if (parts.length != 5 || !parts[0].equals("data") || !parts[2].equals("worldgen")
                        || !parts[4].endsWith(".json") || !groups.containsKey(parts[3])) continue;
                String id = parts[1] + ":" + parts[4].substring(0, parts[4].length() - 5);
                root.getAsJsonObject(groups.get(parts[3])).add(id, read(zip, entry.getName()));
            }
            // The original edition replaces the climate parameter list as well as the noise router.
            if (zip.getEntry("data/minecraft/dimension/overworld.json") != null) {
                generator = read(zip, "data/minecraft/dimension/overworld.json").getAsJsonObject("generator");
            } else {
                generator = new JsonObject();
                var parameters = vanilla.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(ResourceKey.create(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                                ResourceLocation.withDefaultNamespace("overworld"))).value().parameters();
                generator.add("biome_source", BiomeSource.CODEC.encodeStart(vanilla.createSerializationContext(JsonOps.INSTANCE),
                        MultiNoiseBiomeSource.createFromList(parameters)).getOrThrow());
            }
            generator.add("settings", read(zip, "data/minecraft/worldgen/noise_settings/overworld.json"));
            dimension = read(zip, "data/minecraft/dimension_type/overworld.json");
        }
        assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, root),
                "Custom registry entry names must not disable vanilla-codec terrain in Rust");
        var fallback = RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var registries = ClientWorldgenRegistries.decode(root, fallback);
        var settings = NoiseGeneratorSettings.DIRECT_CODEC.parse(registries.ops(), generator.get("settings")).getOrThrow();
        assertTrue(settings.noiseSettings().height() > 384, "Exercise the pack's extended generation height");
        int minY = dimension.get("min_y").getAsInt(), height = dimension.get("height").getAsInt();
        for (long seed : new long[]{0, -918273645L, 123456789L}) {
            var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"), seed,
                    minY, height, "noise", "minecraft:overworld", 0L);
            var context = ClientWorldgenProfileDecoder.decodeJavaSampler(profile,
                    generator.toString().getBytes(StandardCharsets.UTF_8), registries, registries.access());
            assertInstanceOf(MinecraftColumnTerrainSampler.class, context);
            var doc = RustWorldgenDocument.snapshot(generator, root, context);
            for (String color : List.of("grass", "foliage")) doc.add(color + "_colormap", JsonParser.parseString(
                    Files.readString(Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen/" + color + ".json"))));
            long nativeWorld = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), doc.toString());
            try (var nativeSampler = new RustTerrainSampler(nativeWorld, profile, context)) {
            var directRandom = RandomState.create(settings, registries.noiseLookup(), seed);
            var directGenerator = new NoiseBasedChunkGenerator(
                    BiomeSource.CODEC.parse(registries.ops(), generator.get("biome_source")).getOrThrow(), Holder.direct(settings));
            var heights = LevelHeightAccessor.create(minY, height);
            var coordinates = new ArrayList<>(List.of(new int[][]{{0, 0}, {-17, 31}, {1001, -2049}, {-32768, 16384}, {8123, 9137}, {20480, -7168}}));
            var randomCoordinates = new Random(seed ^ 89123);
            for (int i = 0; i < 48; i++) coordinates.add(new int[]{randomCoordinates.nextInt(131073) - 65536, randomCoordinates.nextInt(131073) - 65536});
            for (int[] xz : coordinates) {
                var expected = directGenerator.getBaseColumn(xz[0], xz[1], heights, directRandom);
                var positions = direct(8).putInt(xz[0]).putInt(xz[1]);
                var nativeColumn = direct(16 + settings.noiseSettings().height() * 4);
                assertEquals(1, RustWorldgenBackend.columns(nativeWorld, positions, nativeColumn, 1));
                for (int y = settings.noiseSettings().minY(); y < settings.noiseSettings().minY() + settings.noiseSettings().height(); y++) {
                    int state = nativeColumn.getInt(16 + (y - settings.noiseSettings().minY()) * 4);
                    assertEquals(expected.getBlock(y), nativeSampler.states()[state],
                            "Native column seed=" + seed + " xyz=" + xz[0] + "," + y + "," + xz[1]);
                }
                var actual = context.generatorContext().getBaseColumn(xz[0], xz[1], heights, context.randomStateContext());
                for (int y = minY; y < minY + height; y++) assertEquals(expected.getBlock(y), actual.getBlock(y));
                int floor = minY;
                for (int y = minY + height - 1; y >= minY; y--) {
                    var block = expected.getBlock(y);
                    if (!block.isAir() && block.getFluidState().isEmpty()) { floor = y + 1; break; }
                }
                assertEquals(floor, context.surfaceY(xz[0], xz[1]), "Published prediction surface must use NoiseChunk");
                assertEquals(floor, context.groundY(xz[0], xz[1]));
                if (Math.abs(xz[0]) < 2 && Math.abs(xz[1]) < 2) {
                    assertEquals(floor, nativeSampler.groundY(xz[0], xz[1]), "Production native surface height");
                }
            }
            }
            if (seed == 0) {
                // Isolate spline codec behavior from the pack's stateful cache
                // dependency. This graph is only a numerical regression fixture.
                var splineDoc = doc.deepCopy();
                splineDoc.getAsJsonObject("settings").getAsJsonObject("noise_router").entrySet()
                        .forEach(e -> e.setValue(new JsonPrimitive(0)));
                addSplineRegressions(splineDoc);
                long world = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), splineDoc.toString());
                try { checkSplineRegressions(world, splineDoc, registries); }
                finally { RustWorldgenBackend.close(world); }
            }
        }
    }

    private static void addSplineRegressions(JsonObject doc) {
        for (boolean ordered : new boolean[]{true, false}) {
            var spline = JsonParser.parseString("""
                    {"type":"minecraft:spline","spline":{
                      "coordinate":{"type":"minecraft:y_clamped_gradient","from_y":-4,"to_y":4,"from_value":-4,"to_value":4},
                      "points":[{"location":-1,"value":2,"derivative":0},
                                {"location":0,"value":4,"derivative":0},
                                {"location":0,"value":8,"derivative":0},
                                {"location":2,"value":16,"derivative":0}]}}
                    """).getAsJsonObject();
            if (!ordered) spline.getAsJsonObject("spline").getAsJsonArray("points").get(1).getAsJsonObject().addProperty("location", 1);
            doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("test_spline_" + ordered, spline);
        }
    }


    private static void checkSplineRegressions(long world, JsonObject doc, ClientWorldgenRegistries registries) {
        for (boolean ordered : new boolean[]{true, false}) {
            String key = "test_spline_" + ordered;
            var expected = DensityFunction.DIRECT_CODEC.parse(registries.ops(),
                    doc.getAsJsonObject("settings").getAsJsonObject("noise_router").get(key)).getOrThrow();
            var xyz = direct(9 * 12);
            for (int y = -4; y <= 4; y++) xyz.putInt(0).putInt(y).putInt(0);
            var out = direct(9 * 8);
            assertEquals(9, RustWorldgenBackend.density(world, key, xyz, out, 9));
            for (int y = -4; y <= 4; y++) assertEquals(expected.compute(new DensityFunction.SinglePointContext(0, y, 0)),
                    out.getDouble((y + 4) * 8), 0.0, "Codec spline at/around duplicate or unordered knots");
        }
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static JsonObject read(ZipFile zip, String name) throws Exception {
        try (var input = zip.getInputStream(Objects.requireNonNull(zip.getEntry(name), name))) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static <T> void export(JsonObject root, String name, HolderLookup.Provider lookup,
            ResourceKey<? extends Registry<T>> key, Codec<T> codec) {
        var values = new JsonObject();
        var ops = lookup.createSerializationContext(JsonOps.INSTANCE);
        lookup.lookupOrThrow(key).listElements().forEach(h ->
                values.add(h.key().location().toString(), codec.encodeStart(ops, h.value()).getOrThrow()));
        root.add(name, values);
    }
}

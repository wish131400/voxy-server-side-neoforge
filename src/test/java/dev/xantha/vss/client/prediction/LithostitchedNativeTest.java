package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.net.URLClassLoader;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Compares the released mod's noise implementation with actual JNI results. */
@EnabledIfSystemProperty(named = "vss.lithostitchedJar", matches = ".+")
class LithostitchedNativeTest {
    @BeforeAll
    static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test
    void fastNoiseConfigurationsMatchReleasedJava() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()}, getClass().getClassLoader())) {
            String prefix = "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FNL";
            Class<?> fnl = loader.loadClass(prefix);
            List<JsonObject> configs = new ArrayList<>();
            configs.add(JsonParser.parseString("{\"type\":\"lithostitched:perlin\",\"frequency\":0.013,\"salt\":-9123}").getAsJsonObject());
            for (String fractal : List.of("none", "fbm", "ridged", "ping_pong", "domain_warp_progressive", "domain_warp_independent")) {
                var c = JsonParser.parseString("{\"type\":\"lithostitched:simplex\",\"frequency\":0.017,\"salt\":2147483647,\"octaves\":4,\"gain\":0.45,\"lacunarity\":2.1}").getAsJsonObject();
                c.addProperty("fractal_type", fractal); configs.add(c);
            }
            for (String distance : List.of("euclidean", "euclidean_squared", "manhattan", "hybrid")) {
                for (String result : List.of("cell_value", "distance", "distance_2", "distance_2_add", "distance_2_sub", "distance_2_mul", "distance_2_div")) {
                    var c = JsonParser.parseString("{\"type\":\"lithostitched:cellular\",\"frequency\":0.07,\"salt\":137,\"jitter\":0.8}").getAsJsonObject();
                    c.addProperty("distance_function", distance); c.addProperty("return_type", result); configs.add(c);
                }
            }
            JsonObject doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            for (int i = 0; i < configs.size(); i++) {
                var node = JsonParser.parseString("{\"type\":\"lithostitched:fast_noise\",\"xz_scale\":1.13,\"y_scale\":0.67,\"shift_x\":0.13,\"shift_y\":-0.27,\"shift_z\":0.31}").getAsJsonObject();
                node.add("config", configs.get(i)); router.add("test_" + i, node);
            }
            var registry = new JsonObject();
            registry.add("test:perlin", configs.getFirst());
            var registries = new JsonObject(); registries.add("lithostitched:fast_noise_config", registry);
            doc.add("custom_registries", registries);
            var referenced = router.getAsJsonObject("test_0").deepCopy(); referenced.addProperty("config", "test:perlin");
            router.add("test_0", referenced);
            int[][] points = new int[72][3];
            var random = new Random(81923);
            for (int i = 0; i < points.length; i++) points[i] = new int[]{random.nextInt(60000001) - 30000000, random.nextInt(576) - 64, random.nextInt(60000001) - 30000000};
            for (int i = 0; i < 8; i++) points[i] = new int[]{i - 4, i * 16 - 64, 4 - i};
            var input = direct(points.length * 12);
            for (int[] p : points) for (int n : p) input.putInt(n);
            for (long seed : new long[]{0, -918273645, 123456789, Long.MIN_VALUE, Long.MAX_VALUE}) {
                long world = RustWorldgenBackend.create(seed, BiomeManager.obfuscateSeed(seed), doc.toString());
                try {
                    for (int i = 0; i < configs.size(); i++) {
                        var c = configs.get(i);
                        Object noise = fnl.getConstructor(int.class).newInstance((int) seed + c.get("salt").getAsInt());
                        fnl.getMethod("SetFrequency", float.class).invoke(noise, c.get("frequency").getAsFloat());
                        String type = c.get("type").getAsString().substring("lithostitched:".length());
                        setEnum(fnl, noise, "SetNoiseType", "NoiseType", Map.of("perlin", "Perlin", "simplex", "OpenSimplex2S", "cellular", "Cellular").get(type));
                        setEnum(fnl, noise, "SetFractalType", "FractalType", "None");
                        if (type.equals("simplex")) {
                            setEnum(fnl, noise, "SetFractalType", "FractalType", Map.of("none", "None", "fbm", "FBm", "ridged", "Ridged", "ping_pong", "PingPong", "domain_warp_progressive", "DomainWarpProgressive", "domain_warp_independent", "DomainWarpIndependent").get(c.get("fractal_type").getAsString()));
                            fnl.getMethod("SetFractalOctaves", int.class).invoke(noise, c.get("octaves").getAsInt());
                            fnl.getMethod("SetFractalGain", float.class).invoke(noise, c.get("gain").getAsFloat());
                            fnl.getMethod("SetFractalLacunarity", float.class).invoke(noise, c.get("lacunarity").getAsFloat());
                        } else if (type.equals("cellular")) {
                            setEnum(fnl, noise, "SetCellularDistanceFunction", "CellularDistanceFunction", Map.of("euclidean", "Euclidean", "euclidean_squared", "EuclideanSq", "manhattan", "Manhattan", "hybrid", "Hybrid").get(c.get("distance_function").getAsString()));
                            setEnum(fnl, noise, "SetCellularReturnType", "CellularReturnType", Map.of("cell_value", "CellValue", "distance", "Distance", "distance_2", "Distance2", "distance_2_add", "Distance2Add", "distance_2_sub", "Distance2Sub", "distance_2_mul", "Distance2Mul", "distance_2_div", "Distance2Div").get(c.get("return_type").getAsString()));
                            fnl.getMethod("SetCellularJitter", float.class).invoke(noise, c.get("jitter").getAsFloat());
                        }
                        var out = direct(points.length * 8);
                        assertEquals(points.length, RustWorldgenBackend.density(world, "test_" + i, input, out, points.length));
                        var sample = fnl.getMethod("GetNoise", double.class, double.class, double.class);
                        for (int p = 0; p < points.length; p++) {
                            int[] xyz = points[p];
                            double expected = ((Number) sample.invoke(noise, xyz[0] * 1.13 + 0.13, xyz[1] * 0.67 - 0.27, xyz[2] * 1.13 + 0.31)).doubleValue();
                            assertEquals(expected, out.getDouble(p * 8), 0.0, "seed=" + seed + " config=" + c + " xyz=" + Arrays.toString(xyz));
                        }
                    }
                } finally { RustWorldgenBackend.close(world); }
            }
        }
    }

    @Test
    void densityOperatorsMatchReleasedJava() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("vss.lithostitchedJar")).toUri().toURL()}, getClass().getClassLoader())) {
            String prefix = "dev.worldgen.lithostitched.impl.worldgen.densityfunction.";
            var doc = document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new LinkedHashMap<String, DensityFunction>();
            var slope = DensityFunctions.yClampedGradient(-100, 100, -3.3, 3.3);
            var slopeJson = JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-100,\"to_y\":100,\"from_value\":-3.3,\"to_value\":3.3}");
            for (String name : List.of("Sin", "Cos", "Sqrt", "Floor", "Ceil")) {
                expected.put(name, (DensityFunction) loader.loadClass(prefix + name + "DensityFunction").getConstructor(DensityFunction.class).newInstance(slope));
                var node = new JsonObject(); node.addProperty("type", "lithostitched:" + name.toLowerCase(Locale.ROOT)); node.add("argument", slopeJson); router.add(name, node);
            }
            for (var axis : net.minecraft.core.Direction.Axis.values()) {
                String key = "axis_" + axis.getName();
                expected.put(key, (DensityFunction) loader.loadClass(prefix + "AxisDensityFunction").getConstructor(net.minecraft.core.Direction.Axis.class).newInstance(axis));
                var node = new JsonObject(); node.addProperty("type", "lithostitched:axis"); node.addProperty("axis", axis.getName()); router.add(key, node);
            }
            expected.put("mix", (DensityFunction) loader.loadClass(prefix + "MixDensityFunction").getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class)
                    .newInstance(slope, DensityFunctions.constant(-2), DensityFunctions.constant(7)));
            var mix = JsonParser.parseString("{\"type\":\"lithostitched:mix\",\"argument1\":-2,\"argument2\":7}").getAsJsonObject(); mix.add("input", slopeJson); router.add("mix", mix);
            expected.put("shift", (DensityFunction) loader.loadClass(prefix + "ShiftDensityFunction").getConstructor(DensityFunction.class, DensityFunction.class, DensityFunction.class, DensityFunction.class)
                    .newInstance(slope, DensityFunctions.constant(0.9), DensityFunctions.constant(-0.9), DensityFunctions.constant(1.3)));
            var shift = JsonParser.parseString("{\"type\":\"lithostitched:shift\",\"shift_x\":0.9,\"shift_y\":-0.9,\"shift_z\":1.3}").getAsJsonObject(); shift.add("input", slopeJson); router.add("shift", shift);
            var selection = loader.loadClass(prefix + "SelectDensityFunction$Selection");
            Object first = selection.getConstructor(net.minecraft.util.InclusiveRange.class, DensityFunction.class).newInstance(
                    new net.minecraft.util.InclusiveRange<Double>(-1.0, 1.0), DensityFunctions.constant(7));
            Object second = selection.getConstructor(net.minecraft.util.InclusiveRange.class, DensityFunction.class).newInstance(
                    new net.minecraft.util.InclusiveRange<Double>(0.0, 2.0), DensityFunctions.constant(13));
            expected.put("select", (DensityFunction) loader.loadClass(prefix + "SelectDensityFunction").getMethod("create", DensityFunction.class, DensityFunction.class, List.class)
                    .invoke(null, slope, DensityFunctions.constant(-3), List.of(first, second)));
            var select = JsonParser.parseString("{\"type\":\"lithostitched:select\",\"fallback\":-3,\"selections\":[{\"range\":[-1,1],\"function\":7},{\"range\":[0,2],\"function\":13}]}").getAsJsonObject();
            select.add("input", slopeJson); router.add("select", select);
            long world = RustWorldgenBackend.create(0, 0, doc.toString());
            try {
                var input = direct(201 * 12);
                for (int y = -100; y <= 100; y++) input.putInt(-13).putInt(y).putInt(71);
                var output = direct(201 * 8);
                for (var entry : expected.entrySet()) {
                    assertEquals(201, RustWorldgenBackend.density(world, entry.getKey(), input, output, 201));
                    for (int y = -100; y <= 100; y++) {
                        double java = entry.getValue().compute(new DensityFunction.SinglePointContext(-13, y, 71));
                        assertEquals(java, output.getDouble((y + 100) * 8), Math.ulp(java), entry.getKey() + " y=" + y);
                    }
                }
            } finally { RustWorldgenBackend.close(world); }
        }
    }

    private static void setEnum(Class<?> type, Object instance, String method, String enumName, String value) throws Exception {
        Class<?> enumeration = type.getClassLoader().loadClass(type.getName() + "$" + enumName);
        Object constant = enumeration.getField(value).get(null);
        type.getMethod(method, enumeration).invoke(instance, constant);
    }

    static JsonObject document() throws Exception {
        Path root = Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc = JsonParser.parseString(Files.readString(root.resolve("overworld.json"))).getAsJsonObject();
        for (var entry : Map.of("block_definitions", "blocks", "biomes", "biomes", "grass_colormap", "grass", "foliage_colormap", "foliage").entrySet())
            doc.add(entry.getKey(), JsonParser.parseString(Files.readString(root.resolve(entry.getValue() + ".json"))));
        return doc;
    }

    private static ByteBuffer direct(int size) { return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN); }
}

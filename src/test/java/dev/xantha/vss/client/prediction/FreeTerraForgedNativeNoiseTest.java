package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vss.freeTerraForgedJar", matches = ".+")
class FreeTerraForgedNativeNoiseTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test void releasedNoiseAndDensityOperatorsMatchNative() throws Exception {
        {
            var loader = FreeTerraForgedCompatTest.releaseLoader();
            String prefix = "raccoonman.reterraforged.world.worldgen.";
            Class<?> noise = loader.loadClass(prefix + "noise.module.Noise");
            Class<?> interpolation = loader.loadClass(prefix + "noise.function.Interpolation");
            var expected = new ArrayList<Object>();
            var nodes = new ArrayList<JsonObject>();
            for (String type : List.of("Perlin", "Perlin2", "Simplex", "Simplex2")) {
                for (String curve : List.of("LINEAR", "CURVE3", "CURVE4")) for (int octaves : new int[]{1, 3, 7}) {
                    Class<?> clazz = loader.loadClass(prefix + "noise.module." + type);
                    boolean perlin = type.startsWith("Perlin");
                    var constructor = perlin ? clazz.getDeclaredConstructor(int.class, float.class, int.class, float.class, float.class, interpolation)
                            : clazz.getDeclaredConstructor(float.class, int.class, float.class, float.class, interpolation);
                    constructor.setAccessible(true);
                    Object c = interpolation.getField(curve).get(null);
                    expected.add(perlin ? constructor.newInstance(-918273, 0.013f, octaves, 2.1f, 0.45f, c)
                            : constructor.newInstance(0.013f, octaves, 2.1f, 0.45f, c));
                    var node = JsonParser.parseString("{\"seed\":-918273,\"frequency\":0.013,\"lacunarity\":2.1,\"gain\":0.45}").getAsJsonObject();
                    node.addProperty("type", "reterraforged:" + type.toLowerCase(Locale.ROOT)); node.addProperty("octaves", octaves);
                    var ci = new JsonObject(); ci.addProperty("value", curve); node.add("interpolation", ci); nodes.add(node);
                }
            }
            Class<?> noises = loader.loadClass(prefix + "noise.module.Noises");
            Object white = loader.loadClass(prefix + "noise.module.White").getConstructor(float.class).newInstance(0.37f);
            expected.add(white); nodes.add(JsonParser.parseString("{\"type\":\"reterraforged:white\",\"frequency\":0.37}").getAsJsonObject());
            Object shifted = noises.getMethod("shiftSeed", noise, int.class).invoke(null, white, Integer.MAX_VALUE);
            var shift = new JsonObject(); shift.addProperty("type", "reterraforged:shift"); shift.add("input", nodes.getLast()); shift.addProperty("shift", Integer.MAX_VALUE);
            expected.add(shifted); nodes.add(shift);
            Object clamped = noises.getMethod("clamp", noise, float.class, float.class).invoke(null, shifted, 0.2f, 0.8f);
            var clamp = new JsonObject(); clamp.addProperty("type", "reterraforged:clamp"); clamp.add("input", shift); clamp.addProperty("min", 0.2f); clamp.addProperty("max", 0.8f);
            expected.add(clamped); nodes.add(clamp);
            Object mapped = noises.getMethod("map", noise, float.class, float.class).invoke(null, clamped, -0.3f, 1.2f);
            var map = new JsonObject(); map.addProperty("type", "reterraforged:map"); map.add("alpha", clamp); map.addProperty("from", -0.3f); map.addProperty("to", 1.2f);
            expected.add(mapped); nodes.add(map);
            for (String op : List.of("add", "multiply", "min", "max", "alpha")) {
                String method = op.equals("multiply") ? "mul" : op;
                expected.add(noises.getMethod(method, noise, noise).invoke(null, mapped, shifted));
                var node = new JsonObject(); node.addProperty("type", "reterraforged:" + op);
                node.add(op.equals("alpha") ? "input" : "input1", map); node.add(op.equals("alpha") ? "alpha" : "input2", shift); nodes.add(node);
            }
            for (String op : List.of("abs", "invert")) {
                expected.add(noises.getMethod(op, noise).invoke(null, mapped));
                var node = new JsonObject(); node.addProperty("type", "reterraforged:" + op); node.add("input", map); nodes.add(node);
            }
            expected.add(noises.getMethod("frequency", noise, float.class, float.class).invoke(null, shifted, 0.57f, 1.29f));
            var frequency = new JsonObject(); frequency.addProperty("type", "reterraforged:frequency"); frequency.add("input", shift); frequency.addProperty("x_freq", 0.57f); frequency.addProperty("z_freq", 1.29f); nodes.add(frequency);
            expected.add(noises.getMethod("threshold", noise, float.class, float.class, float.class).invoke(null, shifted, -0.2f, 0.7f, 0.5f));
            var threshold = new JsonObject(); threshold.addProperty("type", "reterraforged:threshold"); threshold.add("input", shift); threshold.addProperty("lower", -0.2f); threshold.addProperty("upper", 0.7f); threshold.addProperty("threshold", 0.5f); nodes.add(threshold);
            expected.add(noises.getMethod("pow", noise, float.class).invoke(null, clamped, 2.3f));
            var power = new JsonObject(); power.addProperty("type", "reterraforged:power"); power.add("input", clamp); power.addProperty("power", 2.3f); nodes.add(power);
            var doc = LithostitchedNativeTest.document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var registry = new JsonObject();
            for (int i = 0; i < nodes.size(); i++) {
                registry.add("test:noise_" + i, nodes.get(i));
                var node = new JsonObject(); node.addProperty("type", "reterraforged:noise"); node.addProperty("noise", "test:noise_" + i); router.add("test_" + i, node);
            }
            var registries = new JsonObject(); registries.add("reterraforged:worldgen/noise", registry); doc.add("custom_registries", registries);
            int count = 512;
            int[][] points = new int[count][3]; var random = new Random(7129);
            for (int i = 0; i < count; i++) points[i] = new int[]{random.nextInt(60000001) - 30000000, i - 256, random.nextInt(60000001) - 30000000};
            for (int i = 0; i < 32; i++) points[i] = new int[]{i - 16, i - 16, 16 - i};
            var input = direct(count * 12); for (int[] p : points) for (int n : p) input.putInt(n);
            var output = direct(count * 8);
            var compute = noise.getMethod("compute", float.class, float.class, int.class);
            var gradient = DensityFunctions.yClampedGradient(-256, 256, -3.7, 3.7);
            var gradientJson = JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-256,\"to_y\":256,\"from_value\":-3.7,\"to_value\":3.7}");
            Class<?> unit = loader.loadClass(prefix + "densityfunction.ClampToNearestUnit");
            var densityExpected = new LinkedHashMap<String, DensityFunction>();
            for (int resolution : new int[]{-7, 0, 1, 16, Integer.MAX_VALUE}) {
                String key = "unit_" + resolution;
                densityExpected.put(key, (DensityFunction) unit.getConstructor(DensityFunction.class, int.class).newInstance(gradient, resolution));
                var node = new JsonObject(); node.addProperty("type", "reterraforged:clamp_to_nearest_unit"); node.add("function", gradientJson); node.addProperty("resolution", resolution); router.add(key, node);
            }
            var spline = loader.loadClass(prefix + "densityfunction.LinearSplineFunction");
            densityExpected.put("linear", (DensityFunction) spline.getConstructor(DensityFunction.class, List.class).newInstance(gradient, List.of(
                    com.mojang.datafixers.util.Pair.of(-2., DensityFunctions.constant(3.)), com.mojang.datafixers.util.Pair.of(0., gradient), com.mojang.datafixers.util.Pair.of(2., DensityFunctions.constant(-1.)))));
            var linear = JsonParser.parseString("{\"type\":\"reterraforged:linear_spline\",\"points\":[[-2,3],[0,0],[2,-1]]}").getAsJsonObject();
            linear.add("input", gradientJson); linear.getAsJsonArray("points").get(1).getAsJsonArray().set(1, gradientJson); router.add("linear", linear);
            for (long seed : new long[]{0, -918273645, Long.MIN_VALUE, Long.MAX_VALUE}) {
                long handle = RustWorldgenBackend.create(seed, 0, doc.toString());
                try {
                    for (int i = 0; i < expected.size(); i++) {
                        RustWorldgenBackend.density(handle, "test_" + i, input, output, count);
                        for (int p = 0; p < count; p++) {
                            float value = (float) compute.invoke(expected.get(i), (float) points[p][0], (float) points[p][2], (int) seed);
                            assertEquals((double) value, output.getDouble(p * 8), 0., "noise=" + i + " seed=" + seed + " p=" + p);
                        }
                    }
                    for (var e : densityExpected.entrySet()) {
                        RustWorldgenBackend.density(handle, e.getKey(), input, output, count);
                        for (int p = 0; p < count; p++) assertEquals(e.getValue().compute(new DensityFunction.SinglePointContext(points[p][0], points[p][1], points[p][2])), output.getDouble(p * 8), 0., e.getKey() + " p=" + p);
                    }
                } finally { RustWorldgenBackend.close(handle); }
            }
        }
    }
    @Test void nativeSamplerClosesOwnedContextExactlyOnce() throws Exception {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), -64, 384, "noise", "minecraft:overworld", 1L);
        class Owned extends ClientTerrainSampler implements AutoCloseable {
            int closes;
            Owned() { super(1, profile); }
            @Override public void close() { closes++; }
        }
        var context = new Owned();
        var document = LithostitchedNativeTest.document();
        document.add("possible_biomes", new JsonArray());
        long handle = RustWorldgenBackend.create(1, 0, document.toString());
        var sampler = new RustTerrainSampler(handle, profile, context);
        sampler.close(); sampler.close();
        assertEquals(1, context.closes);
        assertThrows(java.util.concurrent.CancellationException.class, sampler::handle);
    }
    private static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
}

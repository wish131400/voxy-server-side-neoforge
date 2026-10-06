package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs a complete captured native document through the real packaged JNI ABI.
 * No game is started and no runtime sampler or installed mod is changed. */
public final class NativeSnapshotImport {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5)
            throw new IllegalArgumentException("NativeSnapshotImport library document.json seed zoomSeed [--expect-color-rejection]");
        RustWorldgenBackend.load(Path.of(args[0]));
        String text = Files.readString(Path.of(args[1]));
        long seed = Long.parseLong(args[2]), zoomSeed = Long.parseLong(args[3]);
        long start = System.nanoTime();
        if (args.length == 5) {
            if (!args[4].equals("--expect-color-rejection")) throw new IllegalArgumentException("Unknown option");
            try {
                long world = RustWorldgenBackend.create(seed, zoomSeed, text);
                RustWorldgenBackend.close(world);
                throw new AssertionError("Baseline unexpectedly accepted the signed-color snapshot");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains("invalid biome color")) throw expected;
                JsonObject result = new JsonObject();
                result.addProperty("status", "baseline-color-rejection");
                result.addProperty("reason", expected.getMessage());
                System.out.println(result);
            }
            return;
        }
        JsonObject original = JsonParser.parseString(text).getAsJsonObject();
        JsonObject normalized = original.deepCopy();
        int changed = 0;
        for (var entry : normalized.getAsJsonObject("biomes").entrySet()) {
            JsonObject effects = entry.getValue().getAsJsonObject().getAsJsonObject("effects");
            for (String key : List.of("grass_color", "foliage_color", "water_color")) {
                if (!effects.has(key)) continue;
                int color = effects.get(key).getAsBigDecimal().intValueExact();
                int expected = ((color >> 16) & 255) << 16 | ((color >> 8) & 255) << 8 | (color & 255);
                if (color != expected) changed++;
                effects.addProperty(key, expected);
            }
        }
        long world = 0, oracle = 0;
        try {
            world = RustWorldgenBackend.create(seed, zoomSeed, text);
            double importMs = (System.nanoTime() - start) / 1e6;
            oracle = RustWorldgenBackend.create(seed, zoomSeed, normalized.toString());
            JsonObject describe = JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject();
            JsonObject expectedDescribe = JsonParser.parseString(RustWorldgenBackend.describe(oracle)).getAsJsonObject();
            if (!describe.equals(expectedDescribe)) throw new AssertionError("RGB normalization changed world state mapping");
            JsonElement schedule = JsonParser.parseString(RustWorldgenBackend.schedule(world));
            if (!schedule.equals(JsonParser.parseString(RustWorldgenBackend.schedule(oracle))))
                throw new AssertionError("RGB normalization changed the feature schedule");
            int[][] points = {{-1513, 751}, {-16, -16}, {0, 0}, {15, 15}, {1024, -512}, {4096, 2048}};
            ByteBuffer input = ByteBuffer.allocateDirect(points.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer output = ByteBuffer.allocateDirect(points.length * 40).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer expectedOutput = ByteBuffer.allocateDirect(points.length * 40).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] point : points) input.putInt(point[0]).putInt(point[1]);
            if (RustWorldgenBackend.surfacePoints(world, input, output, points.length) != points.length
                    || RustWorldgenBackend.surfacePoints(oracle, input, expectedOutput, points.length) != points.length)
                throw new AssertionError("Incomplete JNI sample");
            for (int i = 0; i < points.length * 10; i++) {
                if (output.getInt(i * 4) != expectedOutput.getInt(i * 4))
                    throw new AssertionError("RGB normalization changed surface record " + i);
            }
            for (int i = 0; i < points.length; i++) {
                int y = output.getInt(i * 40);
                String colors = RustWorldgenBackend.colors(world, points[i][0], y, points[i][1]);
                String expectedColors = RustWorldgenBackend.colors(oracle, points[i][0], y, points[i][1]);
                if (!JsonParser.parseString(colors).equals(JsonParser.parseString(expectedColors)))
                    throw new AssertionError("RGB normalization changed JNI tints at point " + i);
            }
            JsonObject result = new JsonObject();
            result.addProperty("status", "jni-imported");
            result.addProperty("abi", RustWorldgenBackend.abi());
            result.addProperty("document", args[1]);
            result.addProperty("biomes", original.getAsJsonObject("biomes").size());
            result.addProperty("states", describe.getAsJsonArray("states").size());
            result.addProperty("normalizedArgbFields", changed);
            result.addProperty("surfaceParityPoints", points.length);
            result.addProperty("tintParityPoints", points.length);
            result.addProperty("importMs", importMs);
            result.addProperty("totalMs", (System.nanoTime() - start) / 1e6);
            result.add("terrablenderRouting", describe.get("terrablender_routing"));
            System.out.println(result);
        } finally {
            if (oracle != 0) RustWorldgenBackend.close(oracle);
            if (world != 0) RustWorldgenBackend.close(world);
        }
    }
}

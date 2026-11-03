package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.Level;

/** Calls production Rust sampler, vegetation bridge and Voxy tile encoder; no rendering or disk-cache hits. */
public final class PredictionGenerationComparison {
    private static volatile long sink;

    public static void run(Path output) throws Exception {
        boolean detailed = Boolean.getBoolean("vss.comparison.profile");
        Path results = detailed ? output.resolve("vegetation-profile") : output;
        if (detailed) Files.createDirectory(results); // Never overwrite the original comparison or an existing profile.
        ComparisonBootstrap.loadColors();
        if (!RustTerrainSampler.available()) throw new AssertionError("Native backend not loaded");
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(output.resolve("worldgen-profile.bin"))));
        WorldgenProfileS2CPayload payload;
        try { payload = WorldgenProfileS2CPayload.decode(buffer); } finally { buffer.release(); }
        JsonObject registryRoot = JsonParser.parseString(new String(LodByteCompression.decompress(payload.registries(),
                payload.registriesCompression(), payload.registriesRawSize(), WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
        registryRoot.add("structure_templates", JsonParser.parseString(Files.readString(output.resolve("structure-templates.json"))));
        var profile = payload.dimensions().stream().filter(p -> p.levelKey().equals(Level.OVERWORLD)).findFirst().orElseThrow();
        JsonObject generatorRoot = JsonParser.parseString(new String(LodByteCompression.decompress(profile.generatorData(),
                profile.generatorCompression(), profile.generatorRawSize(), WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES), StandardCharsets.UTF_8)).getAsJsonObject();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Path csv = results.resolve("prediction.csv");
        Files.writeString(csv, "mode,step,round,warmup,chunkX,chunkZ,chunks,wallMs,processCpuMs,samples,featureBoxes,encodedBytes"
                + (detailed ? ",terrainMs,decorationMs,encodingMs" : "") + "\n");
        for (boolean decorated : detailed ? new boolean[]{true} : new boolean[]{false, true})
                for (int step : detailed ? new int[]{4} : new int[]{1, 4}) {
            // Every configuration gets independent caches; all measured windows are new to that sampler.
            var registries = ClientWorldgenRegistries.decode(registryRoot, RegistryAccess.EMPTY);
            registries.bindWorldSeed(payload.seed());
            var context = ClientWorldgenProfileDecoder.decodeJavaSampler(profile, generatorRoot, registries, registries.access());
            if (context == null) throw new AssertionError("Java feature context absent");
            JsonObject document = RustWorldgenDocument.snapshot(generatorRoot, registryRoot, context);
            JsonObject colors = colors(); colors.entrySet().forEach(e -> document.add(e.getKey(), e.getValue()));
            long handle = RustWorldgenBackend.create(profile.seed(), BiomeManager.obfuscateSeed(profile.seed()), document.toString());
            try (var sampler = new RustTerrainSampler(handle, profile, context);
                 var recording = detailed ? new Recording(Configuration.getConfiguration("profile")) : null) {
                if (recording != null) {
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));
                    recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(5));
                }
                var vegetation = decorated ? new PredictionVegetation(sampler, null, true) : null;
                if (vegetation != null && !vegetation.available()) throw new AssertionError("Vegetation context absent");
                for (int round = -2; round < 5; round++) {
                    int cx = 8192 + round * 512, cz = -8192 - round * 512;
                    if (recording != null && round == 0) recording.start();
                    if (detailed) Files.writeString(results.resolve("sampler-before-" + round + ".txt"), sampler.diagnostics());
                    long cpu = os.getProcessCpuTime(), start = System.nanoTime();
                    long samples = 0, featureBoxes = 0, bytes = 0;
                    long terrainNanos = 0, decorationNanos = 0, encodingNanos = 0;
                    int tileSpan = 64 * step, grid = 66;
                    for (int z = 0; z < 256; z += tileSpan) for (int x = 0; x < 256; x += tileSpan) {
                        int bx = cx * 16 + x, bz = cz * 16 + z;
                        var values = new ClientColumnSample[grid * grid];
                        long phaseStart = detailed ? System.nanoTime() : 0;
                        for (int gz = 0; gz < grid; gz += 8) for (int gx = 0; gx < grid; gx += 8)
                            sampler.sampleDisplayGridInPlace(bx + (gx - 1) * step, bz + (gz - 1) * step,
                                    step, Math.min(8, grid - gx), Math.min(8, grid - gz), values, gz * grid + gx, grid);
                        if (detailed) { terrainNanos += System.nanoTime() - phaseStart; phaseStart = System.nanoTime(); }
                        PredictionVegetation.Tile plants = vegetation == null ? PredictionVegetation.Tile.EMPTY
                                : vegetation.tileForRendering(bx, bz, tileSpan, step, true, p -> false, () -> true);
                        if (detailed) { decorationNanos += System.nanoTime() - phaseStart; phaseStart = System.nanoTime(); }
                        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,
                                Math.floorDiv(bx, tileSpan), Math.floorDiv(bz, tileSpan), Integer.numberOfTrailingZeros(step));
                        var tile = PredictionVoxySource.build(key, values, grid, step, sampler, plants, null, decorated);
                        if (detailed) encodingNanos += System.nanoTime() - phaseStart;
                        samples += values.length; featureBoxes += tile.features().size(); bytes += tile.bytes();
                        sink += tile.columns()[0].runs().size() + tile.features().size();
                    }
                    double wall = (System.nanoTime() - start) / 1e6, cpuMs = (os.getProcessCpuTime() - cpu) / 1e6;
                    String row = (decorated ? "terrain_vegetation_structures" : "terrain") + "," + step + "," + round + "," + (round < 0)
                            + "," + cx + "," + cz + ",256," + wall + "," + cpuMs + "," + samples + "," + featureBoxes + "," + bytes
                            + (detailed ? "," + terrainNanos / 1e6 + "," + decorationNanos / 1e6 + "," + encodingNanos / 1e6 : "") + "\n";
                    Files.writeString(csv, row, java.nio.file.StandardOpenOption.APPEND);
                    System.out.println("VSS_PREDICTION_COMPARISON " + row.strip());
                    if (vegetation != null) Files.writeString(results.resolve("features-step" + step + "-round" + round + ".txt"), vegetation.diagnostics());
                    if (detailed) Files.writeString(results.resolve("sampler-after-" + round + ".txt"), sampler.diagnostics());
                }
                if (recording != null) {
                    recording.stop();
                    recording.dump(results.resolve("vegetation.jfr"));
                }
            }
        }
        Files.writeString(results.resolve("prediction-complete.txt"), "success sink=" + sink + "\n");
    }

    private static JsonObject colors() throws Exception {
        var result = new JsonObject();
        for (String name : List.of("grass", "foliage")) {
            {
                var image = ComparisonBootstrap.image(name);
                var values = new com.google.gson.JsonArray(65536);
                for (int value : image.getRGB(0, 0, 256, 256, null, 0, 256)) values.add(value & 0xffffff);
                result.add(name + "_colormap", values);
            }
        }
        return result;
    }
}

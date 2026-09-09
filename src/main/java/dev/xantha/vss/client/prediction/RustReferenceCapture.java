package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Explicit, bounded reference export; never called by ordinary prediction builds. */
public final class RustReferenceCapture {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private RustReferenceCapture() { }

    public static void capture(Path directory, DimensionProfile profile, byte[] generator, byte[] registries,
                               ClientTerrainSampler context, int originX, int originZ) throws Exception {
        Files.createDirectories(directory);
        for (String color : List.of("grass", "foliage")) {
            Path target = directory.resolve(color + "-colormap.png");
            if (!Files.exists(target)) try (InputStream in = RustReferenceCapture.class.getResourceAsStream(
                    "/assets/minecraft/textures/colormap/" + color + ".png")) {
                write(target, Objects.requireNonNull(in).readAllBytes());
            }
        }
        write(directory.resolve("generator.json"), generator);
        write(directory.resolve("registries.json"), registries);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", "vss-rust-reference-2");
        manifest.addProperty("minecraft", "1.21.1");
        manifest.addProperty("vssVersion", "0.3-neoforge-1.21.1");
        manifest.addProperty("seed", Long.toString(profile.seed()));
        manifest.addProperty("dimension", profile.dimension().toString());
        manifest.addProperty("fingerprint", Long.toString(profile.fingerprint()));
        manifest.addProperty("minY", profile.minY());
        manifest.addProperty("height", profile.height());
        manifest.addProperty("seaLevel", context.seaLevel());
        manifest.addProperty("source", "minecraft-java");
        manifest.addProperty("originX", originX);
        manifest.addProperty("originZ", originZ);
        manifest.addProperty("vegetationReference", "Minecraft placed features on VSS bounded predicted ground; not full generated chunks");
        manifest.addProperty("predictionTrees", dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionTrees);
        manifest.addProperty("predictionStructures", dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionStructures);
        manifest.addProperty("densityReference", "Minecraft router point evaluation; base-column runs separately include generator interpolation/aquifer");
        {
            JsonArray grids = new JsonArray();
            try (Writer columns = writer(directory.resolve("columns.jsonl.gz"));
                 Writer density = writer(directory.resolve("density.jsonl.gz"));
                 Writer noise = writer(directory.resolve("noise.jsonl.gz"));
                 Writer base = writer(directory.resolve("base-columns.jsonl.gz"))) {
                for (int spacing : new int[]{1, 16, 256, 4096}) {
                    checkCancelled();
                    int axis = 4;
                    JsonObject grid = new JsonObject();
                    grid.addProperty("axis", axis); grid.addProperty("spacing", spacing);
                    grids.add(grid);
                    ClientColumnSample[] resolved = new ClientColumnSample[axis * axis];
                    for (int i = 0; i < resolved.length; i++) {
                        checkCancelled();
                        resolved[i] = context.sampleSurface(originX + i % axis * spacing, originZ + i / axis * spacing);
                    }
                    for (int i = 0; i < resolved.length; i++) {
                        int x = originX + i % axis * spacing, z = originZ + i / axis * spacing;
                        ClientColumnSample sample = resolved[i];
                        JsonObject entry = point(x, sample.surfaceY(), z);
                        entry.addProperty("spacing", spacing); entry.addProperty("record", i);
                        entry.add("resolved", JSON.toJsonTree(sample));
                        entry.addProperty("topBlock", block(sample.topBlockIndex()));
                        entry.addProperty("underBlock", block(sample.underBlockIndex()));
                        entry.addProperty("deepBlock", block(sample.deepBlockIndex()));
                        var biome = context.noiseBiome(x >> 2, (sample.surfaceY() - 1) >> 2, z >> 2);
                        entry.addProperty("biome", biome.unwrapKey().map(k -> k.location().toString()).orElse("inline"));
                        entry.addProperty("grassRgb", biome.value().getGrassColor(x, z));
                        entry.addProperty("foliageRgb", biome.value().getFoliageColor());
                        entry.addProperty("waterRgb", biome.value().getWaterColor());
                        entry.addProperty("vssSurfaceRgb", context.surfaceColor(x, sample.surfaceY(), z));
                        entry.addProperty("vssFoliageRgb", context.foliageColor(x, sample.surfaceY(), z));
                        var heights = LevelHeightAccessor.create(profile.minY(), profile.height());
                        entry.addProperty("vanillaOceanFloorWg", context.generatorContext().getBaseHeight(x, z,
                                Heightmap.Types.OCEAN_FLOOR_WG, heights, context.randomStateContext()));
                        line(columns, entry);
                        if (i < 4) {
                            sampleDensity(density, noise, context, registries, x, z);
                            var column = context.generatorContext().getBaseColumn(x, z, heights, context.randomStateContext());
                            JsonObject baseEntry = point(x, 0, z);
                            JsonArray runs = new JsonArray();
                            int start = profile.minY(), end = start + profile.height();
                            while (start < end) {
                                var state = column.getBlock(start);
                                int next = start + 1;
                                while (next < end && column.getBlock(next).equals(state)) next++;
                                JsonArray run = new JsonArray(); run.add(start); run.add(next); run.add(state(state)); runs.add(run);
                                start = next;
                            }
                            baseEntry.add("runs", runs); line(base, baseEntry);
                        }
                    }
                }
            }
            manifest.add("grids", grids);
            JsonArray order = new JsonArray();
            Map<net.minecraft.world.level.levelgen.placement.PlacedFeature, String> featureNames = new IdentityHashMap<>();
            var generatorContext = context.generatorContext();
            var biomes = List.copyOf(generatorContext.getBiomeSource().possibleBiomes());
            for (var biome : biomes) for (var step : generatorContext.getBiomeGenerationSettings(biome).features())
                for (var feature : step) featureNames.put(feature.value(), feature.unwrapKey().map(k -> k.location().toString()).orElse("inline"));
            var steps = net.minecraft.world.level.biome.FeatureSorter.buildFeaturesPerStep(biomes,
                    b -> generatorContext.getBiomeGenerationSettings(b).features(), true);
            int decorationStep = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
            if (steps.size() > decorationStep) for (var feature : steps.get(decorationStep).features()) order.add(featureNames.getOrDefault(feature, "inline"));
            write(directory.resolve("vegetation-feature-order.json"), JSON.toJson(order).getBytes(StandardCharsets.UTF_8));
            try (Writer features = writer(directory.resolve("vegetation.jsonl.gz"))) {
                var vegetation = new PredictionVegetation(context);
                for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                    checkCancelled();
                    int cx = Math.floorDiv(originX, 16) + dx, cz = Math.floorDiv(originZ, 16) + dz;
                    JsonObject entry = new JsonObject(); entry.addProperty("chunkX", cx); entry.addProperty("chunkZ", cz);
                    entry.add("blocks", blocks(vegetation.chunk(cx, cz))); line(features, entry);
                }
                manifest.addProperty("vegetationDiagnostics", vegetation.diagnostics());
            }
            exportPaletteAndTags(directory);
            try (Writer rng = writer(directory.resolve("random.jsonl.gz"))) {
                for (int index : new int[]{0, 1, 31, 127}) {
                    var random = new WorldgenRandom(new XoroshiroRandomSource(0));
                    long decoration = random.setDecorationSeed(profile.seed(), originX & ~15, originZ & ~15);
                    random.setFeatureSeed(decoration, index, GenerationStep.Decoration.VEGETAL_DECORATION.ordinal());
                    JsonObject entry = new JsonObject(); entry.addProperty("featureIndex", index);
                    entry.addProperty("decorationSeed", Long.toString(decoration));
                    JsonArray values = new JsonArray();
                    for (int i = 0; i < 32; i++) values.add(Long.toString(random.nextLong()));
                    entry.add("nextLongSequence", values); line(rng, entry);
                }
            }
            JsonObject hashes = new JsonObject();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                    hashes.addProperty(file.getFileName().toString(), hash(Files.readAllBytes(file)));
            }
            manifest.add("sha256", hashes);
            manifest.addProperty("complete", true);
            write(directory.resolve("manifest.json"), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sampleDensity(Writer density, Writer noises, ClientTerrainSampler context,
                                      byte[] registryBytes, int x, int z) throws Exception {
        var random = context.randomStateContext();
        var router = random.router();
        var registry = JsonParser.parseString(new String(registryBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        for (int y = context.profile().minY(); y < context.profile().minY() + context.profile().height(); y += 32) {
            checkCancelled();
            var at = new DensityFunction.SinglePointContext(x, y, z);
            JsonObject entry = point(x, y, z);
            for (var component : NoiseRouter.class.getRecordComponents()) {
                DensityFunction function = (DensityFunction) component.getAccessor().invoke(router);
                entry.addProperty(component.getName(), Double.toHexString(function.compute(at)));
            }
            line(density, entry);
            if (y == context.profile().minY() || y == context.profile().minY() + 64) {
                JsonObject values = point(x, y, z);
                for (String name : registry.getAsJsonObject("noises").keySet()) {
                    var key = ResourceKey.create(Registries.NOISE, ResourceLocation.parse(name));
                    values.addProperty(name, Double.toHexString(random.getOrCreateNoise(key).getValue(x, y, z)));
                }
                line(noises, values);
            }
        }
    }

    private static void exportPaletteAndTags(Path dir) throws Exception {
        try (Writer palette = writer(dir.resolve("block-states.jsonl.gz"))) {
            for (Block block : BuiltInRegistries.BLOCK) for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                JsonObject row = new JsonObject(); row.addProperty("runtimeId", Block.getId(state));
                row.addProperty("state", state(state)); line(palette, row);
            }
        }
        JsonObject tags = new JsonObject();
        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            JsonArray values = new JsonArray();
            pair.getSecond().stream().map(h -> BuiltInRegistries.BLOCK.getKey(h.value()).toString()).sorted().forEach(values::add);
            tags.add(pair.getFirst().location().toString(), values);
        });
        write(dir.resolve("block-tags.json"), JSON.toJson(tags).getBytes(StandardCharsets.UTF_8));
    }

    static JsonArray blocks(Map<BlockPos, BlockState> blocks) {
        JsonArray values = new JsonArray();
        blocks.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<BlockPos, BlockState> e) -> e.getKey().getX())
                .thenComparingInt(e -> e.getKey().getZ()).thenComparingInt(e -> e.getKey().getY())).forEach(e -> {
                    JsonArray row = new JsonArray(); row.add(e.getKey().getX()); row.add(e.getKey().getY());
                    row.add(e.getKey().getZ()); row.add(state(e.getValue())); values.add(row);
                });
        return values;
    }
    static String state(BlockState value) { return net.minecraft.nbt.NbtUtils.writeBlockState(value).toString(); }
    private static String block(int id) { return id == ClientColumnSample.NO_BLOCK ? "unresolved" : BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(id)).toString(); }
    private static JsonObject point(int x, int y, int z) { JsonObject p = new JsonObject(); p.addProperty("x", x); p.addProperty("y", y); p.addProperty("z", z); return p; }
    static Writer writer(Path path) throws IOException { return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)), StandardCharsets.UTF_8)); }
    static void line(Writer out, JsonElement value) throws IOException { out.write(JSON.toJson(value)); out.write('\n'); }
    private static void write(Path file, byte[] data) throws IOException { Files.write(file, data, StandardOpenOption.CREATE_NEW); }
    private static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static void checkCancelled() { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }
}

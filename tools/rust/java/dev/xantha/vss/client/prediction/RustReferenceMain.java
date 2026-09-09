package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.Lifecycle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;

/** Offline fixtures call Minecraft directly and need no native library. */
public final class RustReferenceMain {
    private static final Gson JSON = new Gson();
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/reports/rust-reference").toAbsolutePath();
        Files.createDirectories(root);
        Path output = Files.createTempDirectory(root, "capture-");
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        loadTags(); loadColors();
        var lookup = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> biomes.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        JsonObject registries = new JsonObject();
        registry(registries, "density_functions", Registries.DENSITY_FUNCTION, lookup);
        registry(registries, "noises", Registries.NOISE, lookup);
        registry(registries, "biomes", Registries.BIOME, lookup);
        registry(registries, "configured_features", Registries.CONFIGURED_FEATURE, lookup);
        registry(registries, "placed_features", Registries.PLACED_FEATURE, lookup);
        registry(registries, "configured_carvers", Registries.CONFIGURED_CARVER, lookup);
        registry(registries, "structures", Registries.STRUCTURE, lookup);
        registry(registries, "structure_sets", Registries.STRUCTURE_SET, lookup);
        byte[] registryData = JSON.toJson(registries).getBytes(StandardCharsets.UTF_8);
        long[] seeds = {2527382920944240122L, -1L};
        String[] names = {"forest", "plains", "flower_forest", "bamboo_jungle", "badlands", "snowy_plains", "swamp", "nether_wastes", "the_end"};
        int count = 0;
        for (String name : names) {
            if (args.length > 1 && !name.equals(args[1])) continue;
            for (long seed : name.equals("forest") ? seeds : new long[]{seeds[0]}) {
                var setting = name.equals("nether_wastes") ? NoiseGeneratorSettings.NETHER
                        : name.equals("the_end") ? NoiseGeneratorSettings.END : NoiseGeneratorSettings.OVERWORLD;
                var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(setting);
                var source = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME)
                        .getOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(name))));
                var generator = new NoiseBasedChunkGenerator(source, settings);
                var heights = settings.value().noiseSettings();
                var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace(name.equals("the_end") ? "the_end"
                        : name.equals("nether_wastes") ? "the_nether" : "overworld"), seed,
                        heights.minY(), heights.height(), "noise", setting.location().toString(), seed ^ name.hashCode());
                var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), seed);
                var context = new ClientTerrainSampler(seed, profile, generator, random,
                        LevelHeightAccessor.create(heights.minY(), heights.height()), settings.value().seaLevel(), List.of(), null, access);
                JsonObject generatorJson = new JsonObject();
                generatorJson.add("settings", resource("worldgen/noise_settings/" + setting.location().getPath()));
                generatorJson.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:" + name + "\"}"));
                Path fixture = output.resolve(name + "-" + seed);
                RustReferenceCapture.capture(fixture, profile, JSON.toJson(generatorJson).getBytes(StandardCharsets.UTF_8),
                        registryData, context, -514, -2);
                System.out.println("Captured " + fixture.getFileName());
                count++;
            }
        }
        if (count == 0) throw new IllegalArgumentException("Unknown reference biome filter");
        JsonObject summary = new JsonObject(); summary.addProperty("fixtures", count);
        summary.addProperty("kind", "vanilla fixed-biome fixtures; not the user's world");
        summary.addProperty("minecraft", "1.21.1"); summary.addProperty("complete", true);
        Files.writeString(output.resolve("capture.json"), JSON.toJson(summary), StandardOpenOption.CREATE_NEW);
        System.out.println("RUST_REFERENCE_OUTPUT=" + output);
    }

    private static <T> void registry(JsonObject root, String name, ResourceKey<? extends Registry<T>> key,
                                      HolderLookup.Provider lookup) {
        JsonObject values = new JsonObject();
        lookup.lookupOrThrow(key).listElements().forEach(h -> values.add(h.key().location().toString(),
                resource(key.location().getPath() + "/" + h.key().location().getPath())));
        root.add(name, values);
    }
    private static JsonElement resource(String path) {
        try (InputStream in = RustReferenceMain.class.getResourceAsStream("/data/minecraft/" + path + ".json")) {
            if (in == null) throw new IllegalArgumentException("Missing Minecraft resource " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static void loadColors() throws IOException {
        for (String name : List.of("grass", "foliage")) {
            try (InputStream in = RustReferenceMain.class.getResourceAsStream("/assets/minecraft/textures/colormap/" + name + ".png")) {
                var image = javax.imageio.ImageIO.read(Objects.requireNonNull(in));
                int[] values = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
                if (name.equals("grass")) GrassColor.init(values); else FoliageColor.init(values);
            }
        }
    }

    static void loadTags() throws Exception {
        Map<String, List<Holder<Block>>> resolved = new HashMap<>();
        for (var field : net.minecraft.tags.BlockTags.class.getFields()) {
            if (TagKey.class.isAssignableFrom(field.getType())) {
                TagKey<?> tag = (TagKey<?>) field.get(null);
                blockTag(tag.location().getPath(), resolved, new HashSet<>());
            }
        }
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        resolved.forEach((name, values) -> tags.put(TagKey.create(Registries.BLOCK,
                ResourceLocation.withDefaultNamespace(name)), values));
        BuiltInRegistries.BLOCK.bindTags(tags);
        // Roots query FluidTags.WATER; block tags alone leave this false in a
        // standalone bootstrap and produce an invalid dry-root oracle.
        Map<TagKey<net.minecraft.world.level.material.Fluid>, List<Holder<net.minecraft.world.level.material.Fluid>>> fluidTags = new HashMap<>();
        for (String name : List.of("water", "lava")) {
            try (InputStream in = RustReferenceMain.class.getResourceAsStream("/data/minecraft/tags/fluid/" + name + ".json")) {
                var values = new ArrayList<Holder<net.minecraft.world.level.material.Fluid>>();
                for (var value : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("values")) {
                    values.add(BuiltInRegistries.FLUID.getHolderOrThrow(ResourceKey.create(Registries.FLUID, ResourceLocation.parse(value.getAsString()))));
                }
                fluidTags.put(TagKey.create(Registries.FLUID, ResourceLocation.withDefaultNamespace(name)), List.copyOf(values));
            }
        }
        BuiltInRegistries.FLUID.bindTags(fluidTags);
    }
    private static List<Holder<Block>> blockTag(String name, Map<String, List<Holder<Block>>> resolved, Set<String> pending) throws IOException {
        if (resolved.containsKey(name)) return resolved.get(name);
        if (!pending.add(name)) throw new IllegalArgumentException("Cyclic block tag " + name);
        Set<Holder<Block>> values = new LinkedHashSet<>();
        try (InputStream in = RustReferenceMain.class.getResourceAsStream("/data/minecraft/tags/block/" + name + ".json")) {
            if (in != null) for (JsonElement entry : JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("values")) {
                String id = entry.isJsonPrimitive() ? entry.getAsString() : entry.getAsJsonObject().get("id").getAsString();
                if (id.startsWith("#minecraft:")) values.addAll(blockTag(id.substring(11), resolved, pending));
                else if (id.startsWith("minecraft:")) BuiltInRegistries.BLOCK.getHolder(
                        ResourceKey.create(Registries.BLOCK, ResourceLocation.parse(id))).ifPresent(values::add);
            }
        }
        pending.remove(name);
        var result = List.copyOf(values); resolved.put(name, result); return result;
    }
}

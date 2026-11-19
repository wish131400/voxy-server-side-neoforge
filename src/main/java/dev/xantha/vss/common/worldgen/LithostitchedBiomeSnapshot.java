package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.processing.LodByteCompression;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;

/** Lithostitched's biome-source codec only writes the delegate. Freeze the inputs to its runtime routing. */
public final class LithostitchedBiomeSnapshot {
    public static final String FIELD = "vss_lithostitched_biomes";
    public static final String MANAGER = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.BiomeInjectorManager";
    private static final String ROOT = "dev.worldgen.lithostitched.";
    private static final String SOURCE = ROOT + "impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final int MAX_ROUTING_BYTES = 32 * 1024 * 1024;
    private static final Map<BiomeSource, JsonObject> CAPTURED = Collections.synchronizedMap(new WeakHashMap<>());

    private LithostitchedBiomeSnapshot() { }

    public static void capture(BiomeSource source, RegistryAccess access, Map<?, ?> injectors,
                               Optional<?> regionFunction, Map<?, ?> regions) {
        JsonObject snapshot = new JsonObject();
        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, access);
            snapshot.add("injectors", encode(injectors, codec("api.worldgen.biomeinjector.BiomeInjector"), ops));
            snapshot.add("regions", encode(regions, codec("impl.worldgen.biomeinjector.region.Region"), ops));
            if (regionFunction.isPresent()) {
                Object function = regionFunction.get();
                var key = access.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements()
                        .filter(holder -> holder.value() == function).findFirst().orElseThrow().key();
                snapshot.addProperty("region_function", key.location().toString());
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            snapshot = new JsonObject();
            snapshot.addProperty("error", "Lithostitched runtime biome rules could not be captured");
            VSSLogger.warn("VSS could not capture Lithostitched biome routing", failure);
        }
        CAPTURED.put(source, snapshot);
    }

    public static void write(JsonObject generator, BiomeSource source) {
        if (!source.getClass().getName().equals(SOURCE)) return;
        var snapshot = CAPTURED.get(source);
        if (snapshot == null) return;
        if (snapshot.has("error")) {
            generator.addProperty("vss_unsupported_reason", "Lithostitched runtime biome rules were not captured");
            return;
        }
        if (snapshot.getAsJsonArray("injectors").isEmpty()) return;
        byte[] raw = snapshot.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_ROUTING_BYTES) {
            generator.addProperty("vss_unsupported_reason", "Lithostitched routing exceeds snapshot budget");
            return;
        }
        var encoded = LodByteCompression.compressForNetwork(raw, false);
        var section = new JsonObject();
        section.addProperty("bytes", Base64.getEncoder().encodeToString(encoded.bytes()));
        section.addProperty("raw_size", raw.length);
        section.addProperty("compression", encoded.method());
        generator.add(FIELD, section);
        generator.addProperty("vss_java_biomes", true);
    }

    public static void restore(JsonObject generator, BiomeSource source, DynamicOps<JsonElement> ops,
                               RegistryAccess access, NoiseGeneratorSettings settings, RandomState random, long seed) {
        if (!generator.has(FIELD)) return;
        if (!source.getClass().getName().equals(SOURCE))
            throw new IllegalArgumentException("Lithostitched snapshot requires its injector biome source");
        var section = generator.getAsJsonObject(FIELD);
        try {
            var raw = LodByteCompression.decompress(Base64.getDecoder().decode(section.get("bytes").getAsString()),
                    section.get("compression").getAsInt(), section.get("raw_size").getAsInt(), MAX_ROUTING_BYTES);
            var snapshot = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            var sourceType = source.getClass();
            var apply = java.util.Arrays.stream(sourceType.getMethods())
                    .filter(m -> m.getName().equals("applyInjectors") && m.getParameterCount() == 4)
                    .findFirst().orElseThrow();
            var adapterType = apply.getParameterTypes()[3];
            Object adapter = random;
            if (adapterType != RandomState.class) {
                var accessor = Class.forName(ROOT + "mixin.common.RandomStateAccessor");
                Object positional = accessor.getMethod("getRandom").invoke(random);
                adapter = adapterType.getConstructor(long.class, boolean.class, RandomState.class, PositionalRandomFactory.class)
                        .newInstance(seed, settings.useLegacyRandomSource(), random, positional);
            }
            var injectors = new LinkedHashMap<ResourceLocation, Object>();
            var injectorCodec = codec("api.worldgen.biomeinjector.BiomeInjector");
            for (var value : snapshot.getAsJsonArray("injectors")) {
                var entry = value.getAsJsonObject();
                injectors.put(ResourceLocation.parse(entry.get("id").getAsString()), injectorCodec.parse(ops, entry.get("value")).getOrThrow());
            }
            var regionType = Class.forName(ROOT + "impl.worldgen.biomeinjector.region.Region");
            var regionKey = codec(regionType, "KEY_CODEC");
            var regionCodec = codec(regionType, "CODEC");
            var regions = new LinkedHashMap<Object, Object>();
            for (var value : snapshot.getAsJsonArray("regions")) {
                var entry = value.getAsJsonObject();
                regions.put(regionKey.parse(ops, entry.get("id")).getOrThrow(), regionCodec.parse(ops, entry.get("value")).getOrThrow());
            }
            Optional<?> regionFunction = snapshot.has("region_function")
                    ? Optional.of(access.registryOrThrow(Registries.DENSITY_FUNCTION).getHolderOrThrow(ResourceKey.create(
                            Registries.DENSITY_FUNCTION, ResourceLocation.parse(snapshot.get("region_function").getAsString()))).value())
                    : Optional.empty();
            apply.invoke(source, injectors, regionFunction, regions, adapter);
        } catch (IOException | ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Cannot restore Lithostitched biome routing", failure);
        }
    }

    public static BiomeSource wrap(JsonObject generator, BiomeSource source) {
        if (!generator.has(FIELD) || source.getClass().getName().equals(SOURCE)) return source;
        try {
            return (BiomeSource) Class.forName(SOURCE).getConstructor(BiomeSource.class).newInstance(source);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Cannot rebuild the Lithostitched biome source", failure);
        }
    }

    private static JsonArray encode(Map<?, ?> entries, Codec<Object> codec, DynamicOps<JsonElement> ops) {
        var result = new JsonArray();
        for (var entry : entries.entrySet()) {
            var row = new JsonObject();
            var key = entry.getKey();
            row.addProperty("id", key instanceof ResourceKey<?> resourceKey ? resourceKey.location().toString() : key.toString());
            row.add("value", codec.encodeStart(ops, entry.getValue()).getOrThrow());
            result.add(row);
        }
        return result;
    }

    private static Codec<Object> codec(String className) throws ReflectiveOperationException {
        return codec(Class.forName(ROOT + className), "CODEC");
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> codec(Class<?> type, String field) throws ReflectiveOperationException {
        return (Codec<Object>) type.getField(field).get(null);
    }
}

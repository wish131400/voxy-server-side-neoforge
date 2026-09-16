package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.WorldgenJson;
import java.lang.reflect.Field;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.BiomeSource;

/**
 * Captures Blueprint's {@code blueprint:modded} biome slices for client prediction.
 *
 * <p>Blueprint's {@code ModdedBiomeSource.CODEC} serializes only
 * {@code original_biome_source}: the slice array, its {@code size} and both
 * positional seeds are constructor state that never reaches the vanilla
 * codecs. A client that rebuilds the generator from JSON alone therefore sees
 * a plain forwarder, while the running server routes a large share of the
 * world through slices. This snapshot serializes exactly the missing inputs.
 *
 * <p>Slice order matters: {@code getSlice} walks the array accumulating
 * weights until the running remainder goes negative, so the order captured
 * here is the order the client must replay. Reading it from the live source
 * also sidesteps the fact that Blueprint's own registration order is a
 * HashMap iteration order that cannot be derived from the data files.
 *
 * <p>Blueprint types are reached through reflection so this file never loads
 * a Blueprint class at link time. An absent or reshaped mod simply yields
 * {@code null}, and the caller keeps its fail-closed rejection path.
 */
final class BlueprintBiomeSnapshot {
    private static final String MODDED_SOURCE =
            "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource";
    private static final String MODDED_SLICE =
            "com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSlice";

    /** Keep the section inside the same 8 MiB generator decompress budget TerraBlender uses. */
    private static final int MAX_SECTION_BYTES = 6_815_744; // 6.5 MiB
    private static final int MAX_SLICES = 256;

    private BlueprintBiomeSnapshot() {
    }

    /** Returns the {@code vss_blueprint} section, or {@code null} when unavailable. */
    static JsonObject capture(BiomeSource source, RegistryAccess access, RegistryOps<JsonElement> ops) {
        return capture(source, access, ops, null);
    }

    static JsonObject capture(BiomeSource source, RegistryAccess access, RegistryOps<JsonElement> ops,
                              String terrablenderRegionType) {
        if (source == null) return null;
        try {
            Class<?> type = Class.forName(MODDED_SOURCE);
            if (!type.isInstance(source)) return null;
            Object[] slices = (Object[]) field(type, "slices").get(source);
            if (slices == null || slices.length == 0) return null;
            if (slices.length > MAX_SLICES) {
                VSSLogger.debug("VSS Blueprint slice snapshot: " + slices.length + " slices exceeds budget");
                return null;
            }
            Codec<?> sliceCodec = (Codec<?>) Class.forName(MODDED_SLICE).getField("CODEC").get(null);

            JsonObject section = new JsonObject();
            // The wrapped source must go through the same encoder as the
            // top-level one: a multi-noise source is frequently written in its
            // compact `{"preset": ...}` form, which a client without the
            // parameter-list registry cannot decode. Blueprint wraps whatever
            // the dimension already used, so it is subject to the same case.
            BiomeSource original = (BiomeSource) field(type, "originalSource").get(source);
            section.add("original_biome_source", WorldgenCodecSnapshot.encodeBiomeSource(original, access, ops));
            // Blueprint hides the initialized MultiNoise source from the outer
            // generator check. Its codec loses TerraBlender's positional trees.
            if (terrablenderRegionType != null
                    && original instanceof net.minecraft.world.level.biome.MultiNoiseBiomeSource) {
                JsonObject regions = TerraBlenderRegionSnapshot.capture(terrablenderRegionType, access);
                if (regions == null) throw new IllegalStateException("Missing wrapped TerraBlender regions");
                section.add("original_terrablender", regions);
            }
            JsonArray array = new JsonArray();
            for (Object raw : slices) {
                Pair<?, ?> pair = (Pair<?, ?>) raw;
                JsonObject entry = new JsonObject();
                entry.addProperty("name", String.valueOf(pair.getFirst()));
                entry.add("slice", encode(ops, sliceCodec, pair.getSecond()));
                array.add(entry);
            }
            section.add("slices", array);
            section.addProperty("size", (Integer) field(type, "size").get(source));
            section.addProperty("slices_seed", (Long) field(type, "slicesSeed").get(source));
            section.addProperty("slices_zoom_seed", (Long) field(type, "slicesZoomSeed").get(source));

            int bytes = WorldgenJson.bytes(section).length;
            if (bytes > MAX_SECTION_BYTES) {
                VSSLogger.debug("VSS Blueprint slice snapshot too large: " + slices.length
                        + " slices, " + (bytes / 1024 / 1024) + " MiB; prediction stays unavailable");
                return null;
            }
            VSSLogger.debug("VSS Blueprint slice snapshot: " + slices.length
                    + " slices, " + (bytes / 1024) + " KiB");
            return section;
        } catch (Throwable failure) {
            VSSLogger.debug("VSS Blueprint slice snapshot unavailable: " + failure);
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static JsonElement encode(DynamicOps<JsonElement> ops, Codec<?> codec, Object value) {
        return ((Codec<Object>) codec).encodeStart(ops, value).getOrThrow();
    }
}

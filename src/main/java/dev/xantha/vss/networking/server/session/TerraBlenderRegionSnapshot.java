package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.worldgen.WorldgenJson;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Captures TerraBlender's positional region layout for the client prediction.
 *
 * <p>TerraBlender patches every {@code MultiNoiseBiomeSource} so biome lookup
 * first resolves a "region" from a layer-noise uniqueness grid and then
 * searches the region's own climate points; neither the region registration
 * nor the point lists are part of the vanilla codecs. This snapshot serializes
 * exactly the inputs the client needs to replay that routing: the effective
 * region size, every registered region in registration order with its weight,
 * and each region's {@code (ParameterPoint, biome)} pairs as produced by
 * {@code Region.addBiomes}. Region code is only touched through reflection so
 * this file never loads a TerraBlender class at link time.</p>
 */
final class TerraBlenderRegionSnapshot {
    static final String TYPE_OVERWORLD = "overworld";
    static final String TYPE_NETHER = "nether";

    /** Keep the section inside the 8 MiB generator decompress budget. */
    private static final int MAX_SECTION_BYTES = 6_815_744; // 6.5 MiB
    private static final int MAX_POINTS = 80_000;

    private TerraBlenderRegionSnapshot() {
    }

    /**
     * Mirrors TerraBlender's {@code LevelUtils.getRegionTypeForDimension}:
     * a dimension type tagged {@code terrablender:nether_regions} routes to
     * nether regions, {@code overworld_regions} to overworld regions, and any
     * other dimension keeps vanilla biome selection (TerraBlender never
     * initializes those sources, so they need no snapshot at all).
     */
    static String regionTypeFor(ServerLevel level) {
        Holder<DimensionType> type = level.dimensionTypeRegistration();
        if (type.is(TagKey.create(Registries.DIMENSION_TYPE,
                ResourceLocation.parse("terrablender:nether_regions")))) {
            return TYPE_NETHER;
        }
        if (type.is(TagKey.create(Registries.DIMENSION_TYPE,
                ResourceLocation.parse("terrablender:overworld_regions")))) {
            return TYPE_OVERWORLD;
        }
        return null;
    }

    /**
     * Builds the {@code vss_terrablender} generator section, or {@code null}
     * when TerraBlender is absent, misconfigured or refuses to cooperate. A
     * null return keeps the caller's fail-closed rejection path intact.
     */
    static JsonObject capture(String regionType, RegistryAccess access) {
        try {
            Class<?> regionsClass = Class.forName("terrablender.api.Regions");
            Class<?> regionTypeClass = Class.forName("terrablender.api.RegionType");
            Object type = enumConstant(regionTypeClass, regionType);
            Method get = regionsClass.getMethod("get", regionTypeClass);
            List<?> regions = (List<?>) get.invoke(null, type);
            int regionSize = readRegionSize(regionType);
            if (regions == null || regions.isEmpty() || regionSize < 0) {
                return null;
            }
            Registry<Biome> biomeRegistry = access.registryOrThrow(Registries.BIOME);
            // Resolve the accessors once from the API base class: a Method
            // resolved from one region's concrete class refuses targets of
            // another mod's region subclass.
            Class<?> regionClass = Class.forName("terrablender.api.Region");
            Method addBiomes = regionClass.getMethod("addBiomes", Registry.class, java.util.function.Consumer.class);
            Method getName = regionClass.getMethod("getName");
            Method getWeight = regionClass.getMethod("getWeight");
            JsonArray regionArray = new JsonArray();
            boolean anyWeighted = false;
            int totalPoints = 0;
            for (int index = 0; index < regions.size(); index++) {
                Object region = regions.get(index);
                ResourceLocation regionName = (ResourceLocation) getName.invoke(region);
                int regionWeight = (Integer) getWeight.invoke(region);
                List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> raw = new ArrayList<>();
                // TerraBlender's uniqueness layer counts every pair a region
                // emits; its per-region tree keeps only biomes the registry has.
                addBiomes.invoke(region, biomeRegistry, (java.util.function.Consumer<Object>) pair -> raw.add(asPair(pair)));
                boolean weighted = !raw.isEmpty();
                anyWeighted |= weighted;
                JsonObject entry = new JsonObject();
                entry.addProperty("name", regionName.toString());
                entry.addProperty("weight", regionWeight);
                entry.addProperty("base", index == 0);
                entry.addProperty("weighted", weighted);
                if (index != 0) {
                    Groups groups = encodeGroups(biomeRegistry, raw);
                    entry.add("groups", groups.array());
                    totalPoints += groups.points();
                }
                regionArray.add(entry);
            }
            if (!anyWeighted) {
                return null;
            }
            JsonObject section = new JsonObject();
            section.addProperty("region_size", regionSize);
            section.add("regions", regionArray);
            int sectionBytes = WorldgenJson.bytes(section).length;
            if (sectionBytes > MAX_SECTION_BYTES) {
                VSSLogger.warn("VSS TerraBlender region snapshot too large: " + totalPoints
                        + " points, " + (sectionBytes / 1024 / 1024) + " MiB; "
                        + "prediction stays unavailable for this dimension");
                return null;
            }
            VSSLogger.debug("VSS TerraBlender region snapshot: " + totalPoints
                    + " points, " + (sectionBytes / 1024) + " KiB for " + regionType);
            return section;
        } catch (Throwable failure) {
            VSSLogger.debug("VSS TerraBlender region snapshot unavailable: " + failure);
            return null;
        }
    }

    /** Effective {@code <type>_region_size} from TerraBlender's loaded config. */
    private static int readRegionSize(String regionType) {
        try {
            Class<?> core = Class.forName("terrablender.core.TerraBlender");
            Object config = core.getField("CONFIG").get(null);
            String field = TYPE_NETHER.equals(regionType) ? "netherRegionSize" : "overworldRegionSize";
            return config.getClass().getField(field).getInt(config);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            VSSLogger.debug("VSS TerraBlender region size unavailable: " + failure);
            return -1;
        }
    }

    /**
     * Deduplicated climate points grouped per biome, in the compact wire
     * form: 13 unquantized floats (six [min,max] parameter pairs plus the
     * offset). Region builders re-emit overlapping points heavily, so the
     * (biome, point) dedupe typically halves the payload.
     */
    private record Groups(JsonArray array, int points) {
    }

    private static Groups encodeGroups(Registry<Biome> biomeRegistry,
            List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> raw) {
        java.util.LinkedHashMap<String, JsonObject> groups = new java.util.LinkedHashMap<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int emitted = 0;
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> pair : raw) {
            if (biomeRegistry.getHolder(pair.getSecond()).isEmpty()) continue;
            Climate.ParameterPoint point = pair.getFirst();
            JsonArray flat = new JsonArray(13);
            for (long q : new long[]{point.temperature().min(), point.temperature().max(),
                    point.humidity().min(), point.humidity().max(),
                    point.continentalness().min(), point.continentalness().max(),
                    point.erosion().min(), point.erosion().max(),
                    point.depth().min(), point.depth().max(),
                    point.weirdness().min(), point.weirdness().max(),
                    point.offset()}) {
                flat.add(Climate.unquantizeCoord(q));
            }
            String biome = pair.getSecond().location().toString();
            if (!seen.add(biome + "|" + flat)) continue;
            if (++emitted > MAX_POINTS) {
                throw new IllegalStateException("terrablender region point budget");
            }
            JsonObject group = groups.computeIfAbsent(biome, id -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("biome", id);
                entry.add("points", new JsonArray());
                return entry;
            });
            group.getAsJsonArray("points").add(flat);
        }
        JsonArray array = new JsonArray();
        groups.values().forEach(array::add);
        return new Groups(array, emitted);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name.toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static Pair<Climate.ParameterPoint, ResourceKey<Biome>> asPair(Object pair) {
        return (Pair<Climate.ParameterPoint, ResourceKey<Biome>>) pair;
    }
}

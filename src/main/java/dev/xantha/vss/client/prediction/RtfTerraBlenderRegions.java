package dev.xantha.vss.client.prediction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Climate;

/**
 * ReTerraForged's TerraBlender region selection, applied in VSS's replay of TerraBlender's routing.
 *
 * On the server ReTerraForged's MixinClimateSampler puts its BIOME_REGION value into every TargetPoint, and its
 * MixinParameterList redirects TerraBlender's region index in findValuePositional: round(maxIndex * uniqueness) on the
 * surface, TerraBlender's own index underground (depth >= 0.2, or below Y 48 when Darker Depths or Deeper Darker is
 * installed), with an underground override into those mods' regions. VSS replays findValuePositional in its own
 * RoutingSource, where neither RTF hook attaches, so the rule is applied there. When the snapshot says Biolith's
 * TerraBlender compatibility answers the surface, the surface redirect is bypassed and only the underground override
 * remains, as on the server. Nothing here links ReTerraForged: its two accessor interfaces are called by name.
 *
 * The rule is reproduced from ReTerraForged's MixinParameterList and NoiseUtil.round (MIT, Copyright (c) ReTerraForged
 * contributors; github.com/Campione01/ReterraForged, branch rtf-0.0.7).
 */
final class RtfTerraBlenderRegions {
    /** The region index VSS's own replay of TerraBlender stands for. */
    static final int KEEP = Integer.MIN_VALUE;
    static final String OVERWORLD_BASE = "minecraft:overworld";
    static final String DARKER_DEPTHS = "darkerdepths:overworld";
    static final String DEEPER_DARKER = "deeperdarker:overworld";
    private static final String TARGET_API = "raccoonman.reterraforged.world.worldgen.terrablender.TBTargetPoint";
    private static final String SAMPLER_API = "raccoonman.reterraforged.world.worldgen.terrablender.TBClimateSampler";
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static volatile Links links;

    /** What RTF's initializeForTerraBlender HEAD reads, rebuilt from VSS's region snapshot and registries. */
    record Rule(int maxIndex, boolean compatTarget, int darkerDepths, int deeperDarker, String mode, boolean direct,
                boolean terrablenderSurface) { }

    private RtfTerraBlenderRegions() { }

    /** RTF is installed on this client: its TargetPoint accessor is loadable. */
    static boolean available() {
        return links() != null;
    }

    /** The snapshot lists every registered region in TerraBlender's index order (TerraBlenderRegionSnapshot.capture). */
    static Rule bind(JsonObject section, RegistryAccess access, java.util.function.Predicate<String> loaded) {
        JsonArray regions = section.getAsJsonArray("regions");
        List<String> names = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) names.add(regions.get(i).getAsJsonObject().get("name").getAsString());
        int maxIndex = names.size() - 1;
        boolean compatTarget = loaded.test("darkerdepths") || loaded.test("deeperdarker");
        boolean overworld = !names.isEmpty() && names.getFirst().equals(OVERWORLD_BASE);
        int darker = overworld && compatTarget ? names.indexOf(DARKER_DEPTHS) : -1;
        int deeper = overworld && compatTarget ? names.indexOf(DEEPER_DARKER) : -1;
        String mode = caveCompatibilityMode(access);
        boolean direct = "AUTO".equals(mode) && (valid(darker, maxIndex) || valid(deeper, maxIndex));
        return new Rule(maxIndex, compatTarget, darker, deeper, mode, direct, terrablenderSurface(section));
    }

    static void logBinding(Rule rule, JsonObject section) {
        JsonArray regions = section.getAsJsonArray("regions");
        String set = regions.isEmpty() ? "unnamed" : regions.get(0).getAsJsonObject().get("name").getAsString();
        if (set.startsWith("minecraft:")) set = set.substring("minecraft:".length());
        VSSLogger.info("VSS ReTerraForged TerraBlender region selection bound for " + set + " regions: maxIndex=" + rule.maxIndex()
                + ", darkerdepths=" + rule.darkerDepths() + ", deeperdarker=" + rule.deeperDarker() + ", caveCompatibilityMode=" + rule.mode()
                + ", directUndergroundCompat=" + rule.direct() + ", terrablenderSurface=" + rule.terrablenderSurface()
                + ", regions=" + regions.size() + ", source=" + (section.has("vss_region_source")
                        ? "unwrapped from Lithostitched's InjectorBiomeSource" : "direct MultiNoiseBiomeSource"));
    }

    static boolean terrablenderSurface(JsonObject section) {
        if (!section.has("vss_surface_route")) return false;
        JsonElement value = section.get("vss_surface_route");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !"terrablender".equals(value.getAsString()))
            throw new IllegalArgumentException("Unsupported TerraBlender surface-route marker");
        return true;
    }

    /** RTF's preset caves.compatibilityMode, AUTO when the snapshot carries no RTF preset. */
    private static String caveCompatibilityMode(RegistryAccess access) {
        return access.lookup(LegacyRtfRuntime.PRESETS).flatMap(lookup -> lookup.get(LegacyRtfRuntime.PRESET)).map(Holder::value).map(preset -> {
            try {
                Object caves = preset.getClass().getMethod("caves").invoke(preset);
                return String.valueOf(caves.getClass().getField("compatibilityMode").get(caves));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("RTF preset caves are unavailable", failure);
            }
        }).orElse("AUTO");
    }

    /** RTF's MixinNoiseChunk.cachedClimateSampler: a sampler built from another keeps that sampler's region value. */
    static void carry(Climate.Sampler source, Climate.Sampler target) {
        Links found = links();
        if (found == null || found.samplerApi == null || !found.samplerApi.isInstance(source) || !found.samplerApi.isInstance(target)) return;
        try {
            Object uniqueness = found.getSamplerUniqueness.invoke(source);
            if (uniqueness != null) found.setSamplerUniqueness.invoke(target, uniqueness);
        } catch (Throwable failure) {
            report("TerraBlender region value carry", failure);
        }
    }

    /** True when the sampler carries RTF's region value. */
    static boolean carried(Climate.Sampler sampler) {
        Links found = links();
        if (found == null || found.samplerApi == null || !found.samplerApi.isInstance(sampler)) return false;
        try {
            return found.getSamplerUniqueness.invoke(sampler) != null;
        } catch (Throwable failure) {
            report("TerraBlender region value", failure);
            return false;
        }
    }

    /** RTF's TBTargetPoint.getUniqueness, NaN when the point carries none. */
    static double uniqueness(Climate.TargetPoint target) {
        Links found = links();
        if (target == null || found == null || !found.targetApi.isInstance(target)) return Double.NaN;
        try {
            return (double) found.getTargetUniqueness.invoke((Object) target);
        } catch (Throwable failure) {
            report("TerraBlender region value", failure);
            return Double.NaN;
        }
    }

    /** False where RTF keeps TerraBlender's index whatever the region value is; then select needs no TargetPoint. */
    static boolean needsTarget(Rule rule, int y) {
        return !rule.terrablenderSurface() && !(rule.compatTarget() && QuartPos.toBlock(y) < 48);
    }

    /** RTF's getUniqueness redirect; KEEP where RTF leaves TerraBlender's index in place. uniqueness is NaN without a region value. */
    static int select(Rule rule, double uniqueness, float depth, int x, int y, int z) {
        if (!rule.terrablenderSurface() && !Double.isNaN(uniqueness)) {
            boolean underground = depth >= 0.2F || rule.compatTarget() && QuartPos.toBlock(y) < 48;
            if (!underground) return round((float) rule.maxIndex() * (float) uniqueness);
        }
        return override(rule, x, y, z);
    }

    /** RTF's RETURN hook on TerraBlender's getUniqueness(III): the underground compatibility region, else KEEP. */
    static int override(Rule rule, int x, int y, int z) {
        if (!rule.direct() || QuartPos.toBlock(y) >= 48) return KEEP;
        int roll = roll(x, y, z);
        if (roll >= 4) return KEEP;
        boolean darker = valid(rule.darkerDepths(), rule.maxIndex()), deeper = valid(rule.deeperDarker(), rule.maxIndex());
        if (darker && deeper) return (roll & 1) == 0 ? rule.darkerDepths() : rule.deeperDarker();
        return darker ? rule.darkerDepths() : rule.deeperDarker();
    }

    static int roll(int x, int y, int z) {
        int hash = x >> 4;
        hash = 31 * hash + (y >> 2);
        hash = 31 * hash + (z >> 4);
        hash ^= hash >>> 16;
        hash *= 0x7FEB352D;
        hash ^= hash >>> 15;
        hash *= 0x846CA68B;
        hash ^= hash >>> 16;
        return (hash & Integer.MAX_VALUE) % 20;
    }

    /** ReTerraForged's NoiseUtil.round. */
    static int round(float value) {
        return value >= 0.0F ? (int) (value + 0.5F) : (int) (value - 0.5F);
    }

    private static boolean valid(int index, int maxIndex) {
        return index >= 0 && index <= maxIndex;
    }

    static void report(String hook, Throwable failure) {
        if (REPORTED.add(hook)) VSSLogger.error("VSS ReTerraForged " + hook + " failed; VSS routes TerraBlender regions itself from now on", failure);
    }

    private static Links links() {
        Links found = links;
        if (found != null) return found == Links.ABSENT ? null : found;
        synchronized (RtfTerraBlenderRegions.class) {
            if (links == null) links = Links.resolve(RtfTerraBlenderRegions.class.getClassLoader());
            return links == Links.ABSENT ? null : links;
        }
    }

    /** RTF's two accessor interfaces, which its own mixins add to Climate.TargetPoint and Climate.Sampler. */
    private static final class Links {
        static final Links ABSENT = new Links(null, null, null, null, null, null);
        final Class<?> targetApi;
        final Class<?> samplerApi;
        final MethodHandle getTargetUniqueness;
        final MethodHandle getSamplerUniqueness;
        final MethodHandle setSamplerUniqueness;

        private Links(Class<?> targetApi, Class<?> samplerApi, MethodHandle target, MethodHandle get, MethodHandle set, Object unused) {
            this.targetApi = targetApi;
            this.samplerApi = samplerApi;
            this.getTargetUniqueness = target;
            this.getSamplerUniqueness = get;
            this.setSamplerUniqueness = set;
        }

        static Links resolve(ClassLoader loader) {
            try {
                Class<?> target = Class.forName(TARGET_API, false, loader);
                var lookup = MethodHandles.publicLookup();
                MethodHandle uniqueness = lookup.findVirtual(target, "getUniqueness", MethodType.methodType(double.class))
                        .asType(MethodType.methodType(double.class, Object.class));
                Class<?> sampler = Class.forName(SAMPLER_API, false, loader);
                Class<?> density = net.minecraft.world.level.levelgen.DensityFunction.class;
                MethodHandle get = lookup.findVirtual(sampler, "getUniqueness", MethodType.methodType(density))
                        .asType(MethodType.methodType(Object.class, Object.class));
                MethodHandle set = lookup.findVirtual(sampler, "setUniqueness", MethodType.methodType(void.class, density))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
                return new Links(target, sampler, uniqueness, get, set, null);
            } catch (ClassNotFoundException absent) {
                return ABSENT;
            } catch (ReflectiveOperationException | LinkageError unusable) {
                report("TerraBlender region accessors", unusable);
                return ABSENT;
            }
        }
    }
}

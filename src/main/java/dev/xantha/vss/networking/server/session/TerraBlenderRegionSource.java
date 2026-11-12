package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

/**
 * The biome source whose TerraBlender regions the snapshot describes, and what the client must know about its route.
 *
 * Lithostitched wraps the Overworld's MultiNoiseBiomeSource in its InjectorBiomeSource at server start (any mod with a
 * biome injector, such as Regions Unexplored, causes it). TerraBlender's regions still live on the injector's root
 * source, so the region snapshot is taken from that root; every other use of the generator's source is unchanged.
 * Lithostitched and Biolith are reached by name only.
 */
final class TerraBlenderRegionSource {
    /** Inside vss_terrablender: the snapshot was taken from the root of Lithostitched's injector. */
    static final String SOURCE_MARKER = "vss_region_source";
    static final String UNWRAPPED = "lithostitched_injector_root";
    /** Inside vss_terrablender: Biolith's TerraBlender compatibility answers the surface, so RTF's surface redirect is bypassed. */
    static final String SURFACE_MARKER = "vss_surface_route";
    static final String TERRABLENDER_SURFACE = "terrablender";
    private static final String INJECTOR = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private TerraBlenderRegionSource() { }

    /** The source under Lithostitched's injector layers when nothing else sits between them and the root; null otherwise. */
    static BiomeSource underInjectors(BiomeSource source) {
        return underInjectors(source, INJECTOR);
    }

    /** As underInjectors, for layers of the named class: tests use a stand-in that cannot shadow Lithostitched's own class. */
    static BiomeSource underInjectors(BiomeSource source, String injector) {
        if (source == null || !injector.equals(source.getClass().getName())) return null;
        try {
            Method direct = source.getClass().getMethod("directDelegate");
            Method root = source.getClass().getMethod("rootDelegate");
            Object rootSource = root.invoke(source);
            Object inner = source;
            while (inner != null && injector.equals(inner.getClass().getName())) inner = direct.invoke(inner);
            return inner == rootSource && inner instanceof BiomeSource biome ? biome : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            log("unwrap", "Lithostitched's injector root could not be read (" + unavailable + "); the snapshot keeps the wrapped source");
            return null;
        }
    }

    /** What the region check sees: the generator's MultiNoiseBiomeSource, or the root of its Lithostitched injector. */
    static BiomeSource regionSource(BiomeSource source) {
        return regionSource(source, INJECTOR);
    }

    static BiomeSource regionSource(BiomeSource source, String injector) {
        if (source instanceof MultiNoiseBiomeSource) return source;
        BiomeSource root = underInjectors(source, injector);
        return root instanceof MultiNoiseBiomeSource ? root : source;
    }

    static void markUnwrapped(JsonObject section, BiomeSource generatorSource) {
        if (generatorSource instanceof MultiNoiseBiomeSource) return;
        section.addProperty(SOURCE_MARKER, UNWRAPPED);
        log("unwrapped:" + regionSet(section), "TerraBlender region snapshot for " + regionSet(section)
                + " taken from the root MultiNoiseBiomeSource of Lithostitched's InjectorBiomeSource");
    }

    /**
     * With Biolith's TerraBlender compatibility active, Biolith's cancellable HEAD answers MultiNoiseBiomeSource's
     * lookup from TerraBlender's own fittest-node search over initialized region trees, before TerraBlender's
     * findValuePositional (where ReTerraForged redirects the surface region index) runs.
     */
    static void markSurfaceRoute(JsonObject section, BiomeSource regionSource) {
        try {
            if (!(regionSource instanceof MultiNoiseBiomeSource multi) || !biolithTerraBlender(multi)) return;
            Method parameters = MultiNoiseBiomeSource.class.getDeclaredMethod("parameters");
            parameters.setAccessible(true);
            Object effective;
            try { effective = parameters.invoke(multi); }
            catch (InvocationTargetException failure) { throw failure.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure.getCause()); }
            if (effective == null || !initializedTerraBlenderList(effective)) return;
            section.addProperty(SURFACE_MARKER, TERRABLENDER_SURFACE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            log("surface", "Biolith's TerraBlender surface route could not be read (" + unavailable + "); the snapshot carries no surface marker");
        }
    }

    private static boolean biolithTerraBlender(Object anchor) throws ReflectiveOperationException {
        Class<?> compat;
        try {
            compat = Class.forName("com.terraformersmc.biolith.impl.compat.BiolithCompat", false, anchor.getClass().getClassLoader());
        } catch (ClassNotFoundException absent) {
            return false;
        }
        return compat.getField("COMPAT_TERRABLENDER").getBoolean(null);
    }

    private static boolean initializedTerraBlenderList(Object parameters) throws ReflectiveOperationException {
        for (Class<?> api : parameters.getClass().getInterfaces()) {
            if (api.getName().equals("terrablender.worldgen.IExtendedParameterList"))
                return (boolean) api.getMethod("isInitialized").invoke(parameters);
        }
        return false;
    }

    private static String regionSet(JsonObject section) {
        var regions = section.getAsJsonArray("regions");
        String base = regions == null || regions.isEmpty() ? "unnamed" : regions.get(0).getAsJsonObject().get("name").getAsString();
        return base.startsWith("minecraft:") ? base.substring("minecraft:".length()) : base;
    }

    private static void log(String key, String message) {
        if (LOGGED.add(key)) VSSLogger.info(message);
    }
}

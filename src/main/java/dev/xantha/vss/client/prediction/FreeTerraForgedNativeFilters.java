package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Transfers prediction-owned tiles to the native filter pipeline; never owns a live server generator. */
public final class FreeTerraForgedNativeFilters {
    private static final Map<Object, Binding> BINDINGS = Collections.synchronizedMap(new WeakHashMap<>());

    static void register(Object context) throws ReflectiveOperationException {
        if (context == null || !RustTerrainSampler.available()) return;
        Object generator = context.getClass().getField("generator").get(context);
        var field = generator.getClass().getDeclaredField("filters"); field.setAccessible(true);
        Object filters = field.get(generator);
        if (BINDINGS.containsKey(filters)) return;
        Object preset = context.getClass().getField("preset").get(context);
        Object settings = preset.getClass().getMethod("filters").invoke(preset);
        Object levels = context.getClass().getField("levels").get(context);
        Object seed = context.getClass().getField("seed").get(context);
        JsonObject document = options(settings, (int) seed.getClass().getMethod("root").invoke(seed),
                levels.getClass().getField("worldHeight").getInt(levels), levels.getClass().getField("waterLevel").getInt(levels));
        Object world = preset.getClass().getMethod("world").invoke(preset);
        Object control = world.getClass().getField("controlPoints").get(world);
        document.addProperty("beach_transition", control.getClass().getField("beach").getFloat(control));
        BINDINGS.put(filters, new Binding(document, new Access(context.getClass().getClassLoader())));
    }

    public static boolean apply(Object filters, Object tile, boolean optional) {
        Binding binding = BINDINGS.get(filters);
        if (binding == null) return false;
        try {
            var options = binding.options.deepCopy(); options.addProperty("optional", optional);
            binding.access.apply(tile, options);
            return true;
        } catch (RuntimeException | Error error) { throw error; }
        catch (Throwable failure) { throw new IllegalStateException("FreeTerraForged native prediction filters failed", failure); }
    }

    static JsonObject options(Object settings, int seed, int worldHeight, int seaLevel) throws ReflectiveOperationException {
        Object erosion = settings.getClass().getField("erosion").get(settings);
        Object smoothing = settings.getClass().getField("smoothing").get(settings);
        JsonObject doc = new JsonObject();
        doc.addProperty("optional", true);
        doc.addProperty("seed", seed); doc.addProperty("world_height", worldHeight); doc.addProperty("sea_level", seaLevel);
        for (var e : Map.of("droplets_per_chunk", "dropletsPerChunk", "droplet_lifetime", "dropletLifetime", "droplet_volume", "dropletVolume",
                "droplet_velocity", "dropletVelocity", "erosion_rate", "erosionRate", "deposit_rate", "depositeRate").entrySet())
            doc.addProperty(e.getKey(), (Number) erosion.getClass().getField(e.getValue()).get(erosion));
        for (var e : Map.of("smoothing_iterations", "iterations", "smoothing_radius", "smoothingRadius", "smoothing_rate", "smoothingRate").entrySet())
            doc.addProperty(e.getKey(), (Number) smoothing.getClass().getField(e.getValue()).get(smoothing));
        return doc;
    }

    private record Binding(JsonObject options, Access access) { }

    static final class Access {
        private final Class<?> filterable;
        private final MethodHandle[] getters = new MethodHandle[8], setters = new MethodHandle[4];
        private final MethodHandle terrain, erosionModifier, mask, absent;
        private final MethodHandle coast, wetland, shallow, deep, delegate, setTerrain;
        private final Object beachTerrain, beachCategory;
        Access(ClassLoader loader) throws ReflectiveOperationException {
            String prefix = "raccoonman.reterraforged.world.worldgen.";
            filterable = loader.loadClass(prefix + "densityfunction.tile.filter.Filterable");
            Class<?> cell = loader.loadClass(prefix + "cell.Cell");
            var lookup = MethodHandles.publicLookup();
            String[] names = {"height", "sediment", "heightErosion", "gradient", "terrainRegionEdge", "riverMask", "continentEdge"};
            int[] slots = {0, 1, 2, 3, 5, 6, 7};
            for (int i = 0; i < names.length; i++) {
                var f = cell.getField(names[i]);
                getters[slots[i]] = lookup.unreflectGetter(f).asType(MethodType.methodType(float.class, Object.class));
                if (slots[i] < 4) setters[slots[i]] = lookup.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, float.class));
            }
            var terrainField = cell.getField("terrain");
            terrain = lookup.unreflectGetter(terrainField).asType(MethodType.methodType(Object.class, Object.class));
            erosionModifier = lookup.unreflect(terrainField.getType().getMethod("erosionModifier")).asType(MethodType.methodType(float.class, Object.class));
            coast = lookup.unreflect(terrainField.getType().getMethod("isCoast")).asType(MethodType.methodType(boolean.class, Object.class));
            wetland = lookup.unreflect(terrainField.getType().getMethod("isWetland")).asType(MethodType.methodType(boolean.class, Object.class));
            shallow = lookup.unreflect(terrainField.getType().getMethod("isShallowOcean")).asType(MethodType.methodType(boolean.class, Object.class));
            deep = lookup.unreflect(terrainField.getType().getMethod("isDeepOcean")).asType(MethodType.methodType(boolean.class, Object.class));
            delegate = lookup.unreflect(terrainField.getType().getMethod("getDelegate")).asType(MethodType.methodType(Object.class, Object.class));
            setTerrain = lookup.unreflectSetter(terrainField).asType(MethodType.methodType(void.class, Object.class, Object.class));
            beachTerrain = loader.loadClass(prefix + "cell.terrain.TerrainType").getField("BEACH").get(null);
            beachCategory = loader.loadClass(prefix + "cell.terrain.TerrainCategory").getField("BEACH").get(null);
            mask = lookup.unreflectGetter(cell.getField("erosionMask")).asType(MethodType.methodType(boolean.class, Object.class));
            absent = lookup.unreflect(cell.getMethod("isAbsent")).asType(MethodType.methodType(boolean.class, Object.class));
        }
        void apply(Object tile, JsonObject options) throws Throwable {
            Object size = filterable.getMethod("getBlockSize").invoke(tile);
            int total = (int) size.getClass().getMethod("total").invoke(size);
            Object[] cells = (Object[]) filterable.getMethod("getBacking").invoke(tile);
            if (total < 16 || total > 1024 || cells.length != total * total) throw new IllegalArgumentException("Invalid FTF tile size");
            JsonObject doc = options.deepCopy();
            doc.addProperty("size", total);
            doc.addProperty("border", (int) size.getClass().getMethod("border").invoke(size));
            doc.addProperty("block_x", (int) filterable.getMethod("getBlockX").invoke(tile));
            doc.addProperty("block_z", (int) filterable.getMethod("getBlockZ").invoke(tile));
            ByteBuffer data = org.lwjgl.system.MemoryUtil.memAlloc(cells.length * 36).order(ByteOrder.LITTLE_ENDIAN);
            try {
            for (Object cell : cells) {
                Object t = (Object) terrain.invokeExact(cell);
                for (int i = 0; i < 8; i++) data.putFloat(i == 4 ? (float) erosionModifier.invokeExact(t) : (float) getters[i].invokeExact(cell));
                data.putInt(((boolean) mask.invokeExact(cell) ? 1 : 0) | ((boolean) absent.invokeExact(cell) ? 2 : 0)
                        | ((boolean) coast.invokeExact(t) ? 4 : 0) | ((boolean) wetland.invokeExact(t) ? 8 : 0)
                        | ((boolean) shallow.invokeExact(t) || (boolean) deep.invokeExact(t) ? 16 : 0)
                        | ((Object) delegate.invokeExact(t) == beachCategory ? 32 : 0));
            }
            RustWorldgenBackend.freeTerraForgedFilters(data, doc.toString());
            for (int i = 0; i < cells.length; i++) {
                for (int field = 0; field < 4; field++) setters[field].invokeExact(cells[i], data.getFloat(i * 36 + field * 4));
                if ((data.getInt(i * 36 + 32) & 256) != 0) setTerrain.invokeExact(cells[i], beachTerrain);
            }
            } finally { org.lwjgl.system.MemoryUtil.memFree(data); }
        }
    }

    private FreeTerraForgedNativeFilters() { }
}

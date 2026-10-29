package dev.xantha.vss.networking.server.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.BiomeSource;

/** Captures the initialized server areas, including 4.1.0.8's edge-list island routing. */
final class TerraBlenderEndSnapshot {
    static final String FIELD = "vss_terrablender_end";
    private static final String[] AREAS = {"highlands", "midlands", "edge", "islands"};
    private static final String[] CONFIG = {"endHighlandsBiomeSize", "endMidlandsBiomeSize", "endEdgeBiomeSize", "endIslandBiomeSize"};
    private TerraBlenderEndSnapshot() { }

    static JsonObject capture(BiomeSource source, long seed) {
        Class<?> contract;
        try {
            contract = Class.forName("terrablender.worldgen.IExtendedTheEndBiomeSource", false,
                    source.getClass().getClassLoader());
        } catch (ClassNotFoundException absentOn120) { return null; }
        if (!contract.isInstance(source)) return null;
        try {
            // A source that the server never initialized still uses vanilla selection.
            if (!(Boolean) field(source, "tbInitialized")) return null;
            Object config = Class.forName("terrablender.core.TerraBlender", false,
                    source.getClass().getClassLoader()).getField("CONFIG").get(null);
            JsonObject root = new JsonObject();
            root.addProperty("revision", 1);
            root.addProperty("seed", seed);
            root.addProperty("density_coordinates", "block");
            for (int i = 0; i < AREAS.length; i++) {
                Object area = field(source, AREAS[i] + "Area");
                int configured = config.getClass().getField(CONFIG[i]).getInt(config);
                root.add(AREAS[i], captureArea(area, seed, configured));
            }
            return root;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot snapshot initialized TerraBlender End routing", failure);
        }
    }

    /** Follow the actual area chain; no version guessed list and no client config participates. */
    static JsonObject captureArea(Object area, long seed, int configuredSize) throws ReflectiveOperationException {
        int zooms = 0;
        List<Long> zoomSeeds = new ArrayList<>();
        List<Boolean> fuzzy = new ArrayList<>();
        Object initial = null;
        Object initialContext = null;
        var seen = new IdentityHashMap<Object, Boolean>();
        while (area != null && seen.put(area, Boolean.TRUE) == null) {
            if (zooms > 36) throw new IllegalStateException("TerraBlender End area depth");
            Object operator = field(area, "operator");
            Object parent = null;
            Object context = null;
            Boolean fuzzyLayer = null;
            for (Field member : fields(operator.getClass())) {
                Object captured = member.get(operator);
                if (captured == null) continue;
                String name = captured.getClass().getName();
                if (name.equals("terrablender.worldgen.noise.Area")) parent = captured;
                if (name.equals("terrablender.worldgen.noise.BiomeInitialLayer")) initial = captured;
                if (name.equals("terrablender.worldgen.noise.AreaContext")) context = captured;
                if (captured instanceof Enum<?> value && name.startsWith("terrablender.worldgen.noise.ZoomLayer"))
                    fuzzyLayer = value.name().equals("FUZZY");
            }
            if (parent == null) { initialContext = context; break; }
            if (context == null || fuzzyLayer == null) throw new IllegalStateException("Unknown End zoom layer");
            zoomSeeds.add(((Number) field(context, "seed")).longValue());
            fuzzy.add(fuzzyLayer);
            zooms++;
            area = parent;
        }
        if (initial == null || initialContext == null || zooms < 4)
            throw new IllegalStateException("Unknown TerraBlender End area implementation");
        int size = zooms - 4;
        if (size > 32 || ((Number) field(initialContext, "seed")).longValue() != mixSeed(seed, 1))
            throw new IllegalStateException("TerraBlender End seed/zoom contract changed");
        for (int i = 0; i < zooms; i++) {
            int position = zooms - 1 - i;
            long salt = position == 0 ? 2000 : position <= 3 ? 2000 + position : 1001 + position - 4;
            if (zoomSeeds.get(i) != mixSeed(seed, salt) || fuzzy.get(i) != (position == 0))
                throw new IllegalStateException("TerraBlender End zoom seed/order changed");
        }
        Object weighted = field(initial, "weightedEntries");
        List<?> entries = (List<?>) field(weighted, "items");
        if (entries.size() > 4096) throw new IllegalStateException("TerraBlender End biome count");
        JsonArray biomes = new JsonArray();
        long total = 0;
        for (Object entry : entries) {
            ResourceKey<?> key = null;
            Number weight = null;
            for (Field member : fields(entry.getClass())) {
                Object value = member.get(entry);
                if (value instanceof ResourceKey<?> resource) key = resource;
                else if (value instanceof Number number) weight = number;
                else if (value != null) for (Field nested : fields(value.getClass())) {
                    if (nested.getType() == int.class) weight = (Number) nested.get(value);
                }
            }
            if (key == null || weight == null || weight.intValue() < 0)
                throw new IllegalStateException("Unknown TerraBlender weighted biome entry");
            total += weight.intValue();
            if (total > Integer.MAX_VALUE) throw new IllegalStateException("TerraBlender End weight overflow");
            JsonObject item = new JsonObject();
            item.addProperty("biome", key.location().toString());
            item.addProperty("weight", weight.intValue());
            biomes.add(item);
        }
        JsonObject result = new JsonObject();
        result.addProperty("size", size);
        result.addProperty("configured_size", configuredSize);
        // BiomeInitialLayer's empty weighted list uses Biomes.OCEAN in the released API.
        result.addProperty("default_biome", "minecraft:ocean");
        result.add("biomes", biomes);
        return result;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (; type != null && type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                result.add(field);
            }
        return result;
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(target.getClass().getName() + '.' + name);
    }
    private static long mix(long seed, long operand) {
        return seed * (seed * 6364136223846793005L + 1442695040888963407L) + operand;
    }
    private static long mixSeed(long seed, long salt) {
        long value = mix(mix(mix(salt, salt), salt), salt);
        return mix(mix(mix(seed, value), value), value);
    }
}

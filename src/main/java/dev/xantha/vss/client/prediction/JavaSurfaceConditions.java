package dev.xantha.vss.client.prediction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Narrow JNI surface adapter; terrain noise stays native, mod configuration stays owned by the mod. */
final class JavaSurfaceConditions {
    private static final String ROOT = "com.github.alexmodguy.alexscaves.server.";
    private static final class AlexCaves {
        static final Adapter API = load();
        private static Adapter load() {
            try {
                var loader = JavaSurfaceConditions.class.getClassLoader();
                return new Adapter(Class.forName(ROOT + "level.biome.ACBiomeRarity", true, loader),
                        Class.forName(ROOT + "config.BiomeGenerationConfig", true, loader),
                        Class.forName(ROOT + "config.BiomeGenerationNoiseCondition", true, loader));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unsupported Alex's Caves ac_biome API", failure);
            }
        }
    }

    // Called before the native world is published. An absent/changed mod fails initialization,
    // allowing RustTerrainSampler.open to retain Java instead of inventing a surface result.
    static void verifyAlexCaves() { AlexCaves.API.getClass(); }

    static boolean alexCavesBiome(long seed, int x, int y, int z, int offset) {
        if (y > 50 || seed == 0) return false;
        return AlexCaves.API.matches(seed, x, z, offset);
    }

    /** Reflects the released 2.0.10 API without a required Alex's Caves dependency. */
    static final class Adapter {
        private final Method info, offset, biome, biomes, configuredOffset;
        Adapter(Class<?> rarity, Class<?> config, Class<?> condition) throws ReflectiveOperationException {
            info = rarity.getMethod("getRareBiomeInfoForQuad", long.class, int.class, int.class);
            offset = rarity.getMethod("getRareBiomeOffsetId", info.getReturnType());
            biome = rarity.getMethod("getACBiomeForPosition", long.class, int.class, int.class);
            biomes = config.getMethod("getBiomesSnapshot");
            configuredOffset = condition.getMethod("getRarityOffset");
        }
        boolean matches(long seed, int x, int z, int wanted) {
            try {
                Object cell = info.invoke(null, seed, x >> 2, z >> 2);
                if (cell == null || ((Number) offset.invoke(null, cell)).intValue() != wanted) return false;
                Object key = biome.invoke(null, seed, x, z);
                if (key == null) return false;
                Object definition = ((Map<?, ?>) biomes.invoke(null)).get(key);
                return definition != null && ((Number) configuredOffset.invoke(definition)).intValue() == wanted;
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Alex's Caves ac_biome evaluation failed", failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Alex's Caves ac_biome invocation failed", failure);
            }
        }
    }
    private JavaSurfaceConditions() { }
}

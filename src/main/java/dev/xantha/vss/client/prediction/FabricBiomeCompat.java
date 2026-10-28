package dev.xantha.vss.client.prediction;

/** Preserve Fabric/Forgified Fabric's seeded End-biome context on compiled climate samplers. */
final class FabricBiomeCompat {
    private FabricBiomeCompat() { }

    static void copyClimateContext(Object source, Object target) {
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> api : type.getInterfaces()) {
                if (!api.getName().equals("net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks")) continue;
                try {
                    // RandomState seeds its sampler after construction. Recreating only
                    // the six vanilla roots otherwise loses Fabric's End noise seed.
                    Object seed = api.getMethod("fabric_getSeed").invoke(source);
                    api.getMethod("fabric_setSeed", long.class).invoke(target, seed);
                    return;
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Fabric prediction biome seed unavailable", failure);
                }
            }
        }
    }
}

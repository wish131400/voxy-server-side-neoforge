package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;

/**
 * Bridges VSS prediction to BetterEnd-New-Dawn's PAULEVS terrain path.
 *
 * <p>BetterEnd deliberately keeps the vanilla {@link NoiseBasedChunkGenerator}
 * and replaces {@code NoiseChunk.fillSlice} with its own Java island SDF.  A
 * codec snapshot therefore looks like an ordinary vanilla End generator and a
 * native density graph cannot reproduce the mod's outer islands.  This class
 * discovers the optional BetterEnd classes at runtime and delegates surface
 * sampling to the same {@code TerrainGenerator.fillTerrainDensity} method
 * used by BetterEnd's mixin.  When BetterEnd is absent, or when its VANILLA
 * generator mode is active, it is a no-op.</p>
 */
final class BetterEndCompat {
    private static final String END_DIMENSION = "minecraft:the_end";
    private static final String TERRAIN_GENERATOR =
            "org.betterx.betterend.world.generator.TerrainGenerator";
    private static final String WOVER_END_SOURCE =
            "org.betterx.wover.generator.impl.biomesource.end.WoverEndBiomeSource";
    private static final String PAULEVS = "PAULEVS";

    private BetterEndCompat() {
    }

    /**
     * Returns the decoded sampler with BetterEnd's terrain function installed,
     * or {@code null} when this profile is not an active BetterEnd PAULEVS End.
     */
    static ClientTerrainSampler wrap(DimensionProfile profile, ClientTerrainSampler base) {
        if (profile == null || base == null
                || !END_DIMENSION.equals(profile.dimension().toString())) {
            return null;
        }
        NoiseBasedChunkGenerator generator = base.generatorContext();
        BiomeSource source = base.biomeSourceContext();
        if (generator == null || source == null || !isBetterEndSource(source)) {
            return null;
        }

        try {
            BetterEndSurface surface = BetterEndSurface.open(base);
            if (surface == null) return null;
            surface.initialize(profile.seed(), source, base.randomStateContext().sampler());
            VSSLogger.info("VSS BetterEnd PAULEVS terrain adapter active for "
                    + profile.dimension());
            return ClientTerrainSampler.withSurfaceOverride(base, surface::surfaceY);
        } catch (Throwable failure) {
            VSSLogger.warn("VSS BetterEnd terrain adapter could not be initialized for "
                    + profile.dimension() + "; keeping the decoded vanilla sampler", failure);
            return null;
        }
    }

    private static boolean isBetterEndSource(BiomeSource source) {
        Object configSource = source;
        for (int depth = 0; depth < 8 && configSource != null; depth++) {
            if (isWoverEndSource(configSource)) {
                Object config = invokeNoArg(configSource, "getBiomeSourceConfig");
                Object version = readField(config, "generatorVersion");
                return version != null && PAULEVS.equals(enumName(version));
            }
            configSource = unwrapBlueprint(configSource);
        }
        return false;
    }

    private static boolean isWoverEndSource(Object source) {
        return source != null && (WOVER_END_SOURCE.equals(source.getClass().getName())
                || hasMethod(source.getClass(), "getBiomeSourceConfig"));
    }

    private static Object unwrapBlueprint(Object source) {
        if (source == null || !"com.teamabnormals.blueprint.common.world.modification.ModdedBiomeSource"
                .equals(source.getClass().getName())) {
            return null;
        }
        return readField(source, "originalSource");
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
    }

    private static final class BetterEndSurface {
        private final Method initNoise;
        private final Method fillTerrainDensity;
        private final int scaleXZ;
        private final int scaleY;
        private final int maxHeight;
        private final int minY;
        private final ThreadLocal<double[]> buffers = new ThreadLocal<>();

        private BetterEndSurface(Method initNoise, Method fillTerrainDensity,
                                 NoiseSettings settings) {
            this.initNoise = initNoise;
            this.fillTerrainDensity = fillTerrainDensity;
            this.scaleXZ = settings.getCellWidth();
            this.scaleY = settings.getCellHeight();
            this.maxHeight = settings.height();
            this.minY = settings.minY();
        }

        static BetterEndSurface open(ClientTerrainSampler base) throws ReflectiveOperationException {
            ClassLoader loader = BetterEndCompat.class.getClassLoader();
            Class<?> terrain = Class.forName(TERRAIN_GENERATOR, false, loader);
            Method init = terrain.getMethod("initNoise", long.class, BiomeSource.class,
                    net.minecraft.world.level.biome.Climate.Sampler.class);
            Method fill = terrain.getMethod("fillTerrainDensity", double[].class,
                    int.class, int.class, int.class, int.class, int.class);
            NoiseSettings settings = base.generatorContext().generatorSettings().value().noiseSettings();
            if (settings.getCellWidth() <= 0 || settings.getCellHeight() <= 0 || settings.height() <= 0) {
                return null;
            }
            return new BetterEndSurface(init, fill, settings);
        }

        void initialize(long seed, BiomeSource source,
                        net.minecraft.world.level.biome.Climate.Sampler climate) throws ReflectiveOperationException {
            initNoise.invoke(null, seed, source, climate);
            // initNoise returns normally with a null config when the source is
            // not a supported BetterEnd source.  The source/version check above
            // already filters this case, but leave the reflective call isolated
            // so an incompatible BetterEnd build cleanly falls back.
        }

        int surfaceY(int blockX, int blockZ) {
            int alignedX = Math.floorDiv(blockX, scaleXZ) * scaleXZ;
            int alignedZ = Math.floorDiv(blockZ, scaleXZ) * scaleXZ;
            int length = Math.max(1, maxHeight / scaleY + 1);
            double[] buffer = buffers.get();
            if (buffer == null || buffer.length < length) {
                buffer = new double[length];
                buffers.set(buffer);
            }
            try {
                fillTerrainDensity.invoke(null, buffer, alignedX, alignedZ,
                        scaleXZ, scaleY, maxHeight);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("BetterEnd terrain density sampling failed", failure);
            }
            for (int y = length - 1; y >= 0; y--) {
                if (buffer[y] > 0.0D) {
                    return minY + y * scaleY;
                }
            }
            return minY;
        }
    }
}

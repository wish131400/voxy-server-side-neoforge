package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Captures the applied graph rather than a modifier's replay/source codec. */
public final class DensityFunctionSnapshot {
    private static final DensityFunction.Visitor UNWRAP = function -> {
        while (function instanceof DensityFunctions.HolderHolder holder) {
            function = holder.function().value();
        }
        return legacyTectonic(function);
    };

    private DensityFunctionSnapshot() { }

    private static DensityFunction legacyTectonic(DensityFunction function) {
        String name = function.getClass().getName();
        String prefix = "dev.worldgen.tectonic.worldgen.densityfunction.";
        try {
            if (name.equals(prefix + "ConfigConstant")) {
                return DensityFunctions.constant((double) function.getClass().getMethod("value").invoke(function));
            }
            if (name.equals(prefix + "ConfigNoise")) {
                // Newer ConfigNoise.mapAll already emits vanilla nodes, including
                // smoother scaling. This branch handles the six-field legacy record.
                var type = function.getClass();
                type.getConstructor(DensityFunction.NoiseHolder.class, DensityFunction.class,
                        DensityFunction.class, double.class, double.class, double.class);
                var noise = (DensityFunction.NoiseHolder) type.getMethod("noise").invoke(function);
                var shiftX = (DensityFunction) type.getMethod("shiftX").invoke(function);
                var shiftZ = (DensityFunction) type.getMethod("shiftZ").invoke(function);
                double scale = (double) type.getMethod("scale").invoke(function);
                double multiplier = (double) type.getMethod("multiplier").invoke(function);
                double offset = (double) type.getMethod("offset").invoke(function);
                var shifted = DensityFunctions.shiftedNoise2d(shiftX, shiftZ, scale, noise.noiseData())
                        .mapAll(new DensityFunction.Visitor() {
                            public DensityFunction apply(DensityFunction value) { return value; }
                            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) {
                                return value.noiseData() == noise.noiseData() && value.noise() == null ? noise : value;
                            }
                        });
                return DensityFunctions.add(DensityFunctions.mul(shifted, DensityFunctions.constant(multiplier)),
                        DensityFunctions.constant(offset));
            }
            return function;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture applied Tectonic density function " + name, failure);
        }
    }

    public static DensityFunction applied(DensityFunction function) {
        // mapAll visits the effective graph of Lithostitched's merged functions.
        // HolderHolder.mapAll creates direct holders, which must be unwrapped
        // before a nested DIRECT_CODEC attempts to call HolderHolder.codec().
        return function.mapAll(UNWRAP);
    }

    public static NoiseGeneratorSettings applied(NoiseGeneratorSettings settings) {
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(),
                settings.defaultFluid(), settings.noiseRouter().mapAll(UNWRAP),
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.disableMobGeneration(), settings.aquifersEnabled(),
                settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }
}

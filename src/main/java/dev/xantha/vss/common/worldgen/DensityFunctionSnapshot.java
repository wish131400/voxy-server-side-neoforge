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
        return function;
    };

    private DensityFunctionSnapshot() { }

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

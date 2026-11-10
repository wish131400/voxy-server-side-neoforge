package dev.xantha.vss.common.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

/** Captures the applied graph rather than a modifier's replay/source codec. */
public final class DensityFunctionSnapshot {
    private static final DensityFunction.Visitor UNWRAP = function -> {
        while (function instanceof DensityFunctions.HolderHolder holder) {
            function = holder.function().value();
        }
        return legacyTectonic(normalizeUnvisitedChildren(function));
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
                // 3.0.28 retains ConfigNoise when smootherScaling is enabled;
                // its mapAll does not call Visitor.apply for that branch.
                // Read the optional accessor instead of requiring a legacy constructor.
                var type = function.getClass();
                var noise = (DensityFunction.NoiseHolder) type.getMethod("noise").invoke(function);
                var shiftX = (DensityFunction) type.getMethod("shiftX").invoke(function);
                var shiftZ = (DensityFunction) type.getMethod("shiftZ").invoke(function);
                double scale = (double) type.getMethod("scale").invoke(function);
                double multiplier = (double) type.getMethod("multiplier").invoke(function);
                double offset = (double) type.getMethod("offset").invoke(function);
                boolean smootherScaling;
                try {
                    smootherScaling = (boolean) type.getMethod("smootherScaling").invoke(function);
                } catch (NoSuchMethodException legacyRelease) {
                    smootherScaling = false;
                }
                shiftX = applied(shiftX);
                shiftZ = applied(shiftZ);
                if (smootherScaling) {
                    // Tectonic: (coordinate + shift) * scale.
                    // Vanilla shiftedNoise: coordinate * scale + shift.
                    // Scale the shift too; omitting it changes the generated terrain.
                    shiftX = DensityFunctions.mul(shiftX, DensityFunctions.constant(scale));
                    shiftZ = DensityFunctions.mul(shiftZ, DensityFunctions.constant(scale));
                }
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

    private record RecordInputs(java.lang.reflect.Method[] accessors,
                                java.lang.reflect.Constructor<?> constructor) { }

    private static final ClassValue<RecordInputs> RECORD_INPUTS = new ClassValue<>() {
        protected RecordInputs computeValue(Class<?> type) {
            if (!type.isRecord() || !type.getName().startsWith(DensityFunctions.class.getName() + "$")) return null;
            try {
                var components = type.getRecordComponents();
                var parameters = new Class<?>[components.length];
                var accessors = new java.lang.reflect.Method[components.length];
                for (int i = 0; i < components.length; i++) {
                    parameters[i] = components[i].getType();
                    accessors[i] = components[i].getAccessor();
                    accessors[i].setAccessible(true);
                }
                var constructor = type.getDeclaredConstructor(parameters);
                constructor.setAccessible(true);
                return new RecordInputs(accessors, constructor);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect density snapshot inputs: " + type.getName(), failure);
            }
        }
    };

    private static DensityFunction normalizeUnvisitedChildren(DensityFunction function) {
        // Catch direct children of vanilla records as well as holder-wrapped
        // inputs. ConfigNoise's smoother branch can bypass the visitor entirely.
        // Rebuild only when such a child changed; retain other fields and bounds.
        var inputs = RECORD_INPUTS.get(function.getClass());
        if (inputs == null) return function;
        try {
            Object[] values = new Object[inputs.accessors().length];
            boolean changed = false;
            for (int i = 0; i < values.length; i++) {
                values[i] = inputs.accessors()[i].invoke(function);
                if (values[i] instanceof DensityFunction child && child.getClass().getName().startsWith(
                        "dev.worldgen.tectonic.worldgen.densityfunction.")) {
                    DensityFunction replacement = legacyTectonic(child);
                    changed |= replacement != child;
                    values[i] = replacement;
                }
            }
            return changed ? (DensityFunction) inputs.constructor().newInstance(values) : function;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot normalize density snapshot inputs", failure);
        }
    }

    public static DensityFunction applied(DensityFunction function) {
        // mapAll visits the effective graph of Lithostitched's merged functions.
        // HolderHolder.mapAll creates direct holders, which must be unwrapped
        // before a nested DIRECT_CODEC attempts to call HolderHolder.codec().
        return UNWRAP.apply(function.mapAll(UNWRAP));
    }

    public static NoiseGeneratorSettings applied(NoiseGeneratorSettings settings) {
        var r = settings.noiseRouter();
        // Visit router roots explicitly: an unwrapped ConfigNoise root can also
        // skip Visitor.apply. Keep all fifteen router channels and settings.
        var router = new NoiseRouter(applied(r.barrierNoise()), applied(r.fluidLevelFloodednessNoise()),
                applied(r.fluidLevelSpreadNoise()), applied(r.lavaNoise()), applied(r.temperature()),
                applied(r.vegetation()), applied(r.continents()), applied(r.erosion()), applied(r.depth()),
                applied(r.ridges()), applied(r.initialDensityWithoutJaggedness()), applied(r.finalDensity()),
                applied(r.veinToggle()), applied(r.veinRidged()), applied(r.veinGap()));
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(),
                settings.defaultFluid(), router,
                settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.disableMobGeneration(), settings.aquifersEnabled(),
                settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
    }
}

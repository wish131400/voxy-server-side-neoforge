package dev.xantha.vss.client.prediction;

import com.mojang.serialization.DataResult;
import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Prediction initializer for ReTerraForged (the legacy FreeTerraForged API). RTF's RandomState only initializes from a
 * ServerLevel, which a client never has, so VSS replays RTF's own MixinRandomState.initialize against the snapshot's
 * registries: the additional router functions and TerraBlender's uniqueness are wrapped by the state, the preset is
 * bound and, when the router carries CellSampler markers, a background generator context is built. RTF is reached by
 * reflection only, so nothing here links ReTerraForged.
 *
 * The initializer order and rules reproduce ReTerraForged's MixinRandomState (MIT, github.com/Campione01/ReterraForged,
 * branch rtf-0.0.7); the context uses GeneratorContext.makeBackground so prediction does not compete with world
 * generation threads, and makeCached in RTF builds without it.
 */
final class LegacyRtfRuntime {
    static final String STATE = "raccoonman.reterraforged.world.worldgen.RTFRandomState";
    private static final String PREFIX = "raccoonman.reterraforged.";
    static final ResourceKey<Registry<Object>> PRESETS = ResourceKey.createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/preset"));
    static final ResourceKey<Registry<Object>> NOISES = ResourceKey.createRegistryKey(ResourceLocation.parse("reterraforged:worldgen/noise"));
    static final ResourceKey<Object> PRESET = ResourceKey.create(PRESETS, ResourceLocation.parse("reterraforged:preset"));
    static final TagKey<DensityFunction> ADDITIONAL = TagKey.create(Registries.DENSITY_FUNCTION,
            ResourceLocation.parse("reterraforged:additional_noise_router_functions"));
    static final ResourceKey<DensityFunction> UNIQUENESS = ResourceKey.create(Registries.DENSITY_FUNCTION,
            ResourceLocation.parse("terrablender:uniqueness"));

    private LegacyRtfRuntime() { }

    /** True for RTF's state interface with the ServerLevel-only initializer that VSS's FreeTerraForged path cannot call. */
    static boolean legacyState(Class<?> stateType) {
        if (!STATE.equals(stateType.getName())) return false;
        try {
            stateType.getMethod("initialize", net.minecraft.server.level.ServerLevel.class);
            return true;
        } catch (NoSuchMethodException other) {
            return false;
        }
    }

    /** bound follows RTF's own initializer: the Overworld, and since RTF 0.0.7 the levels sharing its noise settings. */
    static void initialize(Class<?> stateType, Object state, RegistryAccess registries, boolean bound, String dimension) {
        if (!stateType.isInstance(state)) throw LegacyRtfInitialization.failure("RTF RandomState mixin is not active");
        StateView view = new StateView(stateType, state);
        if (!bound) {
            LegacyRtfInitialization.nativeDimension(view);
            return;
        }
        if (registries == null) throw LegacyRtfInitialization.failure("required Overworld snapshot contains no registry access");
        HolderLookup.RegistryLookup<Object> presets = registries.lookupOrThrow(PRESETS);
        HolderLookup.RegistryLookup<Object> noises = registries.lookupOrThrow(NOISES);
        HolderLookup.RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);
        ClassLoader loader = stateType.getClassLoader();
        LegacyRtfInitialization.initialize(true, view, new LegacyRtfInitialization.Inputs<Object, Object>() {
            @Override public void visitAdditional() {
                functions.get(ADDITIONAL).ifPresent(set -> set.forEach(function -> view.wrap(function.value())));
            }
            @Override public void applyTerraBlender() {
                Object sampler = view.call("sampler");
                Class<?> climate = load(loader, "world.worldgen.terrablender.TBClimateSampler");
                if (!climate.isInstance(sampler) || !(boolean) invoke(load(loader, "world.worldgen.terrablender.TBCompat"), null, "isEnabled")) return;
                functions.get(UNIQUENESS).ifPresent(function -> invoke(climate, sampler, "setUniqueness", view.wrap(function.value())));
            }
            @Override public Object preset() { return presets.get(PRESET).map(Holder::value).orElse(null); }
            @Override public Object build(Object preset, long seed) {
                Class<?> config = load(loader, "config.PerformanceConfig");
                Object read;
                try {
                    Field path = config.getField("DEFAULT_FILE_PATH");
                    Method open = config.getMethod("read", java.nio.file.Path.class);
                    @SuppressWarnings("unchecked") DataResult<Object> result = (DataResult<Object>) open.invoke(null, path.get(null));
                    read = result
                            .resultOrPartial(error -> VSSLogger.warn("RTF performance config: " + error))
                            .orElseGet(() -> invoke(config, null, "makeDefault"));
                } catch (ReflectiveOperationException failure) {
                    throw LegacyRtfInitialization.failure("RTF performance config unavailable: " + failure);
                }
                int tileSize = (int) invoke(config, read, "tileSize");
                int batchCount = (int) invoke(config, read, "batchCount");
                boolean queue = (int) invoke(load(loader, "concurrent.ThreadPools"), null, "availableProcessors") > 4;
                Object context = generatorContext(load(loader, "world.worldgen.GeneratorContext"), preset, noises, (int) seed, tileSize, batchCount, queue);
                VSSLogger.info("Legacy RTF prediction context: seedInt=" + (int) seed + ", tileSize=" + tileSize
                        + ", batchCount=" + batchCount + ", queue=" + queue);
                return context;
            }
        });
        VSSLogger.info("Legacy RTF prediction initialized: dimension=" + dimension + ", seed=" + view.seed()
                + ", preset=true, CellSamplerContext=" + view.hasMarkers());
    }

    /** VSS's prediction router reads the context; a marked router without a complete context is refused. */
    static void validateRouter(Class<?> stateType, Object state) {
        if (stateType.isInstance(state)) LegacyRtfInitialization.validate(new StateView(stateType, state));
    }

    /**
     * GeneratorContext's static six-argument factory: makeBackground where RTF has it (0.0.7), else makeCached, which
     * takes the same arguments and builds the same context on RTF's shared pool. Null when neither can be read.
     */
    private static final ClassValue<Method> CONTEXT_FACTORY = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> type) {
            Method cached = null;
            try {
                for (Method method : type.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 6) continue;
                    if (method.getName().equals("makeBackground")) return method;
                    if (method.getName().equals("makeCached")) cached = method;
                }
            } catch (LinkageError unreadable) {
                return null;
            }
            return cached;
        }
    };
    private static final java.util.concurrent.atomic.AtomicBoolean CACHED_FALLBACK_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();

    static Object generatorContext(Class<?> context, Object preset, Object noises, int seed, int tileSize, int batchCount, boolean queue) {
        Method factory = CONTEXT_FACTORY.get(context);
        if (factory == null) throw LegacyRtfInitialization.failure("RTF GeneratorContext.makeBackground and makeCached are unavailable");
        if (!factory.getName().equals("makeBackground") && CACHED_FALLBACK_LOGGED.compareAndSet(false, true))
            VSSLogger.info("RTF GeneratorContext.makeBackground is absent in this ReTerraForged build; prediction builds its context with makeCached");
        try {
            return factory.invoke(null, preset, noises, seed, tileSize, batchCount, queue);
        } catch (ReflectiveOperationException failure) {
            throw LegacyRtfInitialization.failure("RTF generator context could not be built: " + cause(failure));
        }
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(PREFIX + name, false, loader);
        } catch (ClassNotFoundException missing) {
            throw LegacyRtfInitialization.failure("RTF class " + name + " is unavailable");
        }
    }

    private static Object invoke(Class<?> owner, Object target, String name, Object... arguments) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            try {
                return method.invoke(target, arguments);
            } catch (ReflectiveOperationException failure) {
                throw LegacyRtfInitialization.failure(owner.getSimpleName() + "." + name + " failed: " + cause(failure));
            }
        }
        throw LegacyRtfInitialization.failure(owner.getSimpleName() + "." + name + " is unavailable");
    }

    private static String cause(ReflectiveOperationException failure) {
        Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invoked && invoked.getCause() != null
                ? invoked.getCause() : failure;
        if (cause instanceof RuntimeException runtime && cause.getMessage() != null && cause.getMessage().startsWith("VSS/RTF")) throw runtime;
        return String.valueOf(cause);
    }

    /** The fields RTF's MixinRandomState merges into RandomState, read and written as RTF's own initializer does. */
    private static final class StateView implements LegacyRtfInitialization.State<Object, Object> {
        private final Class<?> api;
        private final Object state;
        private final Field markers, seed, preset, context;

        StateView(Class<?> api, Object state) {
            this.api = api;
            this.state = state;
            markers = field("hasContext", boolean.class);
            seed = field("seed", long.class);
            preset = field("preset", null);
            context = field("generatorContext", null);
        }

        private Field field(String name, Class<?> type) {
            for (Class<?> owner = state.getClass(); owner != null; owner = owner.getSuperclass()) {
                try {
                    Field field = owner.getDeclaredField(name);
                    if (type != null && field.getType() != type) continue;
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException next) {
                    // RTF merges the field into RandomState; keep looking up the hierarchy.
                }
            }
            throw LegacyRtfInitialization.failure("RTF RandomState field " + name + " is unavailable");
        }

        Object call(String name) {
            try {
                Method method = state.getClass().getMethod(name);
                return method.invoke(state);
            } catch (ReflectiveOperationException failure) {
                throw LegacyRtfInitialization.failure("RandomState." + name + " failed: " + cause(failure));
            }
        }

        DensityFunction wrap(DensityFunction function) {
            return (DensityFunction) invoke(api, state, "wrap", function);
        }

        private Object get(Field field) {
            try { return field.get(state); }
            catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
        }

        private void set(Field field, Object value) {
            try { field.set(state, value); }
            catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
        }

        @Override public boolean hasMarkers() { return (boolean) get(markers); }
        @Override public long seed() { return (long) get(seed); }
        @Override public Object preset() { return get(preset); }
        @Override public Object context() { return get(context); }
        @Override public void preset(Object value) { set(preset, value); }
        @Override public void context(Object value) { set(context, value); }
        @Override public void clear() {
            set(markers, false);
            set(context, null);
            set(preset, null);
        }
    }
}

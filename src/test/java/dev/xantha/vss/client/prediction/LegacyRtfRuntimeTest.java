package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.common.worldgen.FreeTerraForgedVariant;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The legacy ReTerraForged initializer: its ordered semantics on plain state, and, with -PvssReTerraForgedJar, RTF's
 * own RandomState mixin state initialized by VSS from snapshot registries, compared with a context RTF builds itself.
 */
class LegacyRtfRuntimeTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    /** Plain state for the ordered semantics. */
    private static final class Plain implements LegacyRtfInitialization.State<String, String> {
        boolean markers; long seed = 7; String preset, context;
        @Override public boolean hasMarkers() { return markers; }
        @Override public long seed() { return seed; }
        @Override public String preset() { return preset; }
        @Override public String context() { return context; }
        @Override public void preset(String value) { preset = value; }
        @Override public void context(String value) { context = value; }
        @Override public void clear() { markers = false; preset = null; context = null; }
    }

    private static LegacyRtfInitialization.Inputs<String, String> inputs(List<String> calls, String preset, String context) {
        return new LegacyRtfInitialization.Inputs<>() {
            @Override public void visitAdditional() { calls.add("additional"); }
            @Override public void applyTerraBlender() { calls.add("terrablender"); }
            @Override public String preset() { calls.add("preset"); return preset; }
            @Override public String build(String value, long seed) { calls.add("build " + value + " " + seed); return context; }
        };
    }

    /** A GeneratorContext with both factories, as in RTF 0.0.7; each call is answered by the delegate. */
    public static final class BackgroundAndCachedContext {
        public static Object makeBackground(Object preset, HolderGetter<?> noises, int seed, int tileSize, int batchCount, boolean queue) {
            return List.of("background", preset, noises, seed, tileSize, batchCount, queue);
        }
        public static Object makeCached(Object preset, HolderGetter<?> noises, int seed, int tileSize, int batchCount, boolean queue) {
            return List.of("cached", preset, noises, seed, tileSize, batchCount, queue);
        }
    }

    /** A GeneratorContext without makeBackground, as in RTF builds other than 0.0.7. */
    public static final class CachedOnlyContext {
        static volatile java.util.function.Function<Object[], Object> delegate;
        public static Object makeCached(Object preset, HolderGetter<?> noises, int seed, int tileSize, int batchCount, boolean queue) {
            Object[] arguments = {preset, noises, seed, tileSize, batchCount, queue};
            var target = delegate;
            return target != null ? target.apply(arguments) : List.of("cached", preset, noises, seed, tileSize, batchCount, queue);
        }
        public static Object makeUncached(Object preset, HolderGetter<?> noises, int seed, int tileSize, int tileBorder, int batchCount) {
            throw new AssertionError("not a prediction factory");
        }
    }

    public static final class NoFactoryContext {
        public static Object makeUncached(Object preset, HolderGetter<?> noises, int seed, int tileSize, int tileBorder, int batchCount) {
            throw new AssertionError("not a prediction factory");
        }
    }

    @Test
    void contextFactoryPrefersMakeBackgroundAndFallsBackToMakeCachedWithTheSameArguments() {
        HolderGetter<?> noises = new MappedRegistry<Object>(LegacyRtfRuntime.NOISES, Lifecycle.stable()).createRegistrationLookup();
        assertEquals(List.of("background", "P", noises, 7, 256, 4, true),
                LegacyRtfRuntime.generatorContext(BackgroundAndCachedContext.class, "P", noises, 7, 256, 4, true));
        assertEquals(List.of("cached", "P", noises, 7, 256, 4, true),
                LegacyRtfRuntime.generatorContext(CachedOnlyContext.class, "P", noises, 7, 256, 4, true),
                "without makeBackground the context is built by makeCached from the same arguments");
        var refused = assertThrows(IllegalStateException.class,
                () -> LegacyRtfRuntime.generatorContext(NoFactoryContext.class, "P", noises, 7, 256, 4, true));
        assertTrue(refused.getMessage().contains("makeBackground and makeCached are unavailable"), refused.getMessage());
    }

    @Test
    void boundLevelWrapsRoutesBindsThePresetAndBuildsAContextOnlyForMarkers() {
        var calls = new ArrayList<String>();
        var state = new Plain();
        LegacyRtfInitialization.initialize(true, state, inputs(calls, "P", "C"));
        assertEquals(List.of("additional", "terrablender", "preset"), calls);
        assertEquals("P", state.preset);
        assertNull(state.context, "no CellSampler markers: RTF builds no context");
        calls.clear();
        var marked = new Plain();
        marked.markers = true;
        LegacyRtfInitialization.initialize(true, marked, inputs(calls, "P", "C"));
        assertEquals(List.of("additional", "terrablender", "preset", "build P 7"), calls);
        assertEquals("C", marked.context);
    }

    @Test
    void missingPresetOrContextAndNativeDimensionsWithRtfStateAreRefused() {
        var noPreset = new Plain();
        assertThrows(IllegalStateException.class, () -> LegacyRtfInitialization.initialize(true, noPreset, inputs(new ArrayList<>(), null, "C")));
        var noContext = new Plain();
        noContext.markers = true;
        assertThrows(IllegalStateException.class, () -> LegacyRtfInitialization.initialize(true, noContext, inputs(new ArrayList<>(), "P", null)));
        var nether = new Plain();
        nether.markers = true;
        var failure = assertThrows(IllegalStateException.class,
                () -> LegacyRtfInitialization.initialize(false, nether, inputs(new ArrayList<>(), "P", "C")));
        assertTrue(failure.getMessage().contains("outside the levels"), failure.getMessage());
        assertTrue(nether.markers, "a refusal does not erase the markers, so a retry cannot pass");
        var vanillaNether = new Plain();
        vanillaNether.preset = "stale";
        assertThrows(IllegalStateException.class, () -> LegacyRtfInitialization.nativeDimension(vanillaNether));
        var clean = new Plain();
        var calls = new ArrayList<String>();
        LegacyRtfInitialization.initialize(false, clean, inputs(calls, "P", "C"));
        assertTrue(calls.isEmpty(), "a native dimension reads no RTF registry");
        var orphan = new Plain();
        orphan.context = "C";
        assertThrows(IllegalStateException.class, () -> LegacyRtfInitialization.validate(orphan));
        var unbuilt = new Plain();
        unbuilt.markers = true;
        unbuilt.preset = "P";
        assertThrows(IllegalStateException.class, () -> LegacyRtfInitialization.validate(unbuilt));
    }

    // ---- the published ReTerraForged jar ----

    private static final String STATE = "raccoonman.reterraforged.world.worldgen.RTFRandomState";
    private static final String MIXIN = "raccoonman.reterraforged.mixin.MixinRandomState";
    private static final String HOLDER = "raccoonman.reterraforged.mixin.VssTestRandomState";

    /** RTF's classes plus one generated state class in RTF's mixin package, so it may extend the package-private mixin. */
    private static final class RtfLoader extends URLClassLoader {
        RtfLoader(Path jar) throws Exception { super(new URL[]{jar.toUri().toURL()}, LegacyRtfRuntimeTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    /**
     * The RandomState RTF's mixin produces, outside a Mixin environment: the mixin class with its own fields, the
     * RTFRandomState methods bridged to its prefixed implementations, and sampler() answering null (no TerraBlender).
     */
    private static Class<?> stateClass(RtfLoader loader) throws Exception {
        Class<?> api = loader.loadClass(STATE);
        String self = HOLDER.replace('.', '/'), parent = MIXIN.replace('.', '/');
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, self, null, parent, new String[]{STATE.replace('.', '/')});
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (Method method : api.getMethods()) {
            if (method.isDefault()) continue;
            String desc = Type.getMethodDescriptor(method);
            MethodVisitor bridge = writer.visitMethod(Opcodes.ACC_PUBLIC, method.getName(), desc, null, null);
            bridge.visitCode();
            bridge.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(desc)) {
                bridge.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }
            bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, parent, "reterraforged$RTFRandomState$" + method.getName(), desc, false);
            bridge.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
            bridge.visitMaxs(0, 0);
            bridge.visitEnd();
        }
        MethodVisitor sampler = writer.visitMethod(Opcodes.ACC_PUBLIC, "sampler", "()Lnet/minecraft/world/level/biome/Climate$Sampler;", null, null);
        sampler.visitCode();
        sampler.visitInsn(Opcodes.ACONST_NULL);
        sampler.visitInsn(Opcodes.ARETURN);
        sampler.visitMaxs(0, 0);
        sampler.visitEnd();
        writer.visitEnd();
        loader.loadClass(MIXIN);
        return loader.define(HOLDER, writer.toByteArray());
    }

    /** A new RTF state whose router went through RTF's constructor redirect: seed, wrapper and CellSampler markers. */
    private static Object newState(Class<?> type, ClassLoader loader, long seed, NoiseRouter router, List<NoiseRouter> mapped) throws Exception {
        Object state = type.getConstructor().newInstance();
        Method redirect = null;
        for (Method method : type.getSuperclass().getDeclaredMethods()) if (method.getName().equals("RandomState")) redirect = method;
        assertNotNull(redirect, "RTF's RandomState constructor redirect");
        redirect.setAccessible(true);
        mapped.add((NoiseRouter) redirect.invoke(state, router, (DensityFunction.Visitor) function -> function, null, null, seed));
        return state;
    }

    private static NoiseRouter markedRouter(ClassLoader loader) throws Exception {
        String prefix = "raccoonman.reterraforged.world.worldgen.densityfunction.";
        Class<?> field = loader.loadClass(prefix + "CellSampler$Field");
        Object marker = loader.loadClass(prefix + "CellSampler$Marker").getConstructor(field).newInstance(field.getField("HEIGHT").get(null));
        Class<?>[] types = java.util.Arrays.stream(NoiseRouter.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[types.length];
        java.util.Arrays.fill(values, marker);
        return NoiseRouter.class.getConstructor(types).newInstance(values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RegistryAccess snapshot(ClassLoader loader, Object preset) throws Exception {
        String prefix = "raccoonman.reterraforged.";
        for (String type : List.of("module.Noises", "domain.Domains", "function.CurveFunctions"))
            loader.loadClass(prefix + "world.worldgen.noise." + type).getMethod("bootstrap").invoke(null);
        var registryUtil = loader.loadClass(prefix + "platform.neoforge.RegistryUtilImpl");
        var registersField = registryUtil.getDeclaredField("REGISTERS");
        registersField.setAccessible(true);
        var registers = (Map<?, ?>) registersField.get(null);
        var entriesField = net.neoforged.neoforge.registries.DeferredRegister.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        for (var field : loader.loadClass(prefix + "registries.RTFBuiltInRegistries").getFields()) {
            if (!(field.get(null) instanceof Registry target)) continue;
            Object deferred = registers.get(target.key());
            if (deferred == null) continue;
            var entries = (Map<net.neoforged.neoforge.registries.DeferredHolder<?, ?>, java.util.function.Supplier<?>>) entriesField.get(deferred);
            for (var entry : entries.entrySet()) if (!target.containsKey(entry.getKey().getId()))
                Registry.register(target, entry.getKey().getId(), entry.getValue().get());
        }
        var noises = new MappedRegistry<Object>(LegacyRtfRuntime.NOISES, Lifecycle.stable());
        var registration = noises.createRegistrationLookup();
        var bootstrap = new net.minecraft.data.worldgen.BootstrapContext<Object>() {
            @Override public Holder.Reference<Object> register(ResourceKey<Object> key, Object value, Lifecycle lifecycle) {
                return noises.register(key, value, RegistrationInfo.BUILT_IN);
            }
            @Override public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> key) {
                if (key.equals(LegacyRtfRuntime.NOISES)) return (HolderGetter<S>) registration;
                throw new IllegalStateException("Unexpected noise bootstrap dependency " + key);
            }
        };
        loader.loadClass(prefix + "data.worldgen.preset.PresetNoiseData").getMethod("bootstrap",
                preset.getClass(), net.minecraft.data.worldgen.BootstrapContext.class).invoke(null, preset, bootstrap);
        noises.freeze();
        var presets = new MappedRegistry<Object>(LegacyRtfRuntime.PRESETS, Lifecycle.stable());
        presets.register(LegacyRtfRuntime.PRESET, preset, RegistrationInfo.BUILT_IN);
        presets.freeze();
        var density = new MappedRegistry<DensityFunction>(Registries.DENSITY_FUNCTION, Lifecycle.stable());
        density.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(presets, noises, density));
    }

    @Test
    @EnabledIfSystemProperty(named = "vss.reTerraForgedJar", matches = ".+")
    void reTerraForgedStateInitializesFromSnapshotRegistriesAsItsOwnInitializerWould() throws Exception {
        var loader = new RtfLoader(Path.of(System.getProperty("vss.reTerraForgedJar")));
        var api = new FreeTerraForgedCompat.Api(loader);
        assertEquals(FreeTerraForgedVariant.LEGACY, api.variant);
        assertTrue(api.legacyRtf, "RTF's state initializes only from a ServerLevel");
        assertFalse(api.epochBased);
        Class<?> type = stateClass(loader);
        Object preset = loader.loadClass("raccoonman.reterraforged.data.worldgen.preset.settings.Presets").getMethod("makeRTFDefault").invoke(null);
        RegistryAccess access = snapshot(loader, preset);
        var mapped = new ArrayList<NoiseRouter>();
        var contexts = new ArrayList<Object>();
        Class<?> generatorContext = loader.loadClass("raccoonman.reterraforged.world.worldgen.GeneratorContext");
        try {
            long seed = 918273645123L;
            Object state = newState(type, loader, seed, markedRouter(loader), mapped);
            FreeTerraForgedCompat.scoped(api.overworld, true, () -> { api.initialize(state, access); return null; });
            assertSame(access.registryOrThrow(LegacyRtfRuntime.PRESETS).get(LegacyRtfRuntime.PRESET), api.stateType.getMethod("preset").invoke(state));
            Object context = api.context.invoke(state);
            assertNotNull(context, "CellSampler markers: a generator context is built");
            contexts.add(context);
            LegacyRtfRuntime.validateRouter(api.stateType, state);

            Class<?> config = loader.loadClass("raccoonman.reterraforged.config.PerformanceConfig");
            Object defaults = config.getMethod("makeDefault").invoke(null);
            Object own = generatorContext.getMethod("makeCached", preset.getClass(), HolderGetter.class, int.class, int.class, int.class, boolean.class)
                    .invoke(null, preset, access.lookupOrThrow(LegacyRtfRuntime.NOISES), (int) seed,
                            config.getMethod("tileSize").invoke(defaults), config.getMethod("batchCount").invoke(defaults), false);
            contexts.add(own);
            // An RTF build without makeBackground: VSS's factory lookup takes RTF's own makeCached.
            Method makeCached = generatorContext.getMethod("makeCached", preset.getClass(), HolderGetter.class, int.class, int.class, int.class, boolean.class);
            CachedOnlyContext.delegate = arguments -> {
                try { return makeCached.invoke(null, arguments); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            Object fallback;
            try {
                fallback = LegacyRtfRuntime.generatorContext(CachedOnlyContext.class, preset, access.lookupOrThrow(LegacyRtfRuntime.NOISES), (int) seed,
                        (int) config.getMethod("tileSize").invoke(defaults), (int) config.getMethod("batchCount").invoke(defaults), false);
            } finally {
                CachedOnlyContext.delegate = null;
            }
            assertTrue(generatorContext.isInstance(fallback));
            contexts.add(fallback);
            var predicted = FreeTerraForgedDensity.map(context, mapped.getFirst()).finalDensity();
            var reference = FreeTerraForgedDensity.map(own, mapped.getFirst()).finalDensity();
            var fromFallback = FreeTerraForgedDensity.map(fallback, mapped.getFirst()).finalDensity();
            for (int i = 0; i < 16; i++) {
                var point = new DensityFunction.SinglePointContext(i * 37 - 300, 80, -i * 53 + 200);
                double expected = reference.compute(point);
                assertTrue(Double.isFinite(expected));
                assertEquals(expected, predicted.compute(point), 0.0, "sample " + i);
                assertEquals(expected, fromFallback.compute(point), 0.0, "makeCached fallback sample " + i);
            }

            Object nether = newState(type, loader, seed, markedRouter(loader), mapped);
            var refused = assertThrows(IllegalStateException.class,
                    () -> FreeTerraForgedCompat.scoped(api.overworld, false, () -> { api.initialize(nether, access); return null; }));
            assertTrue(refused.getMessage().contains("outside the levels"), refused.getMessage());

            Object vanilla = newState(type, loader, seed, NoiseRouter.class.cast(mapped.getFirst()), new ArrayList<>());
            var marker = type.getSuperclass().getDeclaredField("hasContext");
            marker.setAccessible(true);
            marker.setBoolean(vanilla, false);
            FreeTerraForgedCompat.scoped(api.overworld, false, () -> { api.initialize(vanilla, access); return null; });
            assertNull(api.context.invoke(vanilla));
            assertNull(api.stateType.getMethod("preset").invoke(vanilla), "a native dimension keeps no RTF preset");

            var contextField = type.getSuperclass().getDeclaredField("generatorContext");
            contextField.setAccessible(true);
            contextField.set(state, null);
            assertThrows(IllegalStateException.class, () -> LegacyRtfRuntime.validateRouter(api.stateType, state),
                    "a marked router without its context is refused before VSS samples it");
        } finally {
            for (Object context : contexts) FreeTerraForgedCompat.releaseTileCache(generatorContext.getField("cache").get(context));
            Class<?> cache = loader.loadClass("raccoonman.reterraforged.concurrent.cache.Cache");
            ((java.util.concurrent.ExecutorService) cache.getField("SCHEDULER").get(null)).shutdownNow();
        }
    }
}

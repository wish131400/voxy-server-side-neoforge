package dev.xantha.vss.client;

import dev.xantha.vss.client.prediction.PredictionPerformanceProfile;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.WeakHashMap;

/** Links only the two registered pending controls; Sodium owns apply, reset and persistence. */
public final class VSSPredictionThreadOptions {
    private static final Map<Object, Pair> PAIRS = new WeakHashMap<>();
    private static final String TIER = "vss:performance_tier";
    private static final String THREADS = "vss:prediction_worker_threads";
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> type) { return new Access(type); }
    };
    private static final ClassValue<Metadata> METADATA = new ClassValue<>() {
        @Override protected Metadata computeValue(Class<?> type) { return new Metadata(type); }
    };

    private VSSPredictionThreadOptions() { }

    public static void registerPair(Object tier, Object threads) {
        registerPair(tier, threads, Runtime.getRuntime().availableProcessors());
    }

    static void registerPair(Object tier, Object threads, int processors) {
        Pair pair = new Pair(tier, threads, Math.max(1, processors));
        synchronized (PAIRS) { PAIRS.put(tier, pair); PAIRS.put(threads, pair); }
    }

    /** All options have joined the parent Config before initial binding loads begin. */
    public static void registerSodium08(Object option) {
        try {
            Metadata metadata = METADATA.get(option.getClass());
            Object id = metadata.id.get(option);
            String name = id.toString();
            if (!name.equals(TIER) && !name.equals(THREADS)) return;
            Object state = metadata.state.get(option);
            Map<?, ?> options = (Map<?, ?>) Metadata.field(state.getClass(), "options").get(state);
            String peerName = name.equals(TIER) ? THREADS : TIER;
            for (var entry : options.entrySet()) if (entry.getKey().toString().equals(peerName)) {
                Object peer = entry.getValue();
                registerPair(name.equals(TIER) ? option : peer, name.equals(TIER) ? peer : option);
                return;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Sodium prediction thread controls could not be linked", e);
        }
    }

    public static void changed(Object option) {
        Pair pair;
        synchronized (PAIRS) { pair = PAIRS.get(option); }
        if (pair != null) pair.changed(option);
    }

    private static final class Pair {
        final WeakReference<Object> tier, threads;
        final int processors;
        boolean updating;
        Pair(Object tier, Object threads, int processors) {
            this.tier = new WeakReference<>(tier); this.threads = new WeakReference<>(threads);
            this.processors = processors;
        }
        void changed(Object source) {
            Object tier = this.tier.get(), threads = this.threads.get();
            if (updating || tier == null || threads == null) return;
            updating = true;
            try {
                Object value = ACCESS.get(tier.getClass()).get(tier);
                if (source == tier) {
                    int index = value instanceof Enum<?> e ? e.ordinal() : ((Number) value).intValue();
                    if (index >= 0 && index < 3)
                        ACCESS.get(threads.getClass()).set(threads, PredictionPerformanceProfile.values()[index].poolSize(processors));
                } else if (source == threads) {
                    int count = ((Number) ACCESS.get(threads.getClass()).get(threads)).intValue();
                    int bounded = Math.max(1, Math.min(processors, count));
                    if (count != bounded) ACCESS.get(threads.getClass()).set(threads, bounded);
                    Object custom = value instanceof Enum<?> e ? custom(e) : Integer.valueOf(3);
                    ACCESS.get(tier.getClass()).set(tier, custom);
                }
            } finally { updating = false; }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object custom(Enum<?> current) { return Enum.valueOf((Class) current.getDeclaringClass(), "CUSTOM"); }

    private static final class Access {
        final MethodHandle get, set;
        Access(Class<?> type) {
            try {
                java.lang.reflect.Method getter;
                boolean modern;
                try { getter = type.getMethod("getValidatedValue"); modern = true; }
                catch (NoSuchMethodException old) { getter = type.getMethod("getValue"); modern = false; }
                get = MethodHandles.publicLookup().unreflect(getter)
                        .asType(MethodType.methodType(Object.class, Object.class));
                set = MethodHandles.publicLookup().unreflect(type.getMethod(modern ? "modifyValue" : "setValue", Object.class))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Sodium pending option API unavailable", e); }
        }
        Object get(Object option) {
            try { return (Object) get.invokeExact(option); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new IllegalStateException("Could not read pending prediction option", e); }
        }
        void set(Object option, Object value) {
            try { set.invokeExact(option, value); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new IllegalStateException("Could not update pending prediction option", e); }
        }
    }

    private static final class Metadata {
        final Field id, state;
        Metadata(Class<?> type) {
            try { id = field(type, "id"); state = field(type, "state"); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Sodium option identity API unavailable", e); }
        }
        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
                catch (NoSuchFieldException next) { }
            }
            throw new NoSuchFieldException(name);
        }
    }
}

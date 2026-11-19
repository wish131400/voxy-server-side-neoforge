package dev.xantha.vss.api.worldgen;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

/** Equivalent optimizations for exact node classes; unknown implementations stay in Java. */
public final class DensityFunctionReplacements {
    public record Context(long seed, RegistryAccess registries, RandomState randomState) { }
    private record Entry(String identity, BiFunction<DensityFunction, Context, DensityFunction> factory) { }
    private static final Map<Class<?>, Entry> ENTRIES = new ConcurrentHashMap<>();
    private DensityFunctionReplacements() { }

    /** Register during initialization. The replacement must preserve values and evaluation semantics.
     * Mutable scratch must be worker/task owned. Changing its algorithm requires a new revision. */
    public static <T extends DensityFunction> void register(Class<T> exactClass, String id, int revision,
            BiFunction<T, Context, DensityFunction> factory) {
        if (id == null || id.isBlank() || revision < 1) throw new IllegalArgumentException("Replacement identity required");
        Objects.requireNonNull(factory);
        ENTRIES.put(Objects.requireNonNull(exactClass), new Entry(id + ':' + revision,
                (node, context) -> factory.apply(exactClass.cast(node), context)));
    }
    public static void unregister(Class<? extends DensityFunction> type) { ENTRIES.remove(type); }
    public static boolean empty() { return ENTRIES.isEmpty(); }
    public static String identity() {
        return ENTRIES.values().stream().map(Entry::identity).sorted().reduce("", (a, b) -> a + '|' + b);
    }
    public static DensityFunction apply(DensityFunction original, Context context) {
        Entry entry = ENTRIES.get(original.getClass());
        return entry == null ? original : Objects.requireNonNull(entry.factory().apply(original, context));
    }
}

package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.*;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Depth-first placement keeps each candidate's terminal random draws before the next candidate. */
final class PredictionPlacementExecutor {
    private static final Map<PlacedFeature, Plan> PLANS = new WeakHashMap<>();
    private static final ClassValue<Boolean> PRISTINE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) { return unmodified(type); }
    };
    private static final ClassValue<Operation> OPERATIONS = new ClassValue<>() {
        @Override protected Operation computeValue(Class<?> type) {
            return PRISTINE.get(type) ? operation(type) : OPAQUE;
        }
    };
    private static final Operation OPAQUE = (modifier, run, next, position) -> {
        // flatMap closes each child stream after fully consuming it, even on failure.
        try (var positions = modifier.getPositions(run.context, run.random, position)) {
            if (positions != null) positions.sequential().forEach(pos -> run.apply(next, pos));
        }
    };

    private PredictionPlacementExecutor() { }

    static boolean available() { return PRISTINE.get(PlacedFeature.class); }

    static void visit(PlacedFeature feature, PlacementContext context, RandomSource random,
                      BlockPos origin, Consumer<BlockPos> terminal) {
        Plan plan;
        synchronized (PLANS) {
            plan = PLANS.get(feature);
            if (plan == null) {
                var modifiers = List.copyOf(feature.placement());
                var operations = new Operation[modifiers.size()];
                for (int i = 0; i < operations.length; i++) operations[i] = OPERATIONS.get(modifiers.get(i).getClass());
                plan = new Plan(modifiers, operations);
                PLANS.put(feature, plan);
            }
        }
        plan.visit(context, random, origin, terminal);
    }

    /** A caller with an adapted terminal still retains opaque modifier implementations. */
    static void stream(PlacedFeature feature, PlacementContext context, RandomSource random,
                       BlockPos origin, Consumer<BlockPos> terminal) {
        java.util.stream.Stream<BlockPos> positions = java.util.stream.Stream.of(origin);
        for (var modifier : feature.placement())
            positions = positions.flatMap(pos -> modifier.getPositions(context, random, pos));
        positions.forEach(terminal);
    }

    static Plan compile(List<PlacementModifier> modifiers, Predicate<Class<?>> pristine) {
        var copy = List.copyOf(modifiers);
        var operations = new Operation[copy.size()];
        for (int i = 0; i < operations.length; i++) {
            Class<?> type = copy.get(i).getClass();
            operations[i] = pristine.test(type) ? operation(type) : OPAQUE;
        }
        return new Plan(copy, operations);
    }

    static final class Plan {
        private final List<PlacementModifier> modifiers;
        private final Operation[] operations;
        Plan(List<PlacementModifier> modifiers, Operation[] operations) {
            this.modifiers = modifiers;
            this.operations = operations;
        }
        void visit(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> terminal) {
            new Run(this, context, random, terminal).apply(0, origin);
        }
        int fastOperations() {
            int count = 0;
            for (var operation : operations) if (operation != OPAQUE) count++;
            return count;
        }
    }

    private record Run(Plan plan, PlacementContext context, RandomSource random, Consumer<BlockPos> terminal) {
        void apply(int index, BlockPos position) {
            if (index == plan.operations.length) terminal.accept(position);
            else plan.operations[index].apply(plan.modifiers.get(index), this, index + 1, position);
        }
    }

    @FunctionalInterface private interface Operation {
        void apply(PlacementModifier modifier, Run run, int next, BlockPos position);
    }

    private static Operation operation(Class<?> type) {
        if (type == InSquarePlacement.class) return (modifier, run, next, pos) -> {
            int x = run.random.nextInt(16) + pos.getX();
            int z = run.random.nextInt(16) + pos.getZ();
            run.apply(next, new BlockPos(x, pos.getY(), z));
        };
        if (type == CountPlacement.class || type == NoiseBasedCountPlacement.class || type == NoiseThresholdCountPlacement.class) {
            var count = method(type, int.class, RandomSource.class, BlockPos.class);
            if (count == null) return OPAQUE;
            return (modifier, run, next, pos) -> {
                int repetitions = count(count, modifier, run.random, pos);
                for (int i = 0; i < repetitions; i++) run.apply(next, pos);
            };
        }
        if (type == BiomeFilter.class || type == BlockPredicateFilter.class || type == RarityFilter.class
                || type == SurfaceWaterDepthFilter.class || type == SurfaceRelativeThresholdFilter.class) {
            var filter = method(type, boolean.class, PlacementContext.class, RandomSource.class, BlockPos.class);
            if (filter == null) return OPAQUE;
            return (modifier, run, next, pos) -> {
                if (filter(filter, modifier, run.context, run.random, pos)) run.apply(next, pos);
            };
        }
        if (type == HeightRangePlacement.class) {
            var height = field(type, HeightProvider.class);
            if (height == null) return OPAQUE;
            return (modifier, run, next, pos) -> run.apply(next,
                    pos.atY(((HeightProvider) read(height, modifier)).sample(run.random, run.context)));
        }
        if (type == HeightmapPlacement.class) {
            var heightmap = field(type, Heightmap.Types.class);
            if (heightmap == null) return OPAQUE;
            return (modifier, run, next, pos) -> {
                int y = run.context.getHeight((Heightmap.Types) read(heightmap, modifier), pos.getX(), pos.getZ());
                if (y > run.context.getMinBuildHeight()) run.apply(next, new BlockPos(pos.getX(), y, pos.getZ()));
            };
        }
        // CountOnEveryLayer eagerly scans every floor before any terminal runs.
        // EnvironmentScan and modded modifiers retain their original predicate hooks.
        return OPAQUE;
    }

    static boolean unmodified(Class<?> type) {
        try {
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (var mixin : Mixins.getMixinsForClass(current.getName()))
                    if (!independentMixin(current, mixin.getClassName())) return false;
                for (Method method : current.getDeclaredMethods()) {
                    var merged = method.getDeclaredAnnotation(MixinMerged.class);
                    if (merged != null && !independentAddition(current, merged.mixin(), method)) return false;
                }
            }
            return true;
        } catch (RuntimeException | LinkageError | org.spongepowered.asm.service.ServiceNotAvailableError unavailable) {
            return false;
        }
    }

    private static boolean independentMixin(Class<?> type, String mixin) {
        if (type == PlacedFeature.class)
            return mixin.equals("dev.worldgen.lithostitched.mixin.common.PlacedFeatureAccessor");
        String prefix = "com.moepus.byepregen.mixin.feature.placement.";
        if (type == PlacementModifier.class) return mixin.equals(prefix + "PlacementModifierMixin");
        if (type == InSquarePlacement.class) return mixin.equals(prefix + "InSquarePlacementMixin");
        if (type == HeightRangePlacement.class) return mixin.equals(prefix + "HeightRangePlacementMixin");
        if (type == HeightmapPlacement.class) return mixin.equals(prefix + "HeightmapPlacementMixin");
        if (type == RepeatingPlacement.class) return mixin.equals(prefix + "RepeatingPlacementMixin");
        if (type == PlacementFilter.class) return mixin.equals(prefix + "PlacementFilterMixin");
        if (type == CountPlacement.class || type == NoiseBasedCountPlacement.class || type == NoiseThresholdCountPlacement.class)
            return mixin.equals(prefix + "RepeatingPlacementPlanCompatibilityMixin");
        return false;
    }

    static boolean independentAddition(Class<?> type, String mixin, Method method) {
        if (!independentMixin(type, mixin) || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers())) return false;
        if (type == PlacedFeature.class) {
            var accessor = method.getDeclaredAnnotation(Accessor.class);
            return accessor != null && accessor.value().equals("feature") && method.getName().equals("setFeature")
                    && method.getReturnType() == void.class
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{net.minecraft.core.Holder.class});
        }
        // Audited ByePregen 1.1.2.4 methods only add an independent API; any overwrite or handler still falls back.
        if (method.getName().equals("byepregen$mayProduceMultipleOrigins"))
            return mixin.endsWith(".RepeatingPlacementPlanCompatibilityMixin")
                    && method.getReturnType() == boolean.class && method.getParameterCount() == 0;
        if (!method.getName().equals("byepregen$collectPositions") || method.getReturnType() != void.class)
            return false;
        var parameters = method.getParameterTypes();
        return parameters.length == 5
                && parameters[0].getName().equals("com.moepus.byepregen.worldgen.feature.FastPlacementContext")
                && parameters[1] == int.class && parameters[2] == int.class
                && parameters[3] == int.class && parameters[4] == int.class;
    }

    private static MethodHandle method(Class<?> type, Class<?> result, Class<?>... parameters) {
        var matches = new ArrayList<Method>();
        for (var method : type.getDeclaredMethods())
            if (method.getReturnType() == result && java.util.Arrays.equals(method.getParameterTypes(), parameters)) matches.add(method);
        if (matches.size() != 1) return null;
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflect(matches.get(0))
                    .asType(MethodType.methodType(result, prepend(PlacementModifier.class, parameters)));
        } catch (IllegalAccessException | RuntimeException inaccessible) { return null; }
    }

    private static Class<?>[] prepend(Class<?> first, Class<?>[] rest) {
        Class<?>[] result = new Class<?>[rest.length + 1];
        result[0] = first;
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private static MethodHandle field(Class<?> type, Class<?> fieldType) {
        Field found = null;
        for (var field : type.getDeclaredFields()) if (field.getType() == fieldType) {
            if (found != null) return null;
            found = field;
        }
        if (found == null) return null;
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflectGetter(found)
                    .asType(MethodType.methodType(Object.class, PlacementModifier.class));
        } catch (IllegalAccessException | RuntimeException inaccessible) { return null; }
    }

    private static int count(MethodHandle method, PlacementModifier modifier, RandomSource random, BlockPos pos) {
        try { return (int) method.invokeExact(modifier, random, pos); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("placement method failed", failure); }
    }

    private static boolean filter(MethodHandle method, PlacementModifier modifier, PlacementContext context,
                                  RandomSource random, BlockPos pos) {
        try { return (boolean) method.invokeExact(modifier, context, random, pos); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("placement method failed", failure); }
    }

    private static Object read(MethodHandle method, PlacementModifier modifier) {
        try { return (Object) method.invokeExact(modifier); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("placement field failed", failure); }
    }
}

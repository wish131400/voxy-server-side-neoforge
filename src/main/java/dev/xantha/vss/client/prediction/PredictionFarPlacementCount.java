package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.*;

/** Samples nominal template density without executing live-world placement wrappers. */
final class PredictionFarPlacementCount {
    private static final ClassValue<Optional<MethodHandle>> COUNTS = new ClassValue<>() {
        @Override protected Optional<MethodHandle> computeValue(Class<?> type) {
            if (type != CountPlacement.class && type != NoiseBasedCountPlacement.class
                    && type != NoiseThresholdCountPlacement.class) return Optional.empty();
            var matches = Arrays.stream(type.getDeclaredMethods()).filter(method ->
                    !Modifier.isStatic(method.getModifiers()) && method.getReturnType() == int.class
                    && Arrays.equals(method.getParameterTypes(), new Class<?>[]{RandomSource.class, BlockPos.class}))
                    .toList();
            if (matches.size() != 1) return Optional.empty();
            try {
                return Optional.of(MethodHandles.privateLookupIn(type, MethodHandles.lookup()).unreflect(matches.get(0))
                        .asType(MethodType.methodType(int.class, PlacementModifier.class, RandomSource.class, BlockPos.class)));
            } catch (IllegalAccessException unavailable) { return Optional.empty(); }
        }
    };
    private static final Optional<MethodHandle> RARITY = rarity();

    private static Optional<MethodHandle> rarity() {
        var fields = Arrays.stream(RarityFilter.class.getDeclaredFields()).filter(field ->
                !Modifier.isStatic(field.getModifiers()) && field.getType() == int.class).toList();
        if (fields.size() != 1) return Optional.empty();
        try {
            return Optional.of(MethodHandles.privateLookupIn(RarityFilter.class, MethodHandles.lookup())
                    .unreflectGetter(fields.get(0)).asType(MethodType.methodType(int.class, PlacementModifier.class)));
        } catch (IllegalAccessException unavailable) { return Optional.empty(); }
    }

    static int sample(PlacementModifier modifier, RandomSource random, BlockPos position) {
        try {
            if (modifier.getClass() == RarityFilter.class) {
                var chance = RARITY.orElseThrow(() -> new UnsupportedOperationException("Unknown rarity density"));
                return random.nextFloat() < 1.0F / (int) chance.invokeExact(modifier) ? 1 : 0;
            }
            var count = COUNTS.get(modifier.getClass()).orElseThrow(() ->
                    new UnsupportedOperationException("Unknown template density: " + modifier.getClass()));
            return Math.max(0, (int) count.invokeExact(modifier, random, position));
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Template density sampling failed", failure); }
    }

    private PredictionFarPlacementCount() { }
}

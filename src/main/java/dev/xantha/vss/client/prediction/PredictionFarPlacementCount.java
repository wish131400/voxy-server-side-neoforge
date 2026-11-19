package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.*;

/** Samples nominal template density without executing live-world placement wrappers. */
final class PredictionFarPlacementCount {
    /** The released Poisson overload accepts coordinates without a live world or chunk. */
    private static final ClassValue<Optional<MethodHandle>> POISSON = new ClassValue<>() {
        @Override protected Optional<MethodHandle> computeValue(Class<?> type) {
            boolean known=Arrays.stream(dev.xantha.vss.common.worldgen.FreeTerraForgedVariant.values())
                    .anyMatch(v->type.getName().equals(v.worldgenClass("feature.placement.poisson.FastPoissonModifier")));
            if(!known)return Optional.empty();
            var methods=Arrays.stream(type.getDeclaredMethods()).filter(m->{
                var p=m.getParameterTypes();
                return !Modifier.isStatic(m.getModifiers()) && m.getReturnType()==Stream.class && p.length==5
                        && p[0]==int.class && p[1]==long.class && p[2]==int.class && p[3]==int.class
                        && p[4].getSimpleName().equals("WorldLookup");
            }).toList();
            if(methods.size()!=1)return Optional.empty();
            try {
                return Optional.of(MethodHandles.privateLookupIn(type,MethodHandles.lookup()).unreflect(methods.get(0))
                        .asType(MethodType.methodType(Stream.class,PlacementModifier.class,int.class,long.class,int.class,int.class,Object.class)));
            } catch(IllegalAccessException failure){return Optional.empty();}
        }
    };

    static OptionalDouble distantDensity(PlacementModifier modifier,long seed,Runnable current) {
        var method=POISSON.get(modifier.getClass());
        if(method.isEmpty())return OptionalDouble.empty();
        long total=0;
        try {
            // Keep FTF's radius, jitter, scale and density noise. Only biome-edge fade is
            // represented by the actual anchor biome, rather than a server WorldLookup.
            for(int i=0;i<64;i++) {
                current.run();
                int x=(int)PredictionFarDecoration.mix(i*0x9e3779b97f4a7c15L)&16383;
                int z=(int)PredictionFarDecoration.mix(i*0xc2b2ae3d27d4eb4fL)&16383;
                try(var points=(Stream<?>)method.get().invokeExact(modifier,(int)seed+234523,seed,x,z,(Object)null)) {
                    total+=points.count();
                }
            }
            return OptionalDouble.of(total/64.0);
        } catch(RuntimeException | Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException("FTF distant density",failure);}
    }
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

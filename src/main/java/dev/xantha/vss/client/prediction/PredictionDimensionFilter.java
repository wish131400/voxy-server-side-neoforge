package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Optional FTF dimension predicates need an identity, not MinecraftServer/ServerLevel objects. */
public final class PredictionDimensionFilter {
    private PredictionDimensionFilter() { }
    private static final ClassValue<java.util.Optional<MethodHandle>> BLACKLIST = new ClassValue<>() {
        @Override protected java.util.Optional<MethodHandle> computeValue(Class<?> type) {
            if (!type.getName().equals("etcodehome.freeterraforged.world.worldgen.feature.placement.BlacklistDimensionFilter")
                    && !type.getName().equals("raccoonman.reterraforged.world.worldgen.feature.placement.BlacklistDimensionFilter"))
                return java.util.Optional.empty();
            try {
                var field = type.getDeclaredField("blacklist");
                field.setAccessible(true);
                return java.util.Optional.of(MethodHandles.lookup().unreflectGetter(field));
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return java.util.Optional.empty(); }
        }
    };

    /** Null keeps an unknown modifier on its original path. */
    public static Boolean allows(Object modifier, ResourceLocation dimension) {
        if (dimension == null) return null;
        var getter = BLACKLIST.get(modifier.getClass()).orElse(null);
        if (getter == null) return null;
        try {
            var keys = (List<?>) getter.invoke(modifier);
            for (var entry : keys) if (((ResourceKey<?>) entry).location().equals(dimension)) return false;
            return true;
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("FTF dimension blacklist query failed", failure); }
    }
}

package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

/** Optional Curios bridge that keeps far-player render capabilities in sync. */
public final class CuriosCompat {
    private static final String API_CLASS = "top.theillusivec4.curios.api.CuriosApi";
    private static final Method GET_INVENTORY = findGetInventory();
    private static boolean reflectionFailed;

    private CuriosCompat() {
    }

    public static CompoundTag capture(LivingEntity entity) {
        if (GET_INVENTORY == null || entity == null) {
            return null;
        }
        try {
            Object handler = resolve(GET_INVENTORY.invoke(null, entity));
            if (handler == null) {
                return null;
            }
            Object tag = findMethod(handler, "writeTag").invoke(handler);
            return tag instanceof CompoundTag compound ? compound.copy() : null;
        } catch (Throwable error) {
            reportFailure(error);
            return null;
        }
    }

    public static boolean apply(LivingEntity entity, CompoundTag tag) {
        if (GET_INVENTORY == null || entity == null || tag == null) {
            return false;
        }
        try {
            Object handler = resolve(GET_INVENTORY.invoke(null, entity));
            if (handler == null) {
                return false;
            }
            findMethod(handler, "readTag", Tag.class).invoke(handler, tag.copy());
            return true;
        } catch (Throwable error) {
            reportFailure(error);
            return false;
        }
    }

    private static Method findGetInventory() {
        try {
            return Class.forName(API_CLASS).getMethod("getCuriosInventory", LivingEntity.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object resolve(Object inventory) throws ReflectiveOperationException {
        if (inventory instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        if (inventory == null) {
            return null;
        }
        Object resolved = inventory.getClass().getMethod("resolve").invoke(inventory);
        return resolved instanceof Optional<?> optional ? optional.orElse(null) : null;
    }

    private static Method findMethod(Object target, String name, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            for (Class<?> type : target.getClass().getInterfaces()) {
                try {
                    return type.getMethod(name, parameterTypes);
                } catch (NoSuchMethodException ignoredInterface) {
                    // Try the next public interface.
                }
            }
            throw ignored;
        }
    }

    private static void reportFailure(Throwable error) {
        if (!reflectionFailed) {
            reflectionFailed = true;
            VSSLogger.warn("Curios compatibility bridge failed; far-player Curios rendering is disabled", error);
        }
    }
}

package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

/** Optional Curios bridge that keeps far-player render capabilities in sync. */
public final class CuriosCompat {
    private static final String API_CLASS = "top.theillusivec4.curios.api.CuriosApi";
    private static final String STACKS_HANDLER_CLASS = "top.theillusivec4.curios.common.inventory.CurioStacksHandler";
    private static final String SYNC_VERSION_KEY = "VssSyncVersion";
    private static final String SYNC_ENTRIES_KEY = "Entries";
    private static final String SYNC_IDENTIFIER_KEY = "Identifier";
    private static final String SYNC_DATA_KEY = "SyncTag";
    private static final int SYNC_VERSION = 1;
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
            return captureHandler(handler);
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
            return applyHandler(handler, tag);
        } catch (Throwable error) {
            reportFailure(error);
            return false;
        }
    }

    static CompoundTag captureHandler(Object handler) throws ReflectiveOperationException {
        try {
            return captureSyncData(handler);
        } catch (NoSuchMethodException ignored) {
            Object tag = findMethod(handler, "writeTag").invoke(handler);
            return tag instanceof CompoundTag compound ? compound.copy() : null;
        }
    }

    static boolean applyHandler(Object handler, CompoundTag tag) throws ReflectiveOperationException {
        if (!tag.contains(SYNC_VERSION_KEY, Tag.TAG_INT) || tag.getInt(SYNC_VERSION_KEY) != SYNC_VERSION) {
            findMethod(handler, "readTag", Tag.class).invoke(handler, tag.copy());
            return true;
        }

        Object currentValue = findMethod(handler, "getCurios").invoke(handler);
        if (!(currentValue instanceof Map<?, ?> currentHandlers)) {
            return false;
        }

        Map<String, Object> syncedHandlers = new LinkedHashMap<>();
        ListTag entries = tag.getList(SYNC_ENTRIES_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            String identifier = entry.getString(SYNC_IDENTIFIER_KEY);
            CompoundTag syncTag = entry.getCompound(SYNC_DATA_KEY);
            if (identifier.isEmpty()) {
                continue;
            }

            Object stacksHandler = currentHandlers.get(identifier);
            if (stacksHandler == null) {
                stacksHandler = createStacksHandler(handler, identifier);
            }
            findMethod(stacksHandler, "applySyncTag", CompoundTag.class).invoke(stacksHandler, syncTag.copy());
            syncedHandlers.put(identifier, stacksHandler);
        }
        findMethod(handler, "setCurios", Map.class).invoke(handler, syncedHandlers);
        return true;
    }

    private static CompoundTag captureSyncData(Object handler) throws ReflectiveOperationException {
        Object curiosValue = findMethod(handler, "getCurios").invoke(handler);
        if (!(curiosValue instanceof Map<?, ?> curios)) {
            throw new ReflectiveOperationException("Curios inventory did not expose a slot map");
        }

        ListTag entries = new ListTag();
        for (Map.Entry<?, ?> entry : curios.entrySet()) {
            if (!(entry.getKey() instanceof String identifier) || entry.getValue() == null) {
                continue;
            }
            Object syncValue = findMethod(entry.getValue(), "getSyncTag").invoke(entry.getValue());
            if (!(syncValue instanceof CompoundTag syncTag)) {
                continue;
            }
            CompoundTag encoded = new CompoundTag();
            encoded.putString(SYNC_IDENTIFIER_KEY, identifier);
            encoded.put(SYNC_DATA_KEY, syncTag.copy());
            entries.add(encoded);
        }

        CompoundTag result = new CompoundTag();
        result.putInt(SYNC_VERSION_KEY, SYNC_VERSION);
        result.put(SYNC_ENTRIES_KEY, entries);
        return result;
    }

    private static Object createStacksHandler(Object inventory, String identifier)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(STACKS_HANDLER_CLASS);
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && parameters[0].isInstance(inventory)
                    && parameters[1] == String.class) {
                return constructor.newInstance(inventory, identifier);
            }
        }
        throw new NoSuchMethodException("No compatible CurioStacksHandler constructor");
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

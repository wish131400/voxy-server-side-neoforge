package dev.xantha.vss.networking.server.compat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Uses the monitor owned by the installed planner, never an unrelated VSS lock. */
final class LostCityPlannerAccess {
    private LostCityPlannerAccess() { }

    @FunctionalInterface
    interface PlannerOperation<T> {
        T run() throws ReflectiveOperationException;
    }

    /**
     * Legacy Lost Cities synchronizes its planner entry points on the
     * BuildingInfo class. Keep preview-side cache and asset reads on that
     * monitor as well; the legacy TimedCache implementation is not thread
     * safe. Modern releases return a separate dimension lock and retain their
     * existing staged access pattern.
     */
    static <T> T withLegacyMonitor(Object plannerLock, PlannerOperation<T> operation)
            throws ReflectiveOperationException {
        if (!(plannerLock instanceof Class<?>)) return operation.run();
        synchronized (plannerLock) {
            return operation.run();
        }
    }

    static Object lock(ClassLoader loader, ResourceKey<Level> dimension) throws ReflectiveOperationException {
        return lock(Class.forName("mcjty.lostcities.worldgen.lost.BuildingInfo", false, loader), dimension);
    }

    static Object lock(Class<?> building, ResourceKey<Level> dimension) throws ReflectiveOperationException {
        try {
            Method method = building.getDeclaredMethod("getDimensionLock", ResourceKey.class);
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType().isPrimitive())
                throw new NoSuchMethodException("Unsupported Lost Cities dimension lock");
            method.setAccessible(true);
            Object result = method.invoke(null, dimension);
            if (result == null) throw new IllegalStateException("Lost Cities returned a null planner lock");
            return result;
        } catch (NoSuchMethodException missing) {
            for (Method method : building.getDeclaredMethods()) {
                if (method.getName().equals("getBuildingInfo") && method.getParameterCount() == 2
                        && method.getReturnType() == building
                        && Modifier.isStatic(method.getModifiers()) && Modifier.isSynchronized(method.getModifiers()))
                    return building;
            }
            throw new NoSuchMethodException("Lost Cities has neither a dimension lock nor a synchronized planner entry");
        }
    }
}

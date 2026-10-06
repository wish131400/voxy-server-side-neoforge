package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class VoxyLocalIndexProbeTest {
    @Test void emptyRegionMissesDoNotHideLaterPublicationOrSwappedStorage() throws Throwable {
        var index = newIndex();
        var lookup = MethodHandles.privateLookupIn(index.getClass(), MethodHandles.lookup());
        var confirmed = lookup.findVirtual(index.getClass(), "hasConfirmed", MethodType.methodType(boolean.class, int.class, int.class)).bindTo(index);
        var stored = lookup.findVirtual(index.getClass(), "hasStored", MethodType.methodType(boolean.class, int.class, int.class)).bindTo(index);
        var mark = lookup.findVirtual(index.getClass(), "markConfirmed", MethodType.methodType(void.class, int.class, int.class)).bindTo(index);
        var swap = lookup.findVirtual(index.getClass(), "swapStored", MethodType.methodType(void.class, ConcurrentHashMap.class)).bindTo(index);
        assertFalse((boolean) confirmed.invokeExact(-65, 97));
        assertFalse((boolean) stored.invokeExact(-65, 97));
        mark.invokeExact(-65, 97);
        assertTrue((boolean) confirmed.invokeExact(-65, 97));
        assertFalse((boolean) confirmed.invokeExact(-64, 97));
        var shadow = new ConcurrentHashMap<Long, long[]>();
        int slot = (-65 & 31) | ((97 & 31) << 5);
        var bitmap = new long[16];
        bitmap[slot >>> 6] |= 1L << (slot & 63);
        shadow.put(((long) -3 << 32) ^ 3L, bitmap);
        swap.invoke(shadow);
        assertTrue((boolean) stored.invokeExact(-65, 97));
        swap.invoke(new ConcurrentHashMap<Long, long[]>());
        assertFalse((boolean) stored.invokeExact(-65, 97));
        assertTrue((boolean) confirmed.invokeExact(-65, 97));
    }

    @Test void reportsProductionEmptyIndexProbeAllocations() throws Throwable {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        var index = newIndex();
        var lookup = MethodHandles.privateLookupIn(index.getClass(), MethodHandles.lookup());
        MethodHandle confirmed = lookup.findVirtual(index.getClass(), "hasConfirmed", MethodType.methodType(boolean.class, int.class, int.class)).bindTo(index);
        MethodHandle stored = lookup.findVirtual(index.getClass(), "hasStored", MethodType.methodType(boolean.class, int.class, int.class)).bindTo(index);
        boolean found = false;
        for (int i = 0; i < 30_000; i++) {
            found |= (boolean) confirmed.invokeExact(i + 1000, -i - 1000);
            found |= (boolean) stored.invokeExact(i + 1000, -i - 1000);
        }
        long thread = Thread.currentThread().getId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 200_000; i++) {
            found |= (boolean) confirmed.invokeExact(i + 1000, -i - 1000);
            found |= (boolean) stored.invokeExact(i + 1000, -i - 1000);
        }
        long bytes = allocation.getThreadAllocatedBytes(thread) - before;
        System.out.println("INDEX_ALLOCATION probes=200000 localEmptyPair=" + bytes);
        assertFalse(found);
    }

    private static Object newIndex() throws Exception {
        Class<?> type = Class.forName("dev.xantha.vss.compat.VoxyCompat$LocalSectionIndex");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}

package dev.xantha.vss.client.prediction;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.ShortByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.*;

/** Windows 10 CPU-set API. CPU-set IDs include processor groups and are not 64-bit affinity masks. */
final class PredictionWindowsCpuSets implements PredictionCoreAffinity.Access {
    interface Kernel extends StdCallLibrary {
        Pointer GetCurrentProcess();
        Pointer GetCurrentThread();
        boolean GetSystemCpuSetInformation(Pointer information, int size, IntByReference required, Pointer process, int flags);
        boolean GetProcessDefaultCpuSets(Pointer process, Pointer ids, int count, IntByReference required);
        boolean GetThreadSelectedCpuSets(Pointer thread, Pointer ids, int count, IntByReference required);
        boolean SetThreadSelectedCpuSets(Pointer thread, Pointer ids, int count);
        boolean GetProcessGroupAffinity(Pointer process, ShortByReference count, Pointer groups);
        boolean GetProcessAffinityMask(Pointer process, Pointer mask, Pointer systemMask);
    }
    private final Kernel kernel = Native.load("kernel32", Kernel.class);
    private final List<PredictionCoreAffinity.Cpu> cpus;

    PredictionWindowsCpuSets() {
        Pointer process = kernel.GetCurrentProcess();
        var required = new IntByReference();
        kernel.GetSystemCpuSetInformation(null, 0, required, process, 0);
        int size = required.getValue();
        if (size <= 0 || size > 16 * 1024 * 1024) throw new IllegalStateException("CPU topology unavailable");
        try (var data = new Memory(size)) {
            check(kernel.GetSystemCpuSetInformation(data, size, required, process, 0), "CPU topology");
            var processSets = readSets(false);
            var allowed = new HashSet<Integer>(); for (int id : processSets) allowed.add(id);
            int singleGroup = -1; long processMask = -1L;
            // In a single processor group, also respect an explicit process affinity mask.
            // Across groups Windows resolves any hard masks before the CPU-set restriction.
            var count = new ShortByReference((short) 64);
            try (var groups = new Memory(128); var mask = new Memory(Native.POINTER_SIZE);
                 var systemMask = new Memory(Native.POINTER_SIZE)) {
                if (kernel.GetProcessGroupAffinity(process, count, groups) && count.getValue() == 1
                        && kernel.GetProcessAffinityMask(process, mask, systemMask)) {
                    singleGroup = Short.toUnsignedInt(groups.getShort(0));
                    processMask = Native.POINTER_SIZE == 8 ? mask.getLong(0) : Integer.toUnsignedLong(mask.getInt(0));
                }
            }
            var result = new ArrayList<PredictionCoreAffinity.Cpu>();
            for (int offset = 0; offset < required.getValue();) {
                if (offset > size - 8) throw new IllegalStateException("Truncated CPU topology");
                int entrySize = data.getInt(offset), type = data.getInt(offset + 4);
                if (entrySize < 8 || entrySize > size - offset) throw new IllegalStateException("Invalid CPU topology entry");
                if (type == 0 && entrySize >= 24) {
                    int id = data.getInt(offset + 8), group = Short.toUnsignedInt(data.getShort(offset + 12));
                    int logical = Byte.toUnsignedInt(data.getByte(offset + 14));
                    int core = Byte.toUnsignedInt(data.getByte(offset + 15));
                    int efficiency = Byte.toUnsignedInt(data.getByte(offset + 18));
                    int flags = Byte.toUnsignedInt(data.getByte(offset + 19));
                    boolean allocatedElsewhere = (flags & 2) != 0 && (flags & 4) == 0;
                    if (!allocatedElsewhere && (allowed.isEmpty() || allowed.contains(id))
                            && (singleGroup < 0 || group == singleGroup && logical < 64 && (processMask & (1L << logical)) != 0))
                        result.add(new PredictionCoreAffinity.Cpu(id, group, logical, core, efficiency));
                }
                offset += entrySize;
            }
            if (result.isEmpty()) throw new IllegalStateException("No available CPU sets");
            cpus = List.copyOf(result);
        }
    }

    @Override public List<PredictionCoreAffinity.Cpu> cpus() { return cpus; }
    @Override public int[] selected() { return readSets(true); }
    private int[] readSets(boolean thread) {
        var required = new IntByReference();
        boolean read = thread ? kernel.GetThreadSelectedCpuSets(kernel.GetCurrentThread(), null, 0, required)
                : kernel.GetProcessDefaultCpuSets(kernel.GetCurrentProcess(), null, 0, required);
        if (required.getValue() == 0) { check(read, "Read default CPU sets"); return new int[0]; }
        int count = required.getValue();
        if (count < 0 || count > 65536) throw new IllegalStateException("Invalid CPU set count");
        try (var data = new Memory(count * 4L)) {
            check(thread ? kernel.GetThreadSelectedCpuSets(kernel.GetCurrentThread(), data, count, required)
                    : kernel.GetProcessDefaultCpuSets(kernel.GetCurrentProcess(), data, count, required), "Read CPU sets");
            return data.getIntArray(0, required.getValue());
        }
    }
    @Override public void select(int[] ids) {
        if (ids.length == 0) { check(kernel.SetThreadSelectedCpuSets(kernel.GetCurrentThread(), null, 0), "Restore CPU sets"); return; }
        try (var data = new Memory(ids.length * 4L)) {
            data.write(0, ids, 0, ids.length);
            check(kernel.SetThreadSelectedCpuSets(kernel.GetCurrentThread(), data, ids.length), "Set worker CPU sets");
        }
    }
    private static void check(boolean success, String operation) {
        if (!success) throw new IllegalStateException(operation + ": Windows error " + Native.getLastError());
    }
}

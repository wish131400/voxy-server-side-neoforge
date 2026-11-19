package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies CPU sets only on VSS-owned workers. Game and shared executors are never rebound. */
public final class PredictionCoreAffinity {
    public enum Mode {
        SYSTEM, RESERVE, PERFORMANCE, EFFICIENCY;
        public String configName() { return name().toLowerCase(Locale.ROOT); }
        public static Mode fromName(String name) {
            return valueOf(VSSClientConfig.normalizeCoreMode(name).toUpperCase(Locale.ROOT));
        }
    }

    record Cpu(int id, int group, int logical, int core, int efficiency) {
        long physical() { return ((long) group << 32) | Integer.toUnsignedLong(core); }
    }
    record Selection(List<Cpu> cpus, boolean available, int reserved) {
        int[] ids() { return cpus.stream().mapToInt(Cpu::id).toArray(); }
    }
    interface Access {
        List<Cpu> cpus();
        int[] selected();
        void select(int[] ids);
    }
    private record Policy(Mode mode, int reserve, Selection selection) { }
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile Policy cached;

    private PredictionCoreAffinity() { }

    private static final class Platform {
        static final Access ACCESS = detect();
        private static Access detect() {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return null;
            try { return new PredictionWindowsCpuSets(); }
            catch (RuntimeException | LinkageError unavailable) {
                VSSLogger.warn("VSS CPU core selection unavailable; keeping system scheduling: " + unavailable);
                return null;
            }
        }
    }

    static Selection select(List<Cpu> cpus, Mode mode, int reserve) {
        if (cpus.isEmpty() || mode == Mode.SYSTEM) return new Selection(cpus, false, 0);
        int highest = cpus.stream().mapToInt(Cpu::efficiency).max().orElse(0);
        boolean hybrid = cpus.stream().anyMatch(cpu -> cpu.efficiency() != highest);
        if (mode == Mode.EFFICIENCY)
            return hybrid ? new Selection(cpus.stream().filter(cpu -> cpu.efficiency() < highest).toList(), true, 0)
                    : new Selection(cpus, false, 0);
        var fastCores = cpus.stream().filter(cpu -> cpu.efficiency() == highest)
                .map(Cpu::physical).distinct().sorted().toList();
        // Never reserve the entire target class. A requested reserve is a count
        // of physical cores: both SMT siblings must be excluded together.
        int maximum = fastCores.size() - (mode == Mode.PERFORMANCE || !hybrid ? 1 : 0);
        int reserved = Math.max(0, Math.min(maximum, reserve));
        var excluded = new HashSet<>(fastCores.subList(0, reserved));
        return new Selection(cpus.stream().filter(cpu -> !excluded.contains(cpu.physical())
                && (mode != Mode.PERFORMANCE || cpu.efficiency() == highest)).toList(), true, reserved);
    }

    private static Policy policy() {
        var config = VSSClientConfig.CONFIG;
        Mode mode = Mode.fromName(config.predictionCoreMode);
        int reserve = Math.max(0, config.predictionReservedCores);
        Policy current = cached;
        if (current != null && current.mode() == mode && current.reserve() == reserve) return current;
        Access access = Platform.ACCESS;
        var cpus = access == null ? List.<Cpu>of() : access.cpus();
        current = new Policy(mode, reserve, select(cpus, mode, reserve));
        cached = current;
        return current;
    }

    public static int capWorkers(int workers) {
        Selection selection = policy().selection();
        return Math.max(1, selection.available() ? Math.min(workers, selection.cpus().size()) : workers);
    }

    /** Shared status for settings and internal performance observations. */
    public static Map<String, Object> diagnostics() {
        Policy policy = policy();
        Access access = Platform.ACCESS;
        var cpus = access == null ? List.<Cpu>of() : access.cpus();
        int highest = cpus.stream().mapToInt(Cpu::efficiency).max().orElse(0);
        long fast = cpus.stream().filter(c -> c.efficiency() == highest).map(Cpu::physical).distinct().count();
        long efficient = cpus.stream().filter(c -> c.efficiency() < highest).map(Cpu::physical).distinct().count();
        return Map.of("mode", policy.mode().configName(), "supported", access != null,
                "hybrid", efficient > 0, "performanceCores", fast, "efficiencyCores", efficient,
                "selectedLogicalProcessors", policy.selection().available() ? policy.selection().cpus().size() : cpus.size(),
                "reservedPhysicalCores", policy.selection().reserved(),
                "appliedSelection", policy.selection().available(), "applyFailed", WARNED.get());
    }

    public static Thread newWorker(Runnable task, String name) { return new Worker(task, name); }

    /** Recheck saved options between tasks, including workers already alive when settings change. */
    static void applyCurrentWorker() {
        if (Thread.currentThread() instanceof Worker worker) worker.refresh();
    }

    private static final class Worker extends Thread {
        private Policy applied;
        private int[] original;
        private boolean changed;
        Worker(Runnable task, String name) { super(task, name); }
        private void refresh() {
            Policy next = policy();
            if (next == applied) return;
            Access access = Platform.ACCESS;
            if (access == null) { applied = next; return; }
            try {
                if (next.selection().available()) {
                    if (original == null) original = access.selected();
                    int[] desired = next.selection().ids();
                    // Respect a launcher or another tool's pre-existing thread CPU set.
                    if (original.length > 0) desired = Arrays.stream(desired)
                            .filter(id -> Arrays.stream(original).anyMatch(previous -> previous == id)).toArray();
                    if (desired.length == 0) throw new IllegalStateException("No selected cores permitted for this worker");
                    access.select(desired);
                    changed = true;
                } else if (changed) {
                    access.select(original);
                    changed = false;
                }
                applied = next;
            } catch (RuntimeException | LinkageError failure) {
                if (WARNED.compareAndSet(false, true)) VSSLogger.warn("VSS worker CPU selection failed: " + failure);
                // Retry restoration/selection at the next task; do not claim a failed apply succeeded.
            }
        }
        @Override public void run() {
            try { super.run(); }
            finally {
                if (changed) try { Platform.ACCESS.select(original); }
                catch (RuntimeException | LinkageError failure) {
                    if (WARNED.compareAndSet(false, true)) VSSLogger.warn("VSS worker CPU set restoration failed: " + failure);
                }
            }
        }
    }
}

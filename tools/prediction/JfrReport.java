import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Streams a recording without expanding class metadata into a multi-gigabyte JSON file. */
class JfrReport {
    static final Map<String, Long> threads = new HashMap<>(), render = new HashMap<>(), vss = new HashMap<>(),
            allocationSites = new HashMap<>(), renderAllocations = new HashMap<>(), allocationThreads = new HashMap<>(),
            parks = new HashMap<>(), locks = new HashMap<>();
    static final List<Double> pauses = new ArrayList<>();
    static long allocated, samples, renderSamples;
    static double cpu, machine;
    static int cpuSamples;
    static final Set<Long> allocationSeen = new HashSet<>();
    static final Map<Long, long[]> allocationCounters = new HashMap<>();
    static final Map<Long, String> threadNames = new HashMap<>();
    static String method(RecordedFrame f) { return f.getMethod().getType().getName() + "." + f.getMethod().getName(); }
    static void add(Map<String, Long> map, String key, long n) { map.merge(key, n, Long::sum); }
    static String thread(RecordedEvent e, String field) {
        RecordedThread t = e.getThread(field); return t == null ? "none" : t.getJavaName();
    }
    public static void main(String[] args) throws Exception {
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent e = recording.readEvent();
                String name = e.getEventType().getName();
                var stack = e.getStackTrace();
                var frames = stack == null ? List.<RecordedFrame>of() : stack.getFrames();
                if (name.equals("jdk.ExecutionSample") || name.equals("jdk.NativeMethodSample")) {
                    String t = thread(e, "sampledThread"); samples++; add(threads, t, 1);
                    boolean main = t.equals("Render thread"); if (main) renderSamples++;
                    var seen = new HashSet<String>();
                    for (RecordedFrame f : frames) {
                        String m = method(f); if (!seen.add(m)) continue;
                        if (main) add(render, m, 1);
                        if (m.startsWith("dev.xantha.vss.")) add(vss, t + " | " + m, 1);
                    }
                } else if (name.equals("jdk.ObjectAllocationSample")) {
                    // A thread's first weighted sample can include allocation
                    // from before this recording started. Do not attribute it to this window.
                    if (!allocationSeen.add(e.getThread("eventThread").getJavaThreadId())) { } else continue;
                    long weight = e.getLong("weight"); allocated += weight;
                    String t = thread(e, "eventThread"); add(allocationThreads, t, weight);
                    String site = frames.isEmpty() ? "unknown" : method(frames.getFirst());
                    add(allocationSites, e.getClass("objectClass").getName() + " | " + site, weight);
                    if (t.equals("Render thread")) {
                        for (RecordedFrame f : frames) if (method(f).startsWith("dev.xantha.vss.")) {
                            add(renderAllocations, method(f), weight); break;
                        }
                    }
                } else if (name.equals("jdk.ThreadAllocationStatistics")) {
                    long id = e.getThread("thread").getJavaThreadId(), value = e.getLong("allocated");
                    threadNames.put(id, thread(e, "thread"));
                    long[] counters = allocationCounters.computeIfAbsent(id, ignored -> new long[]{value, value}); counters[1] = value;
                } else if (name.equals("jdk.GCPhasePause")) pauses.add(e.getDuration().toNanos() / 1e6);
                else if (name.equals("jdk.CPULoad")) { cpu += e.getFloat("jvmUser") + e.getFloat("jvmSystem"); machine += e.getFloat("machineTotal"); cpuSamples++; }
                else if ((name.equals("jdk.ThreadPark") || name.equals("jdk.JavaMonitorEnter")) && thread(e, "eventThread").equals("Render thread")) {
                    String site = frames.isEmpty() ? "unknown" : String.join(" <- ", frames.stream().limit(5).map(JfrReport::method).toList());
                    add(name.equals("jdk.ThreadPark") ? parks : locks, site, e.getDuration().toNanos());
                }
            }
        }
        System.out.println("samples=" + samples + ", renderSamples=" + renderSamples + ", sampledAllocationMiB=" + allocated / 1048576D);
        Collections.sort(pauses);
        System.out.println("GC pauses=" + pauses.size() + ", totalMs=" + pauses.stream().mapToDouble(x -> x).sum()
                + ", maxMs=" + (pauses.isEmpty() ? 0 : pauses.getLast()) + ", over16ms=" + pauses.stream().filter(x -> x > 16.67).count() + ", allMs=" + pauses);
        System.out.println("averageProcessCpu=" + cpu / cpuSamples + ", averageMachineCpu=" + machine / cpuSamples);
        print("SAMPLE_THREADS", threads, 20, 1); print("RENDER_INCLUSIVE", render, 35, 1);
        print("VSS_INCLUSIVE", vss, 40, 1); print("ALLOCATION_THREADS_MIB", allocationThreads, 15, 1048576D);
        print("ALLOCATION_SITES_MIB", allocationSites, 25, 1048576D); print("RENDER_VSS_ALLOCATION_MIB", renderAllocations, 15, 1048576D);
        print("RENDER_PARK_MS", parks, 8, 1e6); print("RENDER_LOCK_MS", locks, 8, 1e6);
        var deltas = new HashMap<String, Long>();
        allocationCounters.forEach((id, counts) -> add(deltas, threadNames.get(id), counts[1] - counts[0]));
        print("ALLOCATION_COUNTER_DELTAS_MIB", deltas, 15, 1048576D);
    }
    static void print(String label, Map<String, Long> map, int limit, double divisor) {
        System.out.println("\n" + label);
        map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit)
                .forEach(e -> System.out.printf(Locale.ROOT, "%.3f %s%n", e.getValue() / divisor, e.getKey()));
    }
}

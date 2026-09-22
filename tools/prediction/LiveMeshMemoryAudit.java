import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.BufferPoolMXBean;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Bounded read-only snapshots; never decode meshes, request GC, or change configuration. */
public class LiveMeshMemoryAudit {
    static Object field(Object owner, String name) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        Field f = type.getDeclaredField(name); f.setAccessible(true);
        return f.get(owner instanceof Class<?> ? null : owner);
    }
    static Object call(Object owner, String name) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name); m.setAccessible(true);
        return m.invoke(owner);
    }
    static long number(Object owner, String name) throws Exception {
        return ((Number)call(owner, name)).longValue();
    }
    static String snapshot(Instrumentation inst) throws Exception {
        Map<String, Class<?>> classes = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) classes.put(c.getName(), c);
        Class<?> state = classes.get("dev.xantha.vss.client.prediction.ClientPredictionState");
        if (state == null) return "predictionStateLoaded=false\n";
        Set<Object> payloads = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        long tiles=0, rawTiles=0, zstdTiles=0, fallbackTiles=0;
        long original=0, compressedOriginal=0, compressedBytes=0, rawBytes=0;
        long tileEstimate=0, actualArrayObjects=0, blobObjects=0;
        for (Object manager : ((Map<?,?>)field(state, "MANAGERS")).values()) {
            List<?> ready;
            synchronized(manager) { ready = List.copyOf(((Map<?,?>)field(manager, "ready")).values()); }
            for (Object tile : ready) {
                Object mesh = call(tile, "mesh");
                Object payload = field(mesh, "gpuPayload");
                if (payload == null || !payloads.add(payload)) continue;
                tiles++;
                tileEstimate += number(tile, "retainedHeapBytes");
                original += number(payload, "quadBytes");
                Object blob = field(payload, "compressed");
                int[] raw = (int[])field(payload, "quads");
                if (raw != null && arrays.add(raw)) {
                    rawBytes += raw.length * 4L;
                    actualArrayObjects += inst.getObjectSize(raw);
                }
                if (blob == null) { rawTiles++; continue; }
                if ((boolean)call(blob, "zstd")) zstdTiles++; else fallbackTiles++;
                byte[] encoded = (byte[])call(blob, "bytes");
                compressedOriginal += number(blob, "rawBytes");
                compressedBytes += encoded.length;
                actualArrayObjects += inst.getObjectSize(encoded);
                blobObjects += inst.getObjectSize(blob);
            }
        }
        long staged=0, extraStaged=0, stageArrayObjects=0, pending=0, stageCount=0, jobs=0;
        Class<?> restore = classes.get("dev.xantha.vss.client.prediction.PredictionMeshRestore");
        if (restore != null) synchronized(restore) {
            pending = ((Number)field(restore, "pendingBytes")).longValue();
            jobs = ((Set<?>)field(restore, "PENDING")).size();
            for (Object o : ((Map<?,?>)field(restore, "READY")).values()) {
                int[] words = (int[])o; stageCount++; staged += words.length * 4L;
                if (arrays.add(words)) { extraStaged += words.length * 4L; stageArrayObjects += inst.getObjectSize(words); }
            }
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        StringBuilder out = new StringBuilder("time=" + java.time.Instant.now() + "\n");
        out.append("tiles=").append(tiles).append(" rawTiles=").append(rawTiles)
           .append(" zstdTiles=").append(zstdTiles).append(" fallbackTiles=").append(fallbackTiles).append('\n');
        out.append("originalWordBytes=").append(original).append(" compressedOriginalBytes=").append(compressedOriginal)
           .append(" compressedBlobBytes=").append(compressedBytes).append(" remainingRawBytes=").append(rawBytes).append('\n');
        out.append("storedWordBytes=").append(rawBytes+compressedBytes).append(" savedWordBytes=").append(original-rawBytes-compressedBytes)
           .append(" stagedBytes=").append(staged).append(" extraStagedBytes=").append(extraStaged)
           .append(" conservativeSavedAfterAllStaging=").append(original-rawBytes-compressedBytes-extraStaged).append('\n');
        out.append("actualArrayObjectBytes=").append(actualArrayObjects).append(" blobObjectBytes=").append(blobObjects)
           .append(" extraStageArrayObjectBytes=").append(stageArrayObjects).append(" stagedArrays=").append(stageCount)
           .append(" pendingRawReservationBytes=").append(pending).append(" restoreJobs=").append(jobs).append('\n');
        out.append("tileRetainedEstimate=").append(tileEstimate)
           .append(" equivalentUncompressedTileEstimate=").append(tileEstimate+original-rawBytes-compressedBytes).append('\n');
        out.append("heapUsed=").append(heap.getUsed()).append(" heapCommitted=").append(heap.getCommitted())
           .append(" heapMax=").append(heap.getMax()).append('\n');
        for (var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            out.append("bufferPool=").append(pool.getName()).append(" used=").append(pool.getMemoryUsed()).append('\n');
        return out.toString();
    }
    public static void agentmain(String output, Instrumentation inst) {
        Thread worker = new Thread(() -> {
            try {
                for (int i=0; i<3; i++) {
                    Files.writeString(Path.of(output + "-" + i + ".txt"), snapshot(inst));
                    if (i<2) Thread.sleep(10000);
                }
            } catch(Throwable e) {
                try { Files.writeString(Path.of(output + "-error.txt"), e.toString()); } catch(Exception ignored) {}
            }
        }, "vss-readonly-mesh-memory-audit");
        worker.setDaemon(true); worker.start();
    }
}

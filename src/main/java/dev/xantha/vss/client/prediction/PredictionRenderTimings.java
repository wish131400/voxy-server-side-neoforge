package dev.xantha.vss.client.prediction;

import java.util.Locale;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Optional sampled diagnostics. Timestamp pairs coexist with a shader pack's elapsed queries. */
final class PredictionRenderTimings {
    enum Stage { PREPARE, COVERAGE, SEAMS, UPLOAD, OPAQUE, WATER, DEPTH_COPY, STATE }
    private static final Stage[] STAGES = Stage.values();
    private static final long[] cpu = new long[STAGES.length], cpuCount = new long[STAGES.length];
    private static final long[] gpu = new long[STAGES.length], gpuCount = new long[STAGES.length];
    private static final Slot[] slots = new Slot[16];
    private static boolean sampling;
    private static volatile boolean reset;
    private static long dropped;
    private static final class Slot {
        final int start = GL15.glGenQueries(), end = GL15.glGenQueries();
        Stage stage;
        boolean pending;
    }

    static void frame(long frame) {
        sampling = (Boolean.getBoolean("vss.renderTimings")
                || dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) && frame % 30 == 0;
        // Called on the render thread. No query allocation when diagnostics are off.
        for (int i = 0; i < slots.length; i++) {
            Slot slot = slots[i];
            if (slot == null) continue;
            if (reset) {
                GL15.glDeleteQueries(slot.start); GL15.glDeleteQueries(slot.end); slots[i] = null;
            } else if (slot.pending && GL15.glGetQueryObjecti(slot.end, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                int stage = slot.stage.ordinal();
                gpu[stage] += Math.max(0, GL33.glGetQueryObjectui64(slot.end, GL15.GL_QUERY_RESULT)
                        - GL33.glGetQueryObjectui64(slot.start, GL15.GL_QUERY_RESULT));
                gpuCount[stage]++; slot.pending = false; slot.stage = null;
            }
        }
        if (reset) {
            java.util.Arrays.fill(cpu, 0); java.util.Arrays.fill(cpuCount, 0);
            java.util.Arrays.fill(gpu, 0); java.util.Arrays.fill(gpuCount, 0);
            dropped = 0; reset = false;
        }
    }

    static void reset() { reset = true; }
    static long start() { return sampling ? System.nanoTime() : 0; }
    static void end(Stage stage, long start) {
        if (start == 0) return;
        cpu[stage.ordinal()] += System.nanoTime() - start; cpuCount[stage.ordinal()]++;
    }
    static int gpuStart(Stage stage) {
        if (!sampling || !(GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_timer_query)) return -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) slots[i] = new Slot();
            Slot slot = slots[i];
            if (slot.pending || slot.stage != null) continue;
            slot.stage = stage;
            GL33.glQueryCounter(slot.start, GL33.GL_TIMESTAMP);
            return i;
        }
        dropped++; return -1;
    }
    static void gpuEnd(int index) {
        if (index < 0) return;
        Slot slot = slots[index];
        GL33.glQueryCounter(slot.end, GL33.GL_TIMESTAMP); slot.pending = true;
    }
    static String diagnostics() {
        StringBuilder result = new StringBuilder("sampleEvery=30");
        for (Stage stage : STAGES) {
            int i = stage.ordinal();
            if (cpuCount[i] != 0) result.append(',').append(stage).append("CpuMs=")
                    .append(String.format(Locale.ROOT, "%.3f", cpu[i] / (cpuCount[i] * 1e6)));
            if (gpuCount[i] != 0) result.append(',').append(stage).append("GpuMs=")
                    .append(String.format(Locale.ROOT, "%.3f", gpu[i] / (gpuCount[i] * 1e6)))
                    .append("(n=").append(gpuCount[i]).append(')');
        }
        return result.append(",dropped=").append(dropped).toString();
    }
    private PredictionRenderTimings() { }
}

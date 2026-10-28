package dev.xantha.vss.client.prediction;
import java.util.*;
/** CPU timings for VSS-owned work. Voxy GPU cost requires independent instrumentation. */
final class PredictionRenderTimings {
    enum Stage { CLIENT_SCHEDULE, CPU_CACHE }
    private static final long[] nanos = new long[Stage.values().length], calls = new long[nanos.length];
    private static final long[] frameNanos = new long[nanos.length], frames = new long[nanos.length], current = new long[nanos.length];
    private static volatile boolean sampling;
    private PredictionRenderTimings() { }
    static synchronized void frame(long frame) {
        for (int i=0;i<nanos.length;i++) if(current[i]>0) { frameNanos[i]+=current[i]; frames[i]++; current[i]=0; }
        sampling = PredictionPerformanceMonitor.active();
    }
    static synchronized void reset() {
        for (long[] a : new long[][]{nanos,calls,frameNanos,frames,current}) Arrays.fill(a,0);
    }
    static long start() { return sampling ? System.nanoTime() : 0; }
    static synchronized void end(Stage stage,long start) {
        if(start==0) return;
        int i=stage.ordinal(); long elapsed=Math.max(0,System.nanoTime()-start);
        nanos[i]+=elapsed; calls[i]++; current[i]+=elapsed;
    }
    static synchronized Map<String,Object> snapshot() {
        var result = new LinkedHashMap<String,Object>();
        for (var stage:Stage.values()) { int i=stage.ordinal();
            result.put(stage.name(), Map.of("cpuNanos",nanos[i],"cpuSamples",calls[i],
                    "cpuFrameNanos",frameNanos[i],"cpuFrames",frames[i])); }
        return result;
    }
}

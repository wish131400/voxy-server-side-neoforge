package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure analysis of detached scalar counters. Wall-time stages can overlap; never sum them as CPU utilization. */
final class PredictionPerformanceAnalysis {
    private PredictionPerformanceAnalysis() { }

    static double number(Map<String, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    static double delta(Map<String, ?> current, Map<String, ?> previous, String key) {
        if (!current.containsKey(key) || !previous.containsKey(key)) return 0;
        return Math.max(0, number(current, key) - number(previous, key));
    }

    static Map<String, Object> rates(Map<String, ?> current, Map<String, ?> previous, double seconds) {
        if (seconds <= 0 || previous.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("publishedTiles", "built", "failed", "diskHits", "diskMisses", "meshRestores"))
            result.put(key + "PerSecond", delta(current, previous, key) / seconds);
        result.put("workerCpuCores", (delta(current, previous, "visibleCpuNanos")
                + delta(current, previous, "backgroundCpuNanos")) / (seconds * 1e9));
        for (String key : List.of("sample", "surface", "mesh", "pack", "disk", "native", "color", "exterior", "queueWait"))
            result.put(key + "WallMsPerSecond", delta(current, previous, key + "Nanos") / (seconds * 1e6));
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> stages(Map<String, ?> current, Map<String, ?> previous) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : current.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> now) || !(previous.get(entry.getKey()) instanceof Map<?, ?> before)) continue;
            var a = (Map<String, ?>) now; var b = (Map<String, ?>) before;
            double cpuSamples = delta(a, b, "cpuSamples"), gpuSamples = delta(a, b, "gpuSamples");
            var stage = new LinkedHashMap<String, Object>();
            stage.put("cpuSamples", cpuSamples); stage.put("gpuSamples", gpuSamples);
            if (cpuSamples > 0) {
                double ms=delta(a,b,"cpuNanos")/1e6;stage.put("cpuMs",ms/cpuSamples);stage.put("cpuTotalMs",ms);
            }
            if (gpuSamples > 0) {
                double ms=delta(a,b,"gpuNanos")/1e6;stage.put("gpuMs",ms/gpuSamples);stage.put("gpuTotalMs",ms);
            }
            for(String kind:List.of("cpu","gpu")) {
                double frames=delta(a,b,kind+"Frames");stage.put(kind+"Frames",frames);
                if(frames>0) stage.put(kind+"FrameMs",delta(a,b,kind+"FrameNanos")/(frames*1e6));
            }
            stage.put("gpuAttempted",delta(a,b,"gpuAttempted"));stage.put("gpuDropped",delta(a,b,"gpuDropped"));
            if(a.containsKey("lastGpuFrame"))stage.put("lastGpuFrame",a.get("lastGpuFrame"));
            if("INDIRECT_SUBMIT".equals(entry.getKey())) stage.put("scope","draw-groups-between-selection-and-bucket-boundaries");
            result.put(entry.getKey(), Map.copyOf(stage));
        }
        return Map.copyOf(result);
    }

    /** Evidence hints, not a claim that CPU/GPU attribution proves causality. */
    @SuppressWarnings("unchecked")
    static List<String> findings(Map<String, ?> sample) {
        var result = new ArrayList<String>();
        Map<?, ?> renderer = sample.get("renderer") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> renderState = renderer.get("renderState") instanceof Map<?, ?> m ? m : Map.of();
        if (Boolean.TRUE.equals(renderState.get("failed")))
            result.add("prediction-render-disabled-after-error: " + java.util.Objects.toString(renderState.get("failureReason"), ""));
        Map<?, ?> submission = renderer.get("submission") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> lifecycle = submission.get("sceneLifecycle") instanceof Map<?, ?> m ? m : Map.of();
        if (Boolean.TRUE.equals(lifecycle.get("preparationFailed")))
            result.add("resident-preparation-failed: ordinary prediction fallback active until resource reset; "
                    + java.util.Objects.toString(lifecycle.get("preparationFailure"), ""));
        Map<String, ?> rates = sample.get("rates") instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.of();
        Map<String, ?> stages = sample.get("stages") instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.of();
        double frameMs = number(sample, "frameMeanMs");
        if (frameMs <= 0) {
            result.add("insufficient-gameplay-samples");
            return List.copyOf(result);
        }
        if (number(sample, "gcMsPerSecond") > 50) result.add("gc-pressure: JVM collection time exceeds 50 ms/s");
        String cpuStage = "", gpuStage = ""; double maxCpu = 0, maxGpu = 0;
        for (var entry : stages.entrySet()) if (entry.getValue() instanceof Map<?, ?> value) {
            var stage = (Map<String, ?>) value;
            double cpu=number(stage,stage.containsKey("cpuFrames")?"cpuFrameMs":"cpuMs");
            double gpu=number(stage,stage.containsKey("gpuFrames")?"gpuFrameMs":"gpuMs");
            if (cpu > maxCpu) { maxCpu = cpu; cpuStage = entry.getKey(); }
            if (gpu > maxGpu) { maxGpu = gpu; gpuStage = entry.getKey(); }
            if(number(stage,"gpuDropped")>0)result.add("gpu-timing-incomplete: "+entry.getKey()+" dropped queries; incomplete frame totals are excluded");
        }
        if (maxCpu > 0) result.add(String.format(Locale.ROOT, "largest-sampled-render-cpu: %s %.2f ms (nested wall time)", cpuStage, maxCpu));
        if (maxGpu > 0) result.add(String.format(Locale.ROOT, "largest-sampled-vss-gpu: %s %.2f ms", gpuStage, maxGpu));
        if (maxGpu > frameMs * .45) result.add("vss-gpu-pressure-suspected: compare resident-only at the same camera");
        if (number(rates, "workerCpuCores") >= .5 && Boolean.TRUE.equals(sample.get("loadingEnabled")))
            result.add("loading-cpu-active: compare loading with resident-only at the same camera");
        String worker = ""; double largest = 0;
        for (String key : List.of("sample", "surface", "mesh", "pack", "exterior")) {
            double value = number(rates, key + "WallMsPerSecond");
            if (value > largest) { largest = value; worker = key; }
        }
        if (largest > 0) result.add(String.format(Locale.ROOT, "largest-completed-worker-stage: %s %.1f wall ms/s", worker, largest));
        if (number(rates, "queueWaitWallMsPerSecond") > 100 && number(rates, "publishedTilesPerSecond") < 1)
            result.add("loading-queue-delay: low publication throughput with queue waiting");
        if ("resident-only".equals(sample.get("mode"))) result.add("resident-only-baseline: prediction loading is disabled");
        if ("prediction-off".equals(sample.get("mode"))) result.add("prediction-off-baseline: includes vanilla, Voxy and other mods");
        if (result.isEmpty()) result.add("no-dominant-vss-stage-in-this-interval");
        return List.copyOf(result);
    }

    /** Compare explicit labels/modes within a dimension and capture epoch; does not claim scenes are identical. */
    static List<Map<String, Object>> summary(List<Map<String, Object>> samples) {
        var groups = new LinkedHashMap<List<Object>, double[]>();
        for (var row : samples) {
            var key = List.of(row.getOrDefault("epoch", 0), row.getOrDefault("dimension", ""),
                    row.getOrDefault("label", ""), row.getOrDefault("mode", ""), row.getOrDefault("renderPath", "auto"));
            var totals = groups.computeIfAbsent(key, ignored -> new double[4]);
            totals[0]++; totals[1] += number(row, "frameCount"); totals[2] += number(row, "frameDurationNanos");
            totals[3] += number(row, "intervalSeconds");
        }
        var result = new ArrayList<Map<String, Object>>();
        groups.forEach((key, v) -> result.add(Map.of("epoch", key.get(0), "dimension", key.get(1), "label", key.get(2),
                "mode", key.get(3), "renderPath", key.get(4), "samples", v[0], "observedSeconds", v[3], "fps", v[2] > 0 ? v[1] * 1e9 / v[2] : 0)));
        return List.copyOf(result);
    }
}

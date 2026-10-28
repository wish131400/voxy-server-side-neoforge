package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PredictionPerformanceAnalysisTest {
    @Test void actualHitchesRemainVisibleAndEmptyIntervalsCannotInventFps() {
        var frames = new PredictionPerformanceFrames();
        assertEquals(0.0, frames.snapshot().get("fps"));
        for (int i = 0; i < 99; i++) frames.record(16_500_000);
        frames.record(3_000_000_000L);
        var row = frames.snapshot();
        assertEquals(17L, row.get("frameP95Ms"));
        assertEquals(3000.0, row.get("frameMaxMs"));
        assertEquals(1L, row.get("framesOver50ms"));
        assertTrue(((Number) row.get("fps")).doubleValue() < 30);
        frames.clear(); assertEquals(0L, frames.snapshot().get("frameCount"));
    }

    @Test void ratesUseIntervalDifferencesAndResetCountersCannotCreateNegativeWork() {
        var before = Map.<String, Object>of("publishedTiles", 50L, "visibleCpuNanos", 1_000_000_000L,
                "backgroundCpuNanos", 0L, "diskNanos", 300_000_000L);
        var after = Map.<String, Object>of("publishedTiles", 56L, "visibleCpuNanos", 2_000_000_000L,
                "backgroundCpuNanos", 1_000_000_000L, "diskNanos", 800_000_000L);
        var rates = PredictionPerformanceAnalysis.rates(after, before, 2);
        assertEquals(3.0, rates.get("publishedTilesPerSecond"));
        assertEquals(1.0, rates.get("workerCpuCores"));
        assertEquals(250.0, rates.get("diskWallMsPerSecond"));
        assertTrue(PredictionPerformanceAnalysis.rates(after, Map.of(), 1).isEmpty());
        assertEquals(0.0, PredictionPerformanceAnalysis.rates(before, after, 1).get("publishedTilesPerSecond"));
    }

    @Test void unavailableOrUnfinishedGpuQueriesStayMissingRatherThanZeroMilliseconds() {
        var previous = Map.<String, Object>of("OPAQUE", Map.of("cpuSamples", 3, "cpuNanos", 9_000_000,
                "gpuSamples", 0, "gpuNanos", 0));
        var current = Map.<String, Object>of("OPAQUE", Map.of("cpuSamples", 5, "cpuNanos", 19_000_000,
                "gpuSamples", 0, "gpuNanos", 0));
        var stage = (Map<?, ?>) PredictionPerformanceAnalysis.stages(current, previous).get("OPAQUE");
        assertEquals(5.0, stage.get("cpuMs")); assertFalse(stage.containsKey("gpuMs"));
    }

    @Test void comparisonsSeparateLoadingAndResidentModesAndWeightFpsByObservedTime() {
        var rows = List.of(row("loading", 60, 1_000_000_000L), row("loading", 60, 2_000_000_000L),
                row("resident-only", 80, 1_000_000_000L));
        var groups = PredictionPerformanceAnalysis.summary(rows);
        assertEquals(2, groups.size());
        assertEquals(40.0, groups.get(0).get("fps"));
        assertEquals(80.0, groups.get(1).get("fps"));
        assertEquals(List.of("insufficient-gameplay-samples"), PredictionPerformanceAnalysis.findings(Map.of()));
    }

    private static Map<String, Object> row(String mode, int frames, long duration) {
        return Map.of("epoch", 1, "dimension", "overworld", "label", "same-camera", "mode", mode,
                "frameCount", frames, "frameDurationNanos", duration, "intervalSeconds", duration / 1e9);
    }

    @Test void repeatedCallsUseCompleteFrameTotalsAndDroppedQueriesCannotInventACompleteFrame() {
        var zero = Map.<String,Object>of("gpuSamples",0,"gpuNanos",0,"gpuFrames",0,"gpuFrameNanos",0,
                "gpuAttempted",0,"gpuDropped",0);
        var now = Map.<String,Object>of("gpuSamples",8,"gpuNanos",20_000_000,"gpuFrames",2,
                "gpuFrameNanos",20_000_000,"gpuAttempted",8,"gpuDropped",0);
        var values=PredictionPerformanceAnalysis.stages(Map.of("GPU_RESIDENT_FILTER",now),Map.of("GPU_RESIDENT_FILTER",zero));
        var stage=(Map<?,?>)values.get("GPU_RESIDENT_FILTER");
        assertEquals(2.5,stage.get("gpuMs"));assertEquals(10.0,stage.get("gpuFrameMs"));
        assertEquals(20.0,stage.get("gpuTotalMs"));assertEquals(2.0,stage.get("gpuFrames"));
        var incomplete=Map.<String,Object>of("gpuSamples",3,"gpuNanos",7_000_000,"gpuFrames",0,
                "gpuFrameNanos",0,"gpuAttempted",4,"gpuDropped",1);
        var missed=PredictionPerformanceAnalysis.stages(Map.of("GPU_RESIDENT_FILTER",incomplete),Map.of("GPU_RESIDENT_FILTER",zero));
        assertFalse(((Map<?,?>)missed.get("GPU_RESIDENT_FILTER")).containsKey("gpuFrameMs"));
        var findings=PredictionPerformanceAnalysis.findings(Map.of("stages",missed,"frameMeanMs",20));
        assertTrue(findings.stream().anyMatch(s->s.startsWith("gpu-timing-incomplete:")));
        assertFalse(findings.stream().anyMatch(s->s.startsWith("largest-sampled-vss-gpu:")));
    }

    @Test void submissionPathsRemainSeparateEvenWithTheSameLabel() {
        var ordinary=new java.util.HashMap<>(row("resident-only",60,1_000_000_000));
        ordinary.put("renderPath","ordinary");
        var resident=new java.util.HashMap<>(ordinary);resident.put("renderPath","resident");
        assertEquals(2,PredictionPerformanceAnalysis.summary(List.of(ordinary,resident)).size());
    }

    @Test void renderFailureRemainsVisibleWithoutFreshFrameTimings() {
        var findings=PredictionPerformanceAnalysis.findings(Map.of("renderer",Map.of("renderState",
                Map.of("failed",true,"failureReason","terrain-draw: fixture failure"))));
        assertTrue(findings.stream().anyMatch(s->s.startsWith("prediction-render-disabled-after-error:") && s.contains("fixture failure")));
        assertTrue(findings.contains("insufficient-gameplay-samples"));
    }

    @Test void residentFailureIsReportedEvenWhenOrdinaryRenderingKeepsFpsHigh() {
        var findings=PredictionPerformanceAnalysis.findings(Map.of("frameMeanMs",5,"renderer",Map.of(
                "renderState",Map.of("failed",false),"submission",Map.of("sceneLifecycle",Map.of(
                        "preparationFailed",true,"preparationFailure","fixture resident failure")))));
        assertTrue(findings.stream().anyMatch(s->s.startsWith("resident-preparation-failed:") && s.contains("fixture resident failure")));
        assertFalse(findings.contains("no-dominant-vss-stage-in-this-interval"));
        assertFalse(findings.stream().anyMatch(s->s.startsWith("prediction-render-disabled-after-error:")));
    }
}

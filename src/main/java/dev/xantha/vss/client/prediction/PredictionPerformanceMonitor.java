package dev.xantha.vss.client.prediction;

import com.google.gson.GsonBuilder;
import dev.xantha.vss.config.VSSClientConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Opt-in observational client boundary. Capture never changes loading or quality.
 * Only the explicit local path command overrides submission for a manual comparison.
 * Client/render thread owns at most 600 interval snapshots. Other threads read detached maps only.
 * World/profile/mode transitions invalidate rate baselines while retaining labelled history.
 */
public final class PredictionPerformanceMonitor {
    private static final long SECOND = 1_000_000_000L;
    private static final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
    private static final PredictionPerformanceFrames frames = new PredictionPerformanceFrames();
    private static final PredictionPerformanceService service = new PredictionPerformanceService();
    private static Object levelIdentity;
    private static long lastFrame, lastSample, epoch;
    private static boolean enabled, gameplayBefore, hud;
    private static String label = "default", previousMode = "";
    private static Map<String, Object> previousLoading = Map.of(), previousStages = Map.of(), previousSystem = Map.of();
    private static Map<String, Object> latest = Map.of();
    private static List<String> hudLines = List.of();

    private PredictionPerformanceMonitor() { }
    static boolean active() { return enabled; }

    /** Client-thread command. Endpoint creation performs disk/network work off-thread. */
    public static CompletableFuture<Path> start() {
        if (Minecraft.getInstance() == null) return CompletableFuture.failedFuture(new IllegalStateException("Client unavailable"));
        enabled = true; history.clear(); latest = Map.of(); label = "default"; reset(); publish(false);
        return service.start(directory());
    }
    /** Stops capture and endpoint; keeps last history available for explicit export. */
    public static void stop() { enabled = false; reset(); publish(false); service.stop(); }
    public static void mark(String value) { label = value.length() > 64 ? value.substring(0, 64) : value; reset(); }
    public static boolean toggleHud() { hud = !hud; return hud; }
    /** Explicit local A/B control. Does not change detail, distance or loading. */

    static void frame() {
        if (!enabled) return;
        var client = Minecraft.getInstance(); Object world = client == null ? null : client.level;
        if (levelIdentity != world) { reset(); levelIdentity = world; }
        long now = System.nanoTime(), gap = lastFrame == 0 ? 0 : now - lastFrame; lastFrame = now;
        boolean gameplay = world != null && client.screen == null && !client.isPaused() && client.isWindowActive();
        if (!gameplay) {
            if (gameplayBefore) { resetInterval(); publish(false); }
            gameplayBefore = false; return;
        }
        var config = VSSClientConfig.CONFIG;
        String mode = !config.enablePrediction ? "prediction-off" : config.isPredictionLoadingEnabled() ? "loading" : "resident-only";
        if (!gameplayBefore || !mode.equals(previousMode)) {
            resetInterval(); previousMode = mode; lastSample = now; gameplayBefore = true; return;
        }
        frames.record(gap);
        if (now - lastSample < SECOND) return;
        double seconds = (now - lastSample) / 1e9;
        var counters = ClientPredictionState.demandSnapshot(client.level.dimension());
        var stages = PredictionRenderTimings.snapshot(); var system = service.system();
        var row = new java.util.LinkedHashMap<String, Object>(frames.snapshot());
        row.put("time", Instant.now().toString()); row.put("epoch", epoch);
        row.put("dimension", client.level.dimension().location().toString()); row.put("label", label); row.put("mode", mode);
        row.put("predictionEnabled", config.enablePrediction); row.put("loadingEnabled", config.isPredictionLoadingEnabled());
        row.put("intervalSeconds", seconds);
        var position = client.gameRenderer.getMainCamera().getPosition();
        row.put("camera", List.of(position.x, position.y, position.z));
        row.put("cameraYaw", client.gameRenderer.getMainCamera().getYRot());
        row.put("cameraPitch", client.gameRenderer.getMainCamera().getXRot());
        row.put("fov", client.options.fov().get());
        row.put("resolution", List.of(client.getWindow().getWidth(), client.getWindow().getHeight()));
        row.put("frameCap", client.options.framerateLimit().get()); row.put("vsync", client.options.enableVsync().get());
        row.put("shadersActive", PredictionIrisBridge.shadersActive());
        row.put("predictionDistanceBlocks", config.predictionDistanceBlocks); row.put("performanceTier", config.performanceTier);
        row.put("loading", counters); row.put("rates", PredictionPerformanceAnalysis.rates(counters, previousLoading, seconds));
        row.put("stages", PredictionPerformanceAnalysis.stages(stages, previousStages)); row.put("system", system);
        row.put("renderPath","voxy");
        row.put("timingDroppedQueries",PredictionPerformanceAnalysis.delta(stages,previousStages,"dropped"));
        row.put("renderer", PredictionRenderer.performanceSnapshot());
        row.put("gcMsPerSecond", PredictionPerformanceAnalysis.delta(system, previousSystem, "gcMillis") / seconds);
        var progress = ClientPredictionState.loadingProgress(client.level.dimension());
        row.put("progress", counters.getOrDefault("progressObservation", Map.of("coverageReady", progress.coverageReady(), "coverageTotal", progress.coverageTotal(),
                "targetReady", progress.targetReady(), "targetTotal", progress.targetTotal(),
                "surfaceReady", progress.surfaceReady(), "surfaceTotal", progress.surfaceTotal(), "waitingFor", progress.waitingFor(95))));
        row.put("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        row.put("heapMaxBytes", Runtime.getRuntime().maxMemory()); row.put("findings", PredictionPerformanceAnalysis.findings(row));
        latest = Map.copyOf(row);
        if (history.size() == 600) history.removeFirst(); history.addLast(latest);
        previousLoading = counters; previousStages = stages; previousSystem = system; lastSample = now; frames.clear();
        hudLines = List.of("VSS Performance [" + label + "/" + mode + "]", status(),
                "MCP: " + service.status() + " | samples=" + history.size());
        publish(true);
    }

    private static void publish(boolean sampling) {
        var samples = List.copyOf(history);
        service.publish(Map.of("schema", 1, "created", Instant.now().toString(), "capturing", enabled,
                "sampling", sampling, "latest", latest, "samples", samples, "summary", PredictionPerformanceAnalysis.summary(samples),
                "measurementNotes", List.of("Observational only; no workload or quality changes.",
                        "GPU timings cover sampled VSS passes, not device utilization or all game/mod work.",
                        "Render CPU stages are sampled wall time and may nest. Do not sum them.",
                        "cpuMs/gpuMs are per-call means. cpuFrameMs/gpuFrameMs aggregate calls from the same sampled render frame; incomplete GPU frames are excluded. GPU query completion may cross intervals.",
                        "INDIRECT_SUBMIT times draw groups between selection/bucket/fallback boundaries, including binds within those groups. It is not one query per MDI call or pure shader execution time.",
                        "Resident selection totals use three asynchronous 48-byte GPU reductions with uint64 phase totals; batch count does not limit completeness. Partial resident scenes keep the configured memory budget and draw excluded source ranges exactly.",
                        "selectionOutput is delayed sampled resident-filter output, not all game geometry. Compare its submissionFrame to renderer.submission.submissionFrame; check ageSeconds and complete. A local path command is the only submission override; MCP remains read-only.",
                        "Worker stages may overlap and include native waits; completed-task counters can be delayed.",
                        "Thread CPU is interval attribution; 1 Hz short stacks are location clues, not a statistical profiler.",
                        "Percentiles use 1 ms buckets capped at 2001 ms; frameMaxMs retains the exact maximum.",
                        "Compare modes only at matching camera, resolution and settings; summaries do not establish causality.")));
    }

    /** Last complete gameplay interval; opening chat does not overwrite it. */
    @SuppressWarnings("unchecked")
    public static String status() {
        var rates = latest.get("rates") instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.<String, Object>of();
        return String.format(Locale.ROOT, "FPS %.1f | P95 %.0f ms | worker %.2f cores | %.2f tiles/s | %s",
                PredictionPerformanceAnalysis.number(latest, "fps"), PredictionPerformanceAnalysis.number(latest, "frameP95Ms"),
                PredictionPerformanceAnalysis.number(rates, "workerCpuCores"), PredictionPerformanceAnalysis.number(rates, "publishedTilesPerSecond"),
                enabled ? "capture=" + service.status() : "stopped");
    }

    /** Render-thread HUD reads cached text only; toggling it does not start capture. */
    public static void renderHud(net.minecraft.client.gui.GuiGraphics graphics) {
        var client = Minecraft.getInstance();
        if (!hud || !enabled || client == null || client.level == null || client.options.hideGui) return;
        int y = 6;
        for (String line : hudLines) {
            graphics.fill(4, y - 1, 8 + client.font.width(line), y + 10, 0x98000000);
            graphics.drawString(client.font, line, 6, y, 0xffeeeeee); y += 12;
        }
    }

    /** Bounded export serializes off-thread, using the same report format as MCP. */
    public static CompletableFuture<Path> report() {
        Path directory = directory();
        publish(gameplayBefore);
        var document = service.reportSnapshot();
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(directory);
                Path path = directory.resolve("performance-" + java.util.UUID.randomUUID() + ".json");
                Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(document),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return path;
            } catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
    }
    private static Path directory() { return Minecraft.getInstance().gameDirectory.toPath().resolve("debug/vss-performance"); }
    private static void resetInterval() { frames.clear(); previousLoading = previousStages = previousSystem = Map.of(); lastSample = 0; }
    /** World/profile reset retains history; rates never cross an epoch. */
    static void reset() {
        resetInterval(); epoch++; levelIdentity = null; lastFrame = 0; gameplayBefore = false; previousMode = "";
        if (enabled) publish(false);
    }
}

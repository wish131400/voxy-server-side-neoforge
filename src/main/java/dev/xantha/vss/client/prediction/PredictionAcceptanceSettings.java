package dev.xantha.vss.client.prediction;

import java.util.Map;

/** Explicit, process-local acceptance settings. No effect on workload or quality. */
final class PredictionAcceptanceSettings {
    private PredictionAcceptanceSettings() { }

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"); }
    static boolean enabled() {
        return Boolean.getBoolean("vss.acceptance.enabled") && validId(System.getProperty("vss.acceptance.runId"));
    }
    static boolean background() { return enabled() && Boolean.getBoolean("vss.acceptance.background"); }
    static boolean autoCapture() { return enabled() && Boolean.getBoolean("vss.acceptance.capture"); }
    static boolean gameplay(boolean world, boolean screen, boolean paused, boolean focused) {
        return world && !screen && !paused && (focused || background());
    }
    static Map<String, Object> metadata() {
        return Map.of("enabled", enabled(), "runId", enabled() ? System.getProperty("vss.acceptance.runId") : "",
                "backgroundAllowed", background(), "processId", ProcessHandle.current().pid());
    }
    static void validateProbe(String id, int side, int step) {
        if (!enabled()) throw new IllegalStateException("Acceptance process was not explicitly enabled");
        if (!validId(id) || side < 1 || side > 8 || step < 1 || step > 256)
            throw new IllegalArgumentException("Probe requires a safe ID, side 1..8 and step 1..256");
    }
}

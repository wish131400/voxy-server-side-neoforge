package dev.xantha.vss.client.prediction;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** The native viewport is authoritative; vanilla observation is a startup/API fallback. */
final class PredictionViewObservation {
    record Snapshot(PredictionWorkView view, double scale, boolean scoping, boolean unified, long observedAt) {
        boolean fresh(long now) { return now >= observedAt && now-observedAt < 1_000_000_000L; }
    }
    private volatile Snapshot current;

    Snapshot observe(Matrix4f projection, Matrix4f modelView, Vec3 camera, int height,
                     boolean scoping, boolean unified, long now) {
        var previous = current;
        if (height <= 0 || !unified && previous != null && previous.unified() && previous.fresh(now)) return null;
        double scale = VssLodProjection.selectionScale(projection, height);
        var direction = VssLodProjection.centerRay(projection, modelView, camera).direction();
        var snapshot = new Snapshot(PredictionWorkView.rendered(projection, modelView, camera, direction, scale),
                scale, scoping, unified, now);
        current = snapshot;
        return snapshot;
    }

    Snapshot current() { return current; }
    void reset() { current = null; }
}

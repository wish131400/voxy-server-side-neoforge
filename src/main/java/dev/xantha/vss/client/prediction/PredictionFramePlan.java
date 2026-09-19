package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;

/** One opaque/transparent pair only; never a cache of previous-frame visibility. */
final class PredictionFramePlan<T> {
    private Object view, level, snapshot;
    private long frame, generation;
    private int viewportFrame, width, height;
    private Matrix4f modelView, projection;
    private Vec3 camera;
    private T value;

    T get(Object view, Object level, Object snapshot, long frame, long generation,
          int viewportFrame, int width, int height, PredictionRenderer.Frame matrices) {
        return this.view == view && this.level == level && this.snapshot == snapshot
                && this.frame == frame && this.generation == generation
                && this.viewportFrame == viewportFrame && this.width == width && this.height == height
                && matrices.camera().equals(camera) && matrices.modelView().equals(modelView)
                && matrices.projection().equals(projection) ? value : null;
    }

    void put(Object view, Object level, Object snapshot, long frame, long generation,
             int viewportFrame, int width, int height, PredictionRenderer.Frame matrices, T value) {
        this.view = view; this.level = level; this.snapshot = snapshot;
        this.frame = frame; this.generation = generation; this.viewportFrame = viewportFrame;
        this.width = width; this.height = height;
        this.modelView = new Matrix4f(matrices.modelView());
        this.projection = new Matrix4f(matrices.projection());
        this.camera = matrices.camera(); this.value = value;
    }

    void clear() { view = null; level = null; snapshot = null; value = null; }
}

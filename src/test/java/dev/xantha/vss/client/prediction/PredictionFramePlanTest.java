package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionFramePlanTest {
    private final Object view = new Object(), level = new Object(), snapshot = new Object();
    private final PredictionFramePlan<Object> cache = new PredictionFramePlan<>();
    private final Object plan = new Object();
    private final PredictionRenderer.Frame matrices = new PredictionRenderer.Frame(
            new Matrix4f(), new Matrix4f().perspective(1, 1, .1f, 4096), new Vec3(0, 64, 0));

    private void put() { cache.put(view, level, snapshot, 10, 20, 30, 1920, 1080, matrices, plan); }
    private Object get() { return cache.get(view, level, snapshot, 10, 20, 30, 1920, 1080, matrices); }

    @Test void reusesOpaquePlanOnlyWithinTheMatchingViewAndFrame() {
        assertNull(get()); put(); assertSame(plan, get());
        assertNull(cache.get(new Object(), level, snapshot, 10, 20, 30, 1920, 1080, matrices));
        assertNull(cache.get(view, new Object(), snapshot, 10, 20, 30, 1920, 1080, matrices));
        assertNull(cache.get(view, level, new Object(), 10, 20, 30, 1920, 1080, matrices));
        assertNull(cache.get(view, level, snapshot, 11, 20, 30, 1920, 1080, matrices));
        assertNull(cache.get(view, level, snapshot, 10, 20, 31, 1920, 1080, matrices));
        assertNull(cache.get(view, level, snapshot, 10, 20, 30, 960, 540, matrices));
        cache.clear(); assertNull(get());
    }

    @Test void interleavedViewOrReloadCannotReuseMutatedGpuResources() {
        put();
        assertNull(cache.get(view, level, snapshot, 10, 21, 30, 1920, 1080, matrices));
        // The matrices are owned by the external renderer and can mutate in place.
        matrices.modelView().rotateY(.1f);
        assertNull(get());
        matrices.modelView().identity(); assertSame(plan, get());
        matrices.projection().scale(2); assertNull(get());
    }

    @Test void cameraChangesDoNotReuseTheOldCoverage() {
        put();
        var moved = new PredictionRenderer.Frame(matrices.modelView(), matrices.projection(), new Vec3(1, 64, 0));
        assertNull(cache.get(view, level, snapshot, 10, 20, 30, 1920, 1080, moved));
    }
}

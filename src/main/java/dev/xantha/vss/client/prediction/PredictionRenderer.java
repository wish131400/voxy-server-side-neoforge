package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.StrictLodVisibility;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Frame observation only. Voxy owns meshing, GPU resources, occlusion and every terrain draw. */
public final class PredictionRenderer {
    private PredictionRenderer() { }
    private static final AtomicLong frameStarts = new AtomicLong();
    private static final PredictionViewObservation observations = new PredictionViewObservation();

    public static void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            observeView(new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()), event.getCamera().getPosition(),
                    Minecraft.getInstance().getMainRenderTarget().height, false);
            PredictionRenderCapture.terrainComplete();
            PredictionRenderCapture.current("vanilla-after-cutout");
        }
        if (PredictionRenderCapture.active() && !PredictionIrisBridge.shadersActive()) {
            if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES)
                PredictionRenderCapture.checkpoint("vanilla-after-entity-submit");
            if (event.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                PredictionRenderCapture.checkpoint("vanilla-after-level");
                PredictionRenderCapture.finish("vanilla-after-level");
            }
        }
    }

    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        PredictionFogBridge.beginFrame();
        PredictionRenderCapture.beginFrame();
        PredictionFramePace.recordFrame();
        PredictionPerformanceMonitor.frame();
        PredictionRenderTimings.frame(frameStarts.incrementAndGet());
        if (PredictionDemandProfile.enabled()) PredictionDemandProfile.observeMenu(Minecraft.getInstance().screen != null);
    }

    /** Uses Voxy's actual projection, including shader-pack and spyglass projection changes. */
    public static void observeUnifiedView(Object viewport) {
        // Iris shadow passes can enter the common hook before Voxy rejects an empty viewport.
        if (viewport == null) return;
        try {
            int width = ((Number)dev.xantha.vss.common.ReflectionAccess.field(viewport,"width")).intValue();
            int height = ((Number)dev.xantha.vss.common.ReflectionAccess.field(viewport,"height")).intValue();
            if (width <= 0 || height <= 0) return;
            var level = Minecraft.getInstance().level;
            if (level == null || !VoxyPredictionBridge.active(level.dimension())) return;
            observeView(new Matrix4f((Matrix4f)dev.xantha.vss.common.ReflectionAccess.field(viewport,"modelView")),
                    new Matrix4f((Matrix4f)dev.xantha.vss.common.ReflectionAccess.field(viewport,"projection")),
                    new Vec3(((Number)dev.xantha.vss.common.ReflectionAccess.field(viewport,"cameraX")).doubleValue(),
                            ((Number)dev.xantha.vss.common.ReflectionAccess.field(viewport,"cameraY")).doubleValue(),
                            ((Number)dev.xantha.vss.common.ReflectionAccess.field(viewport,"cameraZ")).doubleValue()),
                    height, true);
        } catch (ReflectiveOperationException failure) {
            // Client ticks still provide camera demand if an adapter changes its viewport API.
        }
    }

    private static void observeView(Matrix4f modelView, Matrix4f projection, Vec3 camera, int height, boolean unified) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null || minecraft.player == null || !VSSClientConfig.CONFIG.enablePrediction) return;
        var observed = observations.observe(projection, modelView, camera, height,
                minecraft.player.isScoping(), unified, System.nanoTime());
        if (observed == null) return;
        ClientPredictionState.publishWorkView(level.dimension(), observed.view(),
                !observed.scoping() || VSSClientConfig.CONFIG.predictionSpyglassLoading);
        StrictLodVisibility.updateRenderWindow(level.dimension(), camera.x, camera.z,
                ModCompat.getVoxyViewDistanceChunks().orElse(0));
    }

    public static String diagnostics() {
        return "backend=voxy,frameStarts=" + frameStarts.get() + ",overlay={" + VoxyPredictionBridge.diagnostics() + "}";
    }

    /** VSS does not measure Voxy's GPU timings; absence must not be reported as zero GPU cost. */
    static Map<String,Object> performanceSnapshot() {
        var level = Minecraft.getInstance().level;
        boolean active = level != null && VoxyPredictionBridge.active(level.dimension());
        return Map.of("renderState", Map.of("backend", "voxy", "active", active,
                "gpuTimingAvailable", false, "voxyOverlay", VoxyPredictionBridge.diagnostics()));
    }

    /** Invalidates frame demand when the world or resource identity changes. */
    public static void resetOcclusion() {
        ClientPredictionState.clearVisibilityFeedback();
        PredictionRenderTimings.reset();
        observations.reset();
    }

    static boolean normalFogActive() {
        var level = Minecraft.getInstance().level;
        return level != null && VoxyPredictionBridge.active(level.dimension());
    }

    static double selectionPixelsPerBlock(Minecraft minecraft) {
        var observed = observations.current();
        double scale = observed == null ? 0 : observed.scale();
        if (observed != null && observed.fresh(System.nanoTime())
                && scale > 0.0D && observed.scoping() == minecraft.player.isScoping()) {
            // Projection animation and head bob must not invalidate every coverage mask each frame.
            return Math.max(16.0D, Math.rint(scale / 16.0D) * 16.0D);
        }
        return VssLodProjection.unscopedScale(minecraft.options.fov().get(),
                minecraft.getMainRenderTarget().height);
    }

    static double basePixelsPerBlock(Minecraft minecraft) {
        return minecraft.player.isScoping()
                ? VssLodProjection.unscopedScale(minecraft.options.fov().get(),
                    minecraft.getMainRenderTarget().height)
                : selectionPixelsPerBlock(minecraft);
    }

    static PredictionFogBridge.Fog normalFog(Minecraft minecraft) {
        int vanillaDistance = minecraft.options.renderDistance().get() * 16;
        int realDistance = Math.max(vanillaDistance, ModCompat.getVoxyViewDistanceChunks().orElse(0) * 16);
        var fog = VssLodFog.shared(predictionHorizonBlocks(), realDistance, vanillaDistance,
                VSSClientConfig.CONFIG.predictionFog);
        return new PredictionFogBridge.Fog(fog.shaderStart(), fog.shaderEnd(), Math.min(vanillaDistance, fog.start()),
                fog.haze() ? fog.aerialDensity() : 0);
    }

    private static int predictionHorizonBlocks() {
        return Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
    }

    static int lodForBlocks(double distanceBlocks, double pixelsPerBlock,
                            double pixelsPerQuad) {
        if (!(pixelsPerBlock > 0.0D) || !(pixelsPerQuad > 0.0D)
                || !(distanceBlocks > 0.0D)) {
            return 0;
        }
        double spacingMax = distanceBlocks * pixelsPerQuad / pixelsPerBlock;
        if (spacingMax < 1.0D) {
            return 0;
        }
        int lod = (int) Math.floor(Math.log(Math.min(spacingMax, 65536.0D)) / Math.log(2.0D));
        return Math.min(PredictionTileManager.MAX_LOD_LEVEL, Math.max(0, lod));
    }

    static double pixelsPerQuad() {
        return switch (VSSClientConfig.CONFIG.predictionDetail) {
            case "low" -> 12.0D;
            case "high" -> 3.0D;
            case "extreme" -> 1.5D;
            default -> 6.0D;
        };
    }

}

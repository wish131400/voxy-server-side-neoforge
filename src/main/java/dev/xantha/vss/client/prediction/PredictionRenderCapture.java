package dev.xantha.vss.client.prediction;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** One user-requested frame, then background serialization. Disabled hot path is a null check. */
public final class PredictionRenderCapture {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile Request pending;
    private static Request running;
    private static PredictionFrameCapture active;
    private static final LinkedHashSet<Integer> lodTargets = new LinkedHashSet<>();
    private static final LinkedHashSet<String> entityPrograms = new LinkedHashSet<>();
    private static boolean afterTerrain;
    private record Request(double x, double y, long ready, long expires, Object level,
                           Path directory, CompletableFuture<Path> result) { }

    public static CompletableFuture<Path> request(double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return CompletableFuture.failedFuture(new IllegalStateException("Enter a world first"));
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Coordinates must be in [0,1]"));
        if (!BUSY.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("A render capture is already running"));
        var result = new CompletableFuture<Path>();
        result.whenComplete((path, failure) -> BUSY.set(false));
        long now = System.nanoTime();
        pending = new Request(x, y, now + 3_000_000_000L, now + 30_000_000_000L, mc.level,
                mc.gameDirectory.toPath().resolve("debug/vss-render"), result);
        return result;
    }

    static void beginFrame() {
        // Fallback for absent/changed Iris hooks: finish at the next frame without reading stale pixels.
        if (active != null) finish("next-frame");
        Request request = pending;
        if (request == null) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        if (mc.level != request.level() || now > request.expires()) {
            pending = null;
            request.result().completeExceptionally(new IllegalStateException("World changed or no unpaused world frame within 30 seconds"));
            return;
        }
        if (now < request.ready() || mc.screen != null) return;
        pending = null;
        if (!GL.getCapabilities().OpenGL45) {
            request.result().completeExceptionally(new IllegalStateException("Render capture requires OpenGL 4.5")); return;
        }
        running = request;
        active = new PredictionFrameCapture(request.x(), request.y());
        active.report.put("gpu", GL11.glGetString(GL11.GL_RENDERER));
        active.report.put("openGL", GL11.glGetString(GL11.GL_VERSION));
        active.report.put("predictionEnabled", dev.xantha.vss.config.VSSClientConfig.CONFIG.enablePrediction);
        active.report.put("shadersActive", PredictionIrisBridge.shadersActive());
        active.report.put("gameVersion", net.minecraft.SharedConstants.getCurrentVersion().getName());
        active.report.put("mods", net.neoforged.fml.ModList.get().getMods().stream().map(mod ->
                java.util.Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList());
        active.report.put("shaderTextures", new int[]{com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0),
                com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(1), com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(2)});
        lodTargets.clear();
        entityPrograms.clear();
        afterTerrain = false;
    }

    static boolean active() { return active != null; }

    static void terrainComplete() { if (active != null) afterTerrain = true; }

    public static void shaderApplied(Object shader) {
        if (active == null || entityPrograms.size() >= 4) return;
        // Start after the terrain event, including a baseline with prediction disabled.
        if (!afterTerrain) return;
        String name = ((net.minecraft.client.renderer.ShaderInstance) shader).getName();
        if (!(name.contains("entit") || name.contains("eyes") || name.contains("armor")) || !entityPrograms.add(name)) return;
        current("entity-bound-" + name.replaceAll("[^a-zA-Z0-9_-]", "_"));
    }

    static void prediction(String stage, int framebuffer) {
        if (active == null) return;
        // Bound auxiliary views (portals/reflections) as well as attachment data.
        if (lodTargets.size() < 2) lodTargets.add(framebuffer);
        capture(stage, framebuffer);
    }

    static void current(String stage) {
        if (active == null) return;
        capture(stage, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
    }

    /** Iris/Oculus pipeline hooks bracket entity batches, deferred passes and composites. */
    public static void checkpoint(String stage) {
        if (active == null) return;
        Minecraft mc = Minecraft.getInstance();
        capture(stage + "-main", mc.getMainRenderTarget().frameBufferId);
        for (int framebuffer : lodTargets) capture(stage + "-lod", framebuffer);
    }

    private static void capture(String stage, int framebuffer) {
        if (active == null) return;
        try { active.capture(stage, framebuffer); }
        catch (RuntimeException failure) {
            active.report.put("captureError", failure.toString());
            finish("capture-error");
        }
    }

    public static void finish(String reason) {
        if (active == null) return;
        PredictionFrameCapture captured = active;
        Request request = running;
        active = null; running = null;
        captured.report.put("finishedAt", reason);
        CompletableFuture.runAsync(() -> {
            try { request.result().complete(captured.write(request.directory())); }
            catch (Exception failure) { request.result().completeExceptionally(failure); }
        });
    }

    private PredictionRenderCapture() { }
}

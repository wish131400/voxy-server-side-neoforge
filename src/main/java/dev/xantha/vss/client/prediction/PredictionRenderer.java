package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * The prediction renderer, packed-quad renderer.
 *
 * <p>Meshes reach the GPU as packed 48-byte quads in texture buffers and are
 * expanded by the terrain program from {@code gl_VertexID}; a shared index
 * buffer turns each quad into two triangles. Exact-coverage handover is an
 * R8 cell mask texture. Voxel faces remain at their sampled heights so tops
 * and connecting walls cannot separate. Only mesh changes trigger uploads.</p>
 */
public final class PredictionRenderer {
    private static final PredictionRenderTarget predictionTarget = new PredictionRenderTarget();
    private static final PredictionVanillaMask vanillaMask = new PredictionVanillaMask();
    private static final AtomicLong frameStarts = new AtomicLong();
    private static final AtomicLong renderFrames = new AtomicLong();
    private static final AtomicLong seenTiles = new AtomicLong();
    private static final AtomicLong consideredCells = new AtomicLong();
    private static final AtomicLong renderedCells = new AtomicLong();
    private static final AtomicLong culledTiles = new AtomicLong();
    private static final AtomicLong skippedCoverage = new AtomicLong();
    private static final AtomicLong skippedAuthoritative = new AtomicLong();
    private static final AtomicLong meshUploads = new AtomicLong();
    private static final AtomicLong coverageCacheHits = new AtomicLong();
    private static final AtomicLong coverageResolves = new AtomicLong();
    private static final AtomicLong coverageNanos = new AtomicLong();
    private static final AtomicLong drawCalls = new AtomicLong();
    private static final AtomicLong submittedQuads = new AtomicLong();
    /**
     * Frame-local atlas availability. Transient sampler failures may recover
     * on a later frame; they must not disable textures for the whole session.
     */
    private static volatile boolean detailPathEnabled = true;
    private static volatile int boundSpriteTableId = -1;
    private static long lastSamplerErrorNanos;
    private static volatile boolean programBroken;
    private static PredictionTerrainProgram program;
    private static PredictionIrisBridge.Pass irisPass;
    private static DeferredWater deferredWater;
    private record DeferredWater(Frame frame, VssLodProjection.MatrixData projection,
                                 List<Draw> draws, ClientLevel level, RenderTarget target) { }
    record Frame(Matrix4f modelView, Matrix4f projection, Vec3 camera) { }
    private static int sharedVertexArray = -1;
    private static int sharedIndexBuffer = -1;
    private static int sharedIndexQuads;

    private static final Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> gpuTiles =
            new ConcurrentHashMap<>();
    private static final Map<PredictionTileManager.PredictionTileKey, CachedCoverage> coverageCache =
            new ConcurrentHashMap<>();
    private static final PredictionRenderResidency renderResidency = new PredictionRenderResidency();
    private static PredictionTileManager.RenderSnapshot prunedSnapshot;
    private static final PredictionLodSeams lodSeams = new PredictionLodSeams();
    private static final Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> seamTiles = new java.util.HashMap<>();
    private static final PredictionRealBoundarySeams realSeams = new PredictionRealBoundarySeams();
    private static final Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> realSeamTiles = new java.util.HashMap<>();
    private static final PredictionUploadBudget uploadBudget = new PredictionUploadBudget();
    /**
     * Budget for periodic coverage probes. Changes to tile ownership or the
     * view bypass this budget: delaying half of a parent/child handover either
     * overlaps the surfaces or leaves a hole. Unchanged owners keep drawing
     * their cached mask while a periodic probe waits for the next frame.
     */
    private static final int MAX_COVERAGE_RESOLVES_PER_FRAME = 32;
    private static final long COVERAGE_RECHECK_NANOS = 250_000_000L;
    /**
     * Global per-chunk coverage decisions shared across tiles in a frame.
     * The same chunk's yield/desired-LOD computation ran once per owning
     * tile per resolve (a near-field chunk appears in lod 0..3 tiles at
     * once), each pass walking up to twenty ancestor lookups.  A shared
     * frame-scoped cache turns that into one computation per chunk.
     */
    private static final byte UNKNOWN_DECISION = Byte.MIN_VALUE;
    // Render-thread only. Primitive keys mix the entire packed coordinate,
    // avoid Long.hashCode's x^z collisions and allocate no per-chunk arrays.
    private static final Long2ByteOpenHashMap chunkDecisionCache = newDecisionCache();
    private static volatile long decisionCachePixelsBits = Long.MIN_VALUE;
    private static double decisionCachePixelThreshold;
    private static int decisionCachePlayerChunkX = Integer.MIN_VALUE;
    private static int decisionCachePlayerChunkZ = Integer.MIN_VALUE;
    private static volatile VssLodFocus decisionFocus;
    private static long decisionCacheExpiresAt;
    private static volatile long lastRenderLogNanos;

    private PredictionRenderer() {
    }

    /** Runs the packed-quad pass after vanilla/Voxy cutout terrain. */
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!VSSClientConfig.CONFIG.enablePrediction || programBroken || PredictionIrisBridge.shadersActive()) {
            return;
        }
        // Terrain supplies the background before vanilla translucent blocks.
        // Water waits for their depth so it cannot tint real water a second time.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            renderStage(new Frame(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition()));
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderDeferredWater();
        }
    }

    /** Forwarded once per frame by the registered VSSClientNetworking subscriber. */
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        frameStarts.incrementAndGet();
        deferredWater = null;
        PredictionVoxyDepth.clear();
        uploadBudget.reset();
        drainRetiredTiles();
    }

    static void renderIris(Frame frame, PredictionIrisBridge.Pass pass,
                           PredictionTileManager.RenderSnapshot snapshot) {
        if (!VSSClientConfig.CONFIG.enablePrediction) return;
        PredictionTerrainProgram previous = program;
        irisPass = pass;
        program = pass.program();
        try { renderStage(frame, snapshot); }
        finally { irisPass = null; program = previous; }
    }

    private static void renderStage(Frame event) {
        ClientLevel level = Minecraft.getInstance().level;
        renderStage(event, level == null ? null : ClientPredictionState.renderSnapshot(level.dimension()));
    }

    private static void renderStage(Frame event, PredictionTileManager.RenderSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        selectionScale = VssLodProjection.selectionScale(event.projection(),
                minecraft.getMainRenderTarget().height);
        selectionScoping = minecraft.player.isScoping();
        viewRay = VssLodProjection.centerRay(event.projection(), event.modelView(), event.camera());
        if (snapshot == null) return;
        Collection<PredictionTileManager.PredictionTile> tiles = snapshot.tiles().values();
        if (tiles.isEmpty()) {
            return;
        }
        if (!PredictionGraphicsSupport.available(irisPass != null)) return;
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (irisPass == null) predictionTarget.ensure(mainTarget);
        if (irisPass == null && !predictionTarget.available()) {
            return;
        }

        Vec3 camera = event.camera();
        vanillaMask.update(minecraft, camera);
        int playerChunkX = minecraft.player.getBlockX() >> 4;
        int playerChunkZ = minecraft.player.getBlockZ() >> 4;
        int nearDistance = VSSClientNetworking.getEffectiveLodDistanceChunks();
        if (nearDistance <= 0) {
            // During login (and in single-player before the local handshake)
            // the server radius is temporarily zero. Prediction already has
            // its own horizon and must continue rendering while the session
            // distance is negotiated; otherwise the first frame shows only
            // sky/flat fallback and never starts the progressive handoff.
            nearDistance = Math.max(1, ModCompat.getVoxyViewDistanceChunks()
                    .orElse(minecraft.options.renderDistance().get()));
        }
        double pixelsPerBlock = basePixelsPerBlock(minecraft);
        Matrix4f vanillaProjection = new Matrix4f(event.projection());
        VssLodProjection.MatrixData projection = VssLodProjection.of(vanillaProjection);
        Frustum lodFrustum = new Frustum(event.modelView(), projection.culling());
        lodFrustum.prepare(camera.x, camera.y, camera.z);

        long considered = 0L;
        long rendered = 0L;
        long coverageSkipped = 0L;
        long authoritativeSkipped = 0L;
        long frameCoverageResolves = 0L;
        long frameDrawCalls = 0L;
        long frameQuads = 0L;
        long frameCoverageNanos = 0L;
        int minSampleY = Integer.MAX_VALUE;
        int maxSampleY = Integer.MIN_VALUE;
        List<Draw> draws = new ArrayList<>();
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // drop errors raised before this pass; they are not ours
            }
            if (irisPass == null) predictionTarget.beginOpaque(mainTarget);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(irisPass == null ? GL11.GL_GEQUAL : irisPass.depthFunc());
            RenderSystem.disableCull();
            if (irisPass == null || !irisPass.translucent()) RenderSystem.disableBlend();
            RenderSystem.depthMask(true);

            List<PredictionTileManager.PredictionTile> visibleTiles = new ArrayList<>();
            for (PredictionTileManager.PredictionTile tile : tiles) {
                boolean inHorizon = tileWithinHorizon(tile, camera, lodFrustum);
                if (!inHorizon) {
                    culledTiles.incrementAndGet();
                    continue;
                }
                visibleTiles.add(tile);
                minSampleY = Math.min(minSampleY, tile.depthBound().minY());
                maxSampleY = Math.max(maxSampleY, tile.depthBound().maxY());
            }
            visibleTiles.sort(Comparator.comparingDouble(tile ->
                    tileDistanceSquared(tile, camera)));
            if (irisPass == null || !irisPass.translucent()) {
                renderResidency.retain(snapshot);
                // Establish coverage before allocating small descendants. The
                // old same-key mesh remains resident until its upgrade uploads.
                var uploads = new ArrayList<>(visibleTiles);
                VssLodLayout uploadLayout = snapshot.layout();
                VssLodFocus uploadFocus = ClientPredictionState.currentFocus();
                uploads.sort(Comparator.comparingInt((PredictionTileManager.PredictionTile tile) -> PredictionWorkOrder.priority(
                                tile.key(), uploadLayout, PredictionWorkOrder.distanceSquared(tile.key(), uploadLayout,
                                        camera.x, camera.z), 0, false, uploadFocus))
                        .thenComparingInt(tile -> -tile.key().lod())
                        .thenComparingDouble(tile -> PredictionWorkOrder.orderingDistance(tile.key(), uploadLayout,
                                camera.x, camera.z, uploadFocus)));
                for (var tile : uploads) {
                    if (renderResidency.contains(tile) || tile.mesh().gpuPayload() == null) continue;
                    long bytes = (long) tile.mesh().gpuPayload().quads().length * Integer.BYTES;
                    if (!uploadBudget.allows(bytes)) break;
                    long start = System.nanoTime();
                    var gpu = gpuTiles.computeIfAbsent(tile.key(), PredictionGpuTile::new);
                    if (gpu.ensureMesh(tile)) meshUploads.incrementAndGet();
                    uploadBudget.record(bytes, System.nanoTime() - start);
                    renderResidency.uploaded(tile);
                }
            }
            snapshot = renderResidency.snapshot(snapshot);
            if (snapshot != prunedSnapshot) {
                pruneRenderCaches(snapshot.tiles().values());
                prunedSnapshot = snapshot;
            }
            visibleTiles.clear();
            for (var tile : snapshot.tiles().values()) if (tileWithinHorizon(tile, camera, lodFrustum)) visibleTiles.add(tile);
            visibleTiles.sort(Comparator.comparingDouble(tile -> tileDistanceSquared(tile, camera)));
            long nowNanos = System.nanoTime();
            ClientPredictionState.drainCoverageHot(level.dimension());
            CoverageView view = new CoverageView(playerChunkX, playerChunkZ,
                    pixelsPerBlock, ClientPredictionState.currentFocus());
            for (PredictionTileManager.PredictionTile tile : visibleTiles) {
                CachedCoverage cached = coverageCache.get(tile.key());
                long tileEpoch = snapshot.epoch(tile.key());
                boolean ownershipCurrent = cached != null && cached.matchesOwnership(
                        tile.revision(), tileEpoch, view);
                TileCoverage coverage = ownershipCurrent ? cached.coverage() : null;
                if (ownershipCurrent && cached.expiresAtNanos() > nowNanos) {
                    coverageCacheHits.incrementAndGet();
                } else if (!ownershipCurrent
                        || frameCoverageResolves < MAX_COVERAGE_RESOLVES_PER_FRAME) {
                    long coverageStart = System.nanoTime();
                    coverage = resolveCoverage(tile, playerChunkX, playerChunkZ, pixelsPerBlock, snapshot, view.focus());
                    long elapsed = System.nanoTime() - coverageStart;
                    frameCoverageNanos += elapsed;
                    coverageNanos.addAndGet(elapsed);
                    coverageResolves.incrementAndGet();
                    frameCoverageResolves++;
                    long expiry = coverageExpiry(tile, playerChunkX, playerChunkZ,
                            nearDistance, nowNanos);
                    coverageCache.put(tile.key(), new CachedCoverage(tile.revision(),
                            tileEpoch, expiry, view, coverage));
                }
                if (coverage == null || coverage.rendered() == 0L) {
                    continue;
                }
                considered += coverage.considered();
                rendered += coverage.rendered();
                coverageSkipped += coverage.coverageSkipped();
                authoritativeSkipped += coverage.authoritativeSkipped();
                PredictionGpuTile gpu = gpuTiles.get(tile.key());
                if (!gpu.drawable()) {
                    continue;
                }
                gpu.updateCoverage(coverage.allowed());
                draws.add(new Draw(tile, gpu, coverage.allowed()));
            }

            appendSeams(draws);
            if (!draws.isEmpty() && ensureProgram()) {
                long quadsBeforePass = submittedQuads.get();
                frameDrawCalls = irisPass == null ? drawPasses(minecraft, event, draws, camera, projection, false)
                        : drawIris(minecraft, event, draws, camera, projection);
                frameQuads = submittedQuads.get() - quadsBeforePass;
                if (irisPass == null) deferredWater = new DeferredWater(
                        new Frame(new Matrix4f(event.modelView()), new Matrix4f(event.projection()), camera),
                        projection, draws, level, mainTarget);
            }
            if (irisPass == null) {
                predictionTarget.restore(mainTarget);
                predictionTarget.writeDepth(mainTarget, projection);
            }
        } catch (Throwable failure) {
            deferredWater = null;
            dev.xantha.vss.common.VSSLogger.warn(
                    "VSS prediction pass disabled after a render error", failure);
            if (irisPass != null) throw new IllegalStateException("VSS Iris draw failed", failure);
            programBroken = true;
        } finally {
            if (irisPass == null) predictionTarget.restore(mainTarget);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
        renderFrames.incrementAndGet();
        seenTiles.addAndGet(tiles.size());
        consideredCells.addAndGet(considered);
        renderedCells.addAndGet(rendered);
        skippedCoverage.addAndGet(coverageSkipped);
        skippedAuthoritative.addAndGet(authoritativeSkipped);
        long now = System.nanoTime();
        if (VSSClientConfig.CONFIG.debugLogging && now - lastRenderLogNanos > 5_000_000_000L) {
            lastRenderLogNanos = now;
            dev.xantha.vss.common.VSSLogger.debug("VSS prediction render: tiles=" + draws.size()
                    + ", considered=" + considered
                    + ", rendered=" + rendered
                    + ", coverageSkipped=" + coverageSkipped
                    + ", authoritativeSkipped=" + authoritativeSkipped
                    + ", meshUploads=" + meshUploads.get()
                    + ", " + uploadBudget.diagnostics()
                    + ", drawCallsCurrentPass=" + frameDrawCalls
                    + ", quadsCurrentPass=" + frameQuads
                    + ", coverageResolves=" + frameCoverageResolves
                    + ", coverageMs=" + String.format(java.util.Locale.ROOT, "%.2f",
                    frameCoverageNanos / 1_000_000.0D)
                    + ", gpuTiles=" + gpuTiles.size()
                    + ", textures=" + textureDiagnostics(draws)
                    + ", memory={" + PredictionMemoryBudget.SHARED.diagnostics() + "}"
                    + ", buildThrottled=" + PredictionMemoryBudget.SHARED.exhausted()
                    + ", activeBuilds=" + PredictionMemoryBudget.SHARED.activeBuildCount()
                    + "/" + PredictionMemoryBudget.SHARED.buildLimit()
                    + ", surface={" + ClientPredictionState.surfaceDiagnostics(level.dimension()) + "}"
                    + ", sampleY=" + (minSampleY == Integer.MAX_VALUE ? "none"
                    : minSampleY + ".." + maxSampleY));
        }
    }

    private static volatile double selectionScale;
    private static volatile VssLodProjection.ViewRay viewRay;

    static VssLodProjection.ViewRay viewRay() { return viewRay; }

    private static void appendSeams(List<Draw> draws) {
        var surfaces = draws.stream().map(draw -> new PredictionLodSeams.Surface(draw.tile(), draw.allowed())).toList();
        appendSeamPatches(draws, lodSeams.update(surfaces), seamTiles);
        appendSeamPatches(draws, realSeams.update(surfaces, vanillaMask.groundEdges()), realSeamTiles);
    }

    private static void appendSeamPatches(List<Draw> draws, List<PredictionLodSeams.Patch> patches,
                                          Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> tiles) {
        var active = new HashSet<PredictionTileManager.PredictionTileKey>();
        for (var patch : patches) {
            var tile = patch.surface().tile();
            active.add(tile.key());
            var gpu = tiles.computeIfAbsent(tile.key(), PredictionGpuTile::new);
            gpu.ensureSeams(patch.mesh());
            gpu.updateCoverage(patch.surface().allowed());
            draws.add(new Draw(tile, gpu, patch.surface().allowed(), true));
        }
        tiles.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            retiredTiles.add(entry.getValue());
            return true;
        });
    }
    private static volatile boolean selectionScoping;

    static double selectionPixelsPerBlock(Minecraft minecraft) {
        double scale = selectionScale;
        if (scale > 0.0D && selectionScoping == minecraft.player.isScoping()) {
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

    private static boolean ensureProgram() {
        if (program != null) {
            program.use();
            return true;
        }
        try {
            program = PredictionTerrainProgram.create();
            program.use();
            return true;
        } catch (Throwable failure) {
            dev.xantha.vss.common.VSSLogger.error(
                    "VSS prediction terrain program failed to link", failure);
            programBroken = true;
            return false;
        }
    }

    /** Binds the frame-stable samplers and uniforms; returns false to degrade. */
    private static boolean bindFrame(Minecraft minecraft, Frame event,
                                     VssLodProjection.MatrixData projection, Vec3 camera) {
        int spriteRectId = -1;
        int atlasId = 0;
        try {
            PredictionMaterialPalette.refreshAtlas(minecraft.level);
            atlasId = minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getId();
            spriteRectId = VssLodSpriteTable.texture();
            detailPathEnabled = spriteRectId > 0 && VssLodSpriteTable.available();
        } catch (Throwable failure) {
            long now = System.nanoTime();
            if (VSSClientConfig.CONFIG.debugLogging
                    && now - lastSamplerErrorNanos > 5_000_000_000L) {
                lastSamplerErrorNanos = now;
                dev.xantha.vss.common.VSSLogger.debug(
                        "VSS prediction atlas sampler unavailable: " + failure);
            }
            detailPathEnabled = false;
        }
        boundSpriteTableId = spriteRectId;
        boolean detail = detailPathEnabled && spriteRectId != -1;
        int lightmapId = 0;
        minecraft.gameRenderer.lightTexture().turnOnLightLayer();
        Integer bound = RenderSystem.getShaderTexture(2);
        if (bound != null) {
            lightmapId = bound;
        }
        // turnOnLightLayer binds the light texture on the currently active
        // unit. Bind ALL material inputs afterwards, including the atlas.
        bindMaterialTextures(atlasId, lightmapId, spriteRectId);
        program.setSamplers(0, 1, 2, 3, 4);
        program.bindVanillaMask(vanillaMask, camera);
        program.bindMainDepth(irisPass == null ? minecraft.getMainRenderTarget().getDepthTextureId()
                : irisPass.depthTexture(), projection);
        if (irisPass == null) {
            var main = minecraft.getMainRenderTarget();
            program.bindVoxyDepth(PredictionVoxyDepth.current(main.frameBufferId, main.width, main.height, camera),
                    new Matrix4f(event.projection()).mul(event.modelView()));
        }
        NormalFog fog = normalFog(minecraft);
        float[] fogColor = RenderSystem.getShaderFogColor();
        program.setDirectionalLighting(minecraft.level);
        program.setFrame(fogColor, fog.start(), fog.end(), fog.density(),
                fog.hazeStart(), lightmapId != 0, 1.0F);
        // Keep rasterized depth and the main-depth comparison in agreement.
        program.setCamera(event.modelView(), irisPass == null ? projection.matrix() : event.projection());
        if (irisPass != null) irisPass.bindFrame(event);
        return detail;
    }

    record NormalFog(float start, float end, float hazeStart, float density) { }

    static boolean normalFogActive() { return !programBroken && !gpuTiles.isEmpty(); }

    static NormalFog normalFog(Minecraft minecraft) {
        var fog = VssLodFog.of(predictionHorizonBlocks(), VSSClientConfig.CONFIG.predictionFog, .55, .5, true);
        int handoff = Math.max(minecraft.options.renderDistance().get() * 16,
                handoffDistanceChunks(VSSClientNetworking.getEffectiveLodDistanceChunks()) * 16);
        float start = fog.haze() ? Math.max(fog.shaderStart(), handoff) : 10_000_000;
        return new NormalFog(start, fog.haze() ? Math.max(fog.shaderEnd(), start + 1) : 10_000_001,
                handoff, fog.haze() ? fog.aerialDensity() : 0);
    }

    static void bindMaterialTextures(int atlasId, int lightmapId, int spriteRectId) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        GlStateManager._bindTexture(Math.max(0, lightmapId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, lightmapId));
        RenderSystem.activeTexture(GL13.GL_TEXTURE2);
        GlStateManager._bindTexture(Math.max(0, spriteRectId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, spriteRectId));
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(Math.max(0, atlasId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, atlasId));
    }

    private static int handoffDistanceChunks(int nearDistance) {
        int voxyDistance = ModCompat.isVoxyLoaded()
                ? ModCompat.getVoxyViewDistanceChunks().orElse(nearDistance) : nearDistance;
        return Math.max(nearDistance, voxyDistance);
    }

    /** Binds the shared element buffer (two triangles per quad) and grows it. */
    private static void bindSharedIndices(int quadCount) {
        if (sharedVertexArray == -1) {
            sharedVertexArray = GL30.glGenVertexArrays();
            sharedIndexBuffer = GL15.glGenBuffers();
            sharedIndexQuads = 0;
        }
        if (quadCount > sharedIndexQuads) {
            int capacity = Math.max(4096, quadCount + quadCount / 2);
            java.nio.IntBuffer indices = MemoryUtil.memAllocInt(capacity * 6);
            for (int quad = 0; quad < capacity; quad++) {
                int base = quad * 4;
                indices.put(base).put(base + 1).put(base + 2)
                        .put(base).put(base + 2).put(base + 3);
            }
            indices.flip();
            GL30.glBindVertexArray(sharedVertexArray);
            GL15.glBindBuffer(GL30.GL_ELEMENT_ARRAY_BUFFER, sharedIndexBuffer);
            GL15.glBufferData(GL30.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            MemoryUtil.memFree(indices);
            sharedIndexQuads = capacity;
        }
        GL30.glBindVertexArray(sharedVertexArray);
    }

    private static AABB tileBounds(PredictionTileManager.PredictionTile tile) {
        return new AABB(tile.baseBlockX(), tile.depthBound().minY(), tile.baseBlockZ(),
                tile.baseBlockX() + tile.spanBlocks(), tile.depthBound().maxY(),
                tile.baseBlockZ() + tile.spanBlocks());
    }

    private static String textureDiagnostics(List<Draw> draws) {
        int[] spacing = new int[4];
        long withSprite = 0;
        long flat = 0;
        for (Draw draw : draws) {
            int step = draw.tile().spacingBlocks();
            spacing[step <= 1 ? 0 : step <= 2 ? 1 : step <= 4 ? 2 : 3]++;
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            withSprite += packed.spriteQuadCount();
            flat += packed.quadCount() - packed.spriteQuadCount();
        }
        return "{enabled=" + detailPathEnabled + ",tableId=" + boundSpriteTableId
                + ",useAverage=" + !detailPathEnabled
                + ",scoping=" + selectionScoping + ",selectionScale=" + selectionScale
                + ",table=" + VssLodSpriteTable.diagnostics()
                + ",tilesAt1/2/4/8+=" + java.util.Arrays.toString(spacing)
                + ",spriteQuads=" + withSprite + ",flatQuads=" + flat + "}";
    }

    /**
     * The two GPU passes.  Opaque terrain draws every visible face group of
     * every tile front-to-back; water redraws the fluid ranges back-to-front
     * with blending on.  All vertex data comes from the bound texture buffers
     * -- the element buffer only exists so {@code gl_VertexID & 3} walks four
     * corners per quad.
     */
    private static void renderDeferredWater() {
        DeferredWater pending = deferredWater;
        deferredWater = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (pending == null || pending.level() != minecraft.level
                || pending.target() != minecraft.getMainRenderTarget()) return;
        try (var state = new PredictionIrisBridge.State(8)) {
            predictionTarget.beginWater();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_GEQUAL);
            RenderSystem.disableCull();
            if (ensureProgram()) drawPasses(minecraft, pending.frame(), pending.draws(),
                    pending.frame().camera(), pending.projection(), true);
        } catch (Throwable failure) {
            programBroken = true;
            dev.xantha.vss.common.VSSLogger.warn("VSS prediction water disabled after a render error", failure);
        }
    }

    private static long drawPasses(Minecraft minecraft, Frame event,
                                   List<Draw> draws, Vec3 camera,
                                   VssLodProjection.MatrixData projection, boolean water) {
        // All tiles in a frame share one validated binding. Worker-side
        // material discovery must not switch fallback modes halfway through a pass.
        boolean useAverage = !bindFrame(minecraft, event, projection, camera);
        // Fabulous renders vanilla translucency into a separate depth target.
        // It contains the copied opaque depth plus the water/glass just drawn.
        if (water && Minecraft.useShaderTransparency()) {
            RenderTarget translucent = minecraft.levelRenderer.getTranslucentTarget();
            if (translucent != null) {
                program.bindMainDepth(translucent.getDepthTextureId(), projection);
                var main = minecraft.getMainRenderTarget();
                program.bindVoxyDepth(PredictionVoxyDepth.current(main.frameBufferId, main.width, main.height, camera),
                        new Matrix4f(event.projection()).mul(event.modelView()));
            }
        }
        int maxQuads = 0;
        for (Draw draw : draws) {
            maxQuads = Math.max(maxQuads, draw.gpu().quadCount());
        }
        if (maxQuads <= 0) {
            GlProgram.unuse();
            return 0L;
        }
        bindSharedIndices(maxQuads);
        long calls = 0L;
        long quads = 0L;
        try {
            RenderSystem.depthMask(!water);
            program.setOpaqueAlpha(water ? 0.0F : 1.0F);
            if (water) {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            } else RenderSystem.disableBlend();
            for (int index = 0; index < draws.size(); index++) {
                Draw draw = draws.get(water ? draws.size() - 1 - index : index);
                PredictionPackedMesh packed = draw.gpu().packed();
                if (packed == null) continue;
                draw.gpu().bindQuad(4);
                draw.gpu().bindYield(3);
                program.setTile((float) (draw.tile().baseBlockX() - camera.x), (float) -camera.y,
                        (float) (draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(),
                        packed.cellAxis(), useAverage);
                int visible = draw.seam() ? VssLodFaceGroup.ALL
                        : VssLodFaceGroup.visibleMask(tileBounds(draw.tile()), camera);
                for (int group = 0; group < VssLodFaceGroup.COUNT; group++) {
                    int count = water ? packed.waterRangeCount(group) : packed.terrainRangeCount(group);
                    if (count == 0 || (visible & (1 << group)) == 0) continue;
                    int first = water ? packed.waterRangeFirst(group) : packed.terrainRangeFirst(group);
                    GL11.glDrawElements(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_INT, (long) first * 24);
                    calls++;
                    quads += count;
                }
            }
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        } finally {
            GL30.glBindVertexArray(0);
            PredictionVanillaMask.unbind(6);
            RenderSystem.activeTexture(GL13.GL_TEXTURE5);
            RenderSystem.bindTexture(0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE7);
            RenderSystem.bindTexture(0);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            GlProgram.unuse();
        }
        drawCalls.addAndGet(calls);
        submittedQuads.addAndGet(quads);
        return calls;
    }

    private static long drawIris(Minecraft minecraft, Frame event, List<Draw> draws,
                                 Vec3 camera, VssLodProjection.MatrixData projection) {
        boolean useAverage = !bindFrame(minecraft, event, projection, camera);
        boolean water = irisPass.translucent();
        int maxQuads = draws.stream().mapToInt(draw -> draw.gpu().quadCount()).max().orElse(0);
        if (maxQuads == 0) return 0;
        bindSharedIndices(maxQuads);
        program.setOpaqueAlpha(water ? 0.0F : 1.0F);
        RenderSystem.depthMask(true);
        long calls = 0;
        for (int i = 0; i < draws.size(); i++) {
            Draw draw = draws.get(water ? draws.size() - 1 - i : i);
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            draw.gpu().bindQuad(4);
            draw.gpu().bindYield(3);
            program.setTile((float) (draw.tile().baseBlockX() - camera.x), (float) -camera.y,
                    (float) (draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(),
                    packed.cellAxis(), useAverage);
            int visible = draw.seam() ? VssLodFaceGroup.ALL : VssLodFaceGroup.visibleMask(tileBounds(draw.tile()), camera);
            for (int group = 0; group < VssLodFaceGroup.COUNT; group++) {
                int count = water ? packed.waterRangeCount(group) : packed.terrainRangeCount(group);
                if (count == 0 || (visible & (1 << group)) == 0) continue;
                int first = water ? packed.waterRangeFirst(group) : packed.terrainRangeFirst(group);
                GL11.glDrawElements(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_INT, (long) first * 24);
                calls++;
                submittedQuads.addAndGet(count);
            }
        }
        drawCalls.addAndGet(calls);
        return calls;
    }

    private static void pruneRenderCaches(Collection<PredictionTileManager.PredictionTile> tiles) {
        Set<PredictionTileManager.PredictionTileKey> active = new HashSet<>();
        for (PredictionTileManager.PredictionTile tile : tiles) {
            active.add(tile.key());
        }
        gpuTiles.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            retiredTiles.add(entry.getValue());
            return true;
        });
        coverageCache.keySet().removeIf(key -> !active.contains(key));
    }

    private static long coverageExpiry(PredictionTileManager.PredictionTile tile,
                                       int playerChunkX, int playerChunkZ,
                                       int nearDistance, long nowNanos) {
        int minChunkX = Math.floorDiv(tile.baseBlockX(), 16);
        int minChunkZ = Math.floorDiv(tile.baseBlockZ(), 16);
        int maxChunkX = Math.floorDiv(tile.baseBlockX() + tile.spanBlocks() - 1, 16);
        int maxChunkZ = Math.floorDiv(tile.baseBlockZ() + tile.spanBlocks() - 1, 16);
        int dx = playerChunkX < minChunkX ? minChunkX - playerChunkX
                : playerChunkX > maxChunkX ? playerChunkX - maxChunkX : 0;
        int dz = playerChunkZ < minChunkZ ? minChunkZ - playerChunkZ
                : playerChunkZ > maxChunkZ ? playerChunkZ - maxChunkZ : 0;
        if (Math.max(dx, dz) > nearDistance) {
            // Residency epochs invalidate handoffs immediately; periodic
            // distant checks can run less often than near-field checks.
            return nowNanos + 2_000_000_000L;
        }
        return nowNanos + COVERAGE_RECHECK_NANOS;
    }

    private static double tileDistanceSquared(PredictionTileManager.PredictionTile tile, Vec3 camera) {
        double centerX = tile.baseBlockX() + tile.spanBlocks() * 0.5D;
        double centerZ = tile.baseBlockZ() + tile.spanBlocks() * 0.5D;
        double dx = centerX - camera.x;
        double dz = centerZ - camera.z;
        return dx * dx + dz * dz;
    }

    static Long2ByteOpenHashMap newDecisionCache() {
        Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
        cache.defaultReturnValue(UNKNOWN_DECISION);
        return cache;
    }

    private static Long2ByteOpenHashMap decisionCacheFor(int playerChunkX,
                                                      int playerChunkZ,
                                                      double pixelsPerBlock,
                                                      VssLodFocus focus) {
        long pixelsBits = Double.doubleToLongBits(pixelsPerBlock);
        double threshold = pixelsPerQuad();
        if (decisionCachePixelsBits == pixelsBits
                && decisionCachePixelThreshold == threshold
                && decisionCachePlayerChunkX == playerChunkX
                && decisionCachePlayerChunkZ == playerChunkZ
                && java.util.Objects.equals(decisionFocus, focus)
                && System.nanoTime() < decisionCacheExpiresAt) {
            return chunkDecisionCache;
        }
        chunkDecisionCache.clear();
        decisionCachePixelsBits = pixelsBits;
        decisionCachePixelThreshold = threshold;
        decisionCachePlayerChunkX = playerChunkX;
        decisionCachePlayerChunkZ = playerChunkZ;
        decisionFocus = focus;
        decisionCacheExpiresAt = System.nanoTime() + COVERAGE_RECHECK_NANOS;
        return chunkDecisionCache;
    }

    private static TileCoverage resolveCoverage(PredictionTileManager.PredictionTile tile,
                                                int playerChunkX, int playerChunkZ,
                                                double pixelsPerBlock, PredictionTileManager.RenderSnapshot snapshot,
                                                VssLodFocus focus) {
        PredictionMesh mesh = tile.mesh();
        int stepBlocks = tile.spacingBlocks();
        int baseBlockX = tile.baseBlockX();
        int baseBlockZ = tile.baseBlockZ();
        long considered = (long) mesh.cellAxis() * mesh.cellAxis();
        long coverageSkipped = 0L;
        long authoritativeSkipped = 0L;
        int cellAxis = mesh.cellAxis();
        boolean[] allowed = new boolean[cellAxis * cellAxis];
        int groupAxis = Math.max(1, 16 / stepBlocks);
        // This mask selects among prediction tiles only. Vanilla/Voxy
        // occlusion is resolved per fragment against actual rendered depth.
        Long2ByteOpenHashMap decisions = decisionCacheFor(playerChunkX, playerChunkZ,
                pixelsPerBlock, focus);
        for (int z = 0; z < cellAxis; z += groupAxis) {
            for (int x = 0; x < cellAxis; x += groupAxis) {
                int endX = Math.min(cellAxis, x + groupAxis), endZ = Math.min(cellAxis, z + groupAxis);
                int cellChunkX = Math.floorDiv(baseBlockX + x * stepBlocks + stepBlocks / 2, 16);
                int cellChunkZ = Math.floorDiv(baseBlockZ + z * stepBlocks + stepBlocks / 2, 16);
                long chunkKey = (long) cellChunkX << 32 | cellChunkZ & 0xFFFFFFFFL;
                byte shared = decisions.get(chunkKey);
                byte desiredLod;
                if (shared != UNKNOWN_DECISION) {
                    // Selection depends on position, projection and focus.
                    desiredLod = shared;
                } else {
                    double dx = cellChunkX - playerChunkX;
                    double dz = cellChunkZ - playerChunkZ;
                    double chunkDistanceSquared = dx * dx + dz * dz;
                    double distanceBlocks = Math.max(64.0D, Math.sqrt(chunkDistanceSquared) * 16.0D);
                    desiredLod = (byte) lodForBlocks(distanceBlocks, pixelsPerBlock,
                            pixelsPerQuad());
                    if (focus != null) {
                        int cellCenterX = (cellChunkX << 4) + 8;
                        int cellCenterZ = (cellChunkZ << 4) + 8;
                        if (focus.contains(cellCenterX, cellCenterZ)) {
                            int floor = lodForBlocks(distanceBlocks,
                                    focus.selectionScale(pixelsPerBlock), pixelsPerQuad());
                            desiredLod = (byte) Math.min(desiredLod, floor);
                        }
                        VssLodFocus patch = PredictionWorkOrder.surfaceFocus(focus);
                        if (patch.intersects(cellChunkX * 16D, cellChunkZ * 16D,
                                (cellChunkX + 1D) * 16, (cellChunkZ + 1D) * 16)) desiredLod = 0;
                    }
                    if (decisions.size() > 96_000) {
                        decisions.clear();
                    }
                    decisions.put(chunkKey, desiredLod);
                }
                byte decision;
                PredictionTileManager.PredictionTile cover = snapshot.coveringTile(cellChunkX, cellChunkZ, desiredLod);
                if (cover == tile || (cover == null && desiredLod >= tile.key().lod())) {
                    decision = 0;
                } else {
                    decision = 2;
                }
                if (decision == 2) {
                    coverageSkipped += (long) (endX - x) * (endZ - z);
                    continue;
                }
                for (int row = z; row < endZ; row++) java.util.Arrays.fill(allowed,
                        row * cellAxis + x, row * cellAxis + endX, true);
            }
        }
        long rendered = considered - coverageSkipped - authoritativeSkipped;
        return new TileCoverage(allowed, considered, rendered,
                coverageSkipped, authoritativeSkipped);
    }

    public static String diagnostics() {
        return "frames=" + renderFrames.get()
                + ",frameStarts=" + frameStarts.get()
                + "," + uploadBudget.diagnostics()
                + ",tiles=" + seenTiles.get()
                + ",considered=" + consideredCells.get()
                + ",rendered=" + renderedCells.get()
                + ",coverageSkipped=" + skippedCoverage.get()
                + ",authoritativeSkipped=" + skippedAuthoritative.get()
                + ",meshUploads=" + meshUploads.get()
                + ",drawCalls=" + drawCalls.get()
                + ",quads=" + submittedQuads.get()
                + ",gpuTiles=" + gpuTiles.size()
                + ",coverageCacheHits=" + coverageCacheHits.get()
                + ",coverageResolves=" + coverageResolves.get()
                + ",coverageMs=" + String.format(java.util.Locale.ROOT, "%.1f",
                coverageNanos.get() / 1_000_000.0D)
                + ",detailPath=" + detailPathEnabled
                + ",spriteTableId=" + boundSpriteTableId
                + ",spriteTable=" + VssLodSpriteTable.diagnostics();
    }

    /** Drops all GPU caches; called on world change and resource reload. */
    public static void resetOcclusion() {
        deferredWater = null;
        selectionScale = 0.0D;
        viewRay = null;
        PredictionVoxyDepth.clear();
        renderResidency.clear();
        prunedSnapshot = null;
        lodSeams.clear();
        retiredTiles.addAll(seamTiles.values());
        seamTiles.clear();
        realSeams.clear();
        retiredTiles.addAll(realSeamTiles.values());
        realSeamTiles.clear();
        uploadBudget.reset();
        vanillaMask.invalidate();
        coverageCache.clear();
        List<PredictionGpuTile> released = new ArrayList<>(gpuTiles.values());
        gpuTiles.clear();
        retiredTiles.addAll(released);
    }

    private static final java.util.Queue<PredictionGpuTile> retiredTiles = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void drainRetiredTiles() {
        long until = System.nanoTime() + 1_000_000L;
        for (int i = 0; i < 8; i++) {
            PredictionGpuTile tile = retiredTiles.poll();
            if (tile == null) break;
            tile.close();
            if (System.nanoTime() >= until) break;
        }
    }

    private static boolean tileWithinHorizon(PredictionTileManager.PredictionTile tile, Vec3 camera,
                                             Frustum frustum) {
        double horizon = predictionHorizonBlocks();
        double minX = tile.baseBlockX();
        double minZ = tile.baseBlockZ();
        double maxX = minX + tile.spanBlocks();
        double maxZ = minZ + tile.spanBlocks();
        if (frustum != null) {
            AABB bounds = new AABB(minX, tile.depthBound().minY(), minZ,
                    maxX, tile.depthBound().maxY(), maxZ).inflate(2.0D);
            if (!frustum.isVisible(bounds)) return false;
        }
        double dx = camera.x < minX ? minX - camera.x : camera.x > maxX ? camera.x - maxX : 0.0D;
        double dz = camera.z < minZ ? minZ - camera.z : camera.z > maxZ ? camera.z - maxZ : 0.0D;
        double margin = Math.sqrt(2.0D) * tile.spanBlocks() * 0.5D;
        return dx * dx + dz * dz <= (horizon + margin) * (horizon + margin);
    }

    private static int predictionHorizonBlocks() {
        return Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
    }

    record TileCoverage(boolean[] allowed, long considered, long rendered,
                                long coverageSkipped, long authoritativeSkipped) {
    }

    private record Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu,
                        boolean[] allowed, boolean seam) {
        Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu, boolean[] allowed) {
            this(tile, gpu, allowed, false);
        }
    }

    record CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock,
                        VssLodFocus focus, double pixelsPerQuad) {
        CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock, VssLodFocus focus) {
            this(playerChunkX, playerChunkZ, pixelsPerBlock, focus, PredictionRenderer.pixelsPerQuad());
        }
    }

    record CachedCoverage(long tileRevision, long tileEpoch, long expiresAtNanos,
                                  CoverageView view,
                                  TileCoverage coverage) {
        boolean matchesOwnership(long nextTileRevision, long nextTileEpoch, CoverageView nextView) {
            return tileRevision == nextTileRevision
                    && tileEpoch == nextTileEpoch
                    && view.equals(nextView);
        }
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

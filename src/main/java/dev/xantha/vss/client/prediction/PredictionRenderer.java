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
    private static final PredictionEdgeFilter edgeFilter = new PredictionEdgeFilter();
    private static boolean resetEdgeFilter;
    private static final PredictionVanillaMask vanillaMask = new PredictionVanillaMask();
    private static final PredictionExactCoverageMask exactMask = new PredictionExactCoverageMask();
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
    private static final AtomicLong groupedDrawCalls = new AtomicLong();
    private static final java.nio.IntBuffer rangeCounts = org.lwjgl.BufferUtils.createIntBuffer(VssLodFaceGroup.COUNT);
    private static final org.lwjgl.PointerBuffer rangeOffsets = org.lwjgl.BufferUtils.createPointerBuffer(VssLodFaceGroup.COUNT);
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
    private static final AtomicLong planGeneration = new AtomicLong();
    private static final AtomicLong preparedPlans = new AtomicLong(), reusedPlans = new AtomicLong();
    record PreparedFrame(List<Draw> draws) { }
    private record PlanRequest(PredictionFramePlan<PreparedFrame> cache, Object viewport, int frame) { }
    private record DeferredWater(Frame frame, VssLodProjection.MatrixData projection,
                                 List<Draw> draws, ClientLevel level, RenderTarget target) { }
    record Frame(Matrix4f modelView, Matrix4f projection, Vec3 camera) { }
    private static int sharedVertexArray = -1;
    private static int sharedIndexBuffer = -1;
    private static int sharedIndexQuads;
    private static volatile boolean resetQuadPool;

    private static final Map<PredictionTileManager.PredictionTileKey, PredictionGpuTile> gpuTiles =
            new ConcurrentHashMap<>();
    private static final Map<PredictionTileManager.PredictionTileKey, CachedCoverage> coverageCache =
            new ConcurrentHashMap<>();
    private static final PredictionRenderResidency renderResidency = new PredictionRenderResidency();
    private static final Scene scene = new Scene();
    private static final PredictionLodSeams lodSeams = new PredictionLodSeams();
    private static final SeamBatch seamBatch = new SeamBatch();
    private static final PredictionRealBoundarySeams realSeams = new PredictionRealBoundarySeams();
    private static final SeamBatch realSeamBatch = new SeamBatch();
    private static final PredictionUploadBudget uploadBudget = new PredictionUploadBudget();
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
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            boolean active = VSSClientConfig.CONFIG.enablePrediction && VSSClientConfig.CONFIG.predictionAntialiasing
                    && !programBroken && !PredictionIrisBridge.shadersActive() && normalFogActive();
            if (resetEdgeFilter || !active) { edgeFilter.close(); resetEdgeFilter = false; }
            if (active) {
                long start = PredictionRenderTimings.start();
                int query = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.ANTIALIAS);
                try { edgeFilter.render(Minecraft.getInstance().getMainRenderTarget(), event.getProjectionMatrix()); }
                finally {
                    PredictionRenderTimings.gpuEnd(query);
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.ANTIALIAS, start);
                }
            }
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS)
            PredictionRenderCapture.terrainComplete();
        if (PredictionRenderCapture.active() && !PredictionIrisBridge.shadersActive()) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES)
                PredictionRenderCapture.checkpoint("vanilla-after-entity-submit");
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                PredictionRenderCapture.checkpoint("vanilla-after-level");
                PredictionRenderCapture.finish("vanilla-after-level");
            }
        }
        if (!VSSClientConfig.CONFIG.enablePrediction || programBroken || PredictionIrisBridge.shadersActive()) {
            return;
        }
        // Terrain supplies the background before vanilla translucent blocks.
        // Water waits for their depth so it cannot tint real water a second time.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            renderStage(new Frame(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition()));
            PredictionRenderCapture.current("vanilla-after-cutout");
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            renderDeferredWater();
            PredictionRenderCapture.current("vanilla-after-translucent");
        }
    }

    /** Forwarded once per frame by the registered VSSClientNetworking subscriber. */
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        PredictionRenderCapture.beginFrame();
        PredictionFramePace.recordFrame();
        PredictionRenderTimings.frame(frameStarts.incrementAndGet());
        PredictionIrisBridge.beginFrame();
        deferredWater = null;
        PredictionVoxyDepth.clear();
        uploadBudget.reset();
        drainRetiredTiles();
    }

    static void renderIris(Frame frame, PredictionIrisBridge.Pass pass,
                           PredictionTileManager.RenderSnapshot snapshot,
                           PredictionFramePlan<PreparedFrame> cache, Object viewport, int frameId) {
        if (!VSSClientConfig.CONFIG.enablePrediction) return;
        PredictionTerrainProgram previous = program;
        irisPass = pass;
        program = pass.program();
        try { renderStage(frame, snapshot, new PlanRequest(cache, viewport, frameId)); }
        finally { irisPass = null; program = previous; }
    }

    private static void renderStage(Frame event) {
        ClientLevel level = Minecraft.getInstance().level;
        renderStage(event, level == null ? null : ClientPredictionState.renderSnapshot(level.dimension()));
    }

    private static void renderStage(Frame event, PredictionTileManager.RenderSnapshot snapshot) {
        renderStage(event, snapshot, null);
    }

    private static void renderStage(Frame event, PredictionTileManager.RenderSnapshot snapshot, PlanRequest request) {
        try (var unpack = PredictionPixelUnpack.begin()) {
            renderStageUnpacked(event, snapshot, request);
        }
    }

    private static void renderStageUnpacked(Frame event, PredictionTileManager.RenderSnapshot snapshot, PlanRequest request) {
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

        var sourceSnapshot = snapshot;
        PreparedFrame prepared = request != null && irisPass.translucent()
                ? request.cache().get(request.viewport(), level, snapshot, frameStarts.get(), planGeneration.get(),
                    request.frame(), irisPass.width(), irisPass.height(), event) : null;
        long prepareStart = PredictionRenderTimings.start();
        Vec3 camera = event.camera();
        if (prepared == null) vanillaMask.update(minecraft, camera);
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
        List<Draw> draws = List.of();
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // drop errors raised before this pass; they are not ours
            }
            // Uploads must use a captured local unit, not an arbitrary Voxy pack unit.
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
            if (irisPass == null) predictionTarget.beginOpaque(mainTarget);
            PredictionGlState.enableDepthTest();
            PredictionGlState.depthFunc(irisPass == null ? GL11.GL_GEQUAL : irisPass.depthFunc());
            PredictionGlState.disableCull();
            if (irisPass == null || !irisPass.translucent()) PredictionGlState.disableBlend();
            PredictionGlState.depthMask(true);

            if (prepared == null) {
                // Any preparation can replace shared GPU resources, including an interleaved view.
                planGeneration.incrementAndGet();
                List<PredictionTileManager.PredictionTile> visibleTiles = new ArrayList<>();
                renderResidency.retain(snapshot);
                for (PredictionTileManager.PredictionTile tile : renderResidency.pendingUploads(snapshot)) {
                    boolean inHorizon = tileWithinHorizon(tile, camera, lodFrustum);
                    if (!inHorizon) {
                        culledTiles.incrementAndGet();
                        continue;
                    }
                    visibleTiles.add(tile);
                    minSampleY = Math.min(minSampleY, tile.depthBound().minY());
                    maxSampleY = Math.max(maxSampleY, tile.depthBound().maxY());
                }
                if (irisPass == null || !irisPass.translucent()) {
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
                    int coldRequests = 0;
                    for (var tile : uploads) {
                        if (renderResidency.contains(tile) || tile.mesh().gpuPayload() == null) continue;
                        long bytes = tile.mesh().gpuPayload().uploadBytes();
                        if (!uploadBudget.allows(bytes)) break;
                        if (!tile.mesh().gpuPayload().uploadReady()) {
                            if (++coldRequests >= 4) break;
                            continue;
                        }
                        long start = System.nanoTime();
                        var gpu = gpuTiles.computeIfAbsent(tile.key(), PredictionGpuTile::new);
                        if (gpu.ensureMesh(tile)) meshUploads.incrementAndGet();
                        if (!gpu.hasMesh(tile)) continue;
                        uploadBudget.record(bytes, System.nanoTime() - start);
                        PredictionRenderTimings.end(PredictionRenderTimings.Stage.UPLOAD, prepareStart == 0 ? 0 : start);
                        renderResidency.uploaded(tile);
                    }
                }
                snapshot = renderResidency.snapshot(snapshot);
                var changes=renderResidency.drainChanges();
                for(var key:changes) if(!snapshot.tiles().containsKey(key)) {
                    var retired=gpuTiles.remove(key);if(retired!=null) retiredTiles.add(retired);
                    coverageCache.remove(key);
                }
                ClientPredictionState.drainCoverageHot(level.dimension());
                CoverageView view = new CoverageView(playerChunkX, playerChunkZ,
                        pixelsPerBlock, ClientPredictionState.currentFocus());
                draws=scene.prepare(snapshot,changes,view,camera,predictionHorizonBlocks(),nearDistance,
                        event.modelView(),projection.culling(),lodFrustum);
                considered=scene.considered;rendered=scene.rendered;
                coverageSkipped=scene.coverageSkipped;authoritativeSkipped=scene.authoritativeSkipped;
                minSampleY=scene.minY();maxSampleY=scene.maxY();
                frameCoverageResolves=scene.resolves;frameCoverageNanos=scene.resolveNanos;
                if (request != null && !irisPass.translucent()) request.cache().put(request.viewport(), level,
                        sourceSnapshot, frameStarts.get(), planGeneration.get(), request.frame(),
                        irisPass.width(), irisPass.height(), event, new PreparedFrame(draws));
                preparedPlans.incrementAndGet();
            } else {
                draws = prepared.draws();
                reusedPlans.incrementAndGet();
            }
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.PREPARE, prepareStart);
            if (!draws.isEmpty() && ensureProgram()) {
                long quadsBeforePass = submittedQuads.get();
                var timingStage = irisPass != null && irisPass.translucent()
                        ? PredictionRenderTimings.Stage.WATER : PredictionRenderTimings.Stage.OPAQUE;
                long drawStart = PredictionRenderTimings.start();
                int query = PredictionRenderTimings.gpuStart(timingStage);
                try {
                    frameDrawCalls = irisPass == null ? drawPasses(minecraft, event, draws, camera, projection, false)
                            : drawIris(minecraft, event, draws, camera, projection);
                } finally {
                    PredictionRenderTimings.gpuEnd(query);
                    PredictionRenderTimings.end(timingStage, drawStart);
                }
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
            PredictionGlState.depthFunc(GL11.GL_LEQUAL);
            PredictionGlState.depthMask(true);
            PredictionGlState.enableCull();
            PredictionGlState.disableBlend();
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
                    + ", preparedPlans=" + preparedPlans.get() + ", reusedPlans=" + reusedPlans.get()
                    + ", timings={" + PredictionRenderTimings.diagnostics() + "}"
                    + ", drawCallsCurrentPass=" + frameDrawCalls
                    + ", quadsCurrentPass=" + frameQuads
                    + ", coverageResolves=" + frameCoverageResolves
                    + ", coverageMs=" + String.format(java.util.Locale.ROOT, "%.2f",
                    frameCoverageNanos / 1_000_000.0D)
                    + ", wallIndex={" + lodSeams.diagnostics() + "}"
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

    private static void updateCoverage(PredictionGpuTile gpu, boolean[] allowed) {
        long start = System.nanoTime();
        int bytes = gpu.updatePublishedCoverage(allowed);
        if (bytes != 0) uploadBudget.recordRequired(bytes, System.nanoTime() - start);
    }

    /** Applies only changed patches; stable neighbors retain their Draw and GPU binding. */
    static final class SeamBatch {
        private final Map<PredictionTileManager.PredictionTileKey,PredictionGpuTile> tiles=new java.util.HashMap<>();
        private final Map<PredictionTileManager.PredictionTileKey,PredictionLodSeams.Patch> sources=new java.util.HashMap<>();
        private final Map<PredictionTileManager.PredictionTileKey,Draw> draws=new java.util.HashMap<>();
        private List<PredictionLodSeams.Patch> previous;
        private List<Draw> prepared=List.of();
        private long updates;
        long updates() { return updates; }

        Set<PredictionTileManager.PredictionTileKey> apply(PredictionLodSeams.Changes changes) {
            if(changes.patches().isEmpty() && changes.removed().isEmpty()) return Set.of();
            var changed=new HashSet<PredictionTileManager.PredictionTileKey>();
            for(var key:changes.removed()) {
                sources.remove(key);draws.remove(key);
                var gpu=tiles.remove(key);if(gpu!=null) retiredTiles.add(gpu);
                changed.add(key);
            }
            for(var entry:changes.patches().entrySet()) {
                var patch=entry.getValue();var key=entry.getKey();
                if(sources.get(key)==patch) continue;
                updates++;changed.add(key);sources.put(key,patch);
                var tile=patch.surface().tile();
                var gpu=tiles.computeIfAbsent(key,PredictionGpuTile::new);
                long start=System.nanoTime(),bytes=gpu.ensureSeams(patch.mesh());
                if(bytes!=0) uploadBudget.recordRequired(bytes,System.nanoTime()-start);
                updateCoverage(gpu,patch.surface().allowed());
                draws.put(key,new Draw(tile,gpu,patch.surface().allowed(),true,VssLodFaceGroup.ALL,0,patch.bounds()));
            }
            return changed;
        }

        List<Draw> prepare(List<PredictionLodSeams.Patch> patches) {
            if(previous==patches) return prepared;
            var upserts=new java.util.LinkedHashMap<PredictionTileManager.PredictionTileKey,PredictionLodSeams.Patch>();
            var removed=new HashSet<>(sources.keySet());
            for(var patch:patches) { removed.remove(patch.surface().tile().key());upserts.put(patch.surface().tile().key(),patch); }
            apply(new PredictionLodSeams.Changes(upserts,removed,Set.of(),Set.of()));
            var next=new ArrayList<Draw>();for(var patch:patches) next.add(draws.get(patch.surface().tile().key()));
            previous=patches;return prepared=List.copyOf(next);
        }
        void clear() {
            previous=null;prepared=List.of();retiredTiles.addAll(tiles.values());tiles.clear();sources.clear();draws.clear();
        }
    }

    private record DrawKey(PredictionTileManager.PredictionTileKey tile,int kind) { }

    /** World ownership changes independently of camera rotation and transparent passes. */
    static final class Scene {
        private final Map<PredictionTileManager.PredictionTileKey,PredictionRenderGeometry.Entry> geometry=new java.util.HashMap<>();
        private final PredictionSpatialIndex<PredictionTileManager.PredictionTileKey> spatial=new PredictionSpatialIndex<>();
        private final Map<PredictionTileManager.PredictionTileKey,Draw> terrain=new java.util.HashMap<>();
        private final Map<PredictionTileManager.PredictionTileKey,TileCoverage> totals=new java.util.HashMap<>();
        private final java.util.TreeMap<Integer,Integer> lower=new java.util.TreeMap<>(),upper=new java.util.TreeMap<>();
        private final PredictionVisiblePlan<DrawKey,Draw> visible=new PredictionVisiblePlan<>(Draw::bounds);
        private PredictionTileManager.RenderSnapshot snapshot;
        private CoverageView view;
        private Vec3 camera;
        private double horizon;
        private long considered,rendered,coverageSkipped,authoritativeSkipped,resolves,resolveNanos;
        private long worldVisits;
        long worldVisits() { return worldVisits; }
        long visibilityVisits() { return visible.visited(); }
        String diagnostics() { return "worldVisits="+worldVisits+",visibilityVisits="+visible.visited()
                +",orderEdits="+visible.orderEdits()+",listPublications="+visible.publications(); }

        List<Draw> prepare(PredictionTileManager.RenderSnapshot next,Set<PredictionTileManager.PredictionTileKey> changes,
                          CoverageView nextView,Vec3 nextCamera,double nextHorizon,int nearDistance,
                          Matrix4f modelView,Matrix4f projection,Frustum frustum) {
            resolves=0;resolveNanos=0;
            if(snapshot!=null && !snapshot.dimension().equals(next.dimension())) clear();
            boolean reset=snapshot==null || !snapshot.layout().equals(next.layout());
            boolean moved=!nextCamera.equals(camera) || nextHorizon!=horizon;
            if(!reset && !moved && changes.isEmpty() && java.util.Objects.equals(view,nextView)) {
                patches(realSeamBatch,realSeams.apply(lodSeams.index(),Set.of(),vanillaMask.groundEdges()),2);
                snapshot=next;
                return visible.select(nextCamera,modelView,projection,frustum);
            }
            var dirty=new HashSet<PredictionTileManager.PredictionTileKey>();
            if(reset) { dirty.addAll(geometry.keySet());dirty.addAll(next.tiles().keySet()); }
            else dirty.addAll(changes);
            for(var key:dirty) {
                var tile=next.tiles().get(key);
                if(tile==null) { geometry.remove(key);spatial.remove(key); }
                else {
                    var old=geometry.get(key);
                    if(old==null || old.tile()!=tile) geometry.put(key,new PredictionRenderGeometry.Entry(tile));
                    spatial.put(key,key);
                }
            }
            if(moved) { dirty.addAll(geometry.keySet());dirty.addAll(terrain.keySet()); }
            if(!java.util.Objects.equals(view,nextView)) {
                dirty.addAll(next.scopedFamilies());
                if(snapshot!=null) dirty.addAll(snapshot.scopedFamilies());
                focus(view==null?null:view.focus(),dirty);focus(nextView.focus(),dirty);
            }
            var replacements=new ArrayList<PredictionLodSeams.Surface>();
            var removals=new HashSet<PredictionTileManager.PredictionTileKey>();
            long hits=0,now=System.nanoTime();
            worldVisits+=dirty.size();
            for(var key:dirty) {
                var geo=geometry.get(key);var old=terrain.get(key);
                if(geo==null || !geo.visible(nextCamera,null,nextHorizon)) { remove(key,old,removals);continue; }
                var tile=geo.tile();
                var ownerView=nextView.ownership(next.scopeAffects(key));
                var cached=coverageCache.get(key);
                TileCoverage coverage;
                if(cached!=null && cached.matchesOwnership(tile.revision(),next.epoch(key),ownerView)) {
                    coverage=cached.coverage();hits++;
                } else {
                    long timingStart=PredictionRenderTimings.start(),start=System.nanoTime();
                    coverage=resolveCoverage(tile,nextView.playerChunkX(),nextView.playerChunkZ(),nextView.pixelsPerBlock(),next,nextView.focus());
                    if(cached!=null && java.util.Arrays.equals(cached.coverage().allowed(),coverage.allowed()))
                        coverage=new TileCoverage(cached.coverage().allowed(),coverage.considered(),coverage.rendered(),coverage.coverageSkipped(),coverage.authoritativeSkipped());
                    long elapsed=System.nanoTime()-start;resolves++;resolveNanos+=elapsed;
                    PredictionRenderTimings.end(PredictionRenderTimings.Stage.COVERAGE,timingStart);
                    coverageCache.put(key,new CachedCoverage(tile.revision(),next.epoch(key),
                            coverageExpiry(tile,nextView.playerChunkX(),nextView.playerChunkZ(),nearDistance,now),ownerView,coverage));
                }
                var gpu=gpuTiles.get(key);
                if(coverage.rendered()==0 || gpu==null || !gpu.drawable()) { remove(key,old,removals);continue; }
                int faces=VssLodFaceGroup.visibleMask(geo.bounds(),nextCamera);
                if(tile.mesh().gpuPayload()!=null && tile.mesh().gpuPayload().downFaces()) faces|=1<<VssLodFaceGroup.HORIZONTAL;
                float morph=coverage.rendered()!=coverage.considered() || PredictionWorkOrder.scoped(key,next.layout(),nextView.focus())?0:1;
                if(old!=null && old.tile()==tile && old.gpu()==gpu && old.allowed()==coverage.allowed() && old.faces()==faces && old.morph()==morph) continue;
                var draw=new Draw(tile,gpu,coverage.allowed(),false,faces,morph,geo.culling());
                terrain.put(key,draw);visible.put(new DrawKey(key,0),draw);
                statistics(key,old,draw,coverage);
                if(old==null || old.tile()!=tile || old.allowed()!=coverage.allowed()) {
                    updateCoverage(gpu,coverage.allowed());replacements.add(new PredictionLodSeams.Surface(tile,coverage.allowed()));
                }
            }
            coverageCacheHits.addAndGet(hits);coverageResolves.addAndGet(resolves);coverageNanos.addAndGet(resolveNanos);
            long seamStart=PredictionRenderTimings.start();
            var delta=lodSeams.apply(replacements,removals);
            for(var key:delta.boundaries()) {
                var draw=terrain.get(key);
                if(draw!=null) {
                    long start=System.nanoTime();int bytes=draw.gpu().updateBoundaryCoverage(lodSeams.boundaryMask(key));
                    if(bytes!=0) uploadBudget.recordRequired(bytes,System.nanoTime()-start);
                }
            }
            patches(seamBatch,delta,1);
            patches(realSeamBatch,realSeams.apply(lodSeams.index(),delta.boundaries(),vanillaMask.groundEdges()),2);
            PredictionRenderTimings.end(PredictionRenderTimings.Stage.SEAMS,seamStart);
            snapshot=next;view=nextView;camera=nextCamera;horizon=nextHorizon;
            return visible.select(nextCamera,modelView,projection,frustum);
        }

        private void focus(VssLodFocus focus,Set<PredictionTileManager.PredictionTileKey> dirty) {
            if(focus==null) return;
            double radius=Math.min(focus.radius(),PredictionWorkOrder.SCOPED_RADIUS_BLOCKS);
            spatial.intersect((long)Math.floor(focus.x()-radius),(long)Math.floor(focus.z()-radius),
                    (long)Math.ceil(focus.x()+radius)+1,(long)Math.ceil(focus.z()+radius)+1,dirty::add);
        }
        private void remove(PredictionTileManager.PredictionTileKey key,Draw old,Set<PredictionTileManager.PredictionTileKey> removals) {
            if(old==null) return;
            terrain.remove(key);visible.remove(new DrawKey(key,0));removals.add(key);statistics(key,old,null,null);
        }
        private void patches(SeamBatch batch,PredictionLodSeams.Changes delta,int kind) {
            for(var key:batch.apply(delta)) {
                var draw=batch.draws.get(key);
                if(draw==null) visible.remove(new DrawKey(key,kind));else visible.put(new DrawKey(key,kind),draw);
            }
        }
        private void statistics(PredictionTileManager.PredictionTileKey key,Draw old,Draw current,TileCoverage coverage) {
            var prior=totals.remove(key);
            if(prior!=null) { considered-=prior.considered();rendered-=prior.rendered();coverageSkipped-=prior.coverageSkipped();authoritativeSkipped-=prior.authoritativeSkipped(); }
            if(old!=null) { height(lower,old.tile().depthBound().minY(),-1);height(upper,old.tile().depthBound().maxY(),-1); }
            if(current!=null) {
                totals.put(key,coverage);considered+=coverage.considered();rendered+=coverage.rendered();coverageSkipped+=coverage.coverageSkipped();authoritativeSkipped+=coverage.authoritativeSkipped();
                height(lower,current.tile().depthBound().minY(),1);height(upper,current.tile().depthBound().maxY(),1);
            }
        }
        private static void height(java.util.TreeMap<Integer,Integer> values,int y,int change) {
            int count=values.getOrDefault(y,0)+change;if(count==0) values.remove(y);else values.put(y,count);
        }
        int minY() { return lower.isEmpty()?Integer.MAX_VALUE:lower.firstKey(); }
        int maxY() { return upper.isEmpty()?Integer.MIN_VALUE:upper.lastKey(); }
        void clear() {
            geometry.clear();spatial.clear();terrain.clear();totals.clear();lower.clear();upper.clear();visible.clear();
            snapshot=null;view=null;camera=null;considered=rendered=coverageSkipped=authoritativeSkipped=0;
            lodSeams.clear();realSeams.clear();seamBatch.clear();realSeamBatch.clear();
        }
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
        // Iris already installed the entity lightmap. Rebinding here changes
        // Minecraft texture caches and Iris deferred state inside a raw Voxy pass.
        if (irisPass == null) minecraft.gameRenderer.lightTexture().turnOnLightLayer();
        Integer bound = RenderSystem.getShaderTexture(2);
        if (bound != null) {
            lightmapId = bound;
        }
        // turnOnLightLayer binds the light texture on the currently active
        // unit. Bind ALL material inputs afterwards, including the atlas.
        bindMaterialTextures(atlasId, lightmapId, spriteRectId);
        program.setSamplers(0, 1, 2, 3, 4);
        program.bindVanillaMask(vanillaMask, camera);
        program.bindRealCoverage(minecraft.level.dimension(), camera);
        program.setRealRenderDistance(Math.max(vanillaMask.renderDistanceBlocks(),
                ModCompat.getVoxyViewDistanceChunks().orElse(0) * 16.0F));
        program.bindExactCoverage(exactMask, minecraft.level, camera, handoffDistanceChunks(VSSClientNetworking.getEffectiveLodDistanceChunks()));
        program.setHorizon(predictionHorizonBlocks());
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
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionAtlasSampler.bind(atlasId);
        return detail;
    }

    record NormalFog(float start, float end, float hazeStart, float density) { }

    static boolean normalFogActive() { return !programBroken && !gpuTiles.isEmpty(); }

    static NormalFog normalFog(Minecraft minecraft) {
        int vanillaDistance = minecraft.options.renderDistance().get() * 16;
        int realDistance = Math.max(vanillaDistance, ModCompat.getVoxyViewDistanceChunks().orElse(0) * 16);
        var fog = VssLodFog.shared(predictionHorizonBlocks(), realDistance, vanillaDistance,
                VSSClientConfig.CONFIG.predictionFog);
        return new NormalFog(fog.shaderStart(), fog.shaderEnd(), Math.min(vanillaDistance, fog.start()),
                fog.haze() ? fog.aerialDensity() : 0);
    }

    static void bindMaterialTextures(int atlasId, int lightmapId, int spriteRectId) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE1);
        PredictionGlState.bindTexture(Math.max(0, lightmapId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, lightmapId));
        PredictionGlState.activeTexture(GL13.GL_TEXTURE2);
        PredictionGlState.bindTexture(Math.max(0, spriteRectId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, spriteRectId));
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
        PredictionGlState.bindTexture(Math.max(0, atlasId));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, Math.max(0, atlasId));
    }

    private static int handoffDistanceChunks(int nearDistance) {
        int voxyDistance = ModCompat.isVoxyLoaded()
                ? ModCompat.getVoxyViewDistanceChunks().orElse(nearDistance) : nearDistance;
        return ClientPredictionState.coverageRadiusChunks(Math.max(nearDistance, voxyDistance));
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
        try (var state = new PredictionIrisBridge.State(PredictionExactCoverageMask.TEXTURE_UNITS, false)) {
            predictionTarget.beginWater();
            PredictionGlState.enableDepthTest();
            PredictionGlState.depthFunc(GL11.GL_GEQUAL);
            PredictionGlState.disableCull();
            long start = PredictionRenderTimings.start();
            int query = PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.WATER);
            try {
                if (ensureProgram()) drawPasses(minecraft, pending.frame(), pending.draws(),
                        pending.frame().camera(), pending.projection(), true);
            } finally {
                PredictionRenderTimings.gpuEnd(query);
                PredictionRenderTimings.end(PredictionRenderTimings.Stage.WATER, start);
            }
        } catch (Throwable failure) {
            programBroken = true;
            dev.xantha.vss.common.VSSLogger.warn("VSS prediction water disabled after a render error", failure);
        }
    }

    private static long drawPasses(Minecraft minecraft, Frame event,
                                   List<Draw> draws, Vec3 camera,
                                   VssLodProjection.MatrixData projection, boolean water) {
        int previousAtlasSampler = GL30.glGetIntegeri(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING, 0);
        PredictionTerrainProgram original = program;
        try {
            if (!batchProgramBroken && PredictionTerrainArena.supported() && batchWorthwhile(draws, water)) {
                try { if (batchProgram == null) batchProgram = PredictionTerrainProgram.createBatch(); }
                catch (RuntimeException unavailable) { batchProgramBroken = true; }
                if (batchProgram != null) { program = batchProgram; program.use(); }
            }
            return drawNormalPass(minecraft, event, draws, camera, projection, water);
        } finally { program = original; org.lwjgl.opengl.GL33.glBindSampler(0, previousAtlasSampler); }
    }

    /** Single-range calls are already cheap; avoid command assembly when it cannot amortize. */
    static boolean batchWorthwhile(List<Draw> draws, boolean water) {
        int tiles = 0, ranges = 0;
        for (Draw draw : draws) {
            var packed = draw.gpu().packed(); if (packed == null) continue;
            var plan = packed.drawRanges(water, draw.faces()); if (plan.quads == 0) continue;
            if (draw.gpu().arenaSlice() == null) return false;
            tiles++; ranges += plan.first.length;
        }
        return tiles >= 64 && ranges >= tiles + tiles / 4;
    }

    private static long drawNormalPass(Minecraft minecraft, Frame event,
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
        boolean batch = program.supportsBatch();
        if (batch) {
            if (indirectBatch == null) indirectBatch = new PredictionIndirectBatch();
            indirectBatch.begin(program);
        }
        long frameTime = System.nanoTime();
        try {
            PredictionGlState.depthMask(!water);
            program.setOpaqueAlpha(water ? 0.0F : 1.0F);
            if (water) {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                        GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            } else PredictionGlState.disableBlend();
            for (int index = 0; index < draws.size(); index++) {
                Draw draw = draws.get(water ? draws.size() - 1 - index : index);
                PredictionPackedMesh packed = draw.gpu().packed();
                if (packed == null) continue;
                var ranges=packed.drawRanges(water,draw.faces());
                if(ranges.quads==0) continue;
                if (batch && indirectBatch.add(draw, ranges, camera, water, useAverage, frameTime)) {
                    quads += ranges.quads;
                    continue;
                }
                draw.gpu().bindTerrain(program);
                draw.gpu().bindYield(3);
                program.setTile((float) (draw.tile().baseBlockX() - camera.x), (float) -camera.y,
                        (float) (draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(),
                        packed.cellAxis(), useAverage);
                if (!water && !draw.seam()) program.setMorph(packed, draw.morph() * draw.gpu().morphAmount(System.nanoTime()));
                program.setBoundaryReplacement(!water && !draw.seam());
                submitRanges(ranges);
                calls++;
                quads+=ranges.quads;
            }
        } finally {
            if (batch) calls += indirectBatch.end();
            PredictionGlState.depthMask(true);
            PredictionGlState.disableBlend();
            GL30.glBindVertexArray(0);
            PredictionVanillaMask.unbind(6);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE5);
            PredictionGlState.bindTexture(0);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE7);
            PredictionGlState.bindTexture(0);
            PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
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
        PredictionGlState.depthMask(true);
        long calls = 0;
        PredictionRenderCapture.current(water ? "water-bound" : "opaque-bound");
        for (int i = 0; i < draws.size(); i++) {
            Draw draw = draws.get(water ? draws.size() - 1 - i : i);
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            var ranges=packed.drawRanges(water,draw.faces());
            if(ranges.quads==0) continue;
            draw.gpu().bindTerrain(program);
            draw.gpu().bindYield(3);
            program.setTile((float) (draw.tile().baseBlockX() - camera.x), (float) -camera.y,
                    (float) (draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(),
                    packed.cellAxis(), useAverage);
            if (!water && !draw.seam()) program.setMorph(packed, draw.morph() * draw.gpu().morphAmount(System.nanoTime()));
            program.setBoundaryReplacement(!water && !draw.seam());
            submitRanges(ranges);
            calls++;
            submittedQuads.addAndGet(ranges.quads);
        }
        drawCalls.addAndGet(calls);
        return calls;
    }

    /** Same ordered indices and tile bindings, one GL entry per tile/pass.
     * GL 1.4 multidraw is below the renderer's existing GL requirement. */
    static void submitRanges(PredictionDrawRanges ranges) {
        if(ranges.first.length==1) {
            GL11.glDrawElements(GL11.GL_TRIANGLES,ranges.count[0]*6,GL11.GL_UNSIGNED_INT,(long)ranges.first[0]*24);
            return;
        }
        rangeCounts.clear();rangeOffsets.clear();
        for(int i=0;i<ranges.first.length;i++) {
            rangeCounts.put(ranges.count[i]*6);rangeOffsets.put((long)ranges.first[i]*24);
        }
        rangeCounts.flip();rangeOffsets.flip();
        org.lwjgl.opengl.GL14.glMultiDrawElements(GL11.GL_TRIANGLES,rangeCounts,GL11.GL_UNSIGNED_INT,rangeOffsets);
        groupedDrawCalls.incrementAndGet();
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
                    if (distanceBlocks < PredictionDetailBands.fineRadius(snapshot.layout().maxDistanceBlocks(), snapshot.dimension()))
                        desiredLod = 0;
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
                PredictionTileManager.PredictionTile cover = snapshot.coveringTileAtDetail(cellChunkX, cellChunkZ, desiredLod);
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
                + ",preparedPlans=" + preparedPlans.get() + ",reusedPlans=" + reusedPlans.get()
                + ",timings={" + PredictionRenderTimings.diagnostics() + "}"
                + "," + uploadBudget.diagnostics()
                + ",tiles=" + seenTiles.get()
                + ",considered=" + consideredCells.get()
                + ",rendered=" + renderedCells.get()
                + ",coverageSkipped=" + skippedCoverage.get()
                + ",authoritativeSkipped=" + skippedAuthoritative.get()
                + ",meshUploads=" + meshUploads.get()
                + ",meshRestore={" + PredictionMeshRestore.diagnostics() + "}"
                + "," + PredictionQuadBufferPool.SHARED.diagnostics()
                + "," + PredictionTerrainArena.SHARED.diagnostics()
                + ",drawCalls=" + drawCalls.get()
                + ",multiDrawCalls=" + groupedDrawCalls.get()
                + ",quads=" + submittedQuads.get()
                + ",wallIndex={" + lodSeams.diagnostics() + "}"
                + ",scene={" + scene.diagnostics() + "}"
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
        resetEdgeFilter = true;
        resetQuadPool = true;
        deferredWater = null;
        planGeneration.incrementAndGet();
        PredictionRenderTimings.reset();
        selectionScale = 0.0D;
        viewRay = null;
        PredictionVoxyDepth.clear();
        renderResidency.clear();
        PredictionMeshRestore.clear();
        scene.clear();
        uploadBudget.clear();
        vanillaMask.invalidate();
        exactMask.invalidate();
        coverageCache.clear();
        List<PredictionGpuTile> released = new ArrayList<>(gpuTiles.values());
        gpuTiles.clear();
        retiredTiles.addAll(released);
    }

    private static final java.util.Queue<PredictionGpuTile> retiredTiles = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static PredictionIndirectBatch indirectBatch;
    private static PredictionTerrainProgram batchProgram;
    private static boolean batchProgramBroken;

    private static void drainRetiredTiles() {
        if (resetQuadPool) { PredictionQuadBufferPool.SHARED.close(); resetQuadPool = false; }
        PredictionQuadBufferPool.SHARED.trim();
        if (PredictionTerrainArena.supported()) PredictionTerrainArena.SHARED.reap();
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

    record Draw(PredictionTileManager.PredictionTile tile, PredictionGpuTile gpu,
                        boolean[] allowed, boolean seam, int faces, float morph, AABB bounds) {
    }

    record CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock,
                        VssLodFocus focus, double pixelsPerQuad, int fineDistance) {
        private static final CoverageView ORDINARY = new CoverageView(0, 0, 0, null, 0, 0);
        CoverageView ownership(boolean scopedTiles) {
            // Ordinary ownership selects the finest resident data independently
            // of camera distance/FOV. Only telescope-only tiles depend on view.
            // Mesh revisions and GPU residency epochs still invalidate immediately.
            return scopedTiles ? this : ORDINARY;
        }
        CoverageView(int playerChunkX, int playerChunkZ, double pixelsPerBlock, VssLodFocus focus) {
            this(playerChunkX, playerChunkZ, pixelsPerBlock, focus, PredictionRenderer.pixelsPerQuad(),
                    VSSClientConfig.CONFIG.predictionFineDistanceBlocks);
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

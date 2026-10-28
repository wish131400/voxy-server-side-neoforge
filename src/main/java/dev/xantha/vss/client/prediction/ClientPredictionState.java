package dev.xantha.vss.client.prediction;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import dev.xantha.vss.api.VoxelColumnData;

/**
 * 客户端预测会话与维度管理器的所有者，同时维护真实列替换交接入口。
 * <p>网络状态从 ClientLodSession 查询；真实覆盖来自兼容层，不能在此复制握手或 Voxy 索引。
 * 客户端事件负责捕获世界数据，后台计算只消费安全快照；断线/重载关闭旧管理器，
 * GPU 资源的释放仍由渲染线程执行。
 */
public final class ClientPredictionState {
    private static Object resourceAtlasToken;
    private static final Map<ResourceKey<Level>, PredictionTileManager> MANAGERS = new ConcurrentHashMap<>();
    private static volatile long seed;
    private static volatile long revision;
    private static volatile boolean profileReady;
    private static volatile WorldgenProfileS2CPayload acceptedProfile;

    private static volatile PreparedProfile preparedProfile;
    private static boolean dimensionInitializing;
    private static RustWorldgenDocument.SharedInputs connectionInputs;
    private static final Set<ResourceKey<Level>> requestedDimensions = ConcurrentHashMap.newKeySet();
    private record PreparedProfile(ClientWorldgenProfileDecoder.Session decoder, long generation,
                                   PredictionCacheStorage storage) { }

    /** Immutable metadata already received from the server, for explicit reference export. */
    static WorldgenProfileS2CPayload referenceProfile() { return acceptedProfile; }
    private static final Map<CellKey, CellState> cellStates = new ConcurrentHashMap<>();
    private static final Map<CellKey, Long> requestTimes = new ConcurrentHashMap<>();
    private static final Map<CellKey, Long> exactAcceptedTimes = new ConcurrentHashMap<>();
    /**
     * Voxy's local index is deliberately queried outside the render loop's
     * hot path. Only positives are retained in sparse pages; a negative
     * result needs no per-column object. Unknown index state preserves positives.
     */
    private static final PredictionExactCoverageIndex exactCoverage = new PredictionExactCoverageIndex();
    /**
     * Version of confirmed column data used by the asynchronous shader mask.
     * Settled columns own their world-space region inside Voxy's render range.
     * Transient GPU traversal and node replacement do not change this index.
     */
    private static final AtomicLong exactCoverageRevision = new AtomicLong();
    private static final AtomicLong exactCoverageRemovalRevision = new AtomicLong();
    static long exactCoverageRemovalRevision() { return exactCoverageRemovalRevision.get(); }

    static void exactCoverageChanged(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        exactCoverageRevision.incrementAndGet();
    }

    static CompletableFuture<PredictionExactCoverageMask.Snapshot> exactCoverageSnapshot(
            ResourceKey<Level> dimension, int cx, int cz, int radius) {
        return CompletableFuture.supplyAsync(() -> exactCoverage.snapshot(dimension, cx, cz, radius,
                System.nanoTime()), PROFILE_DECODER);
    }

    static long exactCoverageDataRevision() { return exactCoverageRevision.get(); }

    static PredictionExactCoverageIndex.View planningCoverage(ResourceKey<Level> dimension, long now) {
        return exactCoverage.planningView(dimension, now);
    }
    /** Background discovery resumes within separate near/far time budgets. */
    private static final long SWEEP_INTERVAL_NANOS = 200_000_000L;
    private static final PredictionCoverageSweep coverageSweep = new PredictionCoverageSweep();
    private static final java.util.concurrent.atomic.AtomicBoolean sweepUpdating =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastSweepNanos;
    private static volatile long lastSweepLogNanos;
    private static volatile PredictionCoverageOffsets sweepOffsetsReference;
    private static long coverageRetainedAt;
    private static volatile int sweepRadiusChunks = -1;
    private static final long REQUEST_STATE_TIMEOUT_NANOS = 15_000_000_000L;
    private static final AtomicLong DECODE_GENERATION = new AtomicLong();
    private static final AtomicLong LEVEL_GENERATION = new AtomicLong();
    private static final ExecutorService PROFILE_DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vss-worldgen-profile");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private ClientPredictionState() {
    }

    private static final PredictionDimensionProfiles dimensionProfiles = new PredictionDimensionProfiles();

    public static void accept(WorldgenProfileS2CPayload payload) {
        // Revision checks apply even while a dimension is still being decoded.
        if (payload != null && acceptedProfile != null && payload.revision() < revision) return;
        if (payload != null && payload.formatVersion() == WorldgenProfileS2CPayload.FORMAT_VERSION
                && !payload.dimensions().isEmpty() && payload.registries().length != 0) dimensionProfiles.remember(payload);
        if (payload != null && payload.sameWorldgen(acceptedProfile)) {
            revision = Math.max(revision, payload.revision());
            return;
        }
        if (payload != null && profileReady && payload.revision() < revision) {
            return;
        }
        boolean reuseInputs = payload != null && acceptedProfile != null
                && payload.seed() == acceptedProfile.seed() && payload.revision() == acceptedProfile.revision();
        reset(reuseInputs);
        if (payload == null || payload.formatVersion() != WorldgenProfileS2CPayload.FORMAT_VERSION
                || payload.dimensions().isEmpty() || payload.registries().length == 0) {
            return;
        }
        seed = payload.seed();
        revision = payload.revision();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        RegistryAccess clientRegistries = minecraft.getConnection().registryAccess();
        acceptedProfile = payload;
        if (connectionInputs == null) connectionInputs = new RustWorldgenDocument.SharedInputs();
        var inputs = connectionInputs;
        long generation = DECODE_GENERATION.get();
        ResourceKey<Level> preferred = minecraft.level == null ? Level.OVERWORLD : minecraft.level.dimension();
        long receivedNanos = System.nanoTime();
        VSSLogger.info("VSS prediction profile accepted: dimensions=" + payload.dimensions().size()
                + ", revision=" + payload.revision() + ", first=" + preferred.location());
        CompletableFuture.runAsync(() -> {
            ClientWorldgenProfileDecoder.Session decoder = null;
            try {
                // Release the previous world's workers/native sampler before allocating a new registry snapshot.
                PredictionResources.awaitRetired();
                if (generation != DECODE_GENERATION.get()) return;
                decoder = ClientWorldgenProfileDecoder.prepare(payload, clientRegistries,
                        () -> generation == DECODE_GENERATION.get(), inputs);
                var preparedDecoder = decoder;
                minecraft.execute(() -> {
                    if (generation != DECODE_GENERATION.get()) {
                        PROFILE_DECODER.execute(preparedDecoder::close);
                        return;
                    }
                    // Login identity may arrive during decoding; resolve only after the session check.
                    preparedProfile = new PreparedProfile(preparedDecoder, generation, PredictionCacheStorage.current());
                    requestCurrentDimension(minecraft, receivedNanos);
                });
            } catch (java.util.concurrent.CancellationException ignored) {
                if (decoder != null) decoder.close();
            } catch (Exception failure) {
                if (decoder != null) decoder.close();
                if (generation == DECODE_GENERATION.get()) VSSLogger.error("VSS worldgen profile decode failed", failure);
            }
        }, PROFILE_DECODER);
    }

    /** Called on the client thread, including before the first manager is ready. */
    private static void requestCurrentDimension(Minecraft minecraft, long requestedNanos) {
        if (!VSSClientConfig.CONFIG.isPredictionLoadingEnabled() || !VoxyPredictionBridge.isLevelActive(minecraft.level)) return;
        ResourceKey<Level> key = minecraft.level.dimension();
        // Some modded transfers omit the server dimension-change event. Returning
        // to a visited dimension must not depend on another profile packet.
        var restored = dimensionProfiles.restore(key, acceptedProfile);
        if (restored != null) {
            VSSLogger.info("VSS restoring cached prediction profile for " + key.location());
            accept(restored);
            return;
        }
        PreparedProfile prepared = preparedProfile;
        if (prepared == null || dimensionInitializing || !PredictionDimensionProfiles.contains(acceptedProfile, key)) return;
        if (MANAGERS.containsKey(key) || !requestedDimensions.add(key)) return;
        long levelGeneration = LEVEL_GENERATION.get();
        java.util.function.BooleanSupplier active = () -> prepared.generation() == DECODE_GENERATION.get()
                && levelGeneration == LEVEL_GENERATION.get();
        dimensionInitializing = true;
        PROFILE_DECODER.execute(() -> {
            if (!active.getAsBoolean()) return;
            try {
                PredictionResources.awaitRetired();
                prepared.decoder().decode(key, active, (dimension, sampler) -> {
                    var timing = new PredictionInitializationTiming(dimension.location() + " install");
                    PredictionDiskCache cache = prepared.storage() == null ? null : prepared.storage().open(sampler);
                    PredictionTileManager manager;
                    try { manager = new PredictionTileManager(dimension, sampler, PredictionMemoryBudget.SHARED, cache); }
                    catch (RuntimeException | Error failure) {
                        if (cache != null) cache.close();
                        throw failure;
                    }
                    timing.mark("cacheAndManager");
                    long queued = System.nanoTime();
                    minecraft.execute(() -> {
                        if (!active.getAsBoolean()) { manager.close(); return; }
                        // A dimension switch during initialization must not install an unbounded queue of worlds.
                        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) {
                            manager.close(); requestedDimensions.remove(dimension); return;
                        }
                        var previous = MANAGERS.put(dimension, manager);
                        if (previous != null) previous.close();
                        manager.setPaused(!VSSClientConfig.CONFIG.isPredictionLoadingEnabled());
                        profileReady = true;
                        VSSLogger.info("VSS prediction dimension ready: dimension=" + dimension.location()
                                + ", rust=" + (sampler instanceof RustTerrainSampler)
                                + ", receivedToReadyMs=" + (System.nanoTime() - requestedNanos) / 1_000_000L);
                        if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction init installQueueMs="
                                + (System.nanoTime() - queued) / 1_000_000L + ", dimension=" + dimension.location());
                    });
                    timing.finish();
                });
            } catch (java.util.concurrent.CancellationException ignored) {
            } catch (Exception failure) {
                if (active.getAsBoolean())
                    VSSLogger.error("VSS prediction dimension initialization failed: " + key.location(), failure);
            } finally {
                minecraft.execute(() -> {
                    if (active.getAsBoolean()) dimensionInitializing = false;
                });
            }
        });
    }

    /** Extra detail is scoped; ordinary selection covers the whole camera neighbourhood. */
    static VssLodFocus pickViewFocus(ClientTerrainSampler sampler,
                                   net.minecraft.world.phys.Vec3 origin,
                                   net.minecraft.world.phys.Vec3 direction,
                                   double range, double tanHalfFov, double scale) {
        return pickViewFocus(sampler::surfaceY, origin, direction, range, tanHalfFov, scale, Double.MAX_VALUE);
    }

    static VssLodFocus pickResidentViewFocus(PredictionTileManager.RenderSnapshot snapshot,
                                           net.minecraft.world.phys.Vec3 origin,
                                           net.minecraft.world.phys.Vec3 direction,
                                           double range, double tanHalfFov, double scale) {
        // Coarse rendered columns can stand above the exact height at the
        // crosshair. Pick their resident footprint without recomputing density.
        return pickViewFocus((x,z) -> {
            var tile = snapshot.coveringTileAtDetail(Math.floorDiv(x,16), Math.floorDiv(z,16), 0);
            if (tile == null) return Integer.MIN_VALUE;
            int sx = Math.max(0, Math.min(tile.cellAxis()-1, Math.floorDiv(x-tile.baseBlockX(),tile.spacingBlocks())));
            int sz = Math.max(0, Math.min(tile.cellAxis()-1, Math.floorDiv(z-tile.baseBlockZ(),tile.spacingBlocks())));
            var sample = tile.samples()[sz*(tile.cellAxis()+1)+sx];
            if (sample.hasFluid()) return Math.max(sample.fluidY(), sample.hasSurface() ? sample.surfaceY() : Integer.MIN_VALUE);
            return sample.hasSurface() ? tile.heightAt(x-tile.baseBlockX(),z-tile.baseBlockZ()) : Integer.MIN_VALUE;
        }, origin, direction, range, tanHalfFov, scale, 2.0D);
    }

    private static VssLodFocus pickViewFocus(java.util.function.IntBinaryOperator height,
                                           net.minecraft.world.phys.Vec3 origin,
                                           net.minecraft.world.phys.Vec3 direction,
                                           double range, double tanHalfFov, double scale, double maximumStep) {
        double previous = 0.0D;
        // Horizontal and upward rays can hit mountains too. A sky miss has no focus.
        for (double distance = Math.min(2.0D, range); range > 0.0D;
                distance = Math.min(range, distance + Math.min(maximumStep, Math.max(2.0D, distance / 128.0D)))) {
            double x = origin.x + direction.x * distance;
            double y = origin.y + direction.y * distance;
            double z = origin.z + direction.z * distance;
            if (y <= height.applyAsInt((int) Math.floor(x), (int) Math.floor(z))) {
                double hit = refineViewHit(height, origin, direction, previous, distance);
                double radius = Math.min(4096.0D, Math.max(PredictionWorkOrder.SCOPED_RADIUS_BLOCKS,
                        hit * tanHalfFov * 3.0D + 192.0D));
                return new VssLodFocus(origin.x + direction.x * hit,
                        origin.z + direction.z * hit, radius, scale);
            }
            previous = distance;
            if (distance >= range) break;
        }
        return null;
    }

    private static double refineViewHit(java.util.function.IntBinaryOperator height,
                                        net.minecraft.world.phys.Vec3 origin,
                                        net.minecraft.world.phys.Vec3 direction,
                                        double low, double high) {
        for (int step = 0; step < 24 && high - low > 0.001D; step++) {
            double mid = (low + high) * 0.5D;
            double x = origin.x + direction.x * mid;
            double y = origin.y + direction.y * mid;
            double z = origin.z + direction.z * mid;
            int surface = height.applyAsInt((int) Math.floor(x), (int) Math.floor(z));
            if (y <= surface) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return (low + high) * 0.5D;
    }

    private static double predictionHorizonBlocks() {
        return Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
    }

    /** Loading can pause without discarding the manager's resident meshes or render snapshot. */
    static void updateLoadingState(ResourceKey<Level> currentDimension) {
        boolean loading = VSSClientConfig.CONFIG.isPredictionLoadingEnabled();
        MANAGERS.forEach((key, manager) -> manager.setPaused(!loading || !key.equals(currentDimension)));
    }

    public static void tick() {
        VoxyPredictionBridge.updatePredictionState();
        Minecraft current = Minecraft.getInstance();
        if (!VoxyPredictionBridge.isLevelActive(current.level)) return;
        requestCurrentDimension(current, System.nanoTime());
        updateLoadingState(current.level == null ? null : current.level.dimension());
        if (!profileReady || !VSSClientConfig.CONFIG.enablePrediction) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || player.isRemoved()) {
            return;
        }
        var blockAtlas = Minecraft.getInstance().getModelManager()
                .getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        Object atlasToken = blockAtlas.getSprite(net.minecraft.resources.ResourceLocation.tryParse("minecraft:block/stone"));
        if (resourceAtlasToken != atlasToken) {
            resourceAtlasToken = atlasToken;
            resourcesChanged();
        }
        // Keep the exact-coverage cache warm off-thread: the render thread
        // reads it without probing, so a stale entry only means prediction
        // keeps drawing (the safe default) until the sweep refreshes it.
        int nearChunks = VSSApi.clientSession().getEffectiveLodDistanceChunks();
        int voxyRender = ModCompat.isVoxyLoaded()
                ? ModCompat.getVoxyViewDistanceChunks().orElse(nearChunks) : nearChunks;
        sweepExactCoverage(level, player.getBlockX() >> 4, player.getBlockZ() >> 4,
                Math.max(nearChunks, voxyRender));
        PredictionTileManager manager = MANAGERS.get(level.dimension());
        if (manager != null) {
            manager.connectVoxy();
            captureLocalColumns(level, manager);
            manager.serviceCaptures();
            PredictionMotionPace.record(level.dimension(), player.getX(), player.getZ(), player.isSprinting(), System.nanoTime());
            if (VSSClientConfig.CONFIG.isPredictionLoadingEnabled())
                manager.tickCityHints(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            double pixelsPerBlock = PredictionRenderer.basePixelsPerBlock(minecraft);
            var camera = minecraft.gameRenderer.getMainCamera().getPosition();
            var look = minecraft.gameRenderer.getMainCamera().getLookVector();
            var target = minecraft.getMainRenderTarget();
            manager.setWorkView(PredictionWorkView.of(camera.x, camera.y, camera.z,
                    look.x(), look.y(), look.z(), minecraft.options.fov().get(),
                    target.width / (double) Math.max(1, target.height)));
            if (manager.usesVoxyRendering())
                manager.tick(camera.x, camera.y, camera.z, pixelsPerBlock, null, minecraft.options.renderDistance().get() * 16);
        }
        long now = System.nanoTime();
        requestTimes.forEach((key, started) -> {
            if (now - started > REQUEST_STATE_TIMEOUT_NANOS) {
                cellStates.remove(key, CellState.REQUESTED);
                requestTimes.remove(key, started);
            }
        });
    }

    static void publishWorkView(ResourceKey<Level> dimension, PredictionWorkView view, boolean detailLoading) {
        var manager = MANAGERS.get(dimension);
        if (manager != null) {
            manager.setRenderWorkView(view);
            manager.setNativeDetailLoading(detailLoading);
        }
    }

    static void publishVisibilityFeedback(ResourceKey<Level> dimension, PredictionVisibilityFeedback.Snapshot feedback) {
        var manager = MANAGERS.get(dimension);
        if (manager != null) manager.setVisibilityFeedback(feedback);
    }

    static void clearVisibilityFeedback() {
        MANAGERS.values().forEach(manager -> manager.setVisibilityFeedback(PredictionVisibilityFeedback.Snapshot.EMPTY));
    }

    public static PredictionLoadingProgress loadingProgress(ResourceKey<Level> dimension) {
        PredictionTileManager manager = dimension == null ? null : MANAGERS.get(dimension);
        return manager == null ? PredictionLoadingProgress.INITIALIZING : manager.loadingProgress();
    }

    public static void markRequested(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (dimension != null) {
            CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            cellStates.put(key, CellState.REQUESTED);
            requestTimes.put(key, System.nanoTime());
        }
    }

    public static void releaseRequested(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (dimension != null) {
            CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            cellStates.remove(key, CellState.REQUESTED);
            requestTimes.remove(key);
        }
    }

    /** Hooks only enqueue coordinates; block-edit callbacks never scan terrain. */
    public static void onLocalColumnChanged(ClientLevel level, int chunkX, int chunkZ, boolean edited) {
        if (!VSSClientConfig.CONFIG.enablePrediction || level != Minecraft.getInstance().level) return;
        var manager = MANAGERS.get(level.dimension());
        if (manager != null) manager.localCaptures.changed(chunkX, chunkZ, edited, System.nanoTime());
    }

    public static void beforeLocalColumnUnload(ClientLevel level, int chunkX, int chunkZ) {
        var manager = MANAGERS.get(level.dimension());
        if (manager == null || !VSSClientConfig.CONFIG.enablePrediction) return;
        var request = manager.localCaptures.beforeUnload(chunkX, chunkZ, System.nanoTime());
        if (request != null) captureLocalColumn(level, manager, request);
    }

    private static void captureLocalColumns(ClientLevel level, PredictionTileManager manager) {
        // One detached chunk per tick; heavy extraction, mip reduction and compression stay on workers.
        var request = manager.localCaptures.next(System.nanoTime());
        if (request != null) captureLocalColumn(level, manager, request);
    }

    private static void captureLocalColumn(ClientLevel level, PredictionTileManager manager,
                                           PredictionLocalCaptures.Request request) {
        if (!manager.acceptsLocalCapture()) return;
        int x = (int) (request.key() >> 32), z = (int) request.key();
        var real = level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (real == null) {
            manager.localCaptures.completed(request); return;
        }
        var data = PredictionLocalCaptures.snapshot(real);
        if (data == null) return;
        if (manager.captureLocalColumn(x, z, data, request.edited())) {
            manager.localCaptures.snapshots++;
            manager.localCaptures.completed(request);
        }
    }

    public static void onExactColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        // Arrival is not a world edit. Capture refreshes the sampled ground;
        // rendering hands over only once real geometry reaches the screen.
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null) manager.acceptExactColumn(chunkX, chunkZ);
        if (dimension != null) {
            CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            cellStates.put(key, CellState.EXACT);
            exactAcceptedTimes.putIfAbsent(key, System.nanoTime());
            // Publish data presence immediately. GPU readiness independently
            // gates CPU handoff; the depth fallback's snapshot still settles.
            if (exactCoverage.confirm(dimension, chunkX, chunkZ, exactAcceptedTimes.get(key))) {
                exactCoverageChanged(dimension, chunkX, chunkZ);
            }
            requestTimes.remove(key);
        }
    }

    public static boolean onExactColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                                        VoxelColumnData data) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null && !manager.captureExactColumn(chunkX, chunkZ, data)
                && manager.requiresExactCapture(chunkX, chunkZ)) return false;
        onExactColumn(dimension, chunkX, chunkZ);
        return true;
    }

    public static boolean hasReadyTiles(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager != null && manager.hasReadyTiles();
    }

    public static Collection<PredictionTileManager.PredictionTile> readyTiles(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? List.of() : manager.readyTiles();
    }

    static PredictionTileManager.RenderSnapshot renderSnapshot(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? null : manager.renderSnapshot();
    }

    public static boolean isAuthoritative(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager != null && manager.isAuthoritative(chunkX, chunkZ);
    }

    /** Cache-only data presence; it cannot prove that Voxy drew a surface this frame. */
    public static boolean hasExactCoverage(ResourceKey<Level> dimension, ClientLevel level,
                                            int chunkX, int chunkZ) {
        if (dimension == null || level == null || !dimension.equals(level.dimension())) {
            return false;
        }
        return exactCoverage.contains(dimension, chunkX, chunkZ);
    }

    /**
     * Returns true only after an exact column has had time to reach Voxy's
     * ingest/render path. A network acknowledgement alone is not enough to
     * remove the prediction fallback because Voxy uploads its node on a
     * separate queue.
     */
    static boolean hasSettledExactCoverage(ResourceKey<Level> dimension,
                                            int chunkX, int chunkZ) {
        return dimension != null && exactCoverage.owns(dimension, chunkX, chunkZ,
                System.nanoTime());
    }

    static boolean hasSettledExactCoverageBox(ResourceKey<Level> dimension,
                                              int minX, int minZ, int maxX, int maxZ) {
        return dimension != null && exactCoverage.ownsBox(dimension, minX, minZ, maxX, maxZ, System.nanoTime());
    }

    /** Reflection probe that refreshes the cache; background threads only.
     *  Returns the freshly-probed presence. */
    private static boolean probeExactCoverage(ResourceKey<Level> dimension, ClientLevel level,
                                              int chunkX, int chunkZ) {
        long now = System.nanoTime();
        ModCompat.LocalColumnState state = ModCompat.getVoxyLocalColumnState(
                level, chunkX, chunkZ);
        boolean present = state == ModCompat.LocalColumnState.PRESENT;
        if (present) {
            // A storage discovery can precede GPU upload too. All new claims
            // use the same grace period; re-probes preserve the first timestamp.
            if (exactCoverage.confirm(dimension, chunkX, chunkZ, now)) {
                exactCoverageChanged(dimension, chunkX, chunkZ);
            }
        } else {
            // Definitive MISS (index ready, not confirmed, not stored): the
            // ingest record is stale — Voxy genuinely has no data here, so
            // the authoritative claim must be revoked or the cell yields to
            // nothing and shows a hole until the chunk is re-delivered.
            // UNKNOWN (index still building) revokes nothing.
            if (state == ModCompat.LocalColumnState.MISSING) {
                PredictionTileManager manager = MANAGERS.get(dimension);
                if (manager != null && manager.revokeAuthoritative(chunkX, chunkZ)) {
                    exactCoverageChanged(dimension, chunkX, chunkZ);
                }
                if (exactCoverage.remove(dimension, chunkX, chunkZ)) {
                    exactCoverageRemovalRevision.incrementAndGet();
                    exactCoverageChanged(dimension, chunkX, chunkZ);
                }
            }
        }
        return present;
    }

    /**
     * Background sweep over the yield band (request radius out to Voxy's
     * render distance).  Rotates through ring offsets near-first, refreshing
     * a bounded batch per run. Slow probes increase discovery latency instead
     * of monopolizing the profile-decoder thread. Runs there
     * with a re-entry guard like the view-focus raycast.
     */
    private static void sweepExactCoverage(ClientLevel level, int playerChunkX,
                                           int playerChunkZ, int yieldRadiusChunks) {
        if (level == null || !ModCompat.isVoxyLoaded() || sweepUpdating.getAndSet(true)) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSweepNanos < SWEEP_INTERVAL_NANOS) {
            sweepUpdating.set(false);
            return;
        }
        lastSweepNanos = now;
        ResourceKey<Level> dimension = level.dimension();
        int radius = coverageRadiusChunks(yieldRadiusChunks);
        int nearRingChunks = Math.max(16, radius / 4);
        long generation = DECODE_GENERATION.get();
        PROFILE_DECODER.execute(() -> {
            try {
                if (generation != DECODE_GENERATION.get() || !VSSClientConfig.CONFIG.enablePrediction) return;
                // Build/sort offsets on the background executor, never the tick thread.
                PredictionCoverageOffsets offsets = sweepOffsets(radius);
                if (now - coverageRetainedAt >= 5_000_000_000L) {
                    coverageRetainedAt = now;
                    if (exactCoverage.retain(dimension, playerChunkX, playerChunkZ, radius + 64)) {
                        exactCoverageRemovalRevision.incrementAndGet();
                        exactCoverageRevision.incrementAndGet();
                    }
                }
                var result = coverageSweep.run(offsets, nearRingChunks, playerChunkX, playerChunkZ, generation,
                        () -> generation == DECODE_GENERATION.get() && VSSClientConfig.CONFIG.enablePrediction,
                        index -> {
                            int chunkX = playerChunkX + offsets.x(index), chunkZ = playerChunkZ + offsets.z(index);
                            // Recheck acknowledged columns too: otherwise a
                            // stale claim can never reach the MISSING path.
                            return probeExactCoverage(dimension, level, chunkX, chunkZ);
                        });
                long logNow = System.nanoTime();
                if (VSSClientConfig.CONFIG.debugLogging
                        && logNow - lastSweepLogNanos > 10_000_000_000L) {
                    lastSweepLogNanos = logNow;
                    dev.xantha.vss.common.VSSLogger.debug(
                            "VSS exact-coverage sweep: pos=" + result.present()
                                    + " checked=" + result.checked() + ",ms=" + result.nanos() / 1_000_000.0
                                    + ", voxy index [" + ModCompat.voxyLocalIndexDiagnostics() + "]");
                }
            } catch (Throwable ignored) {
            } finally {
                sweepUpdating.set(false);
            }
        });
    }

    /** Near-first ring offsets inside the yield radius, rebuilt on resize. */
    static int coverageRadiusChunks(int requested) {
        int horizon = (int) Math.ceil(predictionHorizonBlocks() / 16.0);
        return Math.max(1, Math.min(512, Math.min(requested, horizon + 32)));
    }

    private static PredictionCoverageOffsets sweepOffsets(int yieldRadiusChunks) {
        PredictionCoverageOffsets offsets = sweepOffsetsReference;
        if (offsets != null && offsets.size() > 0
                && sweepRadiusChunks == yieldRadiusChunks) {
            return offsets;
        }
        PredictionCoverageOffsets rebuilt = PredictionCoverageOffsets.around(yieldRadiusChunks);
        sweepRadiusChunks = yieldRadiusChunks;
        sweepOffsetsReference = rebuilt;
        return rebuilt;
    }

    public static PredictionTileManager.PredictionTile coveringTile(
            ResourceKey<Level> dimension, int chunkX, int chunkZ, int desiredLod) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? null : manager.coveringTile(chunkX, chunkZ, desiredLod);
    }

    public static PredictionTileManager.PredictionTile parentTile(
            ResourceKey<Level> dimension, PredictionTileManager.PredictionTile tile) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? null : manager.parentTile(tile);
    }

    /** Per-tile coverage epoch; see PredictionTileManager.coverageFamilyEpoch. */
    public static long coverageFamilyEpoch(ResourceKey<Level> dimension,
                                           PredictionTileManager.PredictionTileKey key) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? 0L : manager.coverageFamilyEpoch(key);
    }

    /** Drains the changed-tile set for priority resolution; renderer thread. */
    public static Set<PredictionTileManager.PredictionTileKey> drainCoverageHot(
            ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? Set.of() : manager.drainCoverageHot();
    }

    public static void onDirtyColumnsRetired(ResourceKey<Level> dimension, long[] positions) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null) manager.retireExactColumns(positions);
    }

    public static void onDirtyColumns(ResourceKey<Level> dimension, long[] packedPositions) {
        onDirtyColumns(dimension, packedPositions, null);
    }

    public static void onDirtyColumns(ResourceKey<Level> dimension, long[] packedPositions, long[] timestamps) {
        if (packedPositions == null || packedPositions.length == 0) {
            return;
        }
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null) {
            if (timestamps == null) manager.invalidate(packedPositions);
            else manager.awaitExactColumns(packedPositions, timestamps);
        }
        for (long packed : packedPositions) {
            if (dimension != null) {
                CellKey key = new CellKey(dimension, packed);
                cellStates.put(key, CellState.DIRTY);
                requestTimes.remove(key);
                // Keep the exact-coverage entry: a dirty chunk still renders
                // its previous data through Voxy while the refresh is in
                // flight.  Dropping the entry here flipped the cell back to
                // prediction for the whole refresh window, which read as the
                // near Voxy field visibly swapping to prediction LOD.
            }
        }
    }

    public static void onLostCityHints(dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload response) {
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                response.dimension());
        PredictionTileManager manager = MANAGERS.get(key);
        if (manager != null) manager.acceptCityHints(response);
    }

    static String surfaceDiagnostics(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? "unavailable" : manager.surfaceDiagnostics();
    }

    static java.util.Map<String,Object> demandSnapshot(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? java.util.Map.of() : manager.demandSnapshot();
    }

    public static String diagnostics() {
        int ready = 0;
        int pending = 0;
        long voxelBytes = 0;
        int cachedSamples = 0;
        int persistentSamples = 0;
        long built = 0L;
        long failed = 0L;
        int exactSamplers = 0;
        int fallbackSamplers = 0;
        int[] lodCounts = new int[0];
        for (PredictionTileManager manager : MANAGERS.values()) {
            ready += manager.readyCount();
            pending += manager.pendingCount();
            voxelBytes += manager.readyVoxelBytes();
            cachedSamples += manager.sampleCacheSize();
            persistentSamples += manager.persistentSampleCount();
            built += manager.builtTileCount();
            failed += manager.failedTileCount();
            if (manager.exactWorldgen()) exactSamplers++;
            else fallbackSamplers++;
            int[] managerLodCounts = manager.readyLodCounts();
            if (managerLodCounts.length > lodCounts.length) {
                lodCounts = java.util.Arrays.copyOf(lodCounts, managerLodCounts.length);
            }
            for (int lod = 0; lod < managerLodCounts.length; lod++) {
                lodCounts[lod] += managerLodCounts[lod];
            }
        }
        StringBuilder lodBands = new StringBuilder();
        for (int lod = 0; lod < lodCounts.length; lod++) {
            if (lod > 0) lodBands.append('|');
            lodBands.append(lod).append(':').append(lodCounts[lod]);
        }
        return "enabled=" + VSSClientConfig.CONFIG.enablePrediction
                + ",loading=" + VSSClientConfig.CONFIG.isPredictionLoadingEnabled()
                + ",profile=" + profileReady
                + ",revision=" + revision
                + ",seed=" + (profileReady ? "set" : "none")
                + ",tiles=" + ready
                + ",pending=" + pending
                + ",built=" + built
                + ",failed=" + failed
                + ",exactSamplers=" + exactSamplers
                + ",fallbackSamplers=" + fallbackSamplers
                + ",lodBands=" + lodBands
                + ",sampleCache=" + cachedSamples
                + ",coveragePages=" + exactCoverage.pageCount()
                + ",coverageOffsetBytes=" + (sweepOffsetsReference == null ? 0L : 4L * sweepOffsetsReference.size())
                + ",persistentSamples=" + persistentSamples
                + ",layoutLevels=" + MANAGERS.values().stream()
                        .mapToInt(manager -> manager.layout().levelCount()).max().orElse(0)
                + ",voxelBytes=" + voxelBytes
                + ",surface=" + MANAGERS.values().stream().map(PredictionTileManager::surfaceDiagnostics).toList()
                + ",render=" + PredictionRenderer.diagnostics();
    }

    /** Retire scene notifications before closing sources; preserve compressed profiles for returning. */
    public static void onLevelUnload(ClientLevel level) {
        VoxyPredictionBridge.levelUnloaded(level);
        LEVEL_GENERATION.incrementAndGet();
        dimensionInitializing = false;
        requestedDimensions.clear();
        for (PredictionTileManager manager : MANAGERS.values()) manager.close();
        MANAGERS.clear();
        profileReady = false;
        clearLevelTracking();
    }

    public static void clear() { VoxyPredictionBridge.reset(); dimensionProfiles.clear(); reset(false); }

    /** Refresh resource-derived colors; Voxy owns model/atlas and GPU reconstruction. */
    static void resourcesChanged() {
        RustWorldgenDocument.invalidateSharedInputs();
        PredictionColorCache.RESOURCES.invalidate();
        for (var manager : List.copyOf(MANAGERS.values())) {
            if (manager.sampler() instanceof RustTerrainSampler rust) PROFILE_DECODER.execute(() -> {
                try {
                    rust.reloadColormaps();
                } catch (java.util.concurrent.CancellationException ignored) {
                    // The resource reload finished after the world was closed.
                } catch (java.io.IOException | IllegalArgumentException failure) {
                    if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS native colormap reload failed: " + failure);
                }
            });
        }
        PredictionRenderer.resetOcclusion();
    }

    public static void tagsChanged() {
        PredictionVegetationTraits.invalidate();
        MANAGERS.values().forEach(PredictionTileManager::invalidateContent);
    }

    private static void reset(boolean preserveInputs) {
        PredictionPerformanceMonitor.reset();
        PredictionVegetationTraits.invalidate();
        if (!preserveInputs) {
            RustWorldgenDocument.invalidateSharedInputs();
            PredictionColorCache.RESOURCES.invalidate();
            var oldInputs = connectionInputs;
            connectionInputs = null;
            if (oldInputs != null) PROFILE_DECODER.execute(oldInputs::close);
        }
        acceptedProfile = null;
        DECODE_GENERATION.incrementAndGet();
        PreparedProfile oldProfile = preparedProfile;
        preparedProfile = null;
        dimensionInitializing = false;
        requestedDimensions.clear();
        if (oldProfile != null) PROFILE_DECODER.execute(oldProfile.decoder()::close);
        PredictionRenderer.resetOcclusion();
        for (PredictionTileManager manager : MANAGERS.values()) {
            manager.close();
        }
        MANAGERS.clear();
        clearLevelTracking();
        seed = 0L;
        revision = 0L;
        profileReady = false;
    }

    private static void clearLevelTracking() {
        cellStates.clear();
        exactCoverage.clear();
        exactCoverageRemovalRevision.incrementAndGet();
        exactCoverageRevision.incrementAndGet();
        exactAcceptedTimes.clear();
        requestTimes.clear();
    }

    public enum CellState {
        REQUESTED,
        EXACT,
        DIRTY
    }

    record CellKey(ResourceKey<Level> dimension, long packed) {
        @Override
        public int hashCode() {
            // The generated record hash used Long.hashCode(packed), i.e.
            // chunkX ^ chunkZ. A square of nearby coordinates then collapses
            // into a few hundred hashes, and non-Comparable record keys make
            // ConcurrentHashMap's collision trees expensive to search.
            long mixed = it.unimi.dsi.fastutil.HashCommon.mix(packed);
            return 31 * dimension.hashCode() + Long.hashCode(mixed);
        }
    }

}

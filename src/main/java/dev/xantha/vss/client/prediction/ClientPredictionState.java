package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.networking.client.VSSClientNetworking;
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

/** Owns VSS prediction sessions and the exact-column replacement boundary. */
public final class ClientPredictionState {
    private static final Map<ResourceKey<Level>, PredictionTileManager> MANAGERS = new ConcurrentHashMap<>();
    private static volatile long seed;
    private static volatile long revision;
    private static volatile boolean profileReady;
    private static volatile WorldgenProfileS2CPayload acceptedProfile;

    /** Immutable metadata already received from the server, for explicit reference export. */
    static WorldgenProfileS2CPayload referenceProfile() { return acceptedProfile; }
    private static volatile long profileInstalledNanos;
    private static final Map<CellKey, CellState> cellStates = new ConcurrentHashMap<>();
    private static final Map<CellKey, Long> requestTimes = new ConcurrentHashMap<>();
    private static final Map<CellKey, Long> exactAcceptedTimes = new ConcurrentHashMap<>();
    /**
     * Voxy's local index is deliberately queried outside the render loop's
     * hot path. A negative result is short-lived because the index may still
     * be building, while a positive result remains valid for this client
     * world and is removed when the column/session changes.
     */
    private static final Map<CellKey, CoverageCacheEntry> exactCoverage = new ConcurrentHashMap<>();
    /**
     * Bumped whenever a cell's exact ownership MATERIALLY flips to Voxy
     * (ingest, background sweep discovery, session reset).  Renderer
     * coverage caches compare it so far tiles re-resolve on real change
     * instead of a blind timer: a timed far-band recheck resolved thousands
     * of cells per frame on the render thread and showed up as 300 ms
     * coverage spikes every few seconds.
     */
    private static final AtomicLong exactCoverageRevision = new AtomicLong();

    /** Revision of the exact-ownership cache; see {@link #exactCoverageRevision}. */
    public static long exactCoverageRevision() {
        return exactCoverageRevision.get();
    }
    /** Background exact-coverage sweep cadence and batch size.  The batch
     *  keeps a full yield-band cycle around five seconds without stalling
     *  the single profile-decoder thread. */
    private static final long SWEEP_INTERVAL_NANOS = 200_000_000L;
    private static final int SWEEP_BATCH = 32_768;
    private static final java.util.concurrent.atomic.AtomicBoolean sweepUpdating =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastSweepNanos;
    private static volatile long lastSweepLogNanos;
    private static final AtomicInteger sweepCursor = new AtomicInteger();
    private static volatile List<int[]> sweepOffsetsReference;
    private static volatile int sweepRadiusChunks = -1;
    private static final long REQUEST_STATE_TIMEOUT_NANOS = 15_000_000_000L;
    private static final AtomicLong DECODE_GENERATION = new AtomicLong();
    private static final ExecutorService PROFILE_DECODER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vss-worldgen-profile");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPredictionState() {
    }

    public static void accept(WorldgenProfileS2CPayload payload) {
        if (payload != null && payload.sameWorldgen(acceptedProfile)) {
            revision = Math.max(revision, payload.revision());
            return;
        }
        if (payload != null && profileReady && payload.revision() < revision) {
            return;
        }
        clear();
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
        PredictionCacheStorage storage = PredictionCacheStorage.current();
        acceptedProfile = payload;
        long generation = DECODE_GENERATION.get();
        ResourceKey<Level> preferred = minecraft.level == null ? Level.OVERWORLD : minecraft.level.dimension();
        long receivedNanos = System.nanoTime();
        VSSLogger.info("VSS prediction profile accepted: dimensions=" + payload.dimensions().size()
                + ", revision=" + payload.revision() + ", first=" + preferred.location());
        CompletableFuture.runAsync(() -> {
            if (generation != DECODE_GENERATION.get()) return;
            var timing = new PredictionInitializationTiming("profile");
            if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction init queueMs="
                    + (System.nanoTime() - receivedNanos) / 1_000_000L);
            try {
                ClientWorldgenProfileDecoder.decode(payload, clientRegistries, preferred,
                        () -> generation == DECODE_GENERATION.get(), (key, sampler) -> {
                    var installTiming = new PredictionInitializationTiming(key.location() + " install");
                    PredictionDiskCache cache = storage == null ? null : storage.open(sampler);
                    PredictionTileManager manager;
                    try {
                        manager = new PredictionTileManager(key, sampler, PredictionMemoryBudget.SHARED, cache);
                    } catch (RuntimeException | Error failure) {
                        if (cache != null) cache.close();
                        throw failure; // The decoder still owns the sampler on failure.
                    }
                    installTiming.mark("cacheAndManager");
                    long queuedNanos = System.nanoTime();
                    minecraft.execute(() -> {
                        if (generation != DECODE_GENERATION.get()) {
                            manager.close();
                            return;
                        }
                        PredictionTileManager previous = MANAGERS.put(key, manager);
                        if (previous != null) previous.close();
                        manager.setPaused(!VSSClientConfig.CONFIG.enablePrediction);
                        if (!profileReady) profileInstalledNanos = System.nanoTime();
                        profileReady = true;
                        VSSLogger.info("VSS prediction dimension ready: dimension=" + key.location()
                                + ", rust=" + (sampler instanceof RustTerrainSampler)
                                + ", receivedToReadyMs=" + (System.nanoTime() - receivedNanos) / 1_000_000L);
                        if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS prediction init installQueueMs="
                                + (System.nanoTime() - queuedNanos) / 1_000_000L + ", dimension=" + key.location());
                    });
                    installTiming.finish();
                });
                timing.finish();
            } catch (java.util.concurrent.CancellationException cancelled) {
                // A new profile/disconnect invalidated this work; published managers are cleared separately.
            } catch (Exception failure) {
                throw new IllegalStateException("Unable to decode VSS worldgen profile", failure);
            }
        }, PROFILE_DECODER).whenComplete((ignored, failure) -> minecraft.execute(() -> {
            if (generation != DECODE_GENERATION.get()) return;
            if (failure != null) {
                // A later dimension must not remove the current dimension which is already usable.
                VSSLogger.error("VSS worldgen profile decode failed", failure);
                if (MANAGERS.isEmpty()) clear();
                return;
            }
            long exact = MANAGERS.values().stream().filter(PredictionTileManager::exactWorldgen).count();
            long nativeCount = MANAGERS.values().stream()
                    .filter(manager -> manager.sampler() instanceof RustTerrainSampler).count();
            VSSLogger.info("VSS prediction samplers ready: dimensions=" + MANAGERS.size()
                    + ", rust=" + nativeCount + ", javaOrCustom=" + (MANAGERS.size() - nativeCount)
                    + ", exact=" + exact + ", fallback=" + (MANAGERS.size() - exact));
        }));
    }

    /** Extra detail is scoped; ordinary selection covers the whole camera neighbourhood. */
    private static VssLodFocus combinedFocus(Minecraft minecraft, LocalPlayer player) {
        if (!player.isScoping()) return null;
        ViewFocus current = viewFocus;
        if (current == null || current.focus() == null) {
            return null;
        }
        VssLodFocus look = current.focus();
        // Retain sub-block jitter without freezing a nearby target an entire
        // tile away from the crosshair. Residency handles coverage stability.
        VssLodFocus previous = lastFocus;
        if (previous != null
                && Math.hypot(previous.x() - look.x(), previous.z() - look.z()) < 0.125D
                && Math.abs(previous.radius() - look.radius()) < 32.0D
                && Math.abs(previous.pixelsPerBlock() - look.pixelsPerBlock()) < 16.0D) {
            return previous;
        }
        return look;
    }

    private static volatile ViewFocus viewFocus;

    /** Latest combined focus shared by detail planning and coverage selection. */
    private static volatile VssLodFocus lastFocus;

    static VssLodFocus currentFocus() {
        return lastFocus;
    }

    private record ViewFocus(VssLodFocus focus) {
    }

    /** Refreshes the look-direction focus target on a background thread. */
    private static void updateViewFocus(Minecraft minecraft, ClientLevel level) {
        boolean scoping = minecraft.player.isScoping();
        if (scoping != focusScoping) {
            focusScoping = scoping;
            focusGeneration.incrementAndGet();
            lastViewFocusNanos = 0L;
        }
        if (!scoping) {
            viewFocus = null;
            lastFocus = null;
            return;
        }
        if (viewFocusUpdating.getAndSet(true)) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastViewFocusNanos < 500_000_000L) {
            viewFocusUpdating.set(false);
            return;
        }
        lastViewFocusNanos = now;
        var camera = minecraft.gameRenderer.getMainCamera();
        var ray = PredictionRenderer.viewRay();
        net.minecraft.world.phys.Vec3 origin = ray == null ? camera.getPosition() : ray.origin();
        net.minecraft.world.phys.Vec3 direction = ray == null
                ? new net.minecraft.world.phys.Vec3(camera.getLookVector()) : ray.direction();
        PredictionTileManager manager = MANAGERS.get(level.dimension());
        if (manager == null) {
            viewFocusUpdating.set(false);
            return;
        }
        var samplerRef = manager.sampler();
        long generation = focusGeneration.get();
        double scale = PredictionRenderer.selectionPixelsPerBlock(minecraft);
        double tanHalf = Math.max(1, minecraft.getMainRenderTarget().height) / (2.0D * scale);
        PROFILE_DECODER.execute(() -> {
            try {
                VssLodFocus picked = pickViewFocus(samplerRef, origin, direction,
                        predictionHorizonBlocks(), tanHalf, scale);
                minecraft.execute(() -> {
                    if (generation == focusGeneration.get() && minecraft.level == level
                            && minecraft.player != null && minecraft.player.isScoping()) {
                        viewFocus = new ViewFocus(picked);
                    }
                });
            } catch (Throwable ignored) {
                if (generation == focusGeneration.get()) viewFocus = null;
            } finally {
                viewFocusUpdating.set(false);
            }
        });
    }

    static VssLodFocus pickViewFocus(ClientTerrainSampler sampler,
                                   net.minecraft.world.phys.Vec3 origin,
                                   net.minecraft.world.phys.Vec3 direction,
                                   double range, double tanHalfFov, double scale) {
        double previous = 0.0D;
        // Horizontal and upward rays can hit mountains too. A sky miss has no focus.
        for (double distance = Math.min(2.0D, range); range > 0.0D;
                distance = Math.min(range, distance + Math.max(2.0D, distance / 128.0D))) {
            double x = origin.x + direction.x * distance;
            double y = origin.y + direction.y * distance;
            double z = origin.z + direction.z * distance;
            if (y <= sampler.surfaceY((int) Math.floor(x), (int) Math.floor(z))) {
                double hit = refineViewHit(sampler, origin, direction, previous, distance);
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

    private static double refineViewHit(ClientTerrainSampler sampler,
                                        net.minecraft.world.phys.Vec3 origin,
                                        net.minecraft.world.phys.Vec3 direction,
                                        double low, double high) {
        for (int step = 0; step < 24 && high - low > 0.001D; step++) {
            double mid = (low + high) * 0.5D;
            double x = origin.x + direction.x * mid;
            double y = origin.y + direction.y * mid;
            double z = origin.z + direction.z * mid;
            int surface = sampler.surfaceY((int) Math.floor(x), (int) Math.floor(z));
            if (y <= surface) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return (low + high) * 0.5D;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean viewFocusUpdating =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastViewFocusNanos;
    private static final AtomicLong focusGeneration = new AtomicLong();
    private static boolean focusScoping;

    private static double predictionHorizonBlocks() {
        return Math.max(VSSClientConfig.MIN_PREDICTION_DISTANCE_BLOCKS,
                Math.min(VSSClientConfig.MAX_PREDICTION_DISTANCE_BLOCKS,
                        VSSClientConfig.CONFIG.predictionDistanceBlocks));
    }

    public static void tick() {
        MANAGERS.values().forEach(manager -> manager.setPaused(!VSSClientConfig.CONFIG.enablePrediction));
        if (!profileReady || !VSSClientConfig.CONFIG.enablePrediction) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || player.isRemoved()) {
            return;
        }
        PredictionMaterialPalette.refreshAtlas(level);
        // A resource reload replaces atlas sprites while prediction workers
        // may still hold packed row indices. Drop those meshes once the token
        // changes so the next frame rebuilds against the new atlas.
        var blockAtlas = Minecraft.getInstance().getModelManager()
                .getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
        if (VssLodSpriteTable.refresh(blockAtlas.getSprite(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/stone")))) {
            RustWorldgenDocument.invalidateSharedInputs();
            MANAGERS.values().forEach(PredictionTileManager::invalidateAppearance);
            for (var manager : List.copyOf(MANAGERS.values())) {
                if (manager.sampler() instanceof RustTerrainSampler rust) PROFILE_DECODER.execute(() -> {
                    try {
                        rust.reloadColormaps();
                        minecraft.execute(() -> {
                            if (MANAGERS.get(rust.profile().levelKey()) == manager) manager.invalidateAppearance();
                        });
                    } catch (java.util.concurrent.CancellationException ignored) {
                        // The resource reload finished after the world was closed.
                    } catch (java.io.IOException | IllegalArgumentException failure) {
                        if (VSSClientConfig.CONFIG.debugLogging) VSSLogger.debug("VSS native colormap reload failed: " + failure);
                    }
                });
            }
            PredictionRenderer.resetOcclusion();
        }
        VssLodSpriteTable.prepare();
        updateViewFocus(minecraft, level);
        // Keep the exact-coverage cache warm off-thread: the render thread
        // reads it without probing, so a stale entry only means prediction
        // keeps drawing (the safe default) until the sweep refreshes it.
        int nearChunks = VSSClientNetworking.getEffectiveLodDistanceChunks();
        int voxyRender = ModCompat.isVoxyLoaded()
                ? ModCompat.getVoxyViewDistanceChunks().orElse(nearChunks) : nearChunks;
        sweepExactCoverage(level, player.getBlockX() >> 4, player.getBlockZ() >> 4,
                Math.max(nearChunks, voxyRender));
        PredictionTileManager manager = MANAGERS.get(level.dimension());
        if (manager != null) {
            double pixelsPerBlock = PredictionRenderer.basePixelsPerBlock(minecraft);
            VssLodFocus focus = combinedFocus(minecraft, player);
            lastFocus = focus;
            var camera = minecraft.gameRenderer.getMainCamera().getPosition();
            var look = minecraft.gameRenderer.getMainCamera().getLookVector();
            var target = minecraft.getMainRenderTarget();
            manager.setWorkView(PredictionWorkView.of(camera.x, camera.y, camera.z,
                    look.x(), look.y(), look.z(), minecraft.options.fov().get(),
                    target.width / (double) Math.max(1, target.height)));
            manager.tick(camera.x, camera.y, camera.z, pixelsPerBlock, focus, minecraft.options.renderDistance().get() * 16);
        }
        long now = System.nanoTime();
        requestTimes.forEach((key, started) -> {
            if (now - started > REQUEST_STATE_TIMEOUT_NANOS) {
                cellStates.remove(key, CellState.REQUESTED);
                requestTimes.remove(key, started);
            }
        });
    }

    public static boolean shouldDeferExactColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ, long nowNanos) {
        if (!profileReady || !VSSClientConfig.CONFIG.enablePrediction || dimension == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || player.isRemoved() || !dimension.equals(level.dimension())) {
            return false;
        }
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int ring = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
        int exactDistance = VSSClientNetworking.getEffectiveLodDistanceChunks();
        if (exactDistance <= 0 || ring <= exactDistance) {
            return false;
        }
        PredictionTileManager manager = MANAGERS.get(dimension);
        CellState state = cellStates.get(new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ)));
        if (manager == null || state == CellState.REQUESTED || state == CellState.EXACT
                || state == CellState.DIRTY) {
            return false;
        }
        boolean covered = manager.hasCoverage(chunkX, chunkZ)
                || (manager.pendingCount() > 0 && profileInstalledNanos != 0L
                && nowNanos - profileInstalledNanos < 750_000_000L);
        if (covered) {
            cellStates.putIfAbsent(
                    new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ)),
                    CellState.PREDICTED);
        }
        return covered;
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

    public static void onExactColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null) {
            manager.invalidate(chunkX, chunkZ);
        }
        if (dimension != null) {
            CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
            cellStates.put(key, CellState.EXACT);
            exactAcceptedTimes.putIfAbsent(key, System.nanoTime());
            // Write the positive coverage entry directly: the render thread
            // reads the cache without probing, so ingest must publish the
            // handover itself (the ExactCoverageGate settle window inside
            // hasExactCoverage still applies).
            CoverageCacheEntry previous = exactCoverage.put(key, new CoverageCacheEntry(
                    true, exactAcceptedTimes.get(key), Long.MAX_VALUE));
            if (previous == null || !previous.present()) {
                exactCoverageRevision.incrementAndGet();
            }
            requestTimes.remove(key);
        }
    }

    public static void onExactColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                                     VoxelColumnData data) {
        onExactColumn(dimension, chunkX, chunkZ);
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null) manager.captureExactColumn(chunkX, chunkZ, data);
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

    /**
     * Returns whether a column is already rendered by the exact VSS/Voxy
     * path. Two evidence kinds, two latencies: a raw ingest acknowledgement
     * from the delivery path is held behind a short settling window while
     * Voxy builds and uploads render nodes, but a probe positive from
     * Voxy's own storage index yields immediately — that data is already
     * renderable on Voxy's side, and delaying it drew prediction meshes
     * over freshly loaded real LOD after every teleport.
     *
     * <p>Cache-only read for the render thread: NEVER probes Voxy's local
     * index here.  The reflection probe costs microseconds per chunk and a
     * coverage re-resolve sweeps thousands of cells in one frame; probing
     * inline turned far-band re-resolves into 15 ms frame spikes.  A
     * background sweep (sweepExactCoverage) keeps the cache fresh, ingest
     * writes positives directly through onExactColumn, and a miss simply
     * means "prediction draws" — the safe default.</p>
     */
    public static boolean hasExactCoverage(ResourceKey<Level> dimension, ClientLevel level,
                                            int chunkX, int chunkZ) {
        if (dimension == null || level == null || !dimension.equals(level.dimension())) {
            return false;
        }
        CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
        long now = System.nanoTime();
        // Ingest ground truth first: a cell whose column VSS itself delivered
        // (the server's ring scheduler) is rendered by Voxy once the ingest
        // has settled.  This path is immune to Voxy's local index build
        // timing, which the probe cache below depends on — during index
        // rebuilds the probe returns UNKNOWN and the yield used to flap,
        // visibly swapping the near Voxy field to prediction LOD.
        PredictionTileManager manager = MANAGERS.get(dimension);
        if (manager != null && manager.isAuthoritative(chunkX, chunkZ)) {
            Long accepted = exactAcceptedTimes.get(key);
            if (accepted != null
                    && now - accepted >= ExactCoverageGate.SETTLE_NANOS) {
                return true;
            }
        }
        CoverageCacheEntry cached = exactCoverage.get(key);
        return cached != null && cached.ownsAt(now);
    }

    /** Reflection probe that refreshes the cache; background threads only.
     *  Returns the freshly-probed presence. */
    private static boolean probeExactCoverage(ResourceKey<Level> dimension, ClientLevel level,
                                              int chunkX, int chunkZ) {
        CellKey key = new CellKey(dimension, PositionUtil.packPosition(chunkX, chunkZ));
        long now = System.nanoTime();
        ModCompat.LocalColumnState state = ModCompat.getVoxyLocalColumnState(
                level, chunkX, chunkZ);
        boolean present = state == ModCompat.LocalColumnState.PRESENT;
        CoverageCacheEntry previous = exactCoverage.get(key);
        if (present) {
            // Probe positives yield IMMEDIATELY: the probe reads Voxy's own
            // storage index, so the data is already renderable on Voxy's
            // side.  Backdating the transition skips the ingest settle
            // window here — a post-teleport area flips cell by cell as
            // Voxy loads it, and a 2 s settle per cell drew prediction
            // meshes over the freshly loaded real LOD (the mixing report).
            // The settle only protects the delivery path, where the ingest
            // ack precedes Voxy's render-node build (onExactColumn).
            long transitionStarted = previous != null && previous.present()
                    ? previous.transitionStartedNanos()
                    : now - ExactCoverageGate.SETTLE_NANOS;
            if (previous == null || !previous.present()) {
                exactCoverageRevision.incrementAndGet();
            }
            exactCoverage.put(key, new CoverageCacheEntry(
                    true, transitionStarted, Long.MAX_VALUE));
        } else {
            // Definitive MISS (index ready, not confirmed, not stored): the
            // ingest record is stale — Voxy genuinely has no data here, so
            // the authoritative claim must be revoked or the cell yields to
            // nothing and shows a hole until the chunk is re-delivered.
            // UNKNOWN (index still building) revokes nothing.
            if (state == ModCompat.LocalColumnState.MISSING) {
                PredictionTileManager manager = MANAGERS.get(dimension);
                if (manager != null && manager.revokeAuthoritative(chunkX, chunkZ)) {
                    exactCoverageRevision.incrementAndGet();
                }
            }
            exactCoverage.put(key, new CoverageCacheEntry(
                    false, now, Long.MAX_VALUE));
        }
        return present;
    }

    /**
     * Background sweep over the yield band (request radius out to Voxy's
     * render distance).  Rotates through ring offsets near-first, refreshing
     * a bounded batch per run so the whole band cycles in a few seconds
     * once Voxy's local index is built.  Runs on the profile-decoder thread
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
        List<int[]> offsets = sweepOffsets(yieldRadiusChunks);
        if (offsets.isEmpty()) {
            sweepUpdating.set(false);
            return;
        }
        int cursor = sweepCursor.getAndUpdate(
                value -> (value + SWEEP_BATCH) % offsets.size());
        int nearRingChunks = Math.max(16, yieldRadiusChunks / 4);
        long generation = DECODE_GENERATION.get();
        PROFILE_DECODER.execute(() -> {
            try {
                PredictionTileManager manager = MANAGERS.get(dimension);
                int positives = 0;
                int negatives = 0;
                // Near ring first, every cycle: it is the band the player is
                // actually looking at and where VSS's own delivery is slow
                // (rate-limited columns), so stale UNKNOWNs here show as
                // prediction drawn over Voxy's stored LOD.
                for (int[] offset : offsets) {
                    if (generation != DECODE_GENERATION.get() || !VSSClientConfig.CONFIG.enablePrediction) return;
                    if (offset[0] * offset[0] + offset[1] * offset[1]
                            > nearRingChunks * nearRingChunks) {
                        break;
                    }
                    int chunkX = playerChunkX + offset[0];
                    int chunkZ = playerChunkZ + offset[1];
                    if (manager != null && manager.isAuthoritative(chunkX, chunkZ)) {
                        continue;
                    }
                    if (probeExactCoverage(dimension, level, chunkX, chunkZ)) {
                        positives++;
                    } else {
                        negatives++;
                    }
                }
                // Then the rotating far-band batch.
                for (int index = 0; index < SWEEP_BATCH; index++) {
                    if (generation != DECODE_GENERATION.get() || !VSSClientConfig.CONFIG.enablePrediction) return;
                    int[] offset = offsets.get((cursor + index) % offsets.size());
                    if (offset[0] * offset[0] + offset[1] * offset[1]
                            <= nearRingChunks * nearRingChunks) {
                        continue;
                    }
                    int chunkX = playerChunkX + offset[0];
                    int chunkZ = playerChunkZ + offset[1];
                    if (manager != null && manager.isAuthoritative(chunkX, chunkZ)) {
                        continue;
                    }
                    if (probeExactCoverage(dimension, level, chunkX, chunkZ)) {
                        positives++;
                    } else {
                        negatives++;
                    }
                }
                long logNow = System.nanoTime();
                if (VSSClientConfig.CONFIG.debugLogging
                        && logNow - lastSweepLogNanos > 10_000_000_000L) {
                    lastSweepLogNanos = logNow;
                    dev.xantha.vss.common.VSSLogger.debug(
                            "VSS exact-coverage sweep: pos=" + positives
                                    + " neg=" + negatives
                                    + ", voxy index [" + ModCompat.voxyLocalIndexDiagnostics() + "]");
                }
            } catch (Throwable ignored) {
            } finally {
                sweepUpdating.set(false);
            }
        });
    }

    /** Near-first ring offsets inside the yield radius, rebuilt on resize. */
    private static List<int[]> sweepOffsets(int yieldRadiusChunks) {
        List<int[]> offsets = sweepOffsetsReference;
        if (offsets != null && offsets.size() > 0
                && sweepRadiusChunks == yieldRadiusChunks) {
            return offsets;
        }
        int radius = Math.max(1, yieldRadiusChunks);
        List<int[]> rebuilt = new ArrayList<>(radius * radius * 2);
        // Square ring walk ordered by distance so the sweep refreshes the
        // near band far more often than the outer stored-data band.
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    rebuilt.add(new int[]{dx, dz});
                }
            }
        }
        rebuilt.sort(Comparator.comparingInt((int[] offset)
                -> offset[0] * offset[0] + offset[1] * offset[1]));
        sweepRadiusChunks = radius;
        sweepOffsetsReference = rebuilt;
        sweepCursor.set(0);
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

    public static void onDirtyColumns(ResourceKey<Level> dimension, long[] packedPositions) {
        if (packedPositions == null) {
            return;
        }
        for (long packed : packedPositions) {
            int chunkX = PositionUtil.unpackX(packed);
            int chunkZ = PositionUtil.unpackZ(packed);
            PredictionTileManager manager = MANAGERS.get(dimension);
            if (manager != null) {
                manager.invalidate(chunkX, chunkZ);
            }
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

    static String surfaceDiagnostics(ResourceKey<Level> dimension) {
        PredictionTileManager manager = MANAGERS.get(dimension);
        return manager == null ? "unavailable" : manager.surfaceDiagnostics();
    }

    public static String diagnostics() {
        int ready = 0;
        int pending = 0;
        int vertices = 0;
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
            vertices += manager.readyVertexCount();
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
                + ",persistentSamples=" + persistentSamples
                + ",layoutLevels=" + MANAGERS.values().stream()
                        .mapToInt(manager -> manager.layout().levelCount()).max().orElse(0)
                + ",meshVertices=" + vertices
                + ",surface=" + MANAGERS.values().stream().map(PredictionTileManager::surfaceDiagnostics).toList()
                + ",render=" + PredictionRenderer.diagnostics();
    }

    public static void clear() {
        RustWorldgenDocument.invalidateSharedInputs();
        acceptedProfile = null;
        focusGeneration.incrementAndGet();
        focusScoping = false;
        viewFocus = null;
        lastFocus = null;
        lastViewFocusNanos = 0L;
        DECODE_GENERATION.incrementAndGet();
        PredictionRenderer.resetOcclusion();
        for (PredictionTileManager manager : MANAGERS.values()) {
            manager.close();
        }
        MANAGERS.clear();
        cellStates.clear();
        exactCoverage.clear();
        exactCoverageRevision.incrementAndGet();
        exactAcceptedTimes.clear();
        requestTimes.clear();
        seed = 0L;
        revision = 0L;
        profileReady = false;
        profileInstalledNanos = 0L;
    }

    public enum CellState {
        PREDICTED,
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

    private record CoverageCacheEntry(boolean present, long transitionStartedNanos,
                                      long expiresAtNanos) {
        boolean ownsAt(long nowNanos) {
            return present && ExactCoverageGate.ownsPrediction(
                    ModCompat.LocalColumnState.PRESENT, transitionStartedNanos, nowNanos);
        }
    }
}

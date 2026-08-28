package dev.xantha.vss.compat;

import dev.xantha.vss.api.VSSApi;
import dev.xantha.vss.api.VoxelColumnConsumer;
import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.common.LogThrottle;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Xaero's World Map bridge (issue #223, docs/planning/xaero-map-bridge-plan.md):
 * writes VSS-delivered LOD columns into Xaero's World Map so the map records
 * terrain far beyond vanilla render distance. Pure reflection — zero compile-time
 * dependency, zero mixins (the bridge supports the 1.40.x legacy surface and
 * the 1.42–1.45 surface, verified against Xaero WM 1.40.16/1.45.0) — following
 * the {@code VoxyCompat}/
 * {@code MoonriseReadCompat} interop discipline: any resolve failure disables the
 * bridge with one warn (diag shows {@code state=unavailable}); runtime failures
 * latch it dead for the session after {@value #THROW_LATCH} consecutive failures;
 * LOD delivery is NEVER affected — the consumer swallows every throwable
 * ({@code Error}s included: {@code VSSApi.dispatchColumn} converts ANY escape
 * into an ingest-failure report, and a map problem must not trigger re-serves).
 *
 * <p>Two-stage pipeline (plan §2.4): the registered {@link VoxelColumnConsumer}
 * extracts a {@link XaeroTileExtractor.PreparedTile} on the VSS decode thread and
 * offers it to a bounded (count AND bytes) latest-wins queue; {@link #pump()} —
 * the shared end-of-client-tick body, MAIN CLIENT THREAD (Xaero enforces it with
 * {@code isSameThread} throws) — re-runs the native writer's gate ladder
 * verbatim, then commits under Xaero's own locks in the decompiled
 * {@code MapWriter.writeChunk} sequence. Queued columns are grouped by Xaero's
 * 32x32-chunk regions, and a memoryless window keeps up to eight region loads in
 * flight without taking Xaero's native viewing token. Fresh and cache-parked
 * regions therefore make progress without serializing map, minimap, and bridge
 * loading behind one shared region. Texture rebuilds
 * are coalesced per tile chunk and run under Xaero's writer/save gates; the
 * unsafe {@code setToUpdateBuffers} sweep flag is never used.
 *
 * <p><b>Registration lifecycle</b> (review MAJOR): the consumer is what holds the
 * handshake's CAPABILITY_VOXEL_COLUMNS bit ({@code VSSApi.hasVoxelConsumers()}),
 * so an Xaero-only install (no Voxy) legitimately subscribes to LOD data — that
 * IS the feature. But deregistering MID-SESSION would put every arriving column
 * through the no-consumer ingest-failure path (up to 4 re-serves per position
 * before parking — a whole-disc churn for a map problem), so: registration is
 * add-only while a session may be live (init + pump), a disabled or dead bridge
 * becomes a silent no-op consumer (offers are dropped), and deregistration
 * happens ONLY at {@link #onDisconnect()} — which is also where the death latch
 * re-arms (session-scoped: one bad session must not disable the feature for the
 * whole JVM; genuine Xaero drift re-latches within {@value #THROW_LATCH}
 * commits next session).
 */
final class XaeroMapCompat {

    static final int MAX_QUEUE = 8192;
    /** Byte gauge companion to the count cap (the ClientColumnProcessor discipline —
     *  a count cap alone admits ~165 MB of max-overlay tiles; plain tiles are ~4.7 KB
     *  but ocean tiles carry per-pixel overlay runs). Estimated, not exact. */
    static final long MAX_QUEUE_BYTES = 48L * 1024 * 1024;
    /** Stop requesting more columns before the hard queue limits can evict the
     *  near-to-far replay prefix. The low marks add hysteresis so intake resumes
     *  in useful batches instead of oscillating every tick. */
    static final int INTAKE_QUEUE_HIGH_WATERMARK = MAX_QUEUE / 2;
    static final int INTAKE_QUEUE_LOW_WATERMARK = MAX_QUEUE / 4;
    static final long INTAKE_BYTES_HIGH_WATERMARK = MAX_QUEUE_BYTES / 2;
    static final long INTAKE_BYTES_LOW_WATERMARK = MAX_QUEUE_BYTES / 4;
    /** Safety ceiling only — the nanos budget below is the binding constraint
     *  (review MAJOR: 8 committed only 160 tiles/s against 300-1000 delivered
     *  columns/s, making every backfill drop most of the map). */
    static final int MAX_COMMITS_PER_PUMP = 64;
    static final long PUMP_NANOS_BUDGET = 2_000_000L;
    /** Xaero loads map regions on its own worker. Keep a small memoryless window
     *  full so disk-backed regions do not serialize behind one viewing token. */
    static final int MAX_OUTSTANDING_LOADS = 8;
    static final int UPDATE_IDLE_PUMPS = 40;
    static final int PENDING_UPDATES_SOFT_CAP = 256;
    static final int PENDING_UPDATES_HARD_CAP = 1024;
    static final int INTAKE_UPDATES_HIGH_WATERMARK = PENDING_UPDATES_SOFT_CAP;
    static final int INTAKE_UPDATES_LOW_WATERMARK = PENDING_UPDATES_SOFT_CAP / 2;
    static final int UPDATE_MAX_STALL_PUMPS = 1200;
    static final int UPDATE_MAX_DEFER_PUMPS = 4 * UPDATE_IDLE_PUMPS;
    static final long UPDATE_NANOS_BUDGET = 2_000_000L;
    static final long UPDATE_BORROW_NANOS = PUMP_NANOS_BUDGET;
    static final int FRAME_MAX_REBUILDS = 1;
    private static final int COMPLETE_TILE_MASK = 0xFFFF;
    static final int FLUSH_PROBE_EXEMPT_FLOOR = 8;
    /** Ladder-ready deferrals (busy region, PBO download) before an entry drops. */
    static final int DEFER_CAP = 200;
    /** Consecutive failures (commit-side or extraction-side) before the bridge
     *  latches dead for the SESSION (re-armed at disconnect). */
    static final int THROW_LATCH = 5;
    /** The surface layer — native {@code caveLayer} sentinel. */
    private static final int SURFACE_LAYER = Integer.MAX_VALUE;

    private static final LogThrottle EXTRACT_FAIL_WARN = new LogThrottle(60_000);
    private static final LogThrottle COMMIT_FAIL_WARN = new LogThrottle(60_000);

    // ---- test seams (the VoxyCompat discipline: default-wired to production) ----

    /** Resolves the reflected Xaero class names — test seam. */
    @FunctionalInterface
    interface ClassResolver {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    /**
     * The two operations the pump needs on Xaero's world object. A seam because
     * {@code ClientLevel} is unconstructible under fabric-loader-junit — the stub
     * {@code MapProcessor.getWorld()} returns a plain marker object and tests map
     * it here; production casts.
     */
    interface LevelOps {
        Object dimension(Object world);
        boolean isChunkLoaded(Object world, int chunkX, int chunkZ);
    }

    static final LevelOps PRODUCTION_LEVEL_OPS = new LevelOps() {
        @Override
        public Object dimension(Object world) {
            return ((ClientLevel) world).dimension();
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            var chunk = ((ClientLevel) world).getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return chunk != null && !(chunk instanceof EmptyLevelChunk);
        }
    };

    // ---- static facade (production wiring; ModCompat owns the instance) ----

    private static volatile XaeroMapCompat instance;
    /** Xaero present but its internal surface unrecognized — drives the
     *  {@code state=unavailable} diag line (without it a drifted Xaero would be
     *  indistinguishable from "not installed", hiding the plan's top risk). */
    private static volatile boolean resolveFailed;

    /** Client init, Xaero present: resolve + register the consumer (if enabled). */
    static boolean init() {
        return initWith(Class::forName);
    }

    /** The init body with an injectable resolver (the resolve-failure path's test seam). */
    static boolean initWith(ClassResolver resolver) {
        try {
            var h = Handles.resolve(resolver);
            var bridge = new XaeroMapCompat(h, PRODUCTION_LEVEL_OPS,
                    () -> VSSClientConfig.CONFIG.enableXaeroMapBridge,
                    VSSApi::isServerEnabled,
                    VSSApi::registerColumnConsumer, VSSApi::removeColumnConsumer);
            bridge.maybeRegister();
            instance = bridge;
            VSSLogger.info(VSSClientConfig.CONFIG.enableXaeroMapBridge
                    ? "Xaero's World Map detected — LOD map bridge active"
                    : "Xaero's World Map detected — LOD map bridge ready"
                            + " (disabled by enableXaeroMapBridge)");
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException
                 | IllegalAccessException e) {
            resolveFailed = true;
            VSSLogger.warn("Xaero map bridge: this Xaero's World Map version has a different"
                    + " internal surface — bridge disabled (" + e + ")");
            return false;
        } catch (Throwable e) {
            resolveFailed = true;
            VSSLogger.error("Failed to initialize the Xaero map bridge", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread). */
    static void clientTick() {
        var bridge = instance;
        if (bridge != null) bridge.pump();
    }

    /** Per-frame texture rebuild slice. */
    static void renderFrame() {
        var bridge = instance;
        if (bridge != null) bridge.frameFlush();
    }

    /** Disconnect body — session teardown (queue, latches, registration). */
    static void onDisconnect() {
        var bridge = instance;
        if (bridge != null) bridge.onSessionEnd();
    }

    /** The conditional {@code /lss diag} line, or null when Xaero was never detected. */
    static String diagLine() {
        var bridge = instance;
        if (bridge != null) return bridge.describe();
        return resolveFailed
                ? "XaeroMap: state=unavailable (unrecognized Xaero internals — bridge off)"
                : null;
    }

    static boolean isActive() {
        var bridge = instance;
        return bridge != null && !bridge.dead && bridge.enabled.getAsBoolean();
    }

    /** Request-side flow control. Without this, a fast local/dedicated server can
     *  fill the bounded map queue and evict the first replay rings permanently. */
    static boolean shouldBackpressureInput() {
        var bridge = instance;
        return bridge != null && bridge.shouldBackpressureInputNow();
    }

    /** True until both pixel commits and their coalesced texture rebuilds finish. */
    static boolean hasPendingWork() {
        var bridge = instance;
        return bridge != null && bridge.hasPendingWorkNow();
    }

    /** Test seam: forget the static facade state. */
    static void resetFacadeForTest() {
        instance = null;
        resolveFailed = false;
    }

    // ---- instance ----

    private final Handles h;
    private final LevelOps levelOps;
    private final BooleanSupplier enabled;
    /** An VSS session is live — offers outside one are dropped (closes the
     *  disconnect-drain race that could carry one stale tile into the NEXT
     *  server's — or a singleplayer world's — persistent map). */
    private final BooleanSupplier sessionActive;
    private final java.util.function.Consumer<VoxelColumnConsumer> registrar;
    private final java.util.function.Consumer<VoxelColumnConsumer> deregistrar;
    private final VoxelColumnConsumer consumer;
    /** Whether the consumer is currently registered with VSSApi. Main thread only. */
    private boolean registered;

    private final Object queueLock = new Object();
    /** Packed chunk pos → entry; insertion-ordered, latest tile wins in place. */
    private final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    private long queuedBytes; // under queueLock
    private boolean intakeBackpressured; // main client thread, reset under queueLock

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong skippedLoaded = new AtomicLong();
    private final AtomicLong deferEvents = new AtomicLong();
    private final AtomicLong droppedOverflow = new AtomicLong();
    private final AtomicLong droppedStale = new AtomicLong();
    private final AtomicLong droppedExpired = new AtomicLong();
    private final AtomicLong commitFailures = new AtomicLong();
    private final AtomicLong loadRequests = new AtomicLong();
    private volatile boolean dead;
    private int consecutiveFailures; // main thread only
    /** Decode-thread twin of the commit-side latch: a permanently-throwing
     *  extractor must not burn CPU + hold the capability subscription forever. */
    private final AtomicInteger consecutiveExtractFailures = new AtomicInteger();
    /** The per-pump time budget — a field so tests can neutralize MethodHandle warmup. */
    long pumpNanosBudget = PUMP_NANOS_BUDGET;
    long updateNanosBudget = UPDATE_NANOS_BUDGET;
    int updateIdlePumps = UPDATE_IDLE_PUMPS;
    int pendingUpdatesSoftCap = PENDING_UPDATES_SOFT_CAP;
    int pendingUpdatesHardCap = PENDING_UPDATES_HARD_CAP;
    int updateMaxStallPumps = UPDATE_MAX_STALL_PUMPS;
    int updateMaxDeferPumps = UPDATE_MAX_DEFER_PUMPS;
    long updateBorrowNanos = UPDATE_BORROW_NANOS;
    int frameMaxRebuilds = FRAME_MAX_REBUILDS;
    /** Main/render thread only: committed tile chunks awaiting a safe texture rebuild. */
    private final LinkedHashMap<PendingKey, PendingUpdate> pendingUpdates = new LinkedHashMap<>();
    private long pumpCount;
    private volatile boolean sessionEndPending;
    private boolean frameFlushRan;
    private boolean frameActiveThisPump;
    private long rebuildSpentSinceLastPumpNanos;
    private int framesSinceLastPump;
    private final AtomicLong bufferUpdates = new AtomicLong();
    private final AtomicLong frameFlushes = new AtomicLong();
    private final AtomicLong rebuildNanos = new AtomicLong();
    private volatile long rebuildNanosMax;
    private final AtomicLong droppedUpdates = new AtomicLong();
    private final AtomicLong droppedUnloaded = new AtomicLong();
    /** Rotating drain start (the IncomingRequestRouter M4 precedent): without it a
     *  permanently-deferring queue prefix starves committable entries forever. */
    private int drainRotation; // main thread only

    private record PendingKey(Object dimension, long tileChunk) {}

    private record Pending(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {}

    private record WaitingRegion(long regionKey, int tiles, Outcome verdict) {}

    private static final class PendingUpdate {
        final Object processor;
        final String worldId;
        final Object dimension;
        final Object region;
        final Object tileChunk;
        final int localTcX;
        final int localTcZ;
        final long firstTouchPump;
        long lastTouchPump;
        long stalledSincePump = -1;
        int touchedTiles;

        PendingUpdate(Object processor, String worldId, Object dimension, Object region,
                      Object tileChunk, int localTcX, int localTcZ, long pump,
                      int touchedTiles) {
            this.processor = processor;
            this.worldId = worldId;
            this.dimension = dimension;
            this.region = region;
            this.tileChunk = tileChunk;
            this.localTcX = localTcX;
            this.localTcZ = localTcZ;
            this.firstTouchPump = pump;
            this.lastTouchPump = pump;
            this.touchedTiles = touchedTiles;
        }
    }

    private static final class Entry {
        volatile XaeroTileExtractor.PreparedTile tile; // replaced under queueLock (latest wins)
        final Object dimension;
        int bytes; // under queueLock
        int ladderReadyDeferrals; // main thread only

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile, int bytes) {
            this.dimension = dimension;
            this.tile = tile;
            this.bytes = bytes;
        }
    }

    XaeroMapCompat(Handles h, LevelOps levelOps, BooleanSupplier enabled,
                   BooleanSupplier sessionActive,
                   java.util.function.Consumer<VoxelColumnConsumer> registrar,
                   java.util.function.Consumer<VoxelColumnConsumer> deregistrar) {
        this.h = h;
        this.levelOps = levelOps;
        this.enabled = enabled;
        this.sessionActive = sessionActive;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.consumer = buildConsumer();
    }

    /**
     * ADD-only registration reconcile (init + every pump): a mid-session enable
     * starts feeding the map (when a stream exists — an Xaero-only install that
     * joined disabled has no capability bit until rejoin, which the tooltip's
     * wording tolerates). Deregistration is deliberately NOT here — see the class
     * javadoc's registration-lifecycle rule and {@link #onSessionEnd()}.
     */
    void maybeRegister() {
        if (!this.dead && this.enabled.getAsBoolean() && !this.registered) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        }
    }

    /**
     * Session teardown (the loaders' disconnect events): drop the session's queue,
     * re-arm the death latches (session-scoped — one bad session must not disable
     * the feature until restart), and settle registration for the NEXT handshake
     * (a disabled bridge releases the capability bit here, never mid-session).
     */
    void onSessionEnd() {
        clearQueue();
        this.dead = false;
        this.consecutiveExtractFailures.set(0);
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        }
        this.sessionEndPending = true;
    }

    /** Disconnect teardown; drops references without invoking old Xaero objects. */
    private void settleSessionEnd() {
        this.sessionEndPending = false;
        this.droppedUpdates.addAndGet(this.pendingUpdates.size());
        this.pendingUpdates.clear();
        this.consecutiveFailures = 0;
        this.frameFlushRan = false;
        this.frameActiveThisPump = false;
        this.rebuildSpentSinceLastPumpNanos = 0;
        this.framesSinceLastPump = 0;
        this.rebuildNanosMax = 0;
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        } else {
            maybeRegister();
        }
    }

    /** The registered consumer — a thin shell over {@link #offerColumn}. */
    private VoxelColumnConsumer buildConsumer() {
        return (level, dimension, chunkX, chunkZ, columnData) -> {
            try {
                offerColumn(dimension, chunkX, chunkZ,
                        level.getMinBuildHeight(), level.getMaxBuildHeight(), columnData);
                this.consecutiveExtractFailures.set(0);
            } catch (Throwable t) {
                // Swallow EVERYTHING, Errors included: VSSApi.dispatchColumn converts
                // any escape into reportIngestFailure — a re-serve loop for a map
                // problem (review MAJOR). A VM-fatal Error will resurface on a frame
                // that can afford it; here it would cost LOD correctness.
                long n = EXTRACT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    VSSLogger.warn("Xaero map bridge: tile extraction failed (" + n
                            + " failure(s) since the last report)", t);
                }
                if (this.consecutiveExtractFailures.incrementAndGet() >= THROW_LATCH
                        && !this.dead) {
                    this.dead = true;
                    clearQueue();
                    VSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive"
                            + " extraction failures — disabling the bridge for this session"
                            + " (LODs are unaffected)", t);
                }
            }
        };
    }

    /** Decode-thread entry: extract + enqueue (latest-wins, bounded, oldest drops). */
    void offerColumn(ResourceKey<Level> dimension, int chunkX, int chunkZ,
                     int worldBottomY, int worldTopY, VoxelColumnData columnData) {
        if (this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            return;
        }
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        synchronized (this.queueLock) {
            // Don't pay the 256-pixel extraction for a tile the full queue would
            // evict on arrival (sustained-overflow CPU on the LOD decode thread).
            if (this.queue.size() >= MAX_QUEUE && !this.queue.containsKey(key)) {
                this.droppedOverflow.incrementAndGet();
                return;
            }
        }
        var tile = XaeroTileExtractor.extract(chunkX, chunkZ, worldBottomY, worldTopY, columnData);
        offerPrepared(dimension, tile);
    }

    /** Approximate retained bytes for the byte gauge (shallow arrays + overlay runs). */
    static int approxBytes(XaeroTileExtractor.PreparedTile tile) {
        int bytes = 4800;
        for (var runs : tile.overlays()) {
            if (runs != null) bytes += 24 + runs.length * 32;
        }
        return bytes;
    }

    /** Enqueue seam (tests build {@link XaeroTileExtractor.PreparedTile}s directly). */
    void offerPrepared(Object dimension, XaeroTileExtractor.PreparedTile tile) {
        int chunkX = tile.chunkX();
        int chunkZ = tile.chunkZ();
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        int bytes = approxBytes(tile);
        synchronized (this.queueLock) {
            var existing = this.queue.get(key);
            if (existing != null) {
                if (existing.dimension == dimension) {
                    this.queuedBytes += bytes - existing.bytes;
                    existing.tile = tile;
                    existing.bytes = bytes;
                    return;
                }
                // Stale-dimension entry: the new serve replaces it (fresh Entry, so
                // an in-flight pump pass's compare-and-remove cannot delete it).
                this.queuedBytes -= existing.bytes;
                this.queue.remove(key);
            }
            while (!this.queue.isEmpty()
                    && (this.queue.size() >= MAX_QUEUE
                        || this.queuedBytes + bytes > MAX_QUEUE_BYTES)) {
                var it = this.queue.entrySet().iterator();
                this.queuedBytes -= it.next().getValue().bytes;
                it.remove();
                this.droppedOverflow.incrementAndGet();
            }
            this.queuedBytes += bytes;
            this.queue.put(key, new Entry(dimension, tile, bytes));
        }
    }

    void clearQueue() {
        synchronized (this.queueLock) {
            this.queue.clear();
            this.queuedBytes = 0;
            this.intakeBackpressured = false;
        }
    }

    boolean shouldBackpressureInputNow() {
        if (this.dead || !this.enabled.getAsBoolean()) {
            synchronized (this.queueLock) {
                this.intakeBackpressured = false;
            }
            return false;
        }
        synchronized (this.queueLock) {
            int pending = this.pendingUpdates.size();
            if (!this.intakeBackpressured) {
                this.intakeBackpressured = this.queue.size() >= INTAKE_QUEUE_HIGH_WATERMARK
                        || this.queuedBytes >= INTAKE_BYTES_HIGH_WATERMARK
                        || pending >= INTAKE_UPDATES_HIGH_WATERMARK;
            } else if (this.queue.size() <= INTAKE_QUEUE_LOW_WATERMARK
                    && this.queuedBytes <= INTAKE_BYTES_LOW_WATERMARK
                    && pending <= INTAKE_UPDATES_LOW_WATERMARK) {
                this.intakeBackpressured = false;
            }
            return this.intakeBackpressured;
        }
    }

    boolean hasPendingWorkNow() {
        synchronized (this.queueLock) {
            return !this.queue.isEmpty() || !this.pendingUpdates.isEmpty();
        }
    }

    /**
     * Remove only if the entry AND its tile are still the ones this pump pass
     * examined — a plain remove would silently delete a fresher tile (or a
     * replacement Entry) the decode thread installed mid-commit (review MINOR:
     * the latest-wins guarantee must survive the commit window).
     */
    private void removeIfCurrent(Long key, Entry entry, XaeroTileExtractor.PreparedTile tile) {
        synchronized (this.queueLock) {
            var current = this.queue.get(key);
            if (current == entry && entry.tile == tile) {
                this.queuedBytes -= entry.bytes;
                this.queue.remove(key);
            }
        }
    }

    int queuedForTest() {
        synchronized (this.queueLock) {
            return this.queue.size();
        }
    }

    boolean hasQueuedForTest(int chunkX, int chunkZ) {
        synchronized (this.queueLock) {
            return this.queue.containsKey(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        }
    }

    long queuedBytesForTest() {
        synchronized (this.queueLock) {
            return this.queuedBytes;
        }
    }

    boolean deadForTest() {
        return this.dead;
    }

    boolean registeredForTest() {
        return this.registered;
    }

    long counterForTest(String name) {
        return switch (name) {
            case "written" -> this.written.get();
            case "skipped_loaded" -> this.skippedLoaded.get();
            case "defer_events" -> this.deferEvents.get();
            case "dropped_overflow" -> this.droppedOverflow.get();
            case "dropped_stale" -> this.droppedStale.get();
            case "dropped_expired" -> this.droppedExpired.get();
            case "commit_failures" -> this.commitFailures.get();
            case "load_requests" -> this.loadRequests.get();
            case "buffer_updates" -> this.bufferUpdates.get();
            case "frame_flushes" -> this.frameFlushes.get();
            case "pending_updates" -> this.pendingUpdates.size();
            case "dropped_updates" -> this.droppedUpdates.get();
            case "dropped_unloaded" -> this.droppedUnloaded.get();
            default -> throw new IllegalArgumentException(name);
        };
    }

    String describe() {
        String state = this.dead ? "dead" : this.enabled.getAsBoolean() ? "active" : "disabled";
        long dropped = this.droppedOverflow.get() + this.droppedStale.get()
                + this.droppedExpired.get();
        return "XaeroMap: state=" + state + ", queued=" + queuedForTest()
                + ", written=" + this.written.get()
                + ", skipped_loaded=" + this.skippedLoaded.get()
                + ", defer_events=" + this.deferEvents.get()
                + ", dropped=" + dropped
                + ", commit_failures=" + this.commitFailures.get()
                + ", load_requests=" + this.loadRequests.get()
                + ", pending_updates=" + this.pendingUpdates.size()
                + ", buffer_updates=" + this.bufferUpdates.get()
                + ", frame_flushes=" + this.frameFlushes.get()
                + ", rebuild_ms=" + this.rebuildNanos.get() / 1_000_000
                + ", rebuild_max_us=" + this.rebuildNanosMax / 1_000
                + ", dropped_updates=" + this.droppedUpdates.get()
                + ", dropped_unloaded=" + this.droppedUnloaded.get();
    }

    // ---- the pump (main client thread) ----

    void pump() {
        if (this.sessionEndPending) settleSessionEnd();
        maybeRegister();
        if (this.dead) {
            if (!this.pendingUpdates.isEmpty()) {
                this.droppedUpdates.addAndGet(this.pendingUpdates.size());
                this.pendingUpdates.clear();
            }
            return;
        }
        this.pumpCount++;
        this.frameActiveThisPump = this.frameFlushRan;
        this.frameFlushRan = false;
        this.rebuildSpentSinceLastPumpNanos = 0;
        this.framesSinceLastPump = 0;
        boolean commitsEnabled = this.enabled.getAsBoolean();
        if (!commitsEnabled) {
            clearQueue(); // the live toggle: flipping off drops the backlog immediately
        }
        synchronized (this.queueLock) {
            if (this.queue.isEmpty() && this.pendingUpdates.isEmpty()) return;
        }
        try {
            // No blanket failure-count reset here: commit failures are contained per
            // entry inside the drain, so the ladder returning normally proves nothing —
            // only a successful COMMIT resets the death-latch count.
            pumpLadder(commitsEnabled);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        }
    }

    /**
     * The native {@code MapWriter.onRender} gate ladder, verbatim (plan §2.7). Any
     * not-ready gate returns — entries stay queued (deferral, not deletion; the
     * bounded queue is the TTL). The {@code mainStuffSync} dimension equality is
     * THE anti-wrong-dimension binding: like Xaero's own writer, commits pause
     * while the user browses another dimension's map.
     */
    private void pumpLadder(boolean commitsEnabled) throws Throwable {
        Object session = this.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return;
        Object mp = this.h.getMapProcessor.invoke(session);
        if (mp == null) return;
        Object renderPause = this.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.h.isWritingPaused.invoke(mp)) return;
            if ((boolean) this.h.isWaitingForWorldUpdate.invoke(mp)) return;
            Object saveLoad = this.h.getMapSaveLoad.invoke(mp);
            if (!(boolean) this.h.isRegionDetectionComplete.invoke(saveLoad)) return;
            if (!(boolean) this.h.isCurrentMultiworldWritable.invoke(mp)) return;
            Object world = this.h.getWorld.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.h.isCacheOnlyMode.invoke(mapWorld)) {
                return;
            }
            String worldId = (String) this.h.getCurrentWorldId.invoke(mp);
            if (worldId == null
                    || (boolean) this.h.ignoreWorld.invoke(mp, world)) {
                return;
            }
            Object dimensionId;
            Object mainSync = this.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.h.mainWorld.invoke(mp) != world) return;
                dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.levelOps.dimension(world) != dimensionId) return;
            }
            tickFlush(mp, worldId, dimensionId);
            if (this.dead || !commitsEnabled) return;
            drainEntries(mp, saveLoad, world, dimensionId);
        }
    }

    /**
     * Drain by 32x32-chunk Xaero region. An unloaded region is probed once per
     * pump instead of once per queued column, leaving the time budget for real
     * commits and allowing the load phase to keep several regions in flight.
     */
    private void drainEntries(Object mp, Object saveLoad,
                              Object world, Object dimensionId) throws Throwable {
        long start = System.nanoTime();

        List<Pending> snapshot;
        synchronized (this.queueLock) {
            snapshot = new ArrayList<>(this.queue.size());
            for (var queued : this.queue.entrySet()) {
                snapshot.add(new Pending(queued.getKey(), queued.getValue(), queued.getValue().tile));
            }
        }
        var buckets = new LinkedHashMap<Long, List<Pending>>();
        for (var pending : snapshot) {
            long regionKey = ((long) (pending.tile().chunkX() >> 5) << 32)
                    | ((pending.tile().chunkZ() >> 5) & 0xFFFFFFFFL);
            buckets.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(pending);
        }

        var bucketKeys = new ArrayList<>(buckets.keySet());
        var waiting = new ArrayList<WaitingRegion>();
        int commits = 0;
        boolean progressed = false;
        int size = bucketKeys.size();
        int startIndex = size == 0 ? 0 : Math.floorMod(this.drainRotation++, size);
        bucketLoop:
        for (int n = 0; n < size; n++) {
            Long regionKey = bucketKeys.get((startIndex + n) % size);
            var bucket = buckets.get(regionKey);
            for (var pending : bucket) {
                if (this.pendingUpdates.size() >= this.pendingUpdatesHardCap) {
                    break bucketLoop;
                }
                if (progressed && (commits >= MAX_COMMITS_PER_PUMP
                        || System.nanoTime() - start > this.pumpNanosBudget)) {
                    break bucketLoop;
                }
                var entry = pending.entry();
                var tile = pending.tile();
                if (entry.dimension != dimensionId) {
                    removeIfCurrent(pending.key(), entry, tile);
                    this.droppedStale.incrementAndGet();
                    progressed = true;
                    continue;
                }
                if (nativelyWritable(world, tile.chunkX(), tile.chunkZ())
                        && hasValidNativeMapTile(mp, tile)) {
                    removeIfCurrent(pending.key(), entry, tile);
                    this.skippedLoaded.incrementAndGet();
                    progressed = true;
                    continue;
                }

                progressed = true;
                var outcome = commitEntry(mp, dimensionId, tile);
                switch (outcome) {
                    case COMMITTED -> {
                        removeIfCurrent(pending.key(), entry, tile);
                        this.written.incrementAndGet();
                        this.consecutiveFailures = 0;
                        commits++;
                    }
                    case DEFERRED_TILE -> {
                        this.deferEvents.incrementAndGet();
                        if (++entry.ladderReadyDeferrals > DEFER_CAP) {
                            removeIfCurrent(pending.key(), entry, tile);
                            this.droppedExpired.incrementAndGet();
                        }
                    }
                    case DEFERRED_REGION -> {
                        this.deferEvents.incrementAndGet();
                        for (var sibling : bucket) {
                            if (++sibling.entry().ladderReadyDeferrals > DEFER_CAP) {
                                removeIfCurrent(sibling.key(), sibling.entry(), sibling.tile());
                                this.droppedExpired.incrementAndGet();
                            }
                        }
                        continue bucketLoop;
                    }
                    case AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT -> {
                        this.deferEvents.incrementAndGet();
                        waiting.add(new WaitingRegion(regionKey, bucket.size(), outcome));
                        continue bucketLoop;
                    }
                    case FAILED -> {
                        removeIfCurrent(pending.key(), entry, tile);
                        if (this.dead) return;
                    }
                }
            }
        }
        grantLoads(mp, saveLoad, waiting);
    }

    /** Xaero waits for the complete 3x3 neighbourhood before writing a chunk. */
    boolean nativelyWritable(Object world, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!this.levelOps.isChunkLoaded(world, chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A loaded vanilla neighbourhood is not enough to prove that Xaero has
     * already written this column.  After a VSS/Xaero reload the vanilla chunks
     * may still be resident while their map tiles are absent (or only allocated
     * but not written), so treating them as native-owned leaves a black hole
     * until the player teleports and forces a chunk reload.
     */
    private boolean hasValidNativeMapTile(Object mp,
                                          XaeroTileExtractor.PreparedTile tile) throws Throwable {
        int tileChunkX = tile.chunkX() >> 2;
        int tileChunkZ = tile.chunkZ() >> 2;
        Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                tileChunkX >> 3, tileChunkZ >> 3, true);
        if (region == null) return false;

        Object writerPause = this.h.writerThreadPauseSync.invoke(region);
        synchronized (writerPause) {
            synchronized (region) {
                if ((byte) this.h.getLoadState.invoke(region) != 2) return false;
                Object tileChunk = this.h.regionGetChunk.invoke(region,
                        tileChunkX & 7, tileChunkZ & 7);
                if (tileChunk == null
                        || (int) this.h.tileChunkGetLoadState.invoke(tileChunk) != 2) {
                    return false;
                }
                Object mapTile = this.h.getTile.invoke(tileChunk,
                        tile.chunkX() & 3, tile.chunkZ() & 3);
                return mapTile != null
                        && (boolean) this.h.tileIsLoaded.invoke(mapTile)
                        && (boolean) this.h.tileWasWrittenOnce.invoke(mapTile);
            }
        }
    }

    private enum Outcome {
        COMMITTED, DEFERRED_REGION, DEFERRED_TILE,
        AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT,
        FAILED
    }

    /**
     * One entry against its region — the decompiled {@code MapWriter.writeChunk}
     * region discipline: {@code writerThreadPauseSync} + {@code !isWritingPaused()}
     * (the save-race exclusion), the region monitor for load-state/visit/resting,
     * {@code setBeingWritten(true)} set and NEVER cleared by us (save-eligibility —
     * the save path owns the reset), tile-chunk creation with its cache flags,
     * then the pixel commit. Region loads are granted after all buckets are probed.
     */
    private Outcome commitEntry(Object mp, Object dimensionId,
                                XaeroTileExtractor.PreparedTile tile) {
        try {
            int chunkX = tile.chunkX();
            int chunkZ = tile.chunkZ();
            int tileChunkX = chunkX >> 2;
            int tileChunkZ = chunkZ >> 2;
            int localTcX = tileChunkX & 7;
            int localTcZ = tileChunkZ & 7;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    tileChunkX >> 3, tileChunkZ >> 3, true);
            if (region == null) return Outcome.DEFERRED_REGION;
            Object writerPause = this.h.writerThreadPauseSync.invoke(region);
            synchronized (writerPause) {
                if ((boolean) this.h.regionIsWritingPaused.invoke(region)) {
                    return Outcome.DEFERRED_REGION;
                }
                boolean resting;
                boolean createdTileChunk = false;
                Object tileChunk = null;
                synchronized (region) {
                    byte loadState = (byte) this.h.getLoadState.invoke(region);
                    boolean proper = loadState == 2;
                    if (proper) this.h.registerVisit.invoke(region);
                    resting = (boolean) this.h.isResting.invoke(region);
                    if (resting) {
                        this.h.setBeingWritten.invoke(region, true);
                        if (proper) {
                            tileChunk = this.h.regionGetChunk.invoke(region, localTcX, localTcZ);
                            if (tileChunk == null) {
                                tileChunk = this.h.newMapTileChunk.invoke(region, tileChunkX, tileChunkZ);
                                this.h.regionSetChunk.invoke(region, localTcX, localTcZ, tileChunk);
                                this.h.tileChunkSetLoadState.invoke(tileChunk, (byte) 2);
                                this.h.setAllCachePrepared.invoke(region, false);
                                createdTileChunk = true;
                            }
                        }
                    }
                    if (!proper) {
                        if ((boolean) this.h.canRequestReload.invoke(region)) {
                            return Outcome.AWAITING_REQUESTABLE;
                        }
                        return loadState == 3 ? Outcome.AWAITING_PARKED
                                : Outcome.AWAITING_IN_FLIGHT;
                    }
                }
                if (!resting || tileChunk == null) return Outcome.DEFERRED_REGION;
                if ((int) this.h.tileChunkGetLoadState.invoke(tileChunk) != 2) {
                    return Outcome.DEFERRED_TILE;
                }
                Object leafTexture = this.h.getLeafTexture.invoke(tileChunk);
                if ((boolean) this.h.shouldDownloadFromPBO.invoke(leafTexture)) {
                    return Outcome.DEFERRED_TILE;
                }

                commitPixels(mp, dimensionId, region, tileChunk, createdTileChunk,
                        localTcX, localTcZ, tile);
                return Outcome.COMMITTED;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return Outcome.FAILED;
        }
    }

    /** Keep at most eight Xaero region loads in flight, derived from Xaero's own
     * requestability state so failed loads cannot leak slots. */
    private void grantLoads(Object mp, Object saveLoad, List<WaitingRegion> waiting) {
        int inFlight = 0;
        var candidates = new ArrayList<WaitingRegion>();
        for (var region : waiting) {
            if (region.verdict() == Outcome.AWAITING_IN_FLIGHT) {
                inFlight++;
            } else {
                candidates.add(region);
            }
        }
        int budget = MAX_OUTSTANDING_LOADS - inFlight;
        if (budget <= 0 || candidates.isEmpty()) return;
        candidates.sort((a, b) -> Integer.compare(b.tiles(), a.tiles()));
        var chosen = candidates.subList(0, Math.min(budget, candidates.size()));
        // requestLoad front-inserts; issue the largest bucket last so it drains first.
        for (int i = chosen.size() - 1; i >= 0; i--) {
            if (this.dead) return;
            if (requestRegionLoad(mp, saveLoad, chosen.get(i).regionKey())) {
                this.loadRequests.incrementAndGet();
            }
        }
    }

    private boolean requestRegionLoad(Object mp, Object saveLoad, long regionKey) {
        try {
            int regionX = (int) (regionKey >> 32);
            int regionZ = (int) regionKey;
            Object region = this.h.getLeafMapRegion.invoke(mp, SURFACE_LAYER,
                    regionX, regionZ, true);
            if (region == null) return false;
            synchronized (region) {
                byte loadState = (byte) this.h.getLoadState.invoke(region);
                if (loadState == 2) return false;
                boolean revived = false;
                if (loadState == 3) {
                    this.h.setLoadState.invoke(region, (byte) 4);
                    revived = true;
                }
                if (!(boolean) this.h.isResting.invoke(region)
                        || !(boolean) this.h.canRequestReload.invoke(region)) {
                    if (revived) this.h.setLoadState.invoke(region, (byte) 3);
                    return false;
                }
                this.h.setBeingWritten.invoke(region, true);
                this.h.requestLoad.invoke(saveLoad, region, "vss-xaero-bridge");
                return true;
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return false;
        }
    }

    /** The decompiled per-tile commit sequence, verbatim order (plan §1). */
    private void commitPixels(Object mp, Object dimensionId, Object region, Object tileChunk,
                              boolean createdTileChunk, int localTcX, int localTcZ,
                              XaeroTileExtractor.PreparedTile tile) throws Throwable {
        int insideX = tile.chunkX() & 3;
        int insideZ = tile.chunkZ() & 3;
        Object mapTile = this.h.getTile.invoke(tileChunk, insideX, insideZ);
        if (mapTile == null) {
            Object pool = this.h.getTilePool.invoke(mp);
            String dimensionToken = (String) this.h.getCurrentDimension.invoke(mp);
            mapTile = this.h.poolGet.invoke(pool, dimensionToken, tile.chunkX(), tile.chunkZ());
            this.h.tileChunkSetChanged.invoke(tileChunk, true);
        }
        Object overlayManager = this.h.getOverlayManager.invoke(mp);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int i = x * 16 + z;
                Object block = this.h.newMapBlock.invoke();
                this.h.prepareForWriting.invoke(block, tile.worldBottomY());
                var runs = tile.overlays()[i];
                if (runs != null) {
                    for (var run : runs) {
                        Object overlay = this.h.newOverlay.invoke(run.state(), run.light(), run.glowing());
                        this.h.increaseOpacity.invoke(overlay, run.opacity());
                        Object original = this.h.getOriginal.invoke(overlayManager, overlay);
                        this.h.addOverlay.invoke(block, original);
                    }
                }
                this.h.blockWrite.invoke(block, tile.floorState()[i],
                        (int) tile.floorY()[i], (int) tile.topY()[i],
                        tile.biome()[i], tile.light()[i], tile.glowing()[i], false);
                this.h.setBlock.invoke(mapTile, x, z, block);
            }
        }
        this.h.setWorldInterpretationVersion.invoke(mapTile, 1);
        this.h.setWrittenCave.invoke(mapTile, SURFACE_LAYER,
                (int) this.h.getCaveModeDepthConfig.invoke(mp));
        this.h.tileChunkSetChanged.invoke(tileChunk, true);
        this.h.setTile.invoke(tileChunk, insideX, insideZ, mapTile,
                this.h.getBlockStateShortShapeCache.invoke(mp), mp);
        this.h.setWrittenOnce.invoke(mapTile, true);
        this.h.setLoaded.invoke(mapTile, true);
        if (createdTileChunk) {
            if ((boolean) this.h.includeInSave.invoke(tileChunk)) {
                this.h.setHasHadTerrain.invoke(tileChunk);
            }
            Object highlights = this.h.getMapRegionHighlightsPreparer.invoke(mp);
            this.h.highlightsPrepare.invoke(highlights, region, localTcX, localTcZ, false);
        }
        // Never set Xaero's asynchronous sweep flag: it can be consumed after the
        // saver starts. Keep the change marked and rebuild it later under the same
        // writer-pause/resting gates, coalesced per 4x4-chunk tile chunk.
        notePendingUpdate(mp, dimensionId, region, tileChunk, localTcX, localTcZ,
                tile.chunkX() >> 2, tile.chunkZ() >> 2, insideX, insideZ);
    }

    // ---- safe, coalesced texture rebuild phase ----

    void frameFlush() {
        if (this.dead || this.sessionEndPending || this.pendingUpdates.isEmpty()) return;
        this.framesSinceLastPump++;
        if (nothingDue()) {
            this.frameFlushRan = true;
            return;
        }
        if (this.rebuildSpentSinceLastPumpNanos > 0
                && this.rebuildSpentSinceLastPumpNanos >= rebuildBudgetWithBorrow()) {
            this.frameFlushRan = true;
            return;
        }
        try {
            frameLadder();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
        }
    }

    private boolean nothingDue() {
        if (this.pendingUpdates.size() > this.pendingUpdatesSoftCap) return false;
        for (var update : this.pendingUpdates.values()) {
            if (update.touchedTiles == COMPLETE_TILE_MASK
                    || this.pumpCount - update.lastTouchPump >= this.updateIdlePumps
                    || this.pumpCount - update.firstTouchPump >= this.updateMaxDeferPumps
                    || update.stalledSincePump >= 0) {
                return false;
            }
        }
        return true;
    }

    private void frameLadder() throws Throwable {
        Object session = this.h.getCurrentSession.invoke();
        if (session == null || !(boolean) this.h.sessionIsUsable.invoke(session)) return;
        Object mp = this.h.getMapProcessor.invoke(session);
        if (mp == null) return;
        Object renderPause = this.h.renderThreadPauseSync.invoke(mp);
        synchronized (renderPause) {
            if ((boolean) this.h.isWritingPaused.invoke(mp)
                    || (boolean) this.h.isWaitingForWorldUpdate.invoke(mp)) {
                return;
            }
            Object saveLoad = this.h.getMapSaveLoad.invoke(mp);
            if (!(boolean) this.h.isRegionDetectionComplete.invoke(saveLoad)
                    || !(boolean) this.h.isCurrentMultiworldWritable.invoke(mp)) {
                return;
            }
            Object world = this.h.getWorld.invoke(mp);
            Object mapWorld = this.h.getMapWorld.invoke(mp);
            if (world == null || (boolean) this.h.isCurrentMapLocked.invoke(mp)
                    || (boolean) this.h.isCacheOnlyMode.invoke(mapWorld)) {
                return;
            }
            String worldId = (String) this.h.getCurrentWorldId.invoke(mp);
            if (worldId == null || (boolean) this.h.ignoreWorld.invoke(mp, world)) return;
            Object dimensionId;
            Object mainSync = this.h.mainStuffSync.invoke(mp);
            synchronized (mainSync) {
                if (this.h.mainWorld.invoke(mp) != world) return;
                dimensionId = this.h.getCurrentDimensionId.invoke(mapWorld);
                if (this.levelOps.dimension(world) != dimensionId) return;
            }
            this.frameFlushRan = true;
            this.frameFlushes.incrementAndGet();
            int pending = this.pendingUpdates.size();
            boolean scarceFrames = this.framesSinceLastPump <= 1;
            int cap = this.frameMaxRebuilds
                    + (scarceFrames && pending > this.pendingUpdatesSoftCap ? 1 : 0)
                    + (scarceFrames && pending > this.pendingUpdatesHardCap / 2 ? 1 : 0);
            long remaining = Math.max(1L,
                    rebuildBudgetWithBorrow() - this.rebuildSpentSinceLastPumpNanos);
            long before = this.rebuildNanos.get();
            flushPendingUpdates(mp, worldId, dimensionId, remaining, cap, false);
            this.rebuildSpentSinceLastPumpNanos += this.rebuildNanos.get() - before;
        }
    }

    private void notePendingUpdate(Object mp, Object dimensionId, Object region, Object tileChunk,
                                   int localTcX, int localTcZ, int tileChunkX, int tileChunkZ,
                                   int insideX, int insideZ)
            throws Throwable {
        var key = new PendingKey(dimensionId,
                ((long) tileChunkX << 32) | (tileChunkZ & 0xFFFFFFFFL));
        String worldId = (String) this.h.getCurrentWorldId.invoke(mp);
        var existing = this.pendingUpdates.remove(key);
        if (existing != null && existing.tileChunk == tileChunk
                && existing.processor == mp && java.util.Objects.equals(existing.worldId, worldId)) {
            existing.lastTouchPump = this.pumpCount;
            existing.stalledSincePump = -1;
            existing.touchedTiles |= 1 << ((insideZ << 2) | insideX);
            this.pendingUpdates.put(key, existing);
        } else {
            if (existing != null) this.droppedUnloaded.incrementAndGet();
            this.pendingUpdates.put(key, new PendingUpdate(mp, worldId, dimensionId, region,
                    tileChunk, localTcX, localTcZ, this.pumpCount,
                    1 << ((insideZ << 2) | insideX)));
        }
    }

    private enum UpdateResult { DONE, NOT_READY, DROPPED, FAILED }

    private static final class RebuildArgs {
        Object tint;
        Object overlayManager;
        Object shapeCache;
        Object fastConfig;
        int rebuilt;
        int probes;
        final java.util.Set<Object> notReadyRegions =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    private long rebuildBudgetWithBorrow() {
        boolean queueEmpty;
        synchronized (this.queueLock) {
            queueEmpty = this.queue.isEmpty();
        }
        long borrow = queueEmpty ? this.updateBorrowNanos
                : this.pendingUpdates.size() > this.pendingUpdatesSoftCap
                        ? this.updateBorrowNanos / 2 : 0;
        return borrow > Long.MAX_VALUE - this.updateNanosBudget
                ? Long.MAX_VALUE : this.updateNanosBudget + borrow;
    }

    private void tickFlush(Object mp, String worldId, Object dimensionId) {
        boolean frameActive = this.frameActiveThisPump;
        long budget = frameActive ? this.updateNanosBudget : rebuildBudgetWithBorrow();
        flushPendingUpdates(mp, worldId, dimensionId, budget,
                frameActive ? 0 : Integer.MAX_VALUE, true);
    }

    private void flushPendingUpdates(Object mp, String worldId, Object dimensionId,
                                     long budget, int maxRebuilds, boolean keepVisited) {
        if (this.pendingUpdates.isEmpty()) return;
        long start = System.nanoTime();
        var args = new RebuildArgs();
        try {
            if (keepVisited) keepOwedRegionsVisited(mp, worldId, dimensionId);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return;
        }

        int removed = 0;
        int overflow = this.pendingUpdates.size() - this.pendingUpdatesSoftCap;
        var it = this.pendingUpdates.values().iterator();
        while (it.hasNext()) {
            if ((removed > 0 || args.probes > FLUSH_PROBE_EXEMPT_FLOOR)
                    && System.nanoTime() - start > budget) {
                break;
            }
            var update = it.next();
            boolean due = overflow-- > 0
                    || update.touchedTiles == COMPLETE_TILE_MASK
                    || this.pumpCount - update.lastTouchPump >= this.updateIdlePumps
                    || this.pumpCount - update.firstTouchPump >= this.updateMaxDeferPumps
                    || update.stalledSincePump >= 0;
            if (!due) continue;

            UpdateResult result;
            if (update.processor != mp || !java.util.Objects.equals(update.worldId, worldId)) {
                result = UpdateResult.DROPPED;
            } else if (update.dimension != dimensionId) {
                result = UpdateResult.NOT_READY;
            } else if (maxRebuilds == 0) {
                continue;
            } else {
                result = rebuildTileChunk(mp, update, args);
            }

            switch (result) {
                case DONE -> {
                    it.remove();
                    removed++;
                }
                case DROPPED -> {
                    it.remove();
                    removed++;
                    if (update.processor != mp
                            || !java.util.Objects.equals(update.worldId, worldId)) {
                        this.droppedUpdates.incrementAndGet();
                    }
                }
                case NOT_READY -> {
                    if (update.stalledSincePump < 0) {
                        update.stalledSincePump = this.pumpCount;
                    } else if (this.pumpCount - update.stalledSincePump
                            >= this.updateMaxStallPumps) {
                        it.remove();
                        removed++;
                        this.droppedUpdates.incrementAndGet();
                    }
                }
                case FAILED -> {
                    it.remove();
                    removed++;
                    this.droppedUpdates.incrementAndGet();
                }
            }
            if (this.dead) break;
            if (maxRebuilds > 0 && args.rebuilt >= maxRebuilds) break;
        }
    }

    private void keepOwedRegionsVisited(Object mp, String worldId, Object dimensionId)
            throws Throwable {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var update : this.pendingUpdates.values()) {
            if (update.processor != mp || !java.util.Objects.equals(update.worldId, worldId)
                    || update.dimension != dimensionId || !seen.add(update.region)) {
                continue;
            }
            synchronized (update.region) {
                if ((byte) this.h.getLoadState.invoke(update.region) == 2) {
                    this.h.registerVisit.invoke(update.region);
                }
            }
        }
    }

    private UpdateResult rebuildTileChunk(Object mp, PendingUpdate update, RebuildArgs args) {
        if (args.notReadyRegions.contains(update.region)) return UpdateResult.NOT_READY;
        args.probes++;
        try {
            Object writerPause = this.h.writerThreadPauseSync.invoke(update.region);
            synchronized (writerPause) {
                if ((boolean) this.h.regionIsWritingPaused.invoke(update.region)) {
                    args.notReadyRegions.add(update.region);
                    return UpdateResult.NOT_READY;
                }
                synchronized (update.region) {
                    if ((byte) this.h.getLoadState.invoke(update.region) != 2
                            || this.h.regionGetChunk.invoke(update.region,
                                    update.localTcX, update.localTcZ) != update.tileChunk
                            || (int) this.h.tileChunkGetLoadState.invoke(update.tileChunk) != 2) {
                        this.droppedUnloaded.incrementAndGet();
                        return UpdateResult.DROPPED;
                    }
                    if (!(boolean) this.h.isResting.invoke(update.region)) {
                        args.notReadyRegions.add(update.region);
                        return UpdateResult.NOT_READY;
                    }
                    if ((boolean) this.h.tileChunkWasChanged.invoke(update.tileChunk)) {
                        this.h.setBeingWritten.invoke(update.region, true);
                        if (args.fastConfig == null) {
                            args.tint = this.h.getWorldBlockTintProvider.invoke(mp);
                            args.overlayManager = this.h.getOverlayManager.invoke(mp);
                            args.shapeCache = this.h.getBlockStateShortShapeCache.invoke(mp);
                            args.fastConfig = this.h.newMapUpdateFastConfig.invoke(mp);
                        }
                        long start = System.nanoTime();
                        this.h.tileChunkUpdateBuffers.invoke(update.tileChunk, mp, args.tint,
                                args.overlayManager, false, args.shapeCache, args.fastConfig);
                        long took = System.nanoTime() - start;
                        this.rebuildNanos.addAndGet(took);
                        if (took > this.rebuildNanosMax) this.rebuildNanosMax = took;
                        args.rebuilt++;
                        this.h.tileChunkSetChanged.invoke(update.tileChunk, false);
                        this.bufferUpdates.incrementAndGet();
                    }
                    return UpdateResult.DONE;
                }
            }
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            noteFailure(t);
            return UpdateResult.FAILED;
        }
    }

    private void noteFailure(Throwable t) {
        this.commitFailures.incrementAndGet();
        long n = COMMIT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) {
            VSSLogger.warn("Xaero map bridge: commit failed (" + n
                    + " failure(s) since the last report)", t);
        }
        if (++this.consecutiveFailures >= THROW_LATCH) {
            this.dead = true;
            clearQueue();
            VSSLogger.error("Xaero map bridge: " + THROW_LATCH + " consecutive failures — "
                    + "disabling the bridge for this session (LODs are unaffected)", t);
        }
    }

    // ---- the reflective surface (plan §4; all members verified public) ----

    /**
     * Resolve-once handle set. All-or-nothing: any missing member throws and the
     * bridge stays off. Xaero-typed members resolve with exact types from the
     * resolved classes; the three {@code ClientLevel}-typed members
     * ({@code getWorld}, {@code mainWorld}, {@code ignoreWorld}) resolve by
     * name-scan (the {@code MoonriseReadCompat} shape-scan precedent) and are
     * handled as Objects behind {@link LevelOps}, because tests cannot construct
     * a {@code ClientLevel}.
     */
    static final class Handles {
        final MethodHandle getCurrentSession;
        final MethodHandle sessionIsUsable;
        final MethodHandle getMapProcessor;
        final MethodHandle renderThreadPauseSync;
        final MethodHandle mainStuffSync;
        final MethodHandle mainWorld;
        final MethodHandle isWritingPaused;
        final MethodHandle isWaitingForWorldUpdate;
        final MethodHandle isCurrentMapLocked;
        final MethodHandle isCurrentMultiworldWritable;
        final MethodHandle getCurrentWorldId;
        final MethodHandle getCurrentDimension;
        final MethodHandle getWorld;
        final MethodHandle ignoreWorld;
        final MethodHandle getMapWorld;
        final MethodHandle getMapSaveLoad;
        final MethodHandle getLeafMapRegion;
        final MethodHandle getTilePool;
        final MethodHandle getOverlayManager;
        final MethodHandle getBlockStateShortShapeCache;
        final MethodHandle getMapRegionHighlightsPreparer;
        final MethodHandle getCaveModeDepthConfig;
        final MethodHandle isCacheOnlyMode;
        final MethodHandle getCurrentDimensionId;
        final MethodHandle isRegionDetectionComplete;
        final MethodHandle requestLoad;
        final MethodHandle writerThreadPauseSync;
        final MethodHandle regionIsWritingPaused;
        final MethodHandle getLoadState;
        final MethodHandle setLoadState;
        final MethodHandle isResting;
        final MethodHandle registerVisit;
        final MethodHandle setBeingWritten;
        final MethodHandle canRequestReload;
        final MethodHandle setAllCachePrepared;
        final MethodHandle regionGetChunk;
        final MethodHandle regionSetChunk;
        final MethodHandle newMapTileChunk;
        final MethodHandle tileChunkGetLoadState;
        final MethodHandle tileChunkSetLoadState;
        final MethodHandle tileChunkSetChanged;
        final MethodHandle tileChunkWasChanged;
        final MethodHandle tileChunkUpdateBuffers;
        final MethodHandle getWorldBlockTintProvider;
        final MethodHandle newMapUpdateFastConfig;
        final MethodHandle setHasHadTerrain;
        final MethodHandle includeInSave;
        final MethodHandle getLeafTexture;
        final MethodHandle shouldDownloadFromPBO;
        final MethodHandle getTile;
        final MethodHandle tileIsLoaded;
        final MethodHandle tileWasWrittenOnce;
        final MethodHandle setTile;
        final MethodHandle poolGet;
        final MethodHandle setBlock;
        final MethodHandle setWorldInterpretationVersion;
        final MethodHandle setWrittenCave;
        final MethodHandle setWrittenOnce;
        final MethodHandle setLoaded;
        final MethodHandle newMapBlock;
        final MethodHandle prepareForWriting;
        final MethodHandle blockWrite;
        final MethodHandle addOverlay;
        final MethodHandle newOverlay;
        final MethodHandle increaseOpacity;
        final MethodHandle getOriginal;
        final MethodHandle highlightsPrepare;

        static Handles resolve(ClassResolver resolver) throws ClassNotFoundException,
                NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
            return new Handles(resolver, MethodHandles.lookup());
        }

        private Handles(ClassResolver resolver, MethodHandles.Lookup lookup)
                throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException,
                IllegalAccessException {
            Class<?> sessionClass = resolver.resolve("xaero.map.WorldMapSession");
            Class<?> processorClass = resolver.resolve("xaero.map.MapProcessor");
            Class<?> saveLoadClass = resolver.resolve("xaero.map.file.MapSaveLoad");
            Class<?> mapWorldClass = resolver.resolve("xaero.map.world.MapWorld");
            Class<?> regionClass = resolver.resolve("xaero.map.region.MapRegion");
            Class<?> tileChunkClass = resolver.resolve("xaero.map.region.MapTileChunk");
            Class<?> tileClass = resolver.resolve("xaero.map.region.MapTile");
            Class<?> blockClass = resolver.resolve("xaero.map.region.MapBlock");
            Class<?> overlayClass = resolver.resolve("xaero.map.region.Overlay");
            Class<?> overlayManagerClass = resolver.resolve("xaero.map.region.OverlayManager");
            Class<?> poolClass = resolver.resolve("xaero.map.pool.MapTilePool");
            Class<?> leafTextureClass = resolver.resolve("xaero.map.region.texture.LeafRegionTexture");
            Class<?> shapeCacheClass = resolver.resolve("xaero.map.cache.BlockStateShortShapeCache");
            Class<?> highlightsClass = resolver.resolve("xaero.map.highlight.MapRegionHighlightsPreparer");
            Class<?> tintProviderClass = resolver.resolve("xaero.map.biome.BlockTintProvider");
            Class<?> fastConfigClass = resolver.resolve("xaero.map.region.MapUpdateFastConfig");

            this.getCurrentSession = lookup.findStatic(sessionClass, "getCurrentSession",
                    MethodType.methodType(sessionClass)).asType(MethodType.methodType(Object.class));
            this.sessionIsUsable = virtual(lookup, sessionClass, "isUsable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getMapProcessor = virtual(lookup, sessionClass, "getMapProcessor",
                    MethodType.methodType(processorClass), Object.class);

            this.renderThreadPauseSync = getter(lookup, processorClass, "renderThreadPauseSync");
            this.mainStuffSync = getter(lookup, processorClass, "mainStuffSync");
            this.mainWorld = getterByName(lookup, processorClass, "mainWorld");
            this.isWritingPaused = virtual(lookup, processorClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isWaitingForWorldUpdate = virtual(lookup, processorClass, "isWaitingForWorldUpdate",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMapLocked = virtual(lookup, processorClass, "isCurrentMapLocked",
                    MethodType.methodType(boolean.class), boolean.class);
            this.isCurrentMultiworldWritable = virtual(lookup, processorClass,
                    "isCurrentMultiworldWritable",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentWorldId = virtual(lookup, processorClass, "getCurrentWorldId",
                    MethodType.methodType(String.class), Object.class);
            this.getCurrentDimension = virtual(lookup, processorClass, "getCurrentDimension",
                    MethodType.methodType(String.class), String.class);
            this.getWorld = methodByName(lookup, processorClass, "getWorld", 0);
            this.ignoreWorld = methodByName(lookup, processorClass, "ignoreWorld", 1);
            this.getMapWorld = virtual(lookup, processorClass, "getMapWorld",
                    MethodType.methodType(mapWorldClass), Object.class);
            this.getMapSaveLoad = virtual(lookup, processorClass, "getMapSaveLoad",
                    MethodType.methodType(saveLoadClass), Object.class);
            this.getLeafMapRegion = lookup.findVirtual(processorClass, "getLeafMapRegion",
                            MethodType.methodType(regionClass, int.class, int.class, int.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            int.class, int.class, int.class, boolean.class));
            this.getTilePool = virtual(lookup, processorClass, "getTilePool",
                    MethodType.methodType(poolClass), Object.class);
            this.getOverlayManager = virtual(lookup, processorClass, "getOverlayManager",
                    MethodType.methodType(overlayManagerClass), Object.class);
            this.getBlockStateShortShapeCache = virtual(lookup, processorClass,
                    "getBlockStateShortShapeCache",
                    MethodType.methodType(shapeCacheClass), Object.class);
            this.getMapRegionHighlightsPreparer = virtual(lookup, processorClass,
                    "getMapRegionHighlightsPreparer",
                    MethodType.methodType(highlightsClass), Object.class);
            this.getCaveModeDepthConfig = findCaveModeDepthConfig(
                    resolver, lookup, processorClass);
            this.getWorldBlockTintProvider = virtual(lookup, processorClass,
                    "getWorldBlockTintProvider",
                    MethodType.methodType(tintProviderClass), Object.class);
            this.newMapUpdateFastConfig = findFastConfigConstructor(lookup, fastConfigClass,
                    processorClass);

            this.isCacheOnlyMode = virtual(lookup, mapWorldClass, "isCacheOnlyMode",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getCurrentDimensionId = virtual(lookup, mapWorldClass, "getCurrentDimensionId",
                    MethodType.methodType(ResourceKey.class), Object.class);

            this.isRegionDetectionComplete = virtual(lookup, saveLoadClass, "isRegionDetectionComplete",
                    MethodType.methodType(boolean.class), boolean.class);
            this.requestLoad = lookup.findVirtual(saveLoadClass, "requestLoad",
                            MethodType.methodType(void.class, regionClass, String.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, String.class));
            this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
            this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                    MethodType.methodType(byte.class), byte.class);
            this.setLoadState = lookup.findVirtual(regionClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
            this.isResting = virtual(lookup, regionClass, "isResting",
                    MethodType.methodType(boolean.class), boolean.class);
            this.registerVisit = virtual(lookup, regionClass, "registerVisit",
                    MethodType.methodType(void.class), void.class);
            this.setBeingWritten = lookup.findVirtual(regionClass, "setBeingWritten",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.canRequestReload = virtual(lookup, regionClass, "canRequestReload_unsynced",
                    MethodType.methodType(boolean.class), boolean.class);
            this.setAllCachePrepared = lookup.findVirtual(regionClass, "setAllCachePrepared",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.regionGetChunk = lookup.findVirtual(regionClass, "getChunk",
                            MethodType.methodType(tileChunkClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.regionSetChunk = lookup.findVirtual(regionClass, "setChunk",
                            MethodType.methodType(void.class, int.class, int.class, tileChunkClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));

            this.newMapTileChunk = lookup.findConstructor(tileChunkClass,
                            MethodType.methodType(void.class, regionClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.tileChunkGetLoadState = virtual(lookup, tileChunkClass, "getLoadState",
                    MethodType.methodType(int.class), int.class);
            this.tileChunkSetLoadState = lookup.findVirtual(tileChunkClass, "setLoadState",
                            MethodType.methodType(void.class, byte.class))
                    .asType(MethodType.methodType(void.class, Object.class, byte.class));
            this.tileChunkSetChanged = lookup.findVirtual(tileChunkClass, "setChanged",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.tileChunkWasChanged = virtual(lookup, tileChunkClass, "wasChanged",
                    MethodType.methodType(boolean.class), boolean.class);
            this.tileChunkUpdateBuffers = lookup.findVirtual(tileChunkClass, "updateBuffers",
                            MethodType.methodType(void.class, processorClass, tintProviderClass,
                                    overlayManagerClass, boolean.class, shapeCacheClass,
                                    fastConfigClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class,
                            Object.class, Object.class, boolean.class, Object.class, Object.class));
            this.setHasHadTerrain = virtual(lookup, tileChunkClass, "setHasHadTerrain",
                    MethodType.methodType(void.class), void.class);
            this.includeInSave = virtual(lookup, tileChunkClass, "includeInSave",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getLeafTexture = virtual(lookup, tileChunkClass, "getLeafTexture",
                    MethodType.methodType(leafTextureClass), Object.class);
            this.shouldDownloadFromPBO = virtual(lookup, leafTextureClass, "shouldDownloadFromPBO",
                    MethodType.methodType(boolean.class), boolean.class);
            this.getTile = lookup.findVirtual(tileChunkClass, "getTile",
                            MethodType.methodType(tileClass, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
            this.tileIsLoaded = virtual(lookup, tileClass, "isLoaded",
                    MethodType.methodType(boolean.class), boolean.class);
            this.tileWasWrittenOnce = virtual(lookup, tileClass, "wasWrittenOnce",
                    MethodType.methodType(boolean.class), boolean.class);
            this.setTile = findSetTile(lookup, tileChunkClass, tileClass, shapeCacheClass,
                    processorClass);

            this.poolGet = lookup.findVirtual(poolClass, "get",
                            MethodType.methodType(tileClass, String.class, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, Object.class,
                            String.class, int.class, int.class));
            this.setBlock = lookup.findVirtual(tileClass, "setBlock",
                            MethodType.methodType(void.class, int.class, int.class, blockClass))
                    .asType(MethodType.methodType(void.class, Object.class,
                            int.class, int.class, Object.class));
            this.setWorldInterpretationVersion = lookup.findVirtual(tileClass,
                            "setWorldInterpretationVersion",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.setWrittenCave = lookup.findVirtual(tileClass, "setWrittenCave",
                            MethodType.methodType(void.class, int.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
            this.setWrittenOnce = lookup.findVirtual(tileClass, "setWrittenOnce",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            this.setLoaded = lookup.findVirtual(tileClass, "setLoaded",
                            MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));

            this.newMapBlock = lookup.findConstructor(blockClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            this.prepareForWriting = lookup.findVirtual(blockClass, "prepareForWriting",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.blockWrite = lookup.findVirtual(blockClass, "write",
                            MethodType.methodType(void.class, BlockState.class, int.class, int.class,
                                    ResourceKey.class, byte.class, boolean.class, boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, BlockState.class,
                            int.class, int.class, ResourceKey.class, byte.class,
                            boolean.class, boolean.class));
            this.addOverlay = lookup.findVirtual(blockClass, "addOverlay",
                            MethodType.methodType(void.class, overlayClass))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));

            this.newOverlay = lookup.findConstructor(overlayClass,
                            MethodType.methodType(void.class, BlockState.class, byte.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, BlockState.class,
                            byte.class, boolean.class));
            this.increaseOpacity = lookup.findVirtual(overlayClass, "increaseOpacity",
                            MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            this.getOriginal = lookup.findVirtual(overlayManagerClass, "getOriginal",
                            MethodType.methodType(overlayClass, overlayClass))
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));
            this.highlightsPrepare = lookup.findVirtual(highlightsClass, "prepare",
                            MethodType.methodType(void.class, regionClass, int.class, int.class,
                                    boolean.class))
                    .asType(MethodType.methodType(void.class, Object.class, Object.class,
                            int.class, int.class, boolean.class));
        }

        /** Xaero 1.40/1.41 used a no-arg config; 1.42+ receives the processor. */
        private static MethodHandle findFastConfigConstructor(MethodHandles.Lookup lookup,
                                                               Class<?> fastConfigClass,
                                                               Class<?> processorClass)
                throws NoSuchMethodException, IllegalAccessException {
            try {
                return lookup.findConstructor(fastConfigClass,
                                MethodType.methodType(void.class, processorClass))
                        .asType(MethodType.methodType(Object.class, Object.class));
            } catch (NoSuchMethodException ignored) {
                var legacy = lookup.findConstructor(fastConfigClass,
                                MethodType.methodType(void.class))
                        .asType(MethodType.methodType(Object.class));
                return MethodHandles.dropArguments(legacy, 0, Object.class);
            }
        }

        /**
         * Xaero 1.42+ exposes the effective cave depth on MapProcessor. In
         * 1.40/1.41 MapWriter instead reads the same value through Xaero's
         * client config channel before calling writeMap.
         */
        static MethodHandle findCaveModeDepthConfig(ClassResolver resolver,
                                                     MethodHandles.Lookup lookup,
                                                     Class<?> processorClass)
                throws ClassNotFoundException, NoSuchMethodException,
                NoSuchFieldException, IllegalAccessException {
            try {
                return virtual(lookup, processorClass, "getCaveModeDepthConfig",
                        MethodType.methodType(int.class), int.class);
            } catch (NoSuchMethodException ignored) {
                Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
                Class<?> optionsClass = resolver.resolve(
                        "xaero.map.common.config.option.WorldMapProfiledConfigOptions");
                var access = new LegacyCaveDepthAccess(
                        staticGetterByName(lookup, worldMapClass, "INSTANCE"),
                        methodByName(lookup, worldMapClass, "getConfigs", 0),
                        methodByName(lookup,
                                resolver.resolve("xaero.lib.common.config.channel.ConfigChannel"),
                                "getClientConfigManager", 0),
                        staticGetterByName(lookup, optionsClass, "CAVE_MODE_DEPTH"),
                        methodByName(lookup,
                                resolver.resolve("xaero.lib.client.config.ClientConfigManager"),
                                "getEffective", 1));
                return lookup.findVirtual(LegacyCaveDepthAccess.class, "get",
                                MethodType.methodType(int.class, Object.class))
                        .bindTo(access);
            }
        }

        private record LegacyCaveDepthAccess(
                MethodHandle worldMapInstance,
                MethodHandle getConfigs,
                MethodHandle getClientConfigManager,
                MethodHandle caveModeDepthOption,
                MethodHandle getEffective) {
            int get(Object ignoredProcessor) throws Throwable {
                Object worldMap = this.worldMapInstance.invoke();
                Object configs = this.getConfigs.invoke(worldMap);
                Object manager = this.getClientConfigManager.invoke(configs);
                Object option = this.caveModeDepthOption.invoke();
                return ((Number) this.getEffective.invoke(manager, option)).intValue();
            }
        }

        /** Xaero 1.40/1.41 omitted the processor argument from setTile. */
        private static MethodHandle findSetTile(MethodHandles.Lookup lookup,
                                                 Class<?> tileChunkClass,
                                                 Class<?> tileClass,
                                                 Class<?> shapeCacheClass,
                                                 Class<?> processorClass)
                throws NoSuchMethodException, IllegalAccessException {
            try {
                return lookup.findVirtual(tileChunkClass, "setTile",
                                MethodType.methodType(void.class, int.class, int.class, tileClass,
                                        shapeCacheClass, processorClass))
                        .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                                Object.class, Object.class, Object.class));
            } catch (NoSuchMethodException ignored) {
                var legacy = lookup.findVirtual(tileChunkClass, "setTile",
                                MethodType.methodType(void.class, int.class, int.class, tileClass,
                                        shapeCacheClass))
                        .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                                Object.class, Object.class));
                return MethodHandles.dropArguments(legacy, 5, Object.class);
            }
        }

        /** Exact-typed no-arg virtual, adapted to an Object receiver. */
        private static MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner,
                                            String name, MethodType type, Class<?> genericReturn)
                throws NoSuchMethodException, IllegalAccessException {
            return lookup.findVirtual(owner, name, type)
                    .asType(MethodType.methodType(genericReturn, Object.class));
        }

        private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name)
                throws NoSuchFieldException, IllegalAccessException {
            return lookup.findGetter(owner, name, Object.class)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /** Field getter tolerant of the declared type (mainWorld is ClientLevel-typed). */
        private static MethodHandle getterByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name)
                throws NoSuchFieldException, IllegalAccessException {
            var field = owner.getField(name);
            return lookup.unreflectGetter(field)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        /** Static field getter tolerant of the field's declared type. */
        private static MethodHandle staticGetterByName(MethodHandles.Lookup lookup,
                                                       Class<?> owner, String name)
                throws NoSuchFieldException, IllegalAccessException {
            var field = owner.getField(name);
            return lookup.unreflectGetter(field)
                    .asType(MethodType.methodType(Object.class));
        }

        /**
         * Name+arity scan for the ClientLevel-typed boundary methods — the exact
         * parameter/return types stay whatever the class declares, so the stub
         * classes can declare them as Object (tests cannot construct a ClientLevel).
         */
        private static MethodHandle methodByName(MethodHandles.Lookup lookup, Class<?> owner,
                                                 String name, int paramCount)
                throws NoSuchMethodException, IllegalAccessException {
            Method found = null;
            for (Method m : owner.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount
                        && !m.isSynthetic() && !m.isBridge()) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new NoSuchMethodException(owner.getName() + "." + name + "/" + paramCount);
            }
            var handle = lookup.unreflect(found);
            var generic = MethodType.genericMethodType(paramCount + 1);
            if (found.getReturnType() == boolean.class) {
                generic = generic.changeReturnType(boolean.class);
            }
            return handle.asType(generic);
        }
    }
}

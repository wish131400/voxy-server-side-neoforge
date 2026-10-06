package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.client.StrictLodFrontier;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.lwjgl.system.MemoryUtil;

/** Uploaded Voxy readiness for request ordering. Pixel visibility belongs to the frame depth. */
public final class StrictLodVisibility {
    private static final StrictLodFrontier FRONTIER = new StrictLodFrontier();
    private static final StrictVoxyCoverageChanges COVERAGE = new StrictVoxyCoverageChanges();
    private static final StrictVoxyCoverageCache COLUMN_COVERAGE = new StrictVoxyCoverageCache();
    private static final StrictVoxyCoverageRegions REGION_COVERAGE = new StrictVoxyCoverageRegions();
    private static final StrictVoxyNodeIndex NODES = new StrictVoxyNodeIndex(COVERAGE::nodeChanged, COVERAGE::invalidateAll);
    private static volatile Snapshot snapshot = new Snapshot(null, 0, 0, -1, 0, 0);
    private static volatile RenderWindow renderWindow = new RenderWindow(null, 0, 0, 0);
    private static Object levelIdentity, nodeOwner;
    private static ResourceKey<Level> dimension;
    private static boolean uploadedNodes, failed;
    private static volatile boolean renderHookSeen;
    private static long revision;
    private static volatile String waiting = "initializing";
    private static final java.util.concurrent.atomic.AtomicBoolean invalidated = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.Map<Object, Integer> pendingIngest = new java.util.IdentityHashMap<>();
    private static volatile boolean ingestFailed;
    private static final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.Map<Object, Long> ingestColumns = new java.util.IdentityHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Integer> pendingColumns = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile StrictVoxyPipeline meshPipeline;
    private static long checkedChange = -1, checkedFrontier = -1;
    private static long frameChecks, unchangedFrames, coverageRetractions;

    public static void workChanged() { changes.incrementAndGet(); }
    public static long meshPosition(Object task) {
        try { return ((Number) field(task, "position")).longValue(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Voxy mesh task", e); }
    }

    public static void beginIngest(Object section, int cx, int cz) {
        synchronized (pendingIngest) {
            long column = dev.xantha.vss.common.PositionUtil.packPosition(cx, cz);
            ingestColumns.put(section, column);
            pendingColumns.merge(column, 1, Integer::sum);
            beginIngest(section);
        }
    }

    public static void beginIngest(Object section) {
        synchronized (pendingIngest) { pendingIngest.merge(section, 1, Integer::sum); }
        workChanged();
    }
    public static void cancelIngest(Object section) {
        synchronized (pendingIngest) {
            Integer count = pendingIngest.get(section);
            if (count == null) return;
            Long column = ingestColumns.get(section);
            if (column != null) {
                pendingColumns.computeIfPresent(column, (key, value) -> value == 1 ? null : value - 1);
                if (count == 1) ingestColumns.remove(section);
            }
            if (count == 1) pendingIngest.remove(section); else pendingIngest.put(section, count - 1);
        }
        workChanged();
    }
    public static void ingestCompleted(Object task) {
        if (task == null) return;
        try { cancelIngest(field(task, "section")); }
        catch (ReflectiveOperationException | RuntimeException e) {
            ingestFailed = true;
            workChanged();
        }
    }

    private StrictLodVisibility() { }
    public static boolean active() {
        // The complete-ring frontier exists only for the prediction handoff.
        // When prediction is disabled, the regular near-first VSS scheduler
        // must be allowed to refill Voxy from the player's current position.
        return VSSClientNetworking.isPredictionActive()
                && VSSClientNetworking.shouldApplyStrictLodOrder()
                && (renderHookSeen || ModCompat.isVoxyLoaded());
    }
    public static Snapshot snapshot() { return snapshot; }
    public static long revision() { return snapshot.revision(); }
    /**
     * Actual handoff changes invalidate prepared plans. Request-ring bookkeeping
     * uses changes separately so incoming column manifests cannot invalidate
     * every resident prediction tile.
     */
    public static long coverageRevision() { return COVERAGE.revision(); }
    public static long coverageResetRevision() { return COVERAGE.resetRevision(); }
    public static StrictVoxyCoverageChanges.Delta coverageChangesSince(long previous) { return COVERAGE.since(previous); }

    public static void updateRenderWindow(ResourceKey<Level> dim, double cameraX,
                                          double cameraZ, int radiusChunks) {
        if (dim == null || radiusChunks <= 2 || !Double.isFinite(cameraX) || !Double.isFinite(cameraZ)) {
            dim = null;
            cameraX = cameraZ = 0;
            radiusChunks = 0;
        }
        int x = (int) Math.floor(cameraX / 16.0D);
        int z = (int) Math.floor(cameraZ / 16.0D);
        int radius = Math.max(0, radiusChunks - 2);
        RenderWindow old = renderWindow;
        if (old.dimension() == dim && old.x() == x && old.z() == z && old.radius() == radius) return;
        renderWindow = new RenderWindow(dim, x, z, radius);
        if (dim != null && old.dimension() == dim && old.radius() == radius)
            COVERAGE.windowMoved(old.x(), old.z(), x, z, radius);
        else COVERAGE.invalidateAll();
        workChanged();
    }

    /**
     * Queries uploaded residency, not visibility. Never use this to suppress a
     * prediction draw: Voxy traversal may not select the resident mesh. This is kept
     * deliberately conservative: unknown uploaded-node state or an uncovered
     * vertical section leaves prediction enabled. Pending CPU work does not
     * retract the old mesh that Voxy keeps drawing until its next GPU update.
     */
    public static boolean predictionCoverage(ResourceKey<Level> dim, int chunkX,
                                              int minBlockY, int maxBlockY, int chunkZ) {
        if (dim == null || dimension == null || !dim.equals(dimension)
                || (!renderHookSeen && !ModCompat.isVoxyLoaded())
                || !uploadedNodes || failed || meshPipeline == null
                || !renderWindow.contains(dim, chunkX, chunkZ)) {
            return false;
        }
        int minSectionY = Math.floorDiv(Math.min(minBlockY, maxBlockY), 16);
        int maxSectionY = Math.floorDiv(Math.max(minBlockY, maxBlockY), 16);
        return COLUMN_COVERAGE.covers(chunkX, minSectionY, maxSectionY, chunkZ, COVERAGE.columnRevision(chunkX, chunkZ),
                StrictLodVisibility::uncachedPredictionCoverage);
    }
    private static boolean uncachedPredictionCoverage(int chunkX, int minSectionY, int maxSectionY, int chunkZ) {
        return NODES.coversRange(chunkX, minSectionY, maxSectionY, chunkZ);
    }

    /** Inclusive chunk rectangle. The complete footprint must be inside the handoff window. */
    public static boolean predictionCoverageBox(ResourceKey<Level> dim, int minX, int minBlockY, int minZ,
                                                 int maxX, int maxBlockY, int maxZ) {
        if (minX > maxX || minZ > maxZ || !predictionReady(dim) || !renderWindow.containsBox(dim, minX, minZ, maxX, maxZ)) return false;
        if (minX == maxX && minZ == maxZ) return predictionCoverage(dim, minX, minBlockY, maxBlockY, minZ);
        int minY = Math.floorDiv(Math.min(minBlockY, maxBlockY), 16), maxY = Math.floorDiv(Math.max(minBlockY, maxBlockY), 16);
        return REGION_COVERAGE.covers(minX, minY, minZ, maxX, maxY, maxZ, COVERAGE.regionRevision(minX, minZ, maxX, maxZ),
                StrictLodVisibility::uncachedPredictionCoverageBox);
    }
    private static boolean uncachedPredictionCoverageBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return NODES.coversBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
    /** Rejects tiles wholly outside the window before starting a cell refresh. */
    public static boolean predictionMayCover(ResourceKey<Level> dim, int minX, int minZ, int maxX, int maxZ) {
        return minX <= maxX && minZ <= maxZ && predictionReady(dim) && renderWindow.intersects(dim, minX, minZ, maxX, maxZ);
    }
    private static boolean predictionReady(ResourceKey<Level> dim) {
        return dim != null && dimension != null && dim.equals(dimension) && (renderHookSeen || ModCompat.isVoxyLoaded())
                && uploadedNodes && !failed && meshPipeline != null;
    }
    public static void invalidate() { invalidated.set(true); workChanged(); }
    /** Restart coverage bookkeeping while keeping uploaded nodes and valid cache state. */
    public static synchronized void restartOrdering(ResourceKey<Level> dim, int cx, int cz) {
        snapshot = new Snapshot(dim, cx, cz, -1, 0, ++revision);
        invalidated.set(true);
        workChanged();
    }
    public static String diagnostics() {
        Snapshot s = snapshot;
        return "{active=" + active() + ",voxyVisibility=unrestricted,readyRing=" + s.visibleRing() + ",requestRing=" + s.requestRing()
                + ",waiting=" + waiting + ",checks=" + frameChecks + ",unchangedFrames=" + unchangedFrames
                + ",coverageRetractions=" + coverageRetractions + ",meshPending=" + (meshPipeline == null ? -1 : meshPipeline.size())
                + ",handoffRevision=" + COVERAGE.revision() + ",coverageQueries={" + COLUMN_COVERAGE.diagnostics() + "}"
                + ",coverageRegions={" + REGION_COVERAGE.diagnostics() + "},nodeBoxLookups=" + NODES.boxLookups()
                + ",handoffRadius=" + renderWindow.radius() + ",bridgeFailed=" + failed
                + ",predictionHandoff=region-index+frame-depth}";
    }
    public static boolean completed(ResourceKey<Level> dim, int cx, int cz) {
        if (!active()) return true;
        Snapshot s = snapshot;
        return dim != null && dim.equals(s.dimension()) && s.visible(cx, cz);
    }

    public static void reset() {
        resetGeometry();
        synchronized (pendingIngest) { pendingIngest.clear(); ingestColumns.clear(); pendingColumns.clear(); }
        ingestFailed = false;
    }
    private static void resetGeometry() {
        FRONTIER.reset(); NODES.clear(); nodeOwner = null; levelIdentity = null;
        dimension = null; uploadedNodes = false; failed = false;
        renderWindow = new RenderWindow(null, 0, 0, 0);
        meshPipeline = null; checkedChange = -1; checkedFrontier = -1;
        workChanged();
        publish();
    }

    /** Called on the render thread before any Voxy terrain draws, even after a teleport. */
    public static void beginFrame(Object renderSystem, Object viewport) {
        renderHookSeen = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) { resetGeometry(); return; }
        FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                VSSClientNetworking.getEffectiveLodDistanceChunks());
        if (invalidated.getAndSet(false)) {
            FRONTIER.reset();
            FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                    VSSClientNetworking.getEffectiveLodDistanceChunks());
        }
        try {
            if (viewport == null || ((Number) field(viewport, "width")).intValue() <= 0
                    || ((Number) field(viewport, "height")).intValue() <= 0) {
                updateRenderWindow(null, 0, 0, 0);
                return;
            }
            Object owner = field(renderSystem, "nodeManager");
            // A Voxy cache can publish its first GPU batch before the first
            // renderOpaque call of a world. Keep that batch when the render
            // hook binds to the same manager; otherwise the incremental mirror
            // would start empty and prediction could never yield to existing LOD.
            boolean preloadedOwner = levelIdentity == null && nodeOwner == owner;
            if (levelIdentity != mc.level) {
                if (!preloadedOwner) resetGeometry();
                levelIdentity = mc.level;
                dimension = mc.level.dimension();
                COVERAGE.invalidateAll();
            }
            if (nodeOwner != owner) {
                FRONTIER.reset(); NODES.clear(); uploadedNodes = false; nodeOwner = owner;
                meshPipeline = null; failed = false;
                workChanged();
                FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                        VSSClientNetworking.getEffectiveLodDistanceChunks());
            }
            updateRenderWindow(dimension, ((Number) field(viewport, "cameraX")).doubleValue(),
                    ((Number) field(viewport, "cameraZ")).doubleValue(),
                    ModCompat.getVoxyViewDistanceChunks().orElse(0));
            // Uploaded nodes establish ring readiness, not per-pixel visibility.
            if (!active() || failed) return;
            if (meshPipeline == null) {
                Object service = field(renderSystem, "renderGen");
                if (!(service instanceof StrictVoxyPipeline.Source source)) throw new IllegalStateException("Missing scoped mesh completion hook");
                meshPipeline = source.vss$pipeline();
                COVERAGE.invalidateAll();
            }
            long change = changes.get();
            if (checkedChange == change && checkedFrontier == FRONTIER.revision()) {
                unchangedFrames++;
                publish();
                return;
            }
            checkedChange = change; checkedFrontier = FRONTIER.revision(); frameChecks++;
            waiting = failed || ingestFailed ? "unsupported" : "gpu-snapshot";
            if (!failed && !ingestFailed && uploadedNodes) {
                // One complete ring per frame; no timeout or 'mostly complete' shortcut.
                waiting = "distance-limit";
                boolean advanced = FRONTIER.advance((x, z) -> {
                    boolean ready = VSSClientNetworking.strictColumnReady(x, z);
                    if (!ready) waiting = "data@" + x + "," + z;
                    return ready;
                }, (x, z) -> {
                    boolean ready = !pendingColumns.containsKey(dev.xantha.vss.common.PositionUtil.packPosition(x, z))
                            && VSSClientNetworking.strictColumnRenderReady(x, z,
                            y -> meshPipeline.idle(x, y, z) && NODES.coversFinest(x, y, z));
                    if (!ready) waiting = "gpu@" + x + "," + z;
                    return ready;
                });
                if (advanced) waiting = "advanced";
            }
        } catch (ReflectiveOperationException | RuntimeException e) { failClosed(e); }
        publish();
    }

    /** Runs before Voxy recycles a SyncResults object, after its GPU upload commands. */
    public static void uploaded(Object owner, Object result) {
        if (owner == null || result == null || failed) return;
        // The upload callback can run before the first renderOpaque hook. Bind
        // the mirror to that manager so the first frame can retain its nodes.
        if (nodeOwner == null) nodeOwner = owner;
        if (owner != nodeOwner) return;
        try {
            Int2IntMap locations = (Int2IntMap) field(result, "scatterWriteLocationMap");
            Object buffer = field(result, "scatterWriteBuffer");
            long address = ((Number) field(buffer, "address")).longValue();
            long size = ((Number) field(buffer, "size")).longValue();
            var removedPositions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (Int2IntMap.Entry e : locations.int2IntEntrySet()) {
                if (e.getIntKey() < 0) continue; // Geometry metadata, not node metadata.
                long offset = (long) e.getIntValue() * 16;
                if (offset < 0 || offset + 16 > size) throw new IllegalStateException("Voxy node metadata outside buffer");
                long ptr = address + offset;
                long key = (long) MemoryUtil.memGetInt(ptr) << 32 | Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 4));
                long removed = NODES.update(e.getIntKey(), key, MemoryUtil.memGetInt(ptr + 8));
                if (removed != Long.MIN_VALUE) removedPositions.add(removed);
            }
            // A GPU batch is atomic for visibility. Replacements and ancestor coverage must
            // be considered after every delta, not while walking an unordered scatter map.
            int before = FRONTIER.visibleRing();
            if (active()) NODES.retractUncovered(FRONTIER, removedPositions, (x, z) ->
                    VSSClientNetworking.strictColumnRenderReady(x, z, y -> NODES.coversFinest(x, y, z)));
            if (FRONTIER.visibleRing() < before) coverageRetractions++;
            boolean wasUploaded = uploadedNodes;
            uploadedNodes = true;
            if (!wasUploaded) COVERAGE.invalidateAll();
            if (!locations.isEmpty() || !wasUploaded) workChanged();
            publish();
        } catch (ReflectiveOperationException | RuntimeException e) { failClosed(e); publish(); }
    }

    private static final ClassValue<java.util.Map<String, Field>> FIELDS = new ClassValue<>() {
        protected java.util.Map<String, Field> computeValue(Class<?> type) {
            java.util.Map<String, Field> fields = new java.util.HashMap<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true); fields.putIfAbsent(f.getName(), f);
            }
            return fields;
        }
    };
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        if (object == null) throw new IllegalStateException("Missing Voxy object: " + name);
        Field field = FIELDS.get(object.getClass()).get(name);
        if (field == null) throw new NoSuchFieldException(name);
        return field.get(object);
    }
    private static void failClosed(Exception failure) {
        if (failed) return;
        // Missing even one uploaded delta leaves this incremental mirror
        // incomplete. Retrying with stale nodes can repeatedly hide/restore
        // prediction and reset every tile. Retain prediction until a fresh
        // node manager/world starts a new mirror, and invalidate only once.
        VSSLogger.warn("Prediction handoff disabled until Voxy renderer reset: unreadable uploaded boundary", failure);
        failed = true;
        uploadedNodes = false;
        NODES.clear();
        FRONTIER.reset();
        waiting = "unsupported";
        workChanged();
    }
    private static synchronized void publish() {
        Snapshot old = snapshot;
        int visible = failed ? -1 : FRONTIER.visibleRing();
        if (old.dimension() != dimension || old.x() != FRONTIER.centerX() || old.z() != FRONTIER.centerZ()
                || old.visibleRing() != visible || old.requestRing() != FRONTIER.requestRing()) revision++;
        else return;
        snapshot = new Snapshot(dimension, FRONTIER.centerX(), FRONTIER.centerZ(), visible, FRONTIER.requestRing(), revision);
    }

    private record RenderWindow(ResourceKey<Level> dimension, int x, int z, int radius) {
        boolean contains(ResourceKey<Level> dim, int cx, int cz) {
            if (dim == null || !dim.equals(dimension) || radius <= 0) return false;
            // Cover the whole chunk for every sub-chunk camera position. Only
            // chunk movement or distance changes invalidate the ownership cache.
            double dx = Math.abs((long) cx - x) + 1.0D;
            double dz = Math.abs((long) cz - z) + 1.0D;
            return dx * dx + dz * dz < (double) radius * radius;
        }
        boolean containsBox(ResourceKey<Level> dim, int minX, int minZ, int maxX, int maxZ) {
            if (dim == null || !dim.equals(dimension) || radius <= 0) return false;
            double dx = Math.max(Math.abs((long) minX - x), Math.abs((long) maxX - x)) + 1D;
            double dz = Math.max(Math.abs((long) minZ - z), Math.abs((long) maxZ - z)) + 1D;
            return dx * dx + dz * dz < (double) radius * radius;
        }
        boolean intersects(ResourceKey<Level> dim, int minX, int minZ, int maxX, int maxZ) {
            if (dim == null || !dim.equals(dimension) || radius <= 0) return false;
            double dx = (x < minX ? (long) minX - x : x > maxX ? (long) x - maxX : 0) + 1D;
            double dz = (z < minZ ? (long) minZ - z : z > maxZ ? (long) z - maxZ : 0) + 1D;
            return dx * dx + dz * dz < (double) radius * radius;
        }
    }

    public record Snapshot(ResourceKey<Level> dimension, int x, int z, int visibleRing, int requestRing, long revision) {
        public int ring(int cx, int cz) { return Math.max(Math.abs(cx - x), Math.abs(cz - z)); }
        public boolean visible(int cx, int cz) { return ring(cx, cz) <= visibleRing; }
    }
}

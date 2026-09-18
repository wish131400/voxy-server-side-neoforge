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

/** Complete-ring readiness for request ordering and conservative prediction handoff, not a draw limit. */
public final class StrictLodVisibility {
    private static final StrictLodFrontier FRONTIER = new StrictLodFrontier();
    private static final StrictVoxyNodeIndex NODES = new StrictVoxyNodeIndex();
    private static volatile Snapshot snapshot = new Snapshot(null, 0, 0, -1, 0, 0);
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
    private static StrictVoxyPipeline meshPipeline;
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
        catch (ReflectiveOperationException | RuntimeException e) { ingestFailed = true; workChanged(); }
    }

    private StrictLodVisibility() { }
    public static boolean active() {
        return VSSClientNetworking.shouldApplyStrictLodOrder() && (renderHookSeen || ModCompat.isVoxyLoaded());
    }
    public static Snapshot snapshot() { return snapshot; }
    public static long revision() { return snapshot.revision(); }
    public static void invalidate() { invalidated.set(true); workChanged(); }
    public static String diagnostics() {
        Snapshot s = snapshot;
        return "{active=" + active() + ",voxyVisibility=unrestricted,readyRing=" + s.visibleRing() + ",requestRing=" + s.requestRing()
                + ",waiting=" + waiting + ",checks=" + frameChecks + ",unchangedFrames=" + unchangedFrames
                + ",coverageRetractions=" + coverageRetractions + ",meshPending=" + (meshPipeline == null ? -1 : meshPipeline.size()) + "}";
    }
    public static boolean visible(ResourceKey<Level> dim, int cx, int cz) {
        // Cached Voxy terrain stays eligible to draw even while a nearer column
        // is missing. Actual visibility belongs to Voxy's renderer.
        return true;
    }
    public static boolean completed(ResourceKey<Level> dim, int cx, int cz) {
        if (!active()) return true;
        Snapshot s = snapshot;
        return dim != null && dim.equals(s.dimension()) && s.visible(cx, cz);
    }
    public static boolean requestable(ResourceKey<Level> dim, int cx, int cz) {
        if (!active()) return true;
        Snapshot s = snapshot;
        return dim != null && dim.equals(s.dimension()) && s.ring(cx, cz) <= s.requestRing();
    }

    public static void reset() {
        resetGeometry();
        synchronized (pendingIngest) { pendingIngest.clear(); ingestColumns.clear(); pendingColumns.clear(); }
        ingestFailed = false;
    }
    private static void resetGeometry() {
        FRONTIER.reset(); NODES.clear(); nodeOwner = null; levelIdentity = null;
        dimension = null; uploadedNodes = false; failed = false;
        meshPipeline = null; checkedChange = -1; checkedFrontier = -1;
        workChanged();
        publish();
    }

    /** Called on the render thread before any Voxy terrain draws, even after a teleport. */
    public static void beginFrame(Object renderSystem) {
        renderHookSeen = true;
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) { resetGeometry(); return; }
        if (levelIdentity != mc.level) {
            resetGeometry(); levelIdentity = mc.level; dimension = mc.level.dimension();
        }
        FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                VSSClientNetworking.getEffectiveLodDistanceChunks());
        if (invalidated.getAndSet(false)) {
            FRONTIER.reset();
            FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                    VSSClientNetworking.getEffectiveLodDistanceChunks());
        }
        try {
            Object owner = field(renderSystem, "nodeManager");
            if (nodeOwner != owner) {
                FRONTIER.reset(); NODES.clear(); uploadedNodes = false; nodeOwner = owner;
                meshPipeline = null;
                FRONTIER.center(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4,
                        VSSClientNetworking.getEffectiveLodDistanceChunks());
            }
            if (meshPipeline == null) {
                Object service = field(renderSystem, "renderGen");
                if (!(service instanceof StrictVoxyPipeline.Source source)) throw new IllegalStateException("Missing scoped mesh completion hook");
                meshPipeline = source.vss$pipeline();
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
                            y -> meshPipeline.idle(x, y, z) && NODES.covers(x, y, z));
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
        if (!active() || owner != nodeOwner || result == null || failed) return;
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
            NODES.retractUncovered(FRONTIER, removedPositions, (x, z) ->
                    VSSClientNetworking.strictColumnRenderReady(x, z, y -> NODES.covers(x, y, z)));
            if (FRONTIER.visibleRing() < before) coverageRetractions++;
            uploadedNodes = true;
            if (!locations.isEmpty()) workChanged();
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
        if (!failed) VSSLogger.warn("Near-first LOD requests paused: unsupported Voxy readiness bridge: " + failure);
        failed = true; FRONTIER.reset();
    }
    private static void publish() {
        Snapshot old = snapshot;
        int visible = failed ? -1 : FRONTIER.visibleRing();
        if (old.dimension() != dimension || old.x() != FRONTIER.centerX() || old.z() != FRONTIER.centerZ()
                || old.visibleRing() != visible || old.requestRing() != FRONTIER.requestRing()) revision++;
        else return;
        snapshot = new Snapshot(dimension, FRONTIER.centerX(), FRONTIER.centerZ(), visible, FRONTIER.requestRing(), revision);
    }

    public record Snapshot(ResourceKey<Level> dimension, int x, int z, int visibleRing, int requestRing, long revision) {
        public int ring(int cx, int cz) { return Math.max(Math.abs(cx - x), Math.abs(cz - z)); }
        public boolean visible(int cx, int cz) { return ring(cx, cz) <= visibleRing; }
    }
}

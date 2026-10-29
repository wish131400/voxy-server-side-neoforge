package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.reflect.Field;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/** Render-thread owner of native root and traversal capacity. Never owns terrain or mesh data. */
public final class VoxyTraversalCapacity {
    public interface Owner { VoxyTraversalCapacity vss$capacity(); }
    interface Buffers {
        Object allocate(Class<?> type, long bytes) throws ReflectiveOperationException;
        void copy(Object from, Object to, long bytes) throws ReflectiveOperationException;
        void free(Object buffer) throws ReflectiveOperationException;
    }
    private static final Buffers GPU = new Buffers() {
        public Object allocate(Class<?> type, long bytes) throws ReflectiveOperationException {
            return type.getConstructor(long.class).newInstance(bytes);
        }
        public void copy(Object from, Object to, long bytes) throws ReflectiveOperationException {
            GL42.glMemoryBarrier(GL43.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL45.glCopyNamedBufferSubData(from.getClass().getField("id").getInt(from),
                    to.getClass().getField("id").getInt(to), 0, 0, bytes);
        }
        public void free(Object buffer) throws ReflectiveOperationException {
            // OpenGL keeps storage referenced by earlier commands alive; no glFinish or CPU wait.
            buffer.getClass().getMethod("free").invoke(buffer);
        }
    };
    private final Object owner, nodes;
    private final Field rootArray, rootBuffer, queueA, queueB;
    private final MethodHandle maxNodeId;
    private final Buffers buffers;
    private VoxyCapacityPlan plan;
    private int rootCapacity, queueCapacity, roots, peakRoots, highNodeId;
    private long added, removed, rootGrowths, queueGrowths;
    private long nextSnapshotAt;
    private volatile Map<String, Object> snapshot = Map.of();

    public VoxyTraversalCapacity(Object owner, VoxyCapacityPlan plan) {
        this(owner, plan, GPU);
    }

    VoxyTraversalCapacity(Object owner, VoxyCapacityPlan plan, Buffers buffers) {
        this.owner = owner; this.plan = plan; this.buffers = buffers;
        try {
            rootArray = field(owner.getClass(), "idx2topNodeMapping");
            rootBuffer = field(owner.getClass(), "topNodeIds");
            queueA = field(owner.getClass(), "scratchQueueA"); queueB = field(owner.getClass(), "scratchQueueB");
            nodes = field(owner.getClass(), "nodeManager").get(owner);
            maxNodeId = MethodHandles.publicLookup().unreflect(nodes.getClass().getMethod("getCurrentMaxNodeId"))
                    .asType(MethodType.methodType(int.class, Object.class));
            rootCapacity = ((int[]) rootArray.get(owner)).length;
            Object queue = queueA.get(owner);
            queueCapacity = Math.toIntExact(((Number) queue.getClass().getMethod("size").invoke(queue)).longValue() / 4);
            publish();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy capacity layout", e); }
    }

    public static VoxyCapacityPlan initialPlan() {
        var mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) return VoxyCapacityPlan.create(0, 0, 0, 0, 0);
        try {
            Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object instance = config.getField("CONFIG").get(null);
            float configured = ((Number) config.getField("sectionRenderDistance").get(instance)).floatValue();
            var session = dev.xantha.vss.api.VSSApi.clientSession();
            int server = session.isClientLodSessionActive() ? session.getEffectiveLodDistanceChunks() : 0;
            int prediction = VSSClientConfig.CONFIG.enablePrediction ? VSSClientConfig.CONFIG.predictionDistanceBlocks : 0;
            return VoxyCapacityPlan.create(configured, server, prediction, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy distance configuration", e); }
    }

    /** Called once per distance change, before native RingTracker receives the larger horizon. */
    public static VoxyTraversalCapacity prepare(Object renderer, float configured, int server, int prediction) {
        Field handle;
        try { handle = field(renderer.getClass(), "traversal"); }
        catch (NoSuchFieldException unsupported) { return null; }
        try {
            Object traversal = handle.get(renderer);
            if (!(traversal instanceof Owner managed)) return null;
            var level = Minecraft.getInstance().level;
            if (level == null) return null;
            managed.vss$capacity().prepare(VoxyCapacityPlan.create(configured, server, prediction,
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1));
            return managed.vss$capacity();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy horizon capacity preparation", e); }
    }

    void prepare(VoxyCapacityPlan next) {
        plan = next;
        ensureRoots(Math.max(next.roots(), roots));
        ensureQueues(Math.max(next.roots(), (long) highNodeId + 1));
        publish();
        VSSLogger.info("VSS Voxy horizon: configuredSections=" + plan.configuredSections() + ",serverChunks="
                + plan.serverChunks() + ",predictionBlocks=" + plan.predictionBlocks() + ",effectiveBlocks="
                + (long) Math.ceil(plan.sections() * 512.0) + ",height=" + plan.minY() + ".." + plan.maxY()
                + ",rootLayers=" + plan.layers() + ",expectedRoots=" + plan.roots()
                + ",rootCapacity=" + rootCapacity + ",queueCapacity=" + queueCapacity);
    }

    public void beforeAdd(int id, int count, Int2IntOpenHashMap ids) {
        if (ids.containsKey(id)) throw inconsistent("duplicate root add", id, count, ids.size());
        if (count != ids.size()) throw inconsistent("root count before add", id, count, ids.size());
        ensureRoots((long) count + 1);
    }

    public void beforeRemove(int id, int count, Int2IntOpenHashMap ids) {
        if (!ids.containsKey(id) || count != ids.size()) throw inconsistent("unknown root remove", id, count, ids.size());
    }

    public void changed(int count, int mapped, boolean add) {
        if (count != mapped) throw inconsistent("root count after update", -1, count, mapped);
        roots = count; peakRoots = Math.max(peakRoots, count);
        if (add) added++; else removed++;
    }

    /** Native publishes this high-water ID with the same batch as node uploads, before traversal. */
    public void beforeTraversal() {
        try { highNodeId = (int) maxNodeId.invokeExact(nodes); }
        catch (Throwable e) { throw new IllegalStateException("Voxy node high-water mark", e); }
        // A native tree visits a node at most once in a layer. This bounds every intermediate queue,
        // including meshes already resident before prediction or after a shader pipeline replacement.
        ensureQueues(Math.max((long) roots, (long) highNodeId + 1));
        long now = System.nanoTime();
        if (now >= nextSnapshotAt) { publish(); nextSnapshotAt = now + 1_000_000_000L; }
    }

    private void ensureRoots(long required) {
        int next = VoxyCapacityPlan.capacity(rootCapacity, required);
        if (next == rootCapacity) return;
        try {
            int[] array = Arrays.copyOf((int[]) rootArray.get(owner), next);
            Object old = rootBuffer.get(owner), replacement = buffers.allocate(old.getClass(), next * 4L);
            try { if (roots != 0) buffers.copy(old, replacement, roots * 4L); }
            catch (ReflectiveOperationException | RuntimeException e) { buffers.free(replacement); throw e; }
            rootArray.set(owner, array); rootBuffer.set(owner, replacement);
            buffers.free(old); rootCapacity = next; rootGrowths++;
            VSSLogger.info("VSS Voxy root capacity grew to " + next + ",active=" + roots + ",expected=" + plan.roots());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy root capacity growth", e); }
    }

    private void ensureQueues(long required) {
        int next = VoxyCapacityPlan.capacity(queueCapacity, required);
        if (next == queueCapacity) return;
        try {
            Object oldA = queueA.get(owner), oldB = queueB.get(owner);
            Object a = buffers.allocate(oldA.getClass(), next * 4L), b;
            try { b = buffers.allocate(oldB.getClass(), next * 4L); }
            catch (ReflectiveOperationException | RuntimeException e) { buffers.free(a); throw e; }
            // Scratch contents are rebuilt by traverseInternal; preserve only roots and resident meshes.
            queueA.set(owner, a); queueB.set(owner, b);
            buffers.free(oldA); buffers.free(oldB); queueCapacity = next; queueGrowths++;
            VSSLogger.info("VSS Voxy traversal capacity grew to " + next + ",uploadedMaxNodeId=" + highNodeId);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy traversal capacity growth", e); }
    }

    private IllegalStateException inconsistent(String reason, int id, int count, int mapped) {
        return new IllegalStateException("Voxy root lifecycle mismatch: " + reason + ",id=" + id + ",count=" + count
                + ",mapped=" + mapped + ",added=" + added + ",removed=" + removed + ",expected=" + plan.roots());
    }

    private void publish() {
        snapshot = Map.ofEntries(Map.entry("effectiveDistanceBlocks", (long) Math.ceil(plan.sections() * 512.0)),
                Map.entry("verticalRootLayers", plan.layers()), Map.entry("expectedRoots", plan.roots()),
                Map.entry("activeRoots", roots), Map.entry("peakRoots", peakRoots), Map.entry("addedRoots", added),
                Map.entry("removedRoots", removed), Map.entry("rootCapacity", rootCapacity),
                Map.entry("traversalCapacity", queueCapacity), Map.entry("uploadedMaxNodeId", highNodeId),
                Map.entry("rootGrowths", rootGrowths), Map.entry("traversalGrowths", queueGrowths));
    }
    public Map<String, Object> snapshot() { return snapshot; }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}

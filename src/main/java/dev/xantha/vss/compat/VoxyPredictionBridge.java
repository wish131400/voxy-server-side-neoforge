package dev.xantha.vss.compat;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.config.VSSClientConfig;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

/** Render-only overlay. Never enters WorldEngine's section tracker, ingest or save queues. */
public final class VoxyPredictionBridge {
    public interface Mapper {
        long map(BlockState state, String biome);
        default boolean hasSkyLight() { return true; }
        /** A closed renderer must not regain ownership of a source's mapped cache. */
        default boolean isOpen() { return true; }
    }
    public interface Retention extends AutoCloseable { @Override void close(); }
    public interface Source {
        /** Immutable mapped section, or null outside available prediction. */
        long[] section(long position, Mapper mapper);
        default VoxySectionData mappedSection(long position, Mapper mapper) {
            long[] data = section(position, mapper);
            return data == null ? null : VoxySectionData.compact(data);
        }
        /** Notification reads must not promote one-shot sections over mesh center/border data. */
        default VoxySectionData mappedSection(long position, Mapper mapper, boolean promote) {
            return mappedSection(position,mapper);
        }
        /** Obtain the destination lazily: unavailable prediction must not allocate a native section. */
        default boolean copySection(long position,Mapper mapper,boolean promote,java.util.function.Supplier<long[]> target) {
            var data=mappedSection(position,mapper,promote);
            if(data==null)return false;
            data.copyTo(target.get());return true;
        }
        default long[] face(long position,Mapper mapper,int face) {
            var data=mappedSection(position,mapper);
            if(data==null)return null;
            long[] result=new long[1024];data.copyFace(face,result);return result;
        }
        /** Caller owns the destination; implementations keep cached face arrays immutable. */
        default boolean copyFace(long position,Mapper mapper,int face,long[] target) {
            var result=face(position,mapper,face);if(result==null)return false;
            System.arraycopy(result,0,target,0,1024);return true;
        }
        /** Record visible child demand; true only when published samples support that spacing.
         * Real-only regions and sources without prediction refinement remain unrestricted. */
        default boolean requestDetail(long position, RealColumn real) { return true; }
        /** Meshes also need samples when their native parent was subdivided before prediction arrived. */
        default void requestMeshDetail(long position, RealColumn real) { }
        /** Optional shared memory allowance for retaining an immutable native snapshot. */
        default Retention retainSection(long bytes) { return null; }
        /** Release renderer-specific mappings while preserving sampled voxel content. */
        default void releaseMapper(Mapper mapper) { }
        /** Full prediction sources provide cheap face reads for final composed-content comparison. */
        default boolean compareNotificationFaces() { return false; }
    }
    private static final Map<Object, Context> WORLDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ExecutorService UPDATES = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "vss-voxy-overlay-updates"); t.setDaemon(true); return t;
    });
    private static volatile Context current;
    private static volatile java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> unloadedLevel =
            new java.lang.ref.WeakReference<>(null);
    private static volatile boolean meshHook, neighborHook, eventHook, detailHook;
    private static volatile boolean subdivisionShaderReady;
    private static final java.util.regex.Pattern ADD_REQUEST = java.util.regex.Pattern.compile(
            "void\\s+addRequest\\s*\\(\\s*inout\\s+UnpackedNode\\s+node\\s*\\)\\s*\\{");
    private static volatile String status = "waiting-for-voxy";
    private static final LongAdder composed = new LongAdder(), notified = new LongAdder(),
            cacheHits = new LongAdder(), acquisitions = new LongAdder(), faceReads=new LongAdder(),faceSnapshotHits=new LongAdder(),
            unchangedNotifications=new LongAdder(),blockNotifications=new LongAdder(),childNotifications=new LongAdder();
    private static final int MAX_SNAPSHOTS = 512;
    private static final int NOTIFICATIONS_PER_FRAME = 64;
    private record CachedSection(Source source, long version, Object section, Retention retention) { }
    private record Delivered(VoxySectionSignature.Value blocks,byte children,VoxySectionSignature.Value faces) {
        int changes(Delivered previous) {
            if(previous==null)return 3;
            int flags=children==previous.children?0:2;
            if(!blocks.equals(previous.blocks) || faces==null || previous.faces==null || !faces.equals(previous.faces))flags|=1;
            return flags;
        }
    }
    private static final ThreadLocal<Object> NOTIFICATION_SECTION=new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();
    private static final Map<Object, BuildStamp> BUILDS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, BuildStamp> SNAPSHOTS = Collections.synchronizedMap(new WeakHashMap<>());
    private record BuildStamp(Context context, long position, long version) { }
    private static final ThreadLocal<BuildStamp> ACQUIRED = new ThreadLocal<>();
    private VoxyPredictionBridge() { }

    private static final ClassValue<java.lang.reflect.Field> WORLD_FIELDS = new ClassValue<>() {
        protected java.lang.reflect.Field computeValue(Class<?> type) {
            try { var f = type.getDeclaredField("world"); f.setAccessible(true); return f; }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };
    private record NeighborMethods(Method acquire, Method exists) { }
    private static final ClassValue<NeighborMethods> ACQUIRES = new ClassValue<>() {
        protected NeighborMethods computeValue(Class<?> type) {
            try { return new NeighborMethods(type.getMethod("acquire", int.class, int.class, int.class, int.class),
                    type.getMethod("acquireIfExists", int.class, int.class, int.class, int.class)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };
    private static final ClassValue<java.lang.reflect.Field> NEIGHBOR_FACES=new ClassValue<>() {
        protected java.lang.reflect.Field computeValue(Class<?> type) {
            try {var field=type.getDeclaredField("neighboringFaces");field.setAccessible(true);return field;}
            catch(ReflectiveOperationException e) {throw new IllegalStateException(e);}
        }
    };
    /** Verified native face layout; the original six acquisition redirects remain the fallback. */
    private static final ThreadLocal<long[]> NEIGHBOR_BUFFER=ThreadLocal.withInitial(()->new long[1024]);
    public static boolean copyNeighborFaces(Object factory,Object section,int mask) {
        if(!VoxyFaceData.nativeLayoutVerified)return false;
        Context ctx=null;
        try {
            Object world=WORLD_FIELDS.get(factory.getClass()).get(factory);ctx=WORLDS.get(world);
            if(ctx==null || ctx.closed || !ctx.supported || ctx.source==null || !enabled())return false;
            Source source=ctx.source;
            long center=((Number)field(section,"key")).longValue();
            int lod=level(center),sx=x(center),sy=y(center),sz=z(center);
            long[] output=(long[])NEIGHBOR_FACES.get(factory.getClass()).get(factory);
            for(int direction=0;direction<6;direction++)if((mask&1<<direction)!=0) {
                int nx=sx+(direction==0?-1:direction==1?1:0),ny=sy+(direction==2?-1:direction==3?1:0),
                        nz=sz+(direction==4?-1:direction==5?1:0);
                long pos=position(lod,nx,ny,nz);
                var token=ctx.requested.computeIfAbsent(pos,ignored->new java.util.concurrent.atomic.AtomicLong(EPOCH.incrementAndGet()));
                synchronized(token) {
                    long version=token.get();int face=direction^1;
                    Object cached=ctx.cached(source,pos,version);
                    if(cached!=null) {
                        try {var raw=ctx.voxels.read(cached);
                            for(int i=0;i<1024;i++)output[direction*1024+i]=raw.get(VoxyFaceData.index(face,i));
                        } finally {ctx.release.invoke(cached);}
                        faceSnapshotHits.increment();
                    } else {
                        // Acquire the real section only on a cache miss; keep its reference through the copy.
                        Object real=ctx.acquireReal.invoke(ctx.engine,pos);
                        try {
                            var raw=real==null?null:ctx.voxels.read(real);
                            long[] predicted=NEIGHBOR_BUFFER.get();
                            if(!source.copyFace(pos,ctx,face,predicted)) {
                                for(int i=0;i<1024;i++)output[direction*1024+i]=raw==null?0:raw.get(VoxyFaceData.index(face,i));
                            } else {
                                mergeRealFace(predicted,raw,face,lod,nx,nz,VoxyCompat.confirmedPredictionColumns(ctx.engine),(long)ctx.skyLight<<56);
                                System.arraycopy(predicted,0,output,direction*1024,1024);
                            }
                        } finally {if(real!=null)ctx.release.invoke(real);}
                    }
                    if(ctx.closed || ctx.source!=source || token.get()!=version)return false;
                    faceReads.increment();
                }
            }
            return true;
        } catch(ReflectiveOperationException | RuntimeException failure) {
            if(ctx!=null)ctx.supported=false;
            status="neighbor-failed:"+failure.getClass().getSimpleName();
            VSSLogger.error("VSS neighbor face preparation failed; retaining native Voxy data",failure);
            return false;
        }
    }
    public static Object meshSection(Object service, long pos, Object real) {
        try {
            Object engine=WORLD_FIELDS.get(service.getClass()).get(service);
            Context ctx=WORLDS.get(engine);
            Source source=ctx==null ? null : ctx.source;
            if(ctx!=null && !ctx.closed && ctx.supported && source!=null && enabled()) {
                ctx.requested.computeIfAbsent(pos,ignored->new java.util.concurrent.atomic.AtomicLong(EPOCH.incrementAndGet()));
                long version=ctx.version(pos);
                source.requestMeshDetail(pos,ctx);
                ACQUIRED.set(new BuildStamp(ctx,pos,version));
            }
            return compose(engine, pos, real);
        }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    public static Object neighborSection(Object world, int lod, int x, int y, int z) {
        try { return compose(world, position(lod, x, y, z), ACQUIRES.get(world.getClass()).acquire.invoke(world, lod, x, y, z)); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    public static Object neighborSectionIfExists(Object world, int lod, int x, int y, int z) {
        try {
            long pos = position(lod, x, y, z);
            return compose(world, pos, ACQUIRES.get(world.getClass()).exists.invoke(world, lod, x, y, z));
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    /** Called by the two distinct, verified render-only acquisition hooks. */
    public static void hookSeen(boolean neighbor) { if (neighbor) neighborHook = true; else meshHook = true; }
    public static void eventHookSeen() { eventHook = true; }
    public static void detailHookSeen() { detailHook = true; }

    private static Context nodeContext(Object nodes) {
        Context active = current;
        if (active != null && active.refinementNodes == nodes) return active;
        synchronized (WORLDS) {
            for (Context ctx : WORLDS.values()) if (ctx.refinementNodes == nodes) return ctx;
        }
        return null;
    }
    /** Runs on the node thread, including bounded rechecks submitted by the render thread. */
    public static boolean requestDetail(Object nodes, long position) {
        Context ctx = nodeContext(nodes);
        boolean waiting = ctx != null && ctx.subdivisionWaiters.contains(position);
        boolean empty = false;
        if (waiting && ctx.source != null && !ctx.closed && ctx.supported && enabled()) {
            try {
                int encoded = ((it.unimi.dsi.fastutil.longs.Long2IntMap) field(nodes, "activeSectionMap")).get(position);
                empty = encoded != -1 && (encoded & 0x80000000) == 0
                        && VoxyNodeChildren.emptyNonRoot(nodes, field(nodes, "nodeData"), encoded & 0xffffff, position);
                if (empty && !ctx.subdivisionWaiters.needsSamples(position)) return false;
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy child occupancy", e); }
        }
        boolean ready = requestDetail(ctx, position);
        if (!waiting) return true;
        if (empty) {
            // An evicted/deferred demand still needs bounded source rechecks even
            // while native occupancy is zero. Only ready empty nodes can sleep on occupancy alone.
            ctx.subdivisionWaiters.add(position, !ready);
            return false;
        }
        if (ctx.predictionOnly.contains(position) && !ready) return false;
        ctx.subdivisionWaiters.remove(position);
        try {
            // The node can have moved or been replaced while waiting: resolve its current ID.
            int encoded = ((it.unimi.dsi.fastutil.longs.Long2IntMap) field(nodes, "activeSectionMap")).get(position);
            if (encoded != -1 && (encoded & 0x80000000) == 0)
                invoke(NODE_METHODS.get(nodes.getClass()).invalidate, nodes, encoded & 0xffffff);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy detail wakeup", e); }
        return true;
    }
    private static boolean requestDetail(Context ctx, long position) {
        Source source = ctx == null ? null : ctx.source;
        return source == null || ctx.closed || !ctx.supported || !enabled()
                || source.requestDetail(position, ctx);
    }
    /** Keep the parent drawable without inventing a native child request while samples are absent. */
    public static boolean allowSubdivision(Object nodes, int nodeId) {
        Context ctx = nodeContext(nodes);
        if (ctx == null || ctx.source == null || ctx.closed || !ctx.supported || !enabled()) return true;
        try {
            Object store = field(nodes, "nodeData");
            NodeMethods methods = NODE_METHODS.get(nodes.getClass());
            long pos = ((Number) invoke(methods.position, store, nodeId)).longValue();
            boolean empty = VoxyNodeChildren.emptyNonRoot(nodes, store, nodeId, pos);
            boolean ready = !ctx.predictionOnly.contains(pos) && !empty || requestDetail(ctx, pos);
            // Native rejects empty non-root expansion and asks the GPU to try again.
            // Retain the parent and wake on occupancy publication or bounded recheck.
            if (ready && !empty) {
                ctx.subdivisionWaiters.remove(pos);
                return true;
            }
            ctx.subdivisionWaiters.add(pos, !ready);
            // Unknown shader layouts retain ordinary native retries instead of suppressing an unwakeable node.
            if (!subdivisionShaderReady) ctx.subdivisionWaiters.remove(pos);
            invoke(methods.unmark, store, nodeId);
            invoke(methods.invalidate, nodes, nodeId);
            return false;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy detail request bridge", e); }
    }
    private record NodeMethods(Method position, Method unmark, Method invalidate) { }
    private static final ClassValue<NodeMethods> NODE_METHODS = new ClassValue<>() {
        protected NodeMethods computeValue(Class<?> type) {
            try {
                var store = type.getDeclaredField("nodeData").getType();
                var invalidate = type.getDeclaredMethod("invalidateNode", int.class); invalidate.setAccessible(true);
                return new NodeMethods(store.getMethod("nodePosition", int.class),
                        store.getMethod("unmarkRequestInFlight", int.class), invalidate);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };

    /** Bit 1 is reserved by Voxy. Keep waiting separate from bit 0, which also excludes native cleaning. */
    public static void writeSubdivisionState(Object nodes, long address) {
        Context ctx = nodeContext(nodes);
        if (ctx == null || !enabled() || ctx.subdivisionWaiters.size() == 0) return;
        long position = (long) MemoryUtil.memGetInt(address) << 32
                | Integer.toUnsignedLong(MemoryUtil.memGetInt(address + 4));
        if (ctx.subdivisionWaiters.contains(position))
            MemoryUtil.memPutInt(address + 8, MemoryUtil.memGetInt(address + 8) | 2 << 24);
    }

    public static String patchSubdivisionRequests(String path, String source) {
        if (!"voxy:lod/hierarchical/traversal_dev.comp".equals(path)) return source;
        if (source.contains("// VSS deferred subdivision")) return source;
        var match = ADD_REQUEST.matcher(source);
        if (!match.find()) return source;
        int end = match.end();
        if (match.find()) return source;
        subdivisionShaderReady = true;
        return source.substring(0, end) + "\n    // VSS deferred subdivision\n"
                + "    if ((node.flags & 2u) != 0u) return;\n" + source.substring(end);
    }

    /** Publication already delivers a child event, even when occupancy did not change. */
    public static void resumeSubdivision(Object nodes, long position) {
        Context ctx = nodeContext(nodes);
        if (ctx == null || !ctx.subdivisionWaiters.contains(position)) return;
        try { invoke(nodes.getClass().getMethod("processRequest", long.class), nodes, position); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy detail publication", e); }
    }

    public static void forgetSubdivision(Object nodes, long position) {
        Context ctx = nodeContext(nodes);
        if (ctx != null) ctx.subdivisionWaiters.remove(position);
    }

    private record RequestBatches(java.lang.reflect.Constructor<?> create, java.lang.reflect.Field address,
                                  Method submit, Method free) {
        void submit(Object nodes, long[] positions) throws ReflectiveOperationException {
            Object batch = create.newInstance(8L + positions.length * 8L);
            boolean transferred = false;
            try {
                long ptr = address.getLong(batch);
                MemoryUtil.memPutInt(ptr, positions.length);
                MemoryUtil.memPutInt(ptr + 4, 0);
                for (int i = 0; i < positions.length; i++) {
                    MemoryUtil.memPutInt(ptr + 8L + i * 8L, (int) (positions[i] >>> 32));
                    MemoryUtil.memPutInt(ptr + 12L + i * 8L, (int) positions[i]);
                }
                invoke(submit, nodes, batch);
                transferred = true;
            } finally { if (!transferred) invoke(free, batch); }
        }
    }
    private static final ClassValue<RequestBatches> REQUEST_BATCHES = new ClassValue<>() {
        protected RequestBatches computeValue(Class<?> type) {
            try {
                Class<?> batch = Class.forName("me.cortex.voxy.common.util.MemoryBuffer", true, type.getClassLoader());
                return new RequestBatches(batch.getConstructor(long.class), batch.getField("address"),
                        type.getMethod("submitRequestBatch", batch), batch.getMethod("free"));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };

    private static void recheckSubdivisions(Context ctx) throws ReflectiveOperationException {
        if (ctx.subdivisionWaiters.size() == 0) return;
        long now = System.nanoTime();
        if (now - ctx.lastSubdivisionCheck < 250_000_000L) return;
        ctx.lastSubdivisionCheck = now;
        // Re-admit evicted/deferred demands and release disabled prediction without per-frame scans/remeshing.
        long[] positions = ctx.subdivisionWaiters.nextBatch(32);
        if (positions.length != 0) REQUEST_BATCHES.get(ctx.nodes.getClass()).submit(ctx.nodes, positions);
    }

    private static final ClassValue<Method> GENERATES = new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            try { return type.getMethod("generateMesh", Class.forName("me.cortex.voxy.common.world.WorldSection")); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };
    /** Model-bake retries may hold an older snapshot. Refresh immediately before every actual mesh build. */
    public static Object generateMesh(Object service, Object factory, Object held) {
        Object fresh = null;
        Context ctx = null;
        try {
            Object engine = WORLD_FIELDS.get(service.getClass()).get(service);
            ctx = WORLDS.get(engine);
            long pos = ((Number) field(held, "key")).longValue();
            Source source=ctx==null ? null : ctx.source;
            boolean overlay = ctx != null && !ctx.closed && ctx.supported && source != null && enabled();
            if (overlay) {
                // Model-bake retries can reuse held sections without acquiring them again.
                BuildStamp acquired=ACQUIRED.get();
                if(acquired==null || acquired.context!=ctx || acquired.position!=pos || acquired.version!=ctx.version(pos))
                    source.requestMeshDetail(pos,ctx);
                ctx.requested.computeIfAbsent(pos, ignored -> new java.util.concurrent.atomic.AtomicLong(EPOCH.incrementAndGet()));
            }
            long version = ctx == null ? -1 : ctx.version(pos);
            BuildStamp heldStamp = SNAPSHOTS.get(held);
            if (ctx != null && !ctx.closed && (heldStamp != null
                    ? heldStamp.context != ctx || heldStamp.version != version || !overlay : overlay)) {
                fresh = compose(engine, pos, ctx.acquireReal.invoke(engine, pos));
                if (fresh == null) fresh = ctx.empty(pos);
            }
            Object mesh = invoke(GENERATES.get(factory.getClass()), factory, fresh == null ? held : fresh);
            if (overlay) {
                BUILDS.put(mesh, new BuildStamp(ctx, pos, version));
            }
            return mesh;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Voxy prediction mesh bridge", e); }
        finally { if (fresh != null) try { ctx.release.invoke(fresh); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
    }

    public static java.util.function.Consumer<Object> meshConsumer(Object service, java.util.function.Consumer<Object> consumer) {
        if (consumer == null) return null;
        return mesh -> {
            BuildStamp stamp = BUILDS.remove(mesh);
            if(stamp==null) stamp=ACQUIRED.get();
            if (stamp == null) { consumer.accept(mesh); return; }
            // Serialize native queue publication with display-state revocation. An old
            // mesh must be enqueued before withdrawal, or rejected after the toggle.
            synchronized (stamp.context) {
                if (stamp.context.closed || !stamp.context.supported
                        || !enabled() || stamp.version != stamp.context.version(stamp.position)) {
                    try {
                        if (mesh instanceof StrictVoxyPipeline.Mesh tracked && tracked.vss$work() != null) tracked.vss$work().uploaded();
                        invoke(mesh.getClass().getMethod("free"), mesh);
                        if (!stamp.context.closed && stamp.context.supported)
                            invoke(service.getClass().getMethod("enqueueTask", long.class), service, stamp.position);
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    return;
                }
                consumer.accept(mesh);
            }
        };
    }
    public static void meshAttemptFinished() { ACQUIRED.remove(); }

    /** Real ingest events must preserve predicted child occupancy in the render tree only. */
    public static void forwardEvent(Object nodes, Object router, Object borrowed, int flags) {
        Context ctx = null;
        synchronized (WORLDS) { for (Context candidate : WORLDS.values()) if (candidate.nodes == nodes) { ctx = candidate; break; } }
        Object snapshot = null;
        try {
            if (ctx != null && ctx.source != null && !ctx.closed && ctx.supported) {
                long pos = ((Number) field(borrowed, "key")).longValue();
                BuildStamp stamp = SNAPSHOTS.get(borrowed);
                // Synthetic updates already carry the composed data and its change version.
                // Re-composing here both copies the section twice and invalidates in-flight meshes again.
                boolean composedEvent = stamp != null && stamp.context == ctx || NOTIFICATION_SECTION.get() == borrowed;
                int watched = ((Number) invoke(router.getClass().getMethod("get", long.class), router, pos)).intValue();
                if (!composedEvent) {
                    ctx.delivered.remove(pos);
                    // A cached border can be requested without being directly watched by the node router.
                    // Real ingest must revoke it even when only a neighboring mesh observes this event.
                    var version = ctx.requested.get(pos);
                    if (version != null || (watched & flags) != 0) {
                        if (version == null) version = ctx.requested.computeIfAbsent(pos, ignored -> new java.util.concurrent.atomic.AtomicLong());
                        version.set(EPOCH.incrementAndGet()); ctx.invalidateCached(pos);
                    }
                    if ((watched & flags) != 0)
                        snapshot = compose(ctx.engine, pos, ctx.acquireReal.invoke(ctx.engine, pos));
                }
            }
            invoke(router.getClass().getMethod("forwardEvent", borrowed.getClass(), int.class), router,
                    snapshot == null ? borrowed : snapshot, flags);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        finally { if (snapshot != null) try { ctx.release.invoke(snapshot); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); } }
    }

    private static Object invoke(Method method, Object owner, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(owner, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }

    /** Actual current renderer horizon, including VSS range expansion. */
    static int renderDistanceChunks() {
        Context ctx = current;
        return ctx == null || ctx.closed || !ctx.supported ? 0 : Math.max(0, (int) Math.ceil(ctx.distance * 32.0));
    }

    /** Called while Voxy constructs its normal/reverse-Z projection. */
    public static float farPlane(float original) {
        return VoxyRenderAccess.farPlane(original, renderDistanceChunks());
    }

    public static void beginFrame(Object renderSystem) {
        Minecraft mc = Minecraft.getInstance();
        if (!isLevelActive(mc.level)) return;
        try {
            Object engine = field(renderSystem, "worldIn"), nodes = field(renderSystem, "nodeManager");
            Context ctx;
            synchronized (WORLDS) {
                ctx = WORLDS.get(engine);
                if (ctx == null) {
                    ctx = new Context(engine, nodes, mc.level);
                    WORLDS.put(engine, ctx);
                }
            }
            // Render-system replacement invalidates old callbacks without reusing another world's data.
            if(ctx.nodes!=nodes)ctx.delivered.clear();
            ctx.nodes = nodes;
            ctx.refinementNodes = field(nodes, "manager");
            ctx.level = mc.level; ctx.dimension = mc.level.dimension();
            current = ctx;
            updatePredictionState();
            recheckSubdivisions(ctx);
            if (ctx.supported) status = meshHook && neighborHook && eventHook && detailHook ? "ready" : "waiting-for-render-hooks";
            int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
            var session = dev.xantha.vss.api.VSSApi.clientSession();
            int serverDistance = session.isClientLodSessionActive() ? session.getEffectiveLodDistanceChunks() : 0;
            float desired = VoxyRenderAccess.renderDistance(ctx.configDistance(), serverDistance,
                    ctx.source != null && enabled() ? distance : 0);
            if (ctx.renderSystem != renderSystem || Float.compare(ctx.distance, desired) != 0) {
                ctx.capacity = VoxyTraversalCapacity.prepare(renderSystem, ctx.configDistance(), serverDistance,
                        ctx.source != null && enabled() ? distance : 0);
                ctx.setDistance.set(renderSystem, desired);
                ctx.distance = desired; ctx.renderSystem = renderSystem;
            }
            if (mc.player != null) ctx.prune(mc.player.getX(), mc.player.getZ(),
                    Math.max(distance, (int) Math.ceil(desired * 512)) + 1024);
            ctx.flushUpdates();
        } catch (ReflectiveOperationException failure) {
            status = "unsupported:" + failure.getClass().getSimpleName();
            if (current != null) current.supported = false;
        }
    }

    /** Unload fires before Minecraft's forced loading-screen frame and renderer replacement. */
    public static boolean isLevelActive(net.minecraft.client.multiplayer.ClientLevel level) {
        return level != null && level != unloadedLevel.get();
    }

    public static void levelUnloaded(net.minecraft.client.multiplayer.ClientLevel level) {
        unloadedLevel = new java.lang.ref.WeakReference<>(level);
        for (Context ctx : contexts()) if (ctx.level == level) {
            WORLDS.remove(ctx.engine, ctx);
            ctx.close();
            if (current == ctx) current = null;
            synchronized (BUILDS) { BUILDS.values().removeIf(stamp -> stamp.context == ctx); }
            synchronized (SNAPSHOTS) { SNAPSHOTS.values().removeIf(stamp -> stamp.context == ctx); }
        }
    }

    public static void renderClosed(Object renderSystem) {
        try {
            Object engine = field(renderSystem, "worldIn");
            Context ctx = WORLDS.remove(engine);
            if (ctx != null) { ctx.close(); if (current == ctx) current = null; }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    public static boolean attach(ResourceKey<Level> dimension, Source source) {
        Context ctx = current;
        if (!enabled() || !meshHook || !neighborHook || !eventHook || !detailHook || ctx == null || !ctx.supported || ctx.closed
                || !ctx.level.dimension().equals(dimension)) return false;
        if (ctx.source != source) {
            Source previous = ctx.source;
            ctx.source = source;
            ctx.delivered.clear();
            if (previous != null) previous.releaseMapper(ctx);
            changed(source, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            VSSLogger.info("VSS prediction now uses Voxy voxel meshing and rendering: " + dimension.location());
        }
        return true;
    }

    public static boolean active(ResourceKey<Level> dimension) {
        Context ctx = current;
        return enabled() && ctx != null && ctx.supported && ctx.source != null
                && ctx.level.dimension().equals(dimension);
    }
    private static boolean enabled() { return VSSClientConfig.CONFIG.enablePrediction; }

    /** A display toggle changes native residency, not the lifetime of reusable prediction samples. */
    public static void updatePredictionState() {
        boolean enabled = enabled();
        for (Context ctx : contexts()) synchronized (ctx) {
            if (ctx.closed || ctx.predictionEnabled == enabled) continue;
            ctx.predictionEnabled = enabled;
            ctx.delivered.clear();
            ctx.withdrawals.clear();
            if (ctx.source == null) continue;
            // Revoke builds already running with the previous display state. Keep the
            // source/mapper and its reduced columns warm for a subsequent enable.
            ctx.requested.values().forEach(v -> v.set(EPOCH.incrementAndGet()));
            ctx.clearCached();
            ctx.notificationCursor = null;
            if (!enabled) {
                // A last composition can be empty while an older predicted mesh is
                // still resident. Check every observed position against current real data.
                var positions = new ArrayList<>(ctx.requested.keySet());
                // Removing an empty native parent also retires its predicted descendants.
                positions.sort(Comparator.comparingInt(VoxyPredictionBridge::level).reversed());
                ctx.withdrawals.addAll(positions);
            }
            ctx.dirty.addAll(ctx.requested.keySet());
            ctx.flushUpdates();
        }
    }

    /** real carries one acquired reference. Replacement transfers it to a raw untracked snapshot. */
    public static Object compose(Object engine, long pos, Object real) {
        return compose(engine,pos,real,true);
    }
    private static Object compose(Object engine, long pos, Object real, boolean promote) {
        Context ctx = WORLDS.get(engine);
        if (ctx == null || !ctx.supported || ctx.closed) return real;
        var token = ctx.requested.computeIfAbsent(pos, ignored -> new java.util.concurrent.atomic.AtomicLong(EPOCH.incrementAndGet()));
        Source source = enabled() ? ctx.source : null;
        if (source == null) return real;
        acquisitions.increment();
        // Center, border and notification reads of one version share the same immutable section.
        synchronized (token) { return composeLocked(ctx, source, token, pos, real, promote); }
    }
    private static Object composeLocked(Context ctx, Source source, java.util.concurrent.atomic.AtomicLong token,
                                        long pos, Object real, boolean promote) {
        Object snapshot = null;
        SnapshotTarget target=null;
        try {
            long version = token.get();
            Object cached = ctx.cached(source, pos, version);
            if (cached != null) {
                if (real != null) ctx.release.invoke(real);
                cacheHits.increment(); return cached;
            }
            target=new SnapshotTarget(ctx,pos);
            if(!source.copySection(pos,ctx,promote,target)) {ctx.predictionOnly.remove(pos);return real;}
            snapshot=target.section;
            long[] result = ctx.voxels.writable(snapshot);
            byte realChildren = 0;
            VoxyRenderAccess.Voxels raw = null;
            if (real != null) {
                raw = ctx.voxels.read(real);
                realChildren = (byte) ctx.children.invoke(real);
            }
            // An authoritative empty column may have no stored section at all.
            mergeRealVoxels(result, raw, level(pos), x(pos), y(pos), z(pos),
                    VoxyCompat.confirmedPredictionColumns(ctx.engine), (long) ctx.skyLight << 56);
            // Voxy's child mask means occupancy, not permission to subdivide. Its traversal owns refinement.
            byte predictedChildren = children(result, level(pos));
            ctx.setChildren.invoke(snapshot, (byte) (predictedChildren | realChildren));
            // Do not hold native data's traversal behind prediction. Mixed nodes may
            // subdivide; their prediction-only descendants wait on their own samples.
            if (predictedChildren != 0 && realChildren == 0 && (raw == null || !hasBlocks(raw)))
                ctx.predictionOnly.add(pos);
            else ctx.predictionOnly.remove(pos);
            SNAPSHOTS.put(snapshot,new BuildStamp(ctx,pos,version));
            if (promote && ctx.requested.get(pos) == token && token.get() == version && ctx.source == source && !ctx.closed)
                ctx.cache(source, pos, version, snapshot);
            if (real != null) ctx.release.invoke(real);
            composed.increment();
            return snapshot;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (target!=null && target.section != null) try { ctx.release.invoke(target.section); } catch (ReflectiveOperationException ignored) { }
            ctx.supported = false;
            status = "failed:" + failure.getClass().getSimpleName();
            VSSLogger.error("VSS Voxy prediction overlay failed; retaining native Voxy data", failure);
            return real;
        }
    }
    private static final class SnapshotTarget implements java.util.function.Supplier<long[]> {
        final Context context;final long position;Object section;
        SnapshotTarget(Context context,long position) {this.context=context;this.position=position;}
        public long[] get() {
            try {
                if(section==null) {section=context.create.invoke(null,level(position),x(position),y(position),z(position));context.acquire.invoke(section);}
                return context.voxels.writable(section);
            } catch(ReflectiveOperationException failure) {throw new IllegalStateException(failure);}
        }
    }

    static void mergeRealFace(long[] target,VoxyRenderAccess.Voxels real,int face,int lod,int sx,int sz,
                              RealColumn authoritative,long emptyAir) {
        int populated=real!=null && real.data()!=null && lod==0 ? realCells(real.data()) : 0;
        if(real!=null && real.data()==null && !air(real.uniform()))populated=255;
        int step=1<<lod,bx=sx*(32<<lod),bz=sz*(32<<lod),group=Math.max(1,16/step),axis=32/group;
        byte[] known=new byte[axis*axis];
        for(int i=0;i<1024;i++) {
            int index=VoxyFaceData.index(face,i),x=index&31,z=index>>5&31,y=index>>10;
            int cell=z/group*axis+x/group;
            if(known[cell]==0)known[cell]=(byte)(authoritative.contains(Math.floorDiv(bx+x*step,16),Math.floorDiv(bz+z*step,16))?2:1);
            long value=real==null?emptyAir:real.get(index);
            if(known[cell]==2 || lod==0 && (populated&1<<((x>>4)|(z>>4)<<1|(y>>4)<<2))!=0 || !air(value))target[i]=value;
        }
    }

    public interface RealColumn { boolean contains(int chunkX, int chunkZ); }
    /** Known real air wins too. Non-air data always wins while the presence index is warming. */
    static void mergeReal(long[] target, long[] real, int lod, int sx, int sy, int sz, RealColumn authoritative) {
        mergeRealVoxels(target, real == null ? null : new VoxyRenderAccess.Voxels(real, 0), lod, sx, sy, sz, authoritative);
    }
    private static boolean hasBlocks(VoxyRenderAccess.Voxels real) {
        if (real.data() == null) return !air(real.uniform());
        for (long voxel : real.data()) if (!air(voxel)) return true;
        return false;
    }
    static void mergeRealVoxels(long[] target, VoxyRenderAccess.Voxels real, int lod, int sx, int sy, int sz, RealColumn authoritative) {
        mergeRealVoxels(target, real, lod, sx, sy, sz, authoritative, 15L << 56);
    }
    static void mergeRealVoxels(long[] target, VoxyRenderAccess.Voxels real, int lod, int sx, int sy, int sz, RealColumn authoritative, long emptyAir) {
        int step = 1 << lod, bx = sx * (32 << lod), bz = sz * (32 << lod);
        int populated = real != null && real.data() != null && lod == 0 ? realCells(real.data()) : 0;
        if (real != null && real.data() == null && !air(real.uniform())) populated = 255;
        int group = Math.max(1, 16 / step);
        for (int gz=0; gz<32; gz+=group) for (int gx=0; gx<32; gx+=group) {
            boolean exact = authoritative.contains(Math.floorDiv(bx + gx * step,16), Math.floorDiv(bz + gz * step,16));
            // Unknown/empty native sections do not require a 32768-voxel merge.
            if (!exact && (real == null || real.data() == null && air(real.uniform()))) continue;
            // Consecutive X voxels share a cache line. Confirmed columns copy complete rows,
            // including authoritative air, rather than walking Y at a 1024-voxel stride.
            if (exact) {
                for (int y=0;y<32;y++) for (int z=gz;z<gz+group;z++) {
                    int start=y<<10|z<<5|gx;
                    if (real!=null && real.data()!=null) System.arraycopy(real.data(),start,target,start,group);
                    else Arrays.fill(target,start,start+group,real==null ? emptyAir : real.uniform());
                }
                continue;
            }
            for (int y=0; y<32; y++) for (int z=gz; z<gz+group; z++) for (int x=gx; x<gx+group; x++) {
                int i=y<<10|z<<5|x, octant=(x>>4)|(z>>4)<<1|(y>>4)<<2;
                long value=real == null ? emptyAir : real.get(i);
                if (lod == 0 && (populated & 1<<octant) != 0 || !air(value)) target[i]=value;
            }
        }
    }
    private static int realCells(long[] real) {
        int result=0;
        for (int i=0;i<real.length;i++) if (!air(real[i])) {
            result |= 1 << ((i&31)>>4 | (i>>5&31)>>4<<1 | (i>>10)>>4<<2);
            if (result == 255) break;
        }
        return result;
    }
    static boolean air(long value) { return (value & (0xfffffL << 27)) == 0; }
    static byte children(long[] data, int lod) {
        int mask = 0;
        for (int i = 0; i < data.length; i++) if (!air(data[i])) {
            if (lod == 0) return (byte) 255;
            int x = i & 31, z = i >> 5 & 31, y = i >> 10;
            mask |= 1 << ((x >> 4) | (z >> 4) << 1 | (y >> 4) << 2);
        }
        return (byte) mask;
    }

    public static void changed(Source source, int minX, int minZ, int maxX, int maxZ) {
        changed(source, minX, Integer.MIN_VALUE, minZ, maxX, Integer.MAX_VALUE, maxZ);
    }
    public static void changed(Source source, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        changed(source,minX,minY,minZ,maxX,maxY,maxZ,pos->true);
    }
    public static void changed(Source source, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                               java.util.function.LongPredicate affects) {
            for (Context ctx : contexts()) if (ctx.source == source) {
                for (var entry : ctx.requested.intersect(minX,minY,minZ,maxX,maxY,maxZ)) {
                    long pos = entry.getKey();
                    if(!affects.test(pos))continue;
                    entry.getValue().set(EPOCH.incrementAndGet());
                    ctx.invalidateCached(pos); ctx.dirty.add(pos);
                }
                // Versions are invalidated immediately; the next rendered frame flushes
                // one notification per position after all publications in that frame.
            }
    }
    /** A source update is pending before Voxy has even queued its mesh task. */
    static boolean notificationsPending(ResourceKey<Level> dimension, int minX, int minZ, int maxX, int maxZ) {
        for (Context ctx : contexts()) if (!ctx.closed && dimension.equals(ctx.dimension)) {
            if (ctx.dirty instanceof VoxyPendingSections indexed) {
                if (indexed.intersects(minX,minZ,maxX,maxZ)) return true;
            } else for (long pos : ctx.dirty) if (intersectsChunks(pos, minX, minZ, maxX, maxZ)) return true;
            // Read after the queue: the worker sets notifying before removing its dirty entry.
            Long notifying = ctx.notifying;
            if (notifying != null && intersectsChunks(notifying, minX, minZ, maxX, maxZ)) return true;
        }
        return false;
    }
    private static boolean intersectsChunks(long pos, int minX, int minZ, int maxX, int maxZ) {
        long span = 2L << level(pos), bx = (long)x(pos)*span, bz = (long)z(pos)*span;
        return bx <= maxX && bz <= maxZ && bx + span > minX && bz + span > minZ;
    }
    /** Ingest events run before their ticket settles. Publish real air after the last pending section too. */
    public static void realColumnSettled(int chunkX, int chunkZ) {
        VoxyCompat.settlePredictionColumn(chunkX, chunkZ);
        for (Context ctx : contexts()) if (ctx.source != null && !ctx.closed && ctx.supported
                && VoxyCompat.confirmedPredictionColumn(ctx.engine, chunkX, chunkZ)) {
            changed(ctx.source, chunkX * 16 - 16, chunkZ * 16 - 16,
                    chunkX * 16 + 32, chunkZ * 16 + 32);
        }
    }
    public static void detach(Source source) {
        // This path restores native data in a world that remains visible. World unload
        // retires the context first, so it never queues a rebuild of a disappearing scene.
        for (Context ctx : contexts()) synchronized (ctx) {
            if (ctx.closed || ctx.source != source) continue;
            ctx.source = null; source.releaseMapper(ctx);
            ctx.delivered.clear();
            ctx.clearCached(); ctx.requested.values().forEach(v -> v.set(EPOCH.incrementAndGet()));
            ctx.dirty.addAll(ctx.requested.keySet());
        }
    }
    private static List<Context> contexts() { synchronized (WORLDS) { return List.copyOf(WORLDS.values()); } }
    public static void reset() {
        var contexts = contexts();
        synchronized (WORLDS) { WORLDS.clear(); }
        contexts.forEach(Context::close);
        current = null; BUILDS.clear(); SNAPSHOTS.clear();
    }
    public static Map<String, Object> diagnostics() {
        Context ctx = current;
        return Map.ofEntries(Map.entry("status", status), Map.entry("active", ctx != null && active(ctx.level.dimension())),
                Map.entry("sectionsComposed", composed.sum()), Map.entry("remeshNotifications", notified.sum()),
                Map.entry("requestedSections", ctx == null ? 0 : ctx.requested.size()), Map.entry("sectionAcquisitions", acquisitions.sum()),
                Map.entry("snapshotCacheHits", cacheHits.sum()), Map.entry("cachedSnapshots", ctx == null ? 0 : ctx.cachedCount()),
                Map.entry("unchangedNotifications",unchangedNotifications.sum()),Map.entry("blockNotifications",blockNotifications.sum()),
                Map.entry("childNotifications",childNotifications.sum()),
                Map.entry("neighborFaceReads",faceReads.sum()),Map.entry("neighborFaceSnapshotHits",faceSnapshotHits.sum()),
                Map.entry("cachedSnapshotBytes", ctx == null ? 0L : ctx.cachedCount() * 262160L),
                Map.entry("subdivisionWaiters", ctx == null ? 0 : ctx.subdivisionWaiters.size()),
                Map.entry("pendingNotifications", ctx == null ? 0 : ctx.dirty.size()),
                Map.entry("pendingWithdrawals", ctx == null ? 0 : ctx.withdrawals.size()),
                Map.entry("traversalCapacity", ctx == null || ctx.capacity == null ? Map.of() : ctx.capacity.snapshot()));
    }

    private static final class Context implements Mapper, RealColumn {
        final Object engine, mapper;
        final int skyLight;
        volatile Object nodes;
        volatile Object refinementNodes;
        volatile net.minecraft.client.multiplayer.ClientLevel level;
        volatile ResourceKey<Level> dimension;
        volatile Source source;
        volatile boolean supported = true, closed;
        boolean predictionEnabled = enabled();
        final java.util.concurrent.ConcurrentLinkedQueue<Long> withdrawals = new java.util.concurrent.ConcurrentLinkedQueue<>();
        float distance = Float.NaN;
        Object renderSystem;
        VoxyTraversalCapacity capacity;
        VoxySectionPruner pruner = new VoxySectionPruner();
        final VoxyRenderAccess.Sections voxels;
        final Method children, create, acquire, release, setChildren, acquireReal, worldEvent, blockId, biomeId;
        final Method refAcquire, refRelease;
        final VoxyRenderAccess.Distance setDistance;
        final Object config;
        final java.lang.reflect.Field configDistance;
        boolean worldHeld;
        final VoxySectionVersions requested = new VoxySectionVersions();
        final Set<Long> dirty = new VoxyPendingSections();
        final Map<Long,Delivered> delivered = new ConcurrentHashMap<>();
        volatile Long notifying;
        Iterator<Long> notificationCursor;
        final Set<Long> predictionOnly = ConcurrentHashMap.newKeySet();
        final VoxySubdivisionWaiters subdivisionWaiters = new VoxySubdivisionWaiters();
        long lastSubdivisionCheck;
        final java.util.concurrent.atomic.AtomicBoolean scheduled = new java.util.concurrent.atomic.AtomicBoolean();
        final Map<BlockState, Integer> blocks = new ConcurrentHashMap<>();
        final Map<String, Integer> biomes = new ConcurrentHashMap<>();
        final LinkedHashMap<Long, CachedSection> cachedSections = new LinkedHashMap<>(64, .75f, true);
        Context(Object engine, Object nodes, net.minecraft.client.multiplayer.ClientLevel level) throws ReflectiveOperationException {
            this.engine = engine; this.nodes = nodes; this.level = level; this.dimension = level.dimension();
            skyLight = level.dimensionType().hasSkyLight() ? 15 : 0;
            Class<?> section = Class.forName("me.cortex.voxy.common.world.WorldSection");
            voxels = new VoxyRenderAccess.Sections(section); children = section.getMethod("getNonEmptyChildren");
            VoxySectionPosition.bind(engine.getClass());
            create = section.getMethod("_createRawUntrackedUnsafeSection", int.class, int.class, int.class, int.class);
            acquire = section.getMethod("acquire"); release = section.getMethod("release");
            setChildren = section.getMethod("_unsafeSetNonEmptyChildren", byte.class);
            acquireReal = engine.getClass().getMethod("acquireIfExists", long.class);
            worldEvent = nodes.getClass().getMethod("worldEvent", section, int.class, int.class);
            mapper = engine.getClass().getMethod("getMapper").invoke(engine);
            blockId = mapper.getClass().getMethod("getIdForBlockState", BlockState.class);
            biomeId = mapper.getClass().getMethod("getIdForBiome", net.minecraft.core.Holder.class);
            refAcquire = engine.getClass().getMethod("acquireRef"); refRelease = engine.getClass().getMethod("releaseRef");
            setDistance = VoxyRenderAccess.Distance.bind(Class.forName("me.cortex.voxy.client.core.VoxyRenderSystem"));
            Class<?> configType = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            config = configType.getField("CONFIG").get(null); configDistance = configType.getField("sectionRenderDistance");
            refAcquire.invoke(engine); worldHeld = true;
        }
        float configDistance() throws IllegalAccessException { return ((Number) configDistance.get(config)).floatValue(); }
        synchronized void close() {
            if (closed) return;
            closed = true;
            Source previous = source; source = null;
            if (previous != null) previous.releaseMapper(this);
            dirty.clear(); withdrawals.clear(); notificationCursor = null; requested.clear(); predictionOnly.clear();
            delivered.clear();
            pruner.reset();
            subdivisionWaiters.clear();
            clearCached();
            if (!scheduled.get()) releaseWorld();
        }
        private synchronized void releaseWorld() {
            if (!worldHeld) return;
            worldHeld = false;
            try { refRelease.invoke(engine); }
            catch (ReflectiveOperationException e) { VSSLogger.error("VSS overlay world reference release failed", e); }
        }
        public boolean isOpen() { return !closed && source != null; }
        public boolean hasSkyLight() { return skyLight != 0; }
        public long map(BlockState state, String biome) {
            if (state == null || state.isAir()) return (long) skyLight << 56;
            Integer block = blocks.get(state);
            if (block == null) block = blocks.computeIfAbsent(state, s -> number(blockId, s));
            Integer bio = biomes.get(biome);
            if (bio == null) bio = biomes.computeIfAbsent(biome, b -> {
                var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
                var key = net.minecraft.resources.ResourceLocation.tryParse(b);
                var holder = key == null ? null : registry.getHolder(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.BIOME, key)).orElse(null);
                if (holder == null) holder = registry.getHolder(net.minecraft.world.level.biome.Biomes.PLAINS).orElseThrow();
                return number(biomeId, holder);
            });
            int light = Math.min(15, state.getLightEmission()) << 4 | skyLight;
            return (long) light << 56 | (long) bio << 47 | (long) block << 27;
        }
        public boolean contains(int chunkX, int chunkZ) { return VoxyCompat.confirmedPredictionColumn(engine, chunkX, chunkZ); }
        // Notification delivery holds Context's lifecycle monitor and may request a section.
        // Cache readers already own a per-position token: never acquire that lifecycle monitor here.
        int cachedCount() { synchronized (cachedSections) { return cachedSections.size(); } }
        Object cached(Source owner, long pos, long version) throws ReflectiveOperationException {
            synchronized (cachedSections) {
                CachedSection entry = cachedSections.get(pos);
                if (entry == null) return null;
                if (entry.source != owner || entry.version != version || closed) { invalidateCached(pos); return null; }
                acquire.invoke(entry.section); return entry.section;
            }
        }
        void cache(Source owner, long pos, long version, Object section) throws ReflectiveOperationException {
            synchronized (cachedSections) {
                if (closed || source != owner || version != version(pos)) return;
                while (cachedSections.size() >= MAX_SNAPSHOTS) invalidateCached(cachedSections.keySet().iterator().next());
                Retention lease = owner.retainSection(262160L);
                if (lease == null) return;
                try { acquire.invoke(section); }
                catch (ReflectiveOperationException failure) { lease.close(); throw failure; }
                CachedSection previous = cachedSections.put(pos, new CachedSection(owner, version, section, lease));
                releaseCached(previous);
            }
        }
        private void releaseCached(CachedSection entry) {
            if (entry == null) return;
            try { release.invoke(entry.section); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Voxy snapshot release", failure); }
            finally { entry.retention.close(); }
        }
        void invalidateCached(long pos) { synchronized (cachedSections) { releaseCached(cachedSections.remove(pos)); } }
        void clearCached() {
            synchronized (cachedSections) { cachedSections.values().forEach(this::releaseCached); cachedSections.clear(); }
        }
        private int number(Method method, Object value) {
            try { return ((Number) method.invoke(mapper, value)).intValue(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Voxy mapper call", failure); }
        }
        void flushUpdates() { if (!dirty.isEmpty() || !withdrawals.isEmpty()) schedule(); }
        Delivered notificationContent(long pos,Object section) throws ReflectiveOperationException {
            Source owner=source;
            VoxySectionSignature.Value faces=null;
            if(owner!=null && enabled() && owner.compareNotificationFaces()) {
                try(var scratch=VoxyNotificationScratch.borrow()) {
                long[][] border=scratch.faces;
                int lod=level(pos),sx=x(pos),sy=y(pos),sz=z(pos);
                for(int direction=0;direction<6;direction++) {
                    int nx=sx+(direction==0?-1:direction==1?1:0),ny=sy+(direction==2?-1:direction==3?1:0),
                            nz=sz+(direction==4?-1:direction==5?1:0);
                    long neighbor=position(lod,nx,ny,nz);int face=direction^1;
                    Object real=acquireReal.invoke(engine,neighbor);
                    try {
                        var raw=real==null?null:voxels.read(real);
                        if(owner.copyFace(neighbor,this,face,border[direction]))
                            mergeRealFace(border[direction],raw,face,lod,nx,nz,VoxyCompat.confirmedPredictionColumns(engine),(long)skyLight<<56);
                        else for(int i=0;i<1024;i++)border[direction][i]=raw==null?0:raw.get(VoxyFaceData.index(face,i));
                    } finally {if(real!=null)release.invoke(real);}
                }
                faces=VoxySectionSignature.faces(border);
                }
            }
            return new Delivered(VoxySectionSignature.section(voxels.read(section)),(byte)children.invoke(section),faces);
        }
        synchronized void schedule() {
            if (!closed && (supported || source == null) && scheduled.compareAndSet(false, true)) UPDATES.execute(() -> {
                try {
                    // Empty prediction meshes need no model baking or expensive meshing.
                    // Use the native result queue so its node thread owns every removal.
                    for (int i = 0; i < 1024 && !closed && !enabled(); i++) {
                        Long position = withdrawals.poll();
                        if (position == null) break;
                        Object real = acquireReal.invoke(engine, position);
                        try {
                            if (real != null && ((byte) children.invoke(real) != 0 || hasBlocks(voxels.read(real)))) continue;
                            Object section = real == null ? empty(position) : real;
                            try { synchronized (this) {
                                if (closed || enabled()) break;
                                dirty.remove(position);
                                // Clear Voxy's geometry cache and child occupancy first,
                                // then retire resident geometry on its native node thread.
                                worldEvent.invoke(nodes, section, 3, 0);
                                notified.increment();
                                VoxyRenderAccess.withdraw(nodes, position);
                            } } finally { if (real == null) release.invoke(section); }
                        } finally { if (real != null) release.invoke(real); }
                    }
                    // Bound both transient section memory and downstream Voxy update pressure.
                    // Iterate the live queue without copying the entire old scene into another list.
                    // Continue across frames so frequently changed early entries cannot starve later sections.
                    var pending = notificationCursor;
                    if (pending == null || !pending.hasNext()) notificationCursor = pending = dirty.iterator();
                    for (int count = 0; count < NOTIFICATIONS_PER_FRAME && !closed && pending.hasNext(); count++) {
                        long pos = pending.next();
                        notifying = pos;
                        if (!dirty.remove(pos)) continue;
                        long version=version(pos);
                        Object real = acquireReal.invoke(engine, pos);
                        Object section = compose(engine, pos, real, false);
                        if (section == null) {
                            section = create.invoke(null, level(pos), x(pos), y(pos), z(pos)); acquire.invoke(section);
                            Arrays.fill(voxels.writable(section), 0L);
                            setChildren.invoke(section, (byte) 0);
                        }
                        // changed() already includes the voxel-border halo at every native LOD.
                        // All affected requested neighbors are in dirty; broadcasting again rebuilds them repeatedly.
                        try {
                            var content=notificationContent(pos,section);
                            synchronized(this) {if(!closed) {
                                if(version!=version(pos)) {dirty.add(pos);continue;}
                                int flags=content.changes(delivered.get(pos));
                                if(flags!=0) {
                                    NOTIFICATION_SECTION.set(section);
                                    try {worldEvent.invoke(nodes,section,flags,0);} finally {NOTIFICATION_SECTION.remove();}
                                    notified.increment();
                                    if((flags&1)!=0)blockNotifications.increment();
                                    if((flags&2)!=0)childNotifications.increment();
                                } else unchangedNotifications.increment();
                                delivered.put(pos,content);
                            }}
                        }
                        finally { release.invoke(section); notifying = null; }
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    supported = false; status = "notification-failed";
                    dirty.clear();
                    VSSLogger.error("VSS Voxy prediction notification failed", failure);
                } finally {
                    synchronized (this) {
                        notifying = null;
                        scheduled.set(false);
                        if (closed) { notificationCursor = null; releaseWorld(); }
                    }
                }
            });
        }
        void prune(double cameraX, double cameraZ, int radius) {
            pruner.prune(requested, cameraX, cameraZ, radius, System.nanoTime(), pos -> {
                invalidateCached(pos); dirty.remove(pos); predictionOnly.remove(pos);delivered.remove(pos);
            });
        }
        long version(long pos) { var v = requested.get(pos); return v == null ? -1 : v.get(); }
        Object empty(long pos) throws ReflectiveOperationException {
            Object result = create.invoke(null, level(pos), x(pos), y(pos), z(pos)); acquire.invoke(result);
            Arrays.fill(voxels.writable(result), 0L); setChildren.invoke(result, (byte) 0);
            return result;
        }
    }
    private static final ClassValue<Map<String, java.lang.reflect.Field>> FIELDS = new ClassValue<>() {
        protected Map<String, java.lang.reflect.Field> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var fields = FIELDS.get(owner.getClass());
        var field = fields.get(name);
        if (field == null) {
            field = owner.getClass().getDeclaredField(name); field.setAccessible(true); fields.put(name, field);
        }
        return field.get(owner);
    }
    public static long position(int lod, int x, int y, int z) {
        return VoxySectionPosition.key(lod, x, y, z);
    }
    public static int level(long p) { return VoxySectionPosition.level(p); }
    public static int x(long p) { return VoxySectionPosition.x(p); }
    public static int y(long p) { return VoxySectionPosition.y(p); }
    public static int z(long p) { return VoxySectionPosition.z(p); }
}

package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.mixin.voxy.StrictVoxyUploadMixin;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryUtil;

/** The real upload callback must finish reading before a worker can recycle its batch. */
class StrictVoxyUploadOwnershipTest {
    private static final long POSITION = StrictVoxyNodeIndex.key(0, -3, 2, 4);
    private static final VarHandle CACHE;
    static {
        try { CACHE = MethodHandles.lookup().findVarHandle(CacheSlot.class, "value", Object.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private boolean oldPrediction;

    @BeforeAll static void bootstrap() throws Exception {
        var method = Class.forName("dev.xantha.vss.client.prediction.ClientTerrainSamplerTest")
                .getDeclaredMethod("bootstrapMinecraft");
        method.setAccessible(true);
        method.invoke(null);
    }

    @BeforeEach void setup() {
        oldPrediction = VSSClientConfig.CONFIG.enablePrediction;
        VSSClientConfig.CONFIG.enablePrediction = false;
        StrictLodVisibility.reset();
    }

    @AfterEach void cleanup() {
        StrictLodVisibility.reset();
        VSSClientConfig.CONFIG.enablePrediction = oldPrediction;
    }

    @Test void metadataIsReadBeforeTheBatchIsReturnedToTheWorker() throws Exception {
        var slot = new CacheSlot();
        var pipeline = new StrictVoxyPipeline();
        pipeline.queued(POSITION);
        try (var batch = new Batch(slot)) {
            batch.completed.add(pipeline.started(POSITION));
            assertTrue(upload(slot, batch));
            assertFalse(batch.locations.readAfterPublication,
                    "the worker may clear the map or free the buffer as soon as the cache CAS succeeds");
            assertSame(batch, slot.value);
            assertTrue(nodes().coversFinest(-6, 4, 8));
            assertEquals(0, pipeline.size());
            // Simulate immediate worker reuse. The mirror must already own its delta.
            batch.locations.clear();
            MemoryUtil.memSet(batch.scatterWriteBuffer.address, 0, batch.scatterWriteBuffer.size);
            assertTrue(nodes().coversFinest(-6, 4, 8));
        }
    }

    @Test void occupiedPrimaryCacheDoesNotPostponeAnAlreadyUploadedDelta() throws Exception {
        var slot = new CacheSlot();
        slot.value = new Object();
        var pipeline = new StrictVoxyPipeline();
        pipeline.queued(POSITION);
        try (var batch = new Batch(slot)) {
            batch.completed.add(pipeline.started(POSITION));
            assertFalse(upload(slot, batch));
            assertTrue(nodes().coversFinest(-6, 4, 8),
                    "GPU upload has completed even when Voxy must recycle through its second cache slot");
            assertEquals(0, pipeline.size());
            assertTrue(batch.completed.isEmpty());
            var secondSlot = new CacheSlot();
            assertTrue(CACHE.compareAndSet(secondSlot, null, batch));
        }
    }

    @Test void returningABatchDoesNotCompleteANewerMeshEdit() throws Exception {
        var slot = new CacheSlot();
        var pipeline = new StrictVoxyPipeline();
        pipeline.queued(POSITION);
        var old = pipeline.started(POSITION);
        pipeline.queued(POSITION);
        try (var batch = new Batch(slot)) {
            batch.completed.add(old);
            assertTrue(upload(slot, batch));
            assertFalse(pipeline.idle(-6, 4, 8));
            pipeline.started(POSITION).uploaded();
            assertTrue(pipeline.idle(-6, 4, 8));
        }
    }

    @Test void aLostDeltaDisablesTheIncompleteMirrorOnceInsteadOfReusingIt() throws Exception {
        var slot = new CacheSlot();
        try (var batch = new Batch(slot)) {
            StrictLodVisibility.uploaded(slot, batch);
            assertTrue(nodes().coversFinest(-6, 4, 8));
            long before = StrictLodVisibility.coverageResetRevision();
            StrictLodVisibility.uploaded(slot, new Object());
            assertTrue(field("failed").getBoolean(null));
            assertFalse(nodes().coversFinest(-6, 4, 8), "an incremental mirror cannot recover a missed delta on the next batch");
            long reset = StrictLodVisibility.coverageResetRevision();
            assertTrue(reset > before);
            for (int i = 0; i < 100; i++) StrictLodVisibility.uploaded(slot, batch);
            assertEquals(reset, StrictLodVisibility.coverageResetRevision(), "repeated failures must not reset every resident prediction tile");
        }
    }

    private static boolean upload(CacheSlot slot, Batch batch) throws Exception {
        var method = StrictVoxyUploadMixin.class.getDeclaredMethod("vss$uploadedPrimary",
                VarHandle.class, Object.class, Void.class, Object.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new UploadMixin(), CACHE, slot, null, batch);
    }

    private static StrictVoxyNodeIndex nodes() throws Exception { return (StrictVoxyNodeIndex) field("NODES").get(null); }
    private static Field field(String name) throws Exception {
        var field = StrictLodVisibility.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class UploadMixin extends StrictVoxyUploadMixin { }
    private static final class CacheSlot { private volatile Object value; }
    private static final class Buffer {
        private final long address = MemoryUtil.nmemAlloc(80);
        private final long size = 80;
    }

    private static final class Batch implements StrictVoxyPipeline.Batch, AutoCloseable {
        private final Buffer scatterWriteBuffer = new Buffer();
        private final RecycledMap locations;
        private final Int2IntMap scatterWriteLocationMap;
        private final List<StrictVoxyPipeline.Work> completed = new ArrayList<>();
        private Batch(CacheSlot slot) {
            locations = new RecycledMap(slot, this);
            scatterWriteLocationMap = locations;
            locations.put(7, 1);
            long pointer = scatterWriteBuffer.address + 16;
            MemoryUtil.memPutInt(pointer, (int) (POSITION >>> 32));
            MemoryUtil.memPutInt(pointer + 4, (int) POSITION);
            MemoryUtil.memPutInt(pointer + 8, 123);
        }
        @Override public void vss$appendCompletedWork(Collection<StrictVoxyPipeline.Work> work) { completed.addAll(work); }
        @Override public List<StrictVoxyPipeline.Work> vss$takeCompletedWork() {
            var result = new ArrayList<>(completed);
            completed.clear();
            return result;
        }
        @Override public void vss$restoreCompletedWork(Collection<StrictVoxyPipeline.Work> work) { completed.addAll(work); }
        @Override public void close() { MemoryUtil.nmemFree(scatterWriteBuffer.address); }
    }

    /** Models worker reuse at the exact ownership boundary, without relying on thread timing. */
    private static final class RecycledMap extends Int2IntOpenHashMap {
        private final CacheSlot slot;
        private final Batch batch;
        private boolean readAfterPublication;
        private RecycledMap(CacheSlot slot, Batch batch) { this.slot = slot; this.batch = batch; }
        @Override public Int2IntMap.FastEntrySet int2IntEntrySet() {
            if (slot.value == batch) {
                readAfterPublication = true;
                throw new NullPointerException("worker has recycled the scatter map");
            }
            return super.int2IntEntrySet();
        }
    }
}

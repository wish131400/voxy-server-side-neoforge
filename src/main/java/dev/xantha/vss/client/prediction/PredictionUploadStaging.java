package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.lwjgl.system.MemoryUtil;

/** Optional worker-prepared upload bytes. Never gates generation or render admission.
 * The bound includes idle, queued, filling and render-owned buffers. GL calls stay
 * on the render thread; a taken payload owns its memory until upload returns. */
final class PredictionUploadStaging {
    static final long LIMIT_BYTES = 32L * 1024 * 1024;
    static final int MAX_BUFFERS = 128;
    static final PredictionUploadStaging SHARED = new PredictionUploadStaging(LIMIT_BYTES, MAX_BUFFERS,
            new Allocator() {
                public ByteBuffer allocate(int bytes) { return MemoryUtil.memAlloc(bytes); }
                public void free(ByteBuffer buffer) { MemoryUtil.memFree(buffer); }
            });

    interface Allocator {
        ByteBuffer allocate(int bytes);
        void free(ByteBuffer buffer);
    }

    private final long limit;
    private final int maxBuffers;
    private final Allocator allocator;
    private final LinkedHashMap<Object, Payload> ready = new LinkedHashMap<>();
    private final HashMap<Object, Object> pending = new HashMap<>();
    private final ArrayDeque<ByteBuffer> idle = new ArrayDeque<>();
    private long epoch, allocatedBytes, preparedBytes, hits, misses, reused, evicted, allocationFailures;
    private int allocatedBuffers;

    PredictionUploadStaging(long limit, int maxBuffers, Allocator allocator) {
        this.limit = limit;
        this.maxBuffers = maxBuffers;
        this.allocator = allocator;
    }

    synchronized long epoch() { return epoch; }
    synchronized boolean contains(Object key) { return ready.containsKey(key); }

    /** Called before publication, or by the existing cold restore worker. Copying
     * and allocation happen outside the lock used by the render thread. */
    boolean stage(Object key, int[] opaque, int[] water, float[] morph, long generation) {
        int morphWords = morph == null ? 0 : Math.toIntExact(((long) morph.length + 3) / 4 * 4);
        long opaqueBytes = ((long) opaque.length + morphWords) * Integer.BYTES;
        long bytes = opaqueBytes + (long) water.length * Integer.BYTES;
        if (bytes == 0 || bytes > limit || bytes > Integer.MAX_VALUE - 4095) return false;
        int capacity = ((int) bytes + 4095) / 4096 * 4096;
        Object ticket = new Object();
        ByteBuffer buffer;
        synchronized (this) {
            if (generation != epoch) return false;
            if (ready.containsKey(key)) return true;
            if (pending.containsKey(key)) return false;
            buffer = reserve(capacity);
            if (buffer == null && (allocatedBytes + capacity > limit || allocatedBuffers >= maxBuffers)) return false;
            if (buffer == null) { allocatedBytes += capacity; allocatedBuffers++; }
            pending.put(key, ticket);
        }
        Payload payload = null;
        try {
            if (buffer == null) {
                try { buffer = allocator.allocate(capacity); }
                catch (OutOfMemoryError unavailable) {
                    // Optional extra native memory must not cancel a completed
                    // tile or trigger the generation heap-exhaustion fuse.
                    synchronized (this) { allocationFailures++; }
                    return false;
                }
            }
            payload = new Payload(this, buffer, generation, (int) opaqueBytes, water.length * Integer.BYTES);
            buffer.clear().order(ByteOrder.nativeOrder());
            buffer.asIntBuffer().put(opaque);
            buffer.position(opaque.length * Integer.BYTES);
            if (morph != null) {
                for (float value : morph) buffer.putInt(Math.round(value * 256));
                for (int i = morph.length; i < morphWords; i++) buffer.putInt(0);
            }
            buffer.asIntBuffer().put(water);
            buffer.position((int) bytes).flip();
            synchronized (this) {
                if (generation != epoch || !pending.remove(key, ticket)) return false;
                ready.put(key, payload);
                preparedBytes += bytes;
                payload = null; // Ownership passes to the ready map.
                return true;
            }
        } finally {
            synchronized (this) {
                pending.remove(key, ticket);
                if (payload != null) payload.close();
                else if (buffer == null) { allocatedBytes -= capacity; allocatedBuffers--; }
            }
        }
    }

    /** Caller holds the monitor. Reuse first, otherwise retire oldest unused
     * staging. Buffers owned by workers or uploads are never evicted. */
    private ByteBuffer reserve(int capacity) {
        for (;;) {
            ByteBuffer best = null;
            for (ByteBuffer candidate : idle)
                if (candidate.capacity() >= capacity && (best == null || candidate.capacity() < best.capacity())) best = candidate;
            if (best != null) {
                for (var iterator = idle.iterator(); iterator.hasNext();) if (iterator.next() == best) { iterator.remove(); break; }
                reused++; return best;
            }
            if (allocatedBytes + capacity <= limit && allocatedBuffers < maxBuffers) return null;
            if (!idle.isEmpty()) { free(idle.removeFirst()); continue; }
            if (ready.isEmpty()) return null;
            var iterator = ready.entrySet().iterator();
            Payload old = iterator.next().getValue(); iterator.remove();
            old.close(); evicted++;
        }
    }

    synchronized Payload take(Object key) {
        Payload payload = ready.remove(key);
        if (payload == null) misses++; else hits++;
        return payload;
    }

    synchronized void discard(Object key) {
        pending.remove(key);
        Payload payload = ready.remove(key);
        if (payload != null) payload.close();
    }

    synchronized void clear() {
        epoch++;
        pending.clear();
        for (Payload payload : ready.values()) payload.close();
        ready.clear();
        while (!idle.isEmpty()) free(idle.removeFirst());
        // Active copies/uploads retain ownership, and free on return because
        // their epoch no longer matches. They cannot repopulate the new world.
    }

    private void free(ByteBuffer buffer) {
        allocator.free(buffer);
        allocatedBytes -= buffer.capacity(); allocatedBuffers--;
    }

    synchronized long allocatedBytes() { return allocatedBytes; }
    synchronized String diagnostics() {
        return "nativeBytes=" + allocatedBytes + ",buffers=" + allocatedBuffers + ",ready=" + ready.size()
                + ",filling=" + pending.size() + ",hits=" + hits + ",misses=" + misses
                + ",reused=" + reused + ",evicted=" + evicted + ",preparedBytes=" + preparedBytes
                + ",allocationFailures=" + allocationFailures;
    }

    static final class Payload implements AutoCloseable {
        private final PredictionUploadStaging owner;
        private final ByteBuffer buffer;
        private final long generation;
        private final int opaqueBytes, waterBytes;
        private boolean closed;
        private Payload(PredictionUploadStaging owner, ByteBuffer buffer, long generation, int opaqueBytes, int waterBytes) {
            this.owner = owner; this.buffer = buffer; this.generation = generation;
            this.opaqueBytes = opaqueBytes; this.waterBytes = waterBytes;
        }
        ByteBuffer opaque() { return view(0, opaqueBytes); }
        ByteBuffer water() { return waterBytes == 0 ? null : view(opaqueBytes, waterBytes); }
        private ByteBuffer view(int offset, int length) {
            if (closed) throw new IllegalStateException("Upload staging already returned");
            return buffer.duplicate().position(offset).limit(offset + length).slice().order(ByteOrder.nativeOrder());
        }
        @Override public void close() {
            synchronized (owner) {
                if (closed) return;
                closed = true;
                if (generation == owner.epoch) owner.idle.addLast(buffer);
                else owner.free(buffer);
            }
        }
    }
}

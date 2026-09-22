package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.TextureUtil;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;

/** Render-thread spare storage. Never waits for a buffer that the GPU still reads. */
final class PredictionQuadBufferPool implements AutoCloseable {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static final int MAX_ENTRIES = 128;
    static final long MAX_IDLE_NANOS = 2_000_000_000L;
    static final PredictionQuadBufferPool SHARED = new PredictionQuadBufferPool(new OpenGl(), System::nanoTime);

    static final class Allocation {
        final int buffer, texture, capacity;
        long fence, retiredAt;
        Allocation(int buffer, int texture, int capacity) {
            this.buffer = buffer; this.texture = texture; this.capacity = capacity;
        }
    }

    interface Driver {
        Allocation create(ByteBuffer data);
        void upload(Allocation allocation, ByteBuffer data);
        long fence();
        boolean ready(long fence);
        void deleteFence(long fence);
        void delete(Allocation allocation);
    }

    private final Driver driver;
    private final LongSupplier clock;
    private final ArrayDeque<Allocation> spare = new ArrayDeque<>();
    private long bytes, hits, misses, busy;

    PredictionQuadBufferPool(Driver driver, LongSupplier clock) {
        this.driver = driver; this.clock = clock;
    }

    Allocation upload(ByteBuffer data) {
        trim();
        int length = data.remaining();
        int probes = 0;
        for (var iterator = spare.iterator(); iterator.hasNext();) {
            Allocation allocation = iterator.next();
            // Avoid carrying an old large capacity after shrinking the view or mesh.
            // No geometric growth: at most 256 spare bytes in a live reused allocation.
            if (allocation.capacity < length || allocation.capacity - length > 256) continue;
            if (++probes > 8) break;
            if (!driver.ready(allocation.fence)) { busy++; continue; }
            iterator.remove(); bytes -= allocation.capacity;
            driver.deleteFence(allocation.fence); allocation.fence = 0;
            try { driver.upload(allocation, data); }
            catch (RuntimeException | Error failure) { driver.delete(allocation); throw failure; }
            hits++;
            return allocation;
        }
        misses++;
        return driver.create(data);
    }

    void retire(Allocation allocation) {
        if (allocation == null) return;
        trim();
        if (allocation.capacity > MAX_BYTES || spare.size() >= MAX_ENTRIES
                || bytes + allocation.capacity > MAX_BYTES) {
            driver.delete(allocation);
            return;
        }
        allocation.fence = driver.fence();
        if (allocation.fence == 0) { driver.delete(allocation); return; }
        allocation.retiredAt = clock.getAsLong();
        spare.addLast(allocation); bytes += allocation.capacity;
    }

    /** Deleting GL names is safe while commands are in flight; the driver owns deferred disposal. */
    void discard(Allocation allocation) {
        if (allocation == null) return;
        if (allocation.fence != 0) driver.deleteFence(allocation.fence);
        driver.delete(allocation);
    }

    void trim() {
        long now = clock.getAsLong();
        for (int disposed = 0; disposed < 8 && !spare.isEmpty()
                && now - spare.peekFirst().retiredAt >= MAX_IDLE_NANOS; disposed++) {
            Allocation allocation = spare.removeFirst(); bytes -= allocation.capacity;
            discard(allocation);
        }
    }

    long retainedBytes() { return bytes; }
    int retainedCount() { return spare.size(); }
    String diagnostics() {
        return "quadPool={spareBytes=" + bytes + ",entries=" + spare.size()
                + ",hits=" + hits + ",misses=" + misses + ",busy=" + busy + "}";
    }

    @Override public void close() {
        while (!spare.isEmpty()) discard(spare.removeFirst());
        bytes = 0;
    }

    private static final class OpenGl implements Driver {
        @Override public Allocation create(ByteBuffer data) {
            int buffer = GL15.glGenBuffers(), texture = -1;
            try {
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
                GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, data, GL15.GL_STATIC_DRAW);
                texture = TextureUtil.generateTextureId();
                GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
                GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL31.GL_RGBA32UI, buffer);
                return new Allocation(buffer, texture, data.remaining());
            } catch (RuntimeException | Error failure) {
                if (texture != -1) TextureUtil.releaseTextureId(texture);
                GL15.glDeleteBuffers(buffer);
                throw failure;
            } finally {
                GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
                GL31.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            }
        }
        @Override public void upload(Allocation allocation, ByteBuffer data) {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, allocation.buffer);
            try { GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, data); }
            finally { GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0); }
        }
        @Override public long fence() { return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
        @Override public boolean ready(long fence) {
            int result = GL32.glClientWaitSync(fence, 0, 0);
            return result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED;
        }
        @Override public void deleteFence(long fence) { GL32.glDeleteSync(fence); }
        @Override public void delete(Allocation allocation) {
            TextureUtil.releaseTextureId(allocation.texture);
            GL15.glDeleteBuffers(allocation.buffer);
        }
    }
}

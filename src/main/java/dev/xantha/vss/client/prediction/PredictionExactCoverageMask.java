package dev.xantha.vss.client.prediction;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/** A bounded snapshot of the existing column cache, paired with actual frame depth. */
final class PredictionExactCoverageMask {
    static final int TEXTURE_UNITS = 9;
    private static final long REFRESH_NANOS = 250_000_000L;
    private ClientLevel level;
    private CompletableFuture<Snapshot> pending;
    private Snapshot current;
    private long requestedAt;
    private long changedAt;
    private long requestedRevision = -1;
    private int requestedX, requestedZ, requestedRadius;
    private boolean settled;
    private int texture = -1, allocatedSize;

    record Snapshot(int originX, int originZ, int size, byte[] columns) {
        static Snapshot around(int cx, int cz, int radius) {
            int padded = Math.max(1, Math.min(512, radius)) + 32;
            int size = padded * 2 + 1;
            return new Snapshot(Math.floorDiv(cx, 32) * 32 - padded,
                    Math.floorDiv(cz, 32) * 32 - padded, size, new byte[size * size]);
        }
        void mark(int cx, int cz) {
            int x = cx - originX, z = cz - originZ;
            if (x >= 0 && z >= 0 && x < size && z < size) columns[z * size + x] = (byte) 255;
        }
    }

    void bind(ClientLevel nextLevel, Vec3 camera, int radius, int sampler, int bounds) {
        if (level != nextLevel) { invalidate(); level = nextLevel; }
        PredictionGlState.activeTexture(GL13.GL_TEXTURE8);
        if (texture == -1) texture = GL11.glGenTextures();
        PredictionGlState.bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (pending != null && pending.isDone()) {
            Snapshot completed = pending.getNow(null);
            pending = null;
            if (completed != null) {
                current = completed;
                var bytes = MemoryUtil.memAlloc(current.columns().length);
                try {
                    bytes.put(current.columns()).flip();
                    int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                    try {
                        if (allocatedSize != current.size()) {
                            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, current.size(), current.size(),
                                    0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bytes);
                            allocatedSize = current.size();
                        } else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, current.size(), current.size(),
                                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bytes);
                    } finally { GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment); }
                } finally { MemoryUtil.memFree(bytes); }
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            }
        }
        long now = System.nanoTime();
        int cx = (int) Math.floor(camera.x / 16), cz = (int) Math.floor(camera.z / 16);
        long revision = ClientPredictionState.exactCoverageDataRevision();
        boolean changed = revision != requestedRevision || radius != requestedRadius
                || Math.floorDiv(cx, 32) != requestedX || Math.floorDiv(cz, 32) != requestedZ;
        if (nextLevel != null && pending == null && (changed || !settled) && now - requestedAt >= REFRESH_NANOS) {
            if (changed) changedAt = now;
            requestedAt = now;
            requestedRevision = revision;
            requestedX = Math.floorDiv(cx, 32); requestedZ = Math.floorDiv(cz, 32); requestedRadius = radius;
            settled = now - changedAt >= ExactCoverageGate.SETTLE_NANOS;
            pending = ClientPredictionState.exactCoverageSnapshot(nextLevel.dimension(), cx, cz, radius);
        }
        GL20.glUniform1i(sampler, 8);
        if (current == null) GL20.glUniform3f(bounds, 0, 0, 0);
        else GL20.glUniform3f(bounds, (float) (current.originX() * 16.0 - camera.x),
                (float) (current.originZ() * 16.0 - camera.z), current.size());
    }

    void invalidate() { level = null; pending = null; current = null; requestedAt = 0; requestedRevision = -1; settled = false; }
}

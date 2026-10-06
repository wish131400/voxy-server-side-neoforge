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
    private boolean uploadPending;
    private long pendingRemovalRevision, currentRemovalRevision;
    private int texture = -1, allocatedSize;

    record Snapshot(int originX, int originZ, int size, byte[] columns, int[] prefix) {
        Snapshot(int originX, int originZ, int size, byte[] columns) {
            this(originX, originZ, size, columns, new int[(size + 1) * (size + 1)]);
        }
        /** Constant-time proof that every column is settled interior. Inclusive chunk bounds. */
        boolean interior(int minX, int minZ, int maxX, int maxZ) {
            int x = minX - originX, z = minZ - originZ, xx = maxX - originX + 1, zz = maxZ - originZ + 1;
            if (x < 0 || z < 0 || xx > size || zz > size || xx <= x || zz <= z) return false;
            int stride = size + 1;
            return prefix[zz * stride + xx] - prefix[z * stride + xx]
                    - prefix[zz * stride + x] + prefix[z * stride + x] == (xx - x) * (zz - z);
        }
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
        /** 255 is settled interior; 128 is an indexed edge needing actual frame depth. */
        void preserveBoundaryFallback() {
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (columns[i] == 0) continue;
                boolean interior = x > 0 && z > 0 && x < size - 1 && z < size - 1;
                for (int dz = -1; interior && dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    // Both edge and interior remain positive: classification
                    // in place must not erode additional rows as we traverse.
                    if (columns[(z + dz) * size + x + dx] == 0) { interior = false; break; }
                }
                if (!interior) columns[i] = (byte) 128;
            }
            int stride = size + 1;
            for (int z = 0; z < size; z++) {
                int row = 0;
                for (int x = 0; x < size; x++) {
                    if (columns[z * size + x] == (byte) 255) row++;
                    prefix[(z + 1) * stride + x + 1] = prefix[z * stride + x + 1] + row;
                }
            }
        }
    }

    /** Advance once before building either pass, even if all commands are subsequently eliminated. */
    void update(ClientLevel nextLevel, Vec3 camera, int radius) {
        if (level != nextLevel) { invalidate(); level = nextLevel; }
        long removalRevision = ClientPredictionState.exactCoverageRemovalRevision();
        if (current != null && currentRemovalRevision != removalRevision) current = null;
        if (pending != null && pending.isDone()) {
            Snapshot completed = pending.getNow(null);
            pending = null;
            if (completed != null && pendingRemovalRevision == removalRevision) {
                current = completed; currentRemovalRevision = removalRevision; uploadPending = true;
            } else { requestedRevision = -1; settled = false; uploadPending = false; }
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
            pendingRemovalRevision = ClientPredictionState.exactCoverageRemovalRevision();
            pending = ClientPredictionState.exactCoverageSnapshot(nextLevel.dimension(), cx, cz, radius);
        }
    }

    Snapshot submissionSnapshot() {
        return currentRemovalRevision == ClientPredictionState.exactCoverageRemovalRevision() ? current : null;
    }

    void bind(ClientLevel nextLevel, Vec3 camera, int radius, int sampler, int bounds) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE8);
        if (texture == -1) texture = GL11.glGenTextures();
        PredictionGlState.bindTexture(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (current != null && uploadPending) {
                var bytes = MemoryUtil.memAlloc(current.columns().length);
                try {
                    bytes.put(current.columns()).flip();
                    try (var unpack = PredictionPixelUnpack.begin()) {
                        if (allocatedSize != current.size()) {
                            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, current.size(), current.size(),
                                    0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bytes);
                            allocatedSize = current.size();
                        } else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, current.size(), current.size(),
                                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, bytes);
                    }
                } finally { MemoryUtil.memFree(bytes); }
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            uploadPending = false;
        }
        GL20.glUniform1i(sampler, 8);
        if (current == null) GL20.glUniform3f(bounds, 0, 0, 0);
        else GL20.glUniform3f(bounds, (float) (current.originX() * 16.0 - camera.x),
                (float) (current.originZ() * 16.0 - camera.z), current.size());
    }

    void invalidate() { level = null; pending = null; current = null; requestedAt = 0; requestedRevision = -1; settled = false; uploadPending = false; }
}

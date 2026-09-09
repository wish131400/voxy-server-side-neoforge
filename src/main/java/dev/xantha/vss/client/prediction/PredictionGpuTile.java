package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

/**
 * Render-thread GPU cache for one prediction tile, The packed-quad LOD approach style: the
 * mesh lives in a texture buffer of packed 48-byte quads that the vertex
 * program expands from {@code gl_VertexID}, and the exact-coverage yield
 * is a tiny R8 cell mask.
 *
 * <p>Only a mesh change re-uploads the quad payload; coverage
 * flips — the old pipeline's dominant rebuild source — update a few hundred
 * mask bytes.  Camera movement touches nothing.</p>
 */
final class PredictionGpuTile implements AutoCloseable {
    /** Four unsigned words per texel: one packed quad spans three texels. */
    private static final int INTERNAL_RGBA32UI = 0x8D70;
    private static final int TEXTURE_BUFFER = 0x8C2A;

    private final PredictionTileManager.PredictionTileKey key;
    private long meshRevision = Long.MIN_VALUE;
    private PredictionPackedMesh packed;
    private boolean[] coverage;

    private int quadBuffer = -1;
    private int quadTexture = -1;
    private int yieldTexture = -1;

    PredictionGpuTile(PredictionTileManager.PredictionTileKey key) {
        this.key = key;
    }

    /**
     * Maps a cell index of the cellAxis grid onto the tile's margin-cropped
     * sample grid, which is one sample wider per axis.  Linearising the cell
     * index directly would read the wrong column for every row past the
     * first, silently smearing textures diagonally.
     */
    static int sampleIndexForCell(int cell, int cellAxis) {
        int x = cell % cellAxis;
        int z = cell / cellAxis;
        return z * (cellAxis + 1) + x;
    }

    /** Upload worker-prepared data only; the render thread never runs mesh packing. */
    boolean ensureMesh(PredictionTileManager.PredictionTile tile) {
        if (packed != null && meshRevision == tile.revision()) return false;
        PredictionPackedMesh next = tile.mesh().gpuPayload();
        if (next == null) return false;
        // The upload helper retires the previous GPU resources itself;
        // deleting after creation would free the freshly uploaded payload.
        if (next.quadCount() > 0) {
            ensureQuadBuffer(next.quads());
        }
        packed = next;
        meshRevision = tile.revision();
        // The mesh changed shape; force the coverage mask to re-upload even
        // if the boolean array happens to be equal to the previous one.
        coverage = null;
        return true;
    }

    void ensureSeams(PredictionPackedMesh next) {
        if (packed == next) return;
        if (packed == null || !Arrays.equals(packed.quads(), next.quads())) ensureQuadBuffer(next.quads());
        packed = next;
    }

    /** Uploads the exact-coverage cell mask when it changed. */
    void updateCoverage(boolean[] allowed) {
        if (packed == null || Arrays.equals(coverage, allowed)) {
            return;
        }
        int axis = packed.cellAxis();
        ByteBuffer pixels = ByteBuffer.allocateDirect(axis * axis)
                .order(ByteOrder.nativeOrder());
        for (int cell = 0; cell < axis * axis; cell++) {
            pixels.put((byte) (allowed[cell] ? 255 : 0));
        }
        pixels.flip();
        if (yieldTexture == -1) {
            yieldTexture = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(yieldTexture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                    GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                    GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                    GL12Compat.CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                    GL12Compat.CLAMP_TO_EDGE);
        } else {
            GlStateManager._bindTexture(yieldTexture);
        }
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, axis, axis, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
        GlStateManager._bindTexture(0);
        coverage = allowed.clone();
    }

    private void ensureQuadBuffer(int[] quads) {
        closeQuads();
        quadBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(TEXTURE_BUFFER, quadBuffer);
        // OpenGL reads the texture-buffer words in native byte order.  The
        // default ByteBuffer order is BIG_ENDIAN even on Windows, which
        // reverses every packed x/z/y word and turns normal quads into the
        // long green streaks seen in-world.
        ByteBuffer data = MemoryUtil.memAlloc(quads.length * 4)
                .order(ByteOrder.nativeOrder());
        writeQuadPayload(data, quads);
        data.flip();
        GL31.glBufferData(TEXTURE_BUFFER, data, GL31.GL_STATIC_DRAW);
        MemoryUtil.memFree(data);
        GL15.glBindBuffer(TEXTURE_BUFFER, 0);
        quadTexture = TextureUtil.generateTextureId();
        // A texture id's target is fixed by its FIRST bind.  Attaching the
        // payload through the GL_TEXTURE_BUFFER target here is what makes
        // every later bindQuad() legal — binding these ids through the 2D
        // target first made every GL_TEXTURE_BUFFER bind fail with
        // INVALID_OPERATION and the whole terrain pass draw nothing.
        GL31.glBindTexture(TEXTURE_BUFFER, quadTexture);
        GL31.glTexBuffer(TEXTURE_BUFFER, INTERNAL_RGBA32UI, quadBuffer);
        GL31.glBindTexture(TEXTURE_BUFFER, 0);
    }

    static void writeQuadPayload(ByteBuffer data, int[] quads) {
        data.order(ByteOrder.nativeOrder());
        data.asIntBuffer().put(quads);
        data.position(data.position() + quads.length * Integer.BYTES);
    }

    void bindQuad(int unit) {
        bind(unit, quadTexture, TEXTURE_BUFFER);
    }

    void bindYield(int unit) {
        bind(unit, yieldTexture, GL11.GL_TEXTURE_2D);
    }

    private static void bind(int unit, int texture, int target) {
        if (texture == -1) {
            return;
        }
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        if (target == TEXTURE_BUFFER) {
            GL31.glBindTexture(TEXTURE_BUFFER, texture);
        } else {
            GlStateManager._bindTexture(texture);
        }
    }

    PredictionPackedMesh packed() {
        return packed;
    }

    int quadCount() {
        return packed == null ? 0 : packed.quadCount();
    }

    boolean drawable() {
        return packed != null && packed.quadCount() > 0;
    }

    @Override
    public void close() {
        closeQuads();
        if (yieldTexture != -1) {
            TextureUtil.releaseTextureId(yieldTexture);
            yieldTexture = -1;
        }
        packed = null;
        coverage = null;
        meshRevision = Long.MIN_VALUE;
    }

    private void closeQuads() {
        if (quadTexture != -1) {
            TextureUtil.releaseTextureId(quadTexture);
            quadTexture = -1;
        }
        if (quadBuffer != -1) {
            GL15.glDeleteBuffers(quadBuffer);
            quadBuffer = -1;
        }
    }

    private static final class GL12Compat {
        private static final int CLAMP_TO_EDGE = 0x812F;
    }
}

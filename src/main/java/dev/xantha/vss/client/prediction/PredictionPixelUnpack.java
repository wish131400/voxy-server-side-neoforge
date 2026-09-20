package dev.xantha.vss.client.prediction;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/** Render-thread scope for tightly packed CPU uploads, including nested helpers. */
final class PredictionPixelUnpack implements AutoCloseable {
    private static final int[] PARAMETERS = {
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
            GL12.GL_UNPACK_IMAGE_HEIGHT, GL11.GL_UNPACK_SKIP_PIXELS,
            GL11.GL_UNPACK_SKIP_ROWS, GL12.GL_UNPACK_SKIP_IMAGES,
            GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST
    };
    private static final PredictionPixelUnpack NESTED = new PredictionPixelUnpack(null, 0);
    private static boolean active;
    private final int[] previous;
    private final int buffer;

    private PredictionPixelUnpack(int[] previous, int buffer) {
        this.previous = previous;
        this.buffer = buffer;
    }

    static PredictionPixelUnpack begin() {
        if (active) return NESTED;
        int[] previous = new int[PARAMETERS.length];
        int buffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        for (int i = 0; i < PARAMETERS.length; i++) previous[i] = GL11.glGetInteger(PARAMETERS[i]);
        // A bound PBO interprets a CPU pointer as an offset; row/skip state can
        // read beyond a direct buffer even when its Java capacity is correct.
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int i = 0; i < PARAMETERS.length; i++) GL11.glPixelStorei(PARAMETERS[i], i == 0 ? 1 : 0);
        active = true;
        return new PredictionPixelUnpack(previous, buffer);
    }

    @Override public void close() {
        if (previous == null) return;
        for (int i = 0; i < PARAMETERS.length; i++) GL11.glPixelStorei(PARAMETERS[i], previous[i]);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        active = false;
    }
}

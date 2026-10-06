package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Immutable vertical runs. Air is implicit; each run stores bottom, top, block and fluid kind. */
public final class PredictionColumnVolume {
    static final int MAX_RUNS = 4096;
    private static final int[] EMPTY_RUNS = new int[0];
    private static final PredictionColumnVolume EMPTY = new PredictionColumnVolume(EMPTY_RUNS, true);
    private static final ThreadLocal<RunBuffer> BUFFERS = ThreadLocal.withInitial(RunBuffer::new);
    private final int[] runs;

    private static final class RunBuffer {
        int[] values = new int[64];
        boolean inUse;
    }

    PredictionColumnVolume(int[] runs) {
        validate(runs);
        this.runs = runs.clone();
    }

    /** Takes ownership of an array built by the sampler; the array is never mutated afterwards. */
    private PredictionColumnVolume(int[] runs, boolean owned) {
        validate(runs);
        this.runs = owned ? runs : runs.clone();
    }

    private static void validate(int[] runs) {
        if (runs == null || runs.length % 4 != 0 || runs.length / 4 > MAX_RUNS)
            throw new IllegalArgumentException("Invalid column runs");
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < runs.length; i += 4) {
            if (runs[i] < previous || runs[i + 1] <= runs[i] || runs[i + 2] < 0
                    || runs[i + 3] < 0 || runs[i + 3] > 2) throw new IllegalArgumentException("Invalid column interval");
            previous = runs[i + 1];
        }
    }

    static PredictionColumnVolume sample(int minY, int height, IntUnaryOperator blockAt, IntUnaryOperator fluidOf) {
        if (height < 1 || height > MAX_RUNS || (long) minY + height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Unsupported column height");
        RunBuffer buffer = BUFFERS.get();
        // Block queries may reenter sampling. Nested calls must not overwrite
        // the outer scan, and no scratch array may escape into a column.
        if (buffer.inUse) buffer = new RunBuffer();
        buffer.inUse = true;
        try {
            int[] result = buffer.values;
            int size = 0, start = minY, previous = -1;
            for (int offset = 0; offset <= height; offset++) {
                int y = minY + offset;
                if ((y & 15) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                int block = offset == height ? -1 : blockAt.applyAsInt(y);
                if (block == previous) continue;
                if (previous >= 0) {
                    if (size + 4 > result.length) {
                        int next = Math.min(MAX_RUNS * 4, Math.max(size + 4, result.length * 2));
                        buffer.values = result = Arrays.copyOf(result, next);
                    }
                    result[size++] = start; result[size++] = y;
                    result[size++] = previous; result[size++] = fluidOf.applyAsInt(previous);
                }
                start = y; previous = block;
            }
            return size == 0 ? EMPTY : new PredictionColumnVolume(Arrays.copyOf(result, size), true);
        } finally {
            buffer.inUse = false;
        }
    }

    int size() { return runs.length / 4; }
    int bottom(int i) { return runs[i * 4]; }
    int top(int i) { return runs[i * 4 + 1]; }
    int block(int i) { return runs[i * 4 + 2]; }
    int fluid(int i) { return runs[i * 4 + 3]; }
    int minY() { return size() == 0 ? 0 : bottom(0); }
    int maxY() { return size() == 0 ? 0 : top(size() - 1); }
    long bytes() { return 40L + runs.length * 4L; }

    int blockAt(int y) {
        for (int i = 0; i < size(); i++) {
            if (bottom(i) > y) break;
            if (top(i) > y) return block(i);
        }
        return ClientColumnSample.NO_BLOCK;
    }

    boolean occupied(int y, boolean solidOnly) {
        for (int i = 0; i < size(); i++) {
            if (bottom(i) > y) return false;
            if (top(i) > y && (!solidOnly || fluid(i) == 0)) return true;
        }
        return false;
    }

    ClientColumnSample asSample() {
        int solidTop = minY(), block = ClientColumnSample.NO_BLOCK;
        for (int i = 0; i < size(); i++) if (fluid(i) == 0) { solidTop = top(i); block = block(i); }
        return new ClientColumnSample(solidTop, solidTop, -1, block, 0, 0, 0, 0, 0,
                block == ClientColumnSample.NO_BLOCK ? ClientColumnSample.FLAG_NO_SURFACE : 0,
                0, block, block, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, this);
    }

    @Override public boolean equals(Object other) {
        return other instanceof PredictionColumnVolume v && Arrays.equals(runs, v.runs);
    }
    @Override public int hashCode() { return Arrays.hashCode(runs); }
}

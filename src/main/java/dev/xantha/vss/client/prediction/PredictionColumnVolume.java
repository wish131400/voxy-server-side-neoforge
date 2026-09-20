package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Immutable vertical runs. Air is implicit; each run stores bottom, top, block and fluid kind. */
public final class PredictionColumnVolume {
    static final int MAX_RUNS = 4096;
    private final int[] runs;

    PredictionColumnVolume(int[] runs) {
        if (runs.length % 4 != 0 || runs.length / 4 > MAX_RUNS) throw new IllegalArgumentException("Invalid column runs");
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < runs.length; i += 4) {
            if (runs[i] < previous || runs[i + 1] <= runs[i] || runs[i + 2] < 0
                    || runs[i + 3] < 0 || runs[i + 3] > 2) throw new IllegalArgumentException("Invalid column interval");
            previous = runs[i + 1];
        }
        this.runs = runs.clone();
    }

    static PredictionColumnVolume sample(int minY, int height, IntUnaryOperator blockAt, IntUnaryOperator fluidOf) {
        if (height < 1 || height > MAX_RUNS) throw new IllegalArgumentException("Unsupported column height");
        int[] result = new int[height * 4];
        int size = 0, start = minY, previous = -1;
        for (int y = minY; y <= minY + height; y++) {
            if ((y & 15) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int block = y == minY + height ? -1 : blockAt.applyAsInt(y);
            if (block == previous) continue;
            if (previous >= 0) {
                result[size++] = start; result[size++] = y;
                result[size++] = previous; result[size++] = fluidOf.applyAsInt(previous);
            }
            start = y; previous = block;
        }
        return new PredictionColumnVolume(Arrays.copyOf(result, size));
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

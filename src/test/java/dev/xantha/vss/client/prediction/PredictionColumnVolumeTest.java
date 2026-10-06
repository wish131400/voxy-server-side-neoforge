package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionColumnVolumeTest {
    @Test void samplingPreservesEveryBlockAndFluidAndQueriesEachHeightOnce() {
        var random = new Random(731);
        for (int trial = 0; trial < 48; trial++) {
            int min = -128 + trial, height = 32 + random.nextInt(480);
            int[] blocks = new int[height];
            for (int i = 0; i < height; i++) blocks[i] = random.nextInt(4) - 1;
            var calls = new AtomicInteger();
            var volume = PredictionColumnVolume.sample(min, height,
                    y -> { calls.incrementAndGet(); return blocks[y - min]; }, id -> id);
            assertEquals(height, calls.get());
            int run = 0;
            for (int y = min; y < min + height; y++) {
                assertEquals(blocks[y - min] < 0 ? ClientColumnSample.NO_BLOCK : blocks[y - min], volume.blockAt(y));
                assertEquals(blocks[y - min] >= 0, volume.occupied(y, false));
                assertEquals(blocks[y - min] == 0, volume.occupied(y, true));
                if (blocks[y - min] >= 0 && (y == min || blocks[y - min - 1] != blocks[y - min])) {
                    assertEquals(y, volume.bottom(run));
                    assertEquals(blocks[y - min], volume.block(run));
                    assertEquals(blocks[y - min], volume.fluid(run++));
                }
            }
            assertEquals(run, volume.size());
            assertEquals(ClientColumnSample.NO_BLOCK, volume.blockAt(min - 1));
            assertEquals(ClientColumnSample.NO_BLOCK, volume.blockAt(min + height));
        }
    }

    @Test void denseMaximumHeightAndEmptyColumnsDoNotAliasLaterScans() {
        int height = PredictionColumnVolume.MAX_RUNS;
        var dense = PredictionColumnVolume.sample(-64, height, y -> (y + 64) & 1, id -> id);
        assertEquals(height, dense.size());
        var empty = PredictionColumnVolume.sample(-64, height, y -> -1, id -> fail("air has no fluid query"));
        assertEquals(0, empty.size());
        for (int trial = 0; trial < 10; trial++)
            PredictionColumnVolume.sample(0, height, y -> 2, id -> 2);
        for (int i = 0; i < height; i++) {
            assertEquals(i - 64, dense.bottom(i));
            assertEquals(i - 63, dense.top(i));
            assertEquals(i & 1, dense.block(i));
            assertEquals(i & 1, dense.fluid(i));
        }
    }

    @Test void callbacksMayReenterAndFailuresDoNotPoisonLaterScans() {
        var nested = new ArrayList<PredictionColumnVolume>();
        var outer = PredictionColumnVolume.sample(-64, 128, y -> {
            if ((y & 7) == 0) nested.add(PredictionColumnVolume.sample(0, 128, z -> z & 1, id -> id));
            return y < 0 ? 0 : -1;
        }, id -> {
            nested.add(PredictionColumnVolume.sample(40, 128, y -> 2, ignored -> 2));
            return 0;
        });
        assertEquals(new PredictionColumnVolume(new int[]{-64, 0, 0, 0}), outer);
        assertEquals(128, nested.get(0).size());
        assertThrows(IllegalStateException.class, () -> PredictionColumnVolume.sample(0, 128, y -> {
            if (y == 80) throw new IllegalStateException("query failed");
            return y & 1;
        }, id -> id));
        assertEquals(new PredictionColumnVolume(new int[]{-64, 64, 1, 1}),
                PredictionColumnVolume.sample(-64, 128, y -> 1, id -> 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> PredictionColumnVolume.sample(0, 128, y -> 0, id -> 0));
        } finally { Thread.interrupted(); }
        assertEquals(1, PredictionColumnVolume.sample(0, 128, y -> 0, id -> 0).size());
    }

    @Test void concurrentScansHaveIndependentScratchAndDetachedResults() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<PredictionColumnVolume>>();
            for (int thread = 0; thread < 4; thread++) {
                int block = thread;
                tasks.add(pool.submit(() -> {
                    var retained = PredictionColumnVolume.sample(-64, 384, y -> block, id -> 0);
                    for (int scan = 0; scan < 200; scan++)
                        PredictionColumnVolume.sample(0, 384, y -> y & 1, id -> id);
                    return retained;
                }));
            }
            for (int i = 0; i < tasks.size(); i++)
                assertEquals(new PredictionColumnVolume(new int[]{-64, 320, i, 0}), tasks.get(i).get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void constructorCopiesExternalRunsAndHeightBoundsDoNotOverflow() {
        int[] data = {-64, 64, 0, 0};
        var column = new PredictionColumnVolume(data);
        Arrays.fill(data, 9);
        assertEquals(0, column.block(0));
        assertThrows(IllegalArgumentException.class,
                () -> PredictionColumnVolume.sample(Integer.MAX_VALUE - 3, 4, y -> 0, id -> 0));
        var edge = PredictionColumnVolume.sample(Integer.MAX_VALUE - 4, 4, y -> 0, id -> 0);
        assertEquals(Integer.MAX_VALUE, edge.top(0));
    }
}

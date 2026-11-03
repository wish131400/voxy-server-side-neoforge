package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionClimateCacheTest {
    @Test void expressionIdentityPreservesSignedZeroAndArithmeticTree() {
        assertFalse(PredictionClimateIdentity.same(com.google.gson.JsonParser.parseString("-0.0"),com.google.gson.JsonParser.parseString("0.0")));
        assertTrue(PredictionClimateIdentity.same(com.google.gson.JsonParser.parseString("1"),com.google.gson.JsonParser.parseString("1.0")));
        assertFalse(PredictionClimateIdentity.same(com.google.gson.JsonParser.parseString("[1,2]"),com.google.gson.JsonParser.parseString("[2,1]")));
    }
    @Test void retainsRawDoubleBitsAcrossUnrelatedQueriesAndReleasesRowsOnClose() {
        var cache = new PredictionClimateCache(); int lane = cache.reserve();
        var calls = new AtomicInteger();
        double value = Double.longBitsToDouble(0x8000000000000000L);
        for (int round = 0; round < 4; round++) {
            assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(cache.get(lane, -17, 31,
                    () -> { calls.incrementAndGet(); return value; })));
            cache.get(lane, 24, -8, () -> 0.25);
        }
        assertEquals(1, calls.get());
        cache.close();
        assertTrue(cache.diagnostics().contains("horizontalBytes=0"));
        assertEquals(0.75, cache.get(lane, -17, 31, () -> 0.75));
    }
    @Test void capacityCollisionsNeverReturnAnotherCoordinatesValue() {
        try (var cache = new PredictionClimateCache()) {
            int lane = cache.reserve();
            for (int round = 0; round < 2; round++) for (int x = -20000; x < 20000; x++) {
                int coordinate = x;
                assertEquals(coordinate * 0.125, cache.get(lane, coordinate, -coordinate, () -> coordinate * 0.125));
            }
        }
    }
    @Test void manyWorkersStayWithinWorldBudgetAndCloseReleasesOtherThreadsRows() throws Exception {
        var cache = new PredictionClimateCache();
        for (int i = 0; i < 16; i++) cache.reserve();
        var executor = Executors.newFixedThreadPool(12);
        try {
            var jobs = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 12; i++) { int worker = i;
                jobs.add(() -> {
                    for (int x = 0; x < 128; x++) {
                        int coordinate = x;
                        assertEquals(worker + coordinate * 0.125,
                                cache.get(worker, x, -x, () -> worker + coordinate * 0.125));
                    }
                    return null;
                });
            }
            for (var future : executor.invokeAll(jobs)) future.get();
            String info = cache.diagnostics();
            long bytes = Long.parseLong(info.split("horizontalBytes=")[1].split(",")[0]);
            assertTrue(bytes > 0 && bytes <= (8L << 20), info);
            cache.close(); assertTrue(cache.diagnostics().contains("horizontalBytes=0"));
        } finally { executor.shutdownNow(); cache.close(); }
    }
}

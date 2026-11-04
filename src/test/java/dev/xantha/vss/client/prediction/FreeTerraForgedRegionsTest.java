package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;

class FreeTerraForgedRegionsTest {
    @Test void taskSharesChunksAcrossReadersAndNestedPhasesButReleasesThemOnExit() {
        var owner = new FreeTerraForgedRegions();
        var fieldReader = new FreeTerraForgedRegions();
        Object context = new Object(), chunk = new Object();
        long negativeChunk = (long)-3 << 32 | (-7 & 0xffffffffL);
        try (var task = owner.request(context)) {
            FreeTerraForgedRegions.put(context, negativeChunk, chunk);
            try (var phase = fieldReader.request(context)) {
                assertSame(chunk, FreeTerraForgedRegions.get(context, negativeChunk));
            }
            assertSame(chunk, FreeTerraForgedRegions.get(context, negativeChunk));
        }
        assertNull(FreeTerraForgedRegions.get(context, negativeChunk));
        try (var next = owner.request(context)) { assertNull(FreeTerraForgedRegions.get(context, negativeChunk)); }
    }

    @Test void contextsThreadsAndExceptionalExitCannotShareScratch() {
        var owner = new FreeTerraForgedRegions();
        Object a = new Object(), b = new Object(), chunk = new Object();
        try (var outer = owner.request(a)) {
            FreeTerraForgedRegions.put(a, 0, chunk);
            try (var nested = owner.request(b)) {
                assertNull(FreeTerraForgedRegions.get(b, 0));
                assertNull(FreeTerraForgedRegions.get(a, 0));
                FreeTerraForgedRegions.put(b, 0, new Object());
            }
            assertSame(chunk, FreeTerraForgedRegions.get(a, 0));
            assertTrue(CompletableFuture.supplyAsync(() -> FreeTerraForgedRegions.get(a, 0) == null).join());
        }
        assertThrows(IllegalStateException.class, () -> {
            try (var task = owner.request(a)) {
                FreeTerraForgedRegions.put(a, 0, chunk); throw new IllegalStateException();
            }
        });
        assertNull(FreeTerraForgedRegions.get(a, 0));
    }

    @Test void boundsRetainedChunksAndKeepsRecentlyUsedChunk() {
        var owner = new FreeTerraForgedRegions(); var context = new Object();
        try (var task = owner.request(context)) {
            for (int i=0; i<FreeTerraForgedRegions.CAPACITY; i++) FreeTerraForgedRegions.put(context, i, new Object());
            var first = FreeTerraForgedRegions.get(context, 0);
            FreeTerraForgedRegions.put(context, FreeTerraForgedRegions.CAPACITY, new Object());
            assertNull(FreeTerraForgedRegions.get(context, 1));
            assertSame(first, FreeTerraForgedRegions.get(context, 0));
            assertNotNull(FreeTerraForgedRegions.get(context, FreeTerraForgedRegions.CAPACITY));
        }
        assertTrue(owner.diagnostics().contains("evictions=1"));
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionColorCacheTest {
    @Test void unchangedResourcesSurviveReentryAndReloadButChangedMapsInvalidate() {
        byte[] grass = {1, 2, 3}, foliage = {4, 5};
        var reads = new AtomicInteger();
        var cache = new PredictionColorCache(() -> {
            reads.incrementAndGet();
            return PredictionColorCache.fingerprint(grass, foliage);
        });
        long initial = cache.fingerprint();
        for (int tile = 0; tile < 1000; tile++) assertEquals(initial, cache.fingerprint());
        assertEquals(1, reads.get(), "do not decode/hash resources for each tile");
        cache.invalidate();
        assertEquals(initial, cache.fingerprint(), "reload generation is not persistent identity");
        assertEquals(initial, new PredictionColorCache(() -> PredictionColorCache.fingerprint(grass, foliage)).fingerprint());
        foliage[0]++;
        cache.invalidate();
        long changed = cache.fingerprint();
        assertNotEquals(initial, changed);
        grass[0]++;
        cache.invalidate();
        assertNotEquals(changed, cache.fingerprint());
    }

    @Test void unavailableIdentityDisablesReuseWithoutRepeatedResourceIo() {
        var reads = new AtomicInteger();
        var cache = new PredictionColorCache(() -> reads.incrementAndGet() == 1 ? Long.MIN_VALUE : 123);
        assertEquals(Long.MIN_VALUE, cache.fingerprint());
        assertEquals(Long.MIN_VALUE, cache.fingerprint());
        assertEquals(1, reads.get());
        cache.invalidate();
        assertEquals(123, cache.fingerprint());
    }
}

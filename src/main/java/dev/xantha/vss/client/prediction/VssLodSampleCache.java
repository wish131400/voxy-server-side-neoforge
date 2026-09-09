package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Bounded JVM-side sample cache. Parent and child tiles share samples at their
 * borders, which avoids re-running the expensive density march on every LOD
 * rebuild while retaining deterministic results.
 */
public final class VssLodSampleCache {
    private static final int DEFAULT_CAPACITY = 131_072;
    private final int capacity;
    private final Map<Long, ClientColumnSample> entries;

    public VssLodSampleCache() {
        this(DEFAULT_CAPACITY);
    }

    public VssLodSampleCache(int capacity) {
        if (capacity < 256) {
            throw new IllegalArgumentException("sample cache capacity too small");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(capacity, 0.75F, true);
    }

    public synchronized ClientColumnSample get(long key) {
        return entries.get(key);
    }

    public ClientColumnSample getOrCompute(long key,
                                           LongFunction<ClientColumnSample> factory) {
        ClientColumnSample existing;
        synchronized (this) {
            existing = entries.get(key);
        }
        if (existing != null) {
            return existing;
        }
        // Density sampling is the expensive part; do not hold the cache lock
        // while a worker evaluates the NoiseRouter.
        ClientColumnSample created = factory.apply(key);
        if (created == null) {
            throw new IllegalArgumentException("sample factory returned null");
        }
        synchronized (this) {
            ClientColumnSample raced = entries.get(key);
            if (raced != null) {
                return raced;
            }
            entries.put(key, created);
            while (entries.size() > capacity) {
                entries.remove(entries.keySet().iterator().next());
            }
            return created;
        }
    }

    public synchronized void put(long key, ClientColumnSample sample) {
        if (sample == null) return;
        ClientColumnSample previous = entries.get(key);
        if (previous != null && previous.captured() && !sample.captured()) return;
        entries.put(key, sample);
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    public synchronized void remove(long key) {
        entries.remove(key);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }
}

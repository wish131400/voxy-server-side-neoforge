package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Pin immutable released chunk arrays only for one worker task, shared by its cell-field readers. */
final class FreeTerraForgedRegions {
    static final int CAPACITY = 128;
    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private final LongAdder requests = new LongAdder(), hits = new LongAdder(), loads = new LongAdder(), evictions = new LongAdder();

    ClientTerrainSampler.SamplingScope request(Object context) {
        var previous = CURRENT.get();
        if (previous != null && previous.context == context) return () -> {};
        var next = new Request(context, previous);
        CURRENT.set(next);
        return next;
    }

    static Object get(Object context, long key) {
        var request = CURRENT.get();
        if (request == null || request.context != context) return null;
        var value = request.chunks.getAndMoveToLast(key);
        if (value != null) request.hits++;
        return value;
    }

    static void put(Object context, long key, Object value) {
        var request = CURRENT.get();
        if (request == null || request.context != context) return;
        request.chunks.putAndMoveToLast(key, value); request.loads++;
        if (request.chunks.size() > CAPACITY) { request.chunks.removeFirst(); request.evictions++; }
    }

    String diagnostics() {
        return "requests=" + requests.sum() + ",hits=" + hits.sum() + ",loads=" + loads.sum()
                + ",evictions=" + evictions.sum() + ",requestChunkCapacity=" + CAPACITY;
    }

    private final class Request implements ClientTerrainSampler.SamplingScope {
        final Object context;
        final Request previous;
        final Long2ObjectLinkedOpenHashMap<Object> chunks = new Long2ObjectLinkedOpenHashMap<>(16);
        long hits, loads, evictions;
        Request(Object context, Request previous) { this.context = context; this.previous = previous; }
        @Override public void close() {
            chunks.clear();
            requests.increment(); FreeTerraForgedRegions.this.hits.add(hits);
            FreeTerraForgedRegions.this.loads.add(loads); FreeTerraForgedRegions.this.evictions.add(evictions);
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}

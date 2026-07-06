package dev.xantha.vss.networking.server.diagnostics;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerRequestStats {
    private final AtomicLong columnRequests = new AtomicLong();
    private final AtomicLong duplicateRequests = new AtomicLong();
    private final AtomicLong distanceRejectedRequests = new AtomicLong();
    private final AtomicLong upToDateResponses = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong diskReadsSubmitted = new AtomicLong();
    private final AtomicLong diskReadHits = new AtomicLong();
    private final AtomicLong diskReadMisses = new AtomicLong();
    private final AtomicLong diskReadFailures = new AtomicLong();

    public void recordColumnRequest() {
        columnRequests.incrementAndGet();
    }

    public void recordDuplicateRequest() {
        duplicateRequests.incrementAndGet();
    }

    public void recordDistanceRejectedRequest() {
        distanceRejectedRequests.incrementAndGet();
    }

    public void recordUpToDateResponse() {
        upToDateResponses.incrementAndGet();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordDiskReadSubmitted() {
        diskReadsSubmitted.incrementAndGet();
    }

    public void recordDiskReadHit() {
        diskReadHits.incrementAndGet();
    }

    public void recordDiskReadMiss() {
        diskReadMisses.incrementAndGet();
    }

    public void recordDiskReadFailure() {
        diskReadFailures.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                columnRequests.get(),
                duplicateRequests.get(),
                distanceRejectedRequests.get(),
                upToDateResponses.get(),
                cacheHits.get(),
                diskReadsSubmitted.get(),
                diskReadHits.get(),
                diskReadMisses.get(),
                diskReadFailures.get());
    }

    public record Snapshot(
            long columnRequests,
            long duplicateRequests,
            long distanceRejectedRequests,
            long upToDateResponses,
            long cacheHits,
            long diskReadsSubmitted,
            long diskReadHits,
            long diskReadMisses,
            long diskReadFailures) {
    }
}

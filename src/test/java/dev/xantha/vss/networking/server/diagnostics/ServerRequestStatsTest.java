package dev.xantha.vss.networking.server.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerRequestStatsTest {

    @Test
    void recordsEachCounterIndependently() {
        ServerRequestStats stats = new ServerRequestStats();

        stats.recordColumnRequest();
        stats.recordDuplicateRequest();
        stats.recordDistanceRejectedRequest();
        stats.recordUpToDateResponse();
        stats.recordCacheHit();
        stats.recordDiskReadSubmitted();
        stats.recordDiskReadHit();
        stats.recordDiskReadMiss();
        stats.recordDiskReadFailure();

        ServerRequestStats.Snapshot snapshot = stats.snapshot();
        assertEquals(1L, snapshot.columnRequests());
        assertEquals(1L, snapshot.duplicateRequests());
        assertEquals(1L, snapshot.distanceRejectedRequests());
        assertEquals(1L, snapshot.upToDateResponses());
        assertEquals(1L, snapshot.cacheHits());
        assertEquals(1L, snapshot.diskReadsSubmitted());
        assertEquals(1L, snapshot.diskReadHits());
        assertEquals(1L, snapshot.diskReadMisses());
        assertEquals(1L, snapshot.diskReadFailures());
    }

    @Test
    void snapshotDoesNotChangeAfterLaterRecords() {
        ServerRequestStats stats = new ServerRequestStats();
        ServerRequestStats.Snapshot before = stats.snapshot();

        stats.recordColumnRequest();

        assertEquals(0L, before.columnRequests());
        assertEquals(1L, stats.snapshot().columnRequests());
    }
}

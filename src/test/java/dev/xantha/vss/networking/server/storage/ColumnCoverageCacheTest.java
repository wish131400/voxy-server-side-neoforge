package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.api.column.ColumnCoverage;
import dev.xantha.vss.api.column.ColumnCoverageApi;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import java.util.concurrent.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColumnCoverageCacheTest {
    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }
    private VSSServerConfig config;
    private ColumnLodCache cache;
    private ColumnCoverage view;
    private final ChunkPos pos = new ChunkPos(-7, 12);

    @BeforeEach void start() {
        config = new VSSServerConfig();
        config.enabled = true;
        config.enableColumnCache = true;
        config.columnCacheMaxEntries = 4;
        cache = new ColumnLodCache(config);
        cache.openCoverage();
        view = ColumnCoverageApi.get().orElseThrow();
    }
    @AfterEach void stop() { cache.closeCoverage(); }

    @Test void fullColumnsOwnAllSectionsIncludingUnserializedAir() {
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        var snapshot = view.snapshot(Level.OVERWORLD, pos);
        assertTrue(snapshot.authoritative());
        assertTrue(snapshot.revision() > 0);
        assertEquals(ColumnCoverage.State.VSS_OWNED, snapshot.section(-4));
        assertEquals(ColumnCoverage.State.VSS_OWNED, snapshot.section(19));
        assertEquals(ColumnCoverage.State.UNKNOWN, view.section(Level.NETHER, pos, 0));
        assertEquals(0, view.revision(Level.OVERWORLD, new ChunkPos(12, -7)));
    }

    @Test void unknownNullIncompleteAndForeignResultsNeverGrantAuthority() {
        assertEquals(ColumnCoverage.State.UNKNOWN, view.section(Level.OVERWORLD, pos, 0));
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(null, pos));
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, null));
        var full = column(100, pos, (byte) 1);
        cache.put(Level.OVERWORLD, new EncodedColumnData(pos.x, pos.z, full.compression(), full.rawSize(),
                full.encodedBytes(), 100, full.schemaVersion(), false));
        assertEquals(0, view.revision(Level.OVERWORLD, pos));
        cache.put(Level.OVERWORLD, new EncodedColumnData(pos.x, pos.z, full.compression(), full.rawSize(),
                full.encodedBytes(), 100, full.schemaVersion(), false, new int[]{-4}, new int[]{1}, 0,
                new dev.xantha.vss.common.processing.ColumnCoverage("f".repeat(64))));
        assertFalse(view.authoritative(Level.OVERWORLD, pos));
        assertEquals(0, view.revision(Level.OVERWORLD, pos));
    }

    @Test void dataAndLightChangesWithTheSameSourceTimestampGetNewTokens() {
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        long first = view.revision(Level.OVERWORLD, pos);
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 2));
        long changed = view.revision(Level.OVERWORLD, pos);
        assertTrue(changed > first);
        cache.put(Level.OVERWORLD, column(99, pos, (byte) 3));
        assertEquals(changed, view.revision(Level.OVERWORLD, pos), "older results must not replace current coverage");
    }

    @Test void dirtyInvalidationAndWarmRestorationRetractAndRenewCoverage() {
        var column = column(100, pos, (byte) 1);
        cache.put(Level.OVERWORLD, column);
        long old = view.revision(Level.OVERWORLD, pos);
        cache.invalidateOlderThan(Level.OVERWORLD, pos.x, pos.z, 100);
        assertEquals(old, view.revision(Level.OVERWORLD, pos));
        cache.invalidateOlderThan(Level.OVERWORLD, pos.x, pos.z, 101);
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        cache.putPreloaded(Level.OVERWORLD, column.withColumnStamp(101));
        assertTrue(view.revision(Level.OVERWORLD, pos) > old);
        long warm = view.revision(Level.OVERWORLD, pos);
        cache.get(Level.OVERWORLD, pos.x, pos.z);
        assertEquals(warm, view.revision(Level.OVERWORLD, pos), "reading/promoting a cache hit does not republish data");
        cache.invalidate(Level.OVERWORLD, pos.x, pos.z);
        assertEquals(0, view.revision(Level.OVERWORLD, pos));
    }

    @Test void lruByteAndPreloadEvictionsCannotLeaveAnOwnershipClaim() {
        config.columnCacheMaxEntries = 1;
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        var other = new ChunkPos(4, 5);
        cache.put(Level.OVERWORLD, column(101, other, (byte) 2));
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        config.preloadCacheMaxPercent = 0;
        cache.putPreloaded(Level.OVERWORLD, column(102, pos, (byte) 3));
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        assertTrue(view.authoritative(Level.OVERWORLD, other));
        cache.clear();
        assertEquals(0, view.revision(Level.OVERWORLD, other));
        config.columnCacheMaxBytes = 1;
        cache.put(Level.OVERWORLD, column(103, pos, (byte) 4));
        assertEquals(0, view.revision(Level.OVERWORLD, pos));
    }

    @Test void disablingTheCacheOrServerCannotGrantWritePermissionToOthers() {
        config.enableColumnCache = false;
        cache.refreshCoverageConfig();
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        assertEquals(ColumnCoverage.State.UNKNOWN, view.section(Level.OVERWORLD, pos, 0));
        config.enabled = false;
        cache.refreshCoverageConfig();
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        assertFalse(view.authoritative(Level.OVERWORLD, pos));
    }

    @Test void byteBudgetEvictionAndDisableReenableCannotReuseOldClaims() {
        config.columnCacheMaxBytes = 3;
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        long old = view.revision(Level.OVERWORLD, pos);
        var other = new ChunkPos(1, 2);
        cache.put(Level.OVERWORLD, column(101, other, (byte) 2));
        assertEquals(0, view.revision(Level.OVERWORLD, pos));
        config.enabled = false;
        cache.refreshCoverageConfig();
        config.enabled = true;
        cache.refreshCoverageConfig();
        assertEquals(0, view.revision(Level.OVERWORLD, other));
        cache.put(Level.OVERWORLD, column(102, pos, (byte) 1));
        assertTrue(view.revision(Level.OVERWORLD, pos) > old);
    }

    @Test void shutdownAndRestartInvalidateRetainedHandlesAndDoNotRecycleVersions() {
        var column = column(100, pos, (byte) 1);
        cache.put(Level.OVERWORLD, column);
        long first = view.revision(Level.OVERWORLD, pos);
        cache.closeCoverage();
        assertTrue(ColumnCoverageApi.get().isEmpty());
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        cache.clear();
        cache.openCoverage();
        cache.put(Level.OVERWORLD, column);
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, pos));
        assertTrue(ColumnCoverageApi.get().orElseThrow().revision(Level.OVERWORLD, pos) > first);
    }

    @Test void workersCanReadCoherentSnapshotsWhileTheCacheMutationLockIsHeld() throws Exception {
        cache.put(Level.OVERWORLD, column(100, pos, (byte) 1));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            synchronized (cache) {
                var result = worker.submit(() -> view.snapshot(Level.OVERWORLD, pos)).get(5, TimeUnit.SECONDS);
                assertTrue(result.authoritative());
                assertEquals(ColumnCoverage.State.VSS_OWNED, result.section(-4));
                assertTrue(result.revision() > 0);
            }
            Future<?> reads = worker.submit(() -> {
                for (int i = 0; i < 2000; i++) {
                    var result = view.snapshot(Level.OVERWORLD, pos);
                    if (result.revision() == 0) assertEquals(ColumnCoverage.Snapshot.UNKNOWN, result);
                    else {
                        assertTrue(result.authoritative());
                        assertEquals(ColumnCoverage.State.VSS_OWNED, result.section(0));
                    }
                }
            });
            for (int i = 0; i < 100; i++) {
                cache.invalidate(Level.OVERWORLD, pos.x, pos.z);
                cache.put(Level.OVERWORLD, column(101 + i, pos, (byte) i));
            }
            reads.get(5, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
        assertEquals("", ColumnFilters.snapshot().cacheKey());
    }

    private static EncodedColumnData column(long stamp, ChunkPos pos, byte contents) {
        byte[] bytes = {contents, 0};
        return new EncodedColumnData(pos.x, pos.z, LodByteCompression.METHOD_NONE,
                bytes.length, bytes, stamp, EncodedColumnData.SCHEMA_VERSION, true);
    }
}

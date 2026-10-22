package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.api.column.ColumnCoverage;
import dev.xantha.vss.api.column.ColumnCoverageApi;
import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.api.column.SectionDecision;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ColumnCoverageFilteredTest {
    private static final AtomicInteger calls = new AtomicInteger();
    private final VSSServerConfig config = new VSSServerConfig();
    private ColumnLodCache cache;
    private ColumnCoverage view;
    @TempDir Path world;

    @BeforeAll static void register() {
        ColumnFilterFixtures.biomes();
        ColumnFilters.register("test:coverage", 1, column -> {
            calls.incrementAndGet();
            if (column.chunkX() == 3) throw new IllegalStateException("provider failed");
            if (column.chunkX() == 1) return ColumnDecision.updateAll();
            return new ColumnDecision(SectionDecision.UNTOUCHED, column.chunkX() == 2 ? Map.of()
                    : Map.of(-1, SectionDecision.UPDATE, 0, SectionDecision.CLEAR));
        });
    }
    @BeforeEach void start() {
        cache = new ColumnLodCache(config);
        cache.openCoverage();
        view = ColumnCoverageApi.get().orElseThrow();
    }
    @AfterEach void stop() { cache.closeCoverage(); }

    @Test void updateAndClearOwnTheirSectionsButOmissionsDoNotAuthorizeOthers() throws Exception {
        cache.put(Level.OVERWORLD, filtered(0));
        var snapshot = view.snapshot(Level.OVERWORLD, new ChunkPos(0, 7));
        assertEquals(ColumnCoverage.State.VSS_OWNED, snapshot.section(-1));
        assertEquals(ColumnCoverage.State.VSS_OWNED, snapshot.section(0), "explicit air is still a VSS write");
        assertEquals(ColumnCoverage.State.VSS_UNTOUCHED, snapshot.section(1));
        assertEquals(ColumnCoverage.State.VSS_UNTOUCHED, snapshot.section(-2));
        assertFalse(snapshot.authoritative());
        assertTrue(snapshot.revision() > 0);
    }

    @Test void allUpdateAndAllUntouchedPoliciesAreStillScopedColumns() throws Exception {
        cache.put(Level.OVERWORLD, filtered(1));
        cache.put(Level.OVERWORLD, filtered(2));
        assertFalse(view.authoritative(Level.OVERWORLD, new ChunkPos(1, 7)));
        assertFalse(view.authoritative(Level.OVERWORLD, new ChunkPos(2, 7)));
        assertEquals(ColumnCoverage.State.VSS_OWNED, view.section(Level.OVERWORLD, new ChunkPos(1, 7), -2));
        assertEquals(ColumnCoverage.State.VSS_UNTOUCHED, view.section(Level.OVERWORLD, new ChunkPos(2, 7), 0));
        assertTrue(view.revision(Level.OVERWORLD, new ChunkPos(2, 7)) > 0);
    }

    @Test void diskCacheRestoresTheExactScopeWithoutReexecutingTheFilter() throws Exception {
        var data = filtered(0);
        int filteredCalls = calls.get();
        var store = new PersistentColumnLodStore(config);
        var server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        assertTrue(store.writeConfirmed(server, Level.OVERWORLD, data));
        var restored = new PersistentColumnLodStore(config).read(server, Level.OVERWORLD, 0, 7, 0).columnData();
        cache.put(Level.OVERWORLD, restored);
        var pos = new ChunkPos(0, 7);
        long revision = view.revision(Level.OVERWORLD, pos);
        for (int i = 0; i < 100; i++) {
            assertEquals(ColumnCoverage.State.VSS_OWNED, view.section(Level.OVERWORLD, pos, 0));
            assertEquals(ColumnCoverage.State.VSS_UNTOUCHED, view.section(Level.OVERWORLD, pos, 1));
            assertEquals(revision, view.revision(Level.OVERWORLD, pos));
        }
        assertEquals(filteredCalls, calls.get());
    }

    @Test void failedFilteringCannotPublishAPartialOwnershipResult() {
        assertThrows(IllegalStateException.class, () -> cache.put(Level.OVERWORLD, filtered(3)));
        assertEquals(ColumnCoverage.Snapshot.UNKNOWN, view.snapshot(Level.OVERWORLD, new ChunkPos(3, 7)));
    }

    private static EncodedColumnData filtered(int cx) throws Exception {
        return EncodedColumnData.encode(ColumnFilterPipeline.apply(ColumnFilterFixtures.context(),
                ColumnFilterFixtures.source(cx)), 100);
    }
}

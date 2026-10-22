package dev.xantha.vss.networking.server.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.xantha.vss.api.column.ColumnDecision;
import dev.xantha.vss.api.column.ColumnFilters;
import dev.xantha.vss.common.processing.ColumnCoverage;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColumnFilterCacheTest {
    private static final AtomicInteger calls = new AtomicInteger();
    @TempDir Path world;
    @BeforeAll static void register() {
        ColumnFilterFixtures.biomes();
        ColumnFilters.register("test:cache", 1, c -> {
            calls.incrementAndGet();
            assertEquals(-16, c.section(-1).maxBlockY());
            assertTrue(c.hasTopSolidY());
            assertEquals(16, c.topSolidY(0, 0));
            assertEquals(Integer.MIN_VALUE, c.topSolidY(15, 15));
            return ColumnDecision.updateAll();
        });
    }

    private MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        return server;
    }

    private EncodedColumnData column(long stamp) throws Exception {
        return EncodedColumnData.encode(ColumnFilterPipeline.apply(ColumnFilterFixtures.context(), ColumnFilterFixtures.source(0)), stamp);
    }

    @Test void scopedMemoryAndDiskCachesPreserveProvenanceAndCompleteness() throws Exception {
        var config = new VSSServerConfig(); var column = column(1000L);
        int afterFiltering = calls.get();
        var memory = new ColumnLodCache(config); memory.put(Level.OVERWORLD, column);
        var cached = memory.get(Level.OVERWORLD, 0, 7).columnData();
        assertEquals(column.coverage(), cached.coverage()); assertFalse(cached.completeColumn());
        var store = new PersistentColumnLodStore(config); var server = server();
        assertTrue(store.writeConfirmed(server, Level.OVERWORLD, column));
        store.flushDirtyIndexesBlocking(server);
        var reloaded = new PersistentColumnLodStore(config).read(server, Level.OVERWORLD, 0, 7, 0L).columnData();
        assertFalse(reloaded.completeColumn()); assertTrue(reloaded.isDeliverable());
        assertEquals(column.coverage(), reloaded.coverage()); assertArrayEquals(column.encodedBytes(), reloaded.encodedBytes());
        assertTrue(Files.isDirectory(world.resolve("data/vss-column-cache-filtered").resolve(column.coverage().filterKey())));
        assertFalse(Files.exists(world.resolve("data/vss-column-cache")));
        assertEquals(afterFiltering, calls.get(), "Cache hits must reuse the filtered bytes without rerunning the height scan/provider");
    }

    @Test void fullAndWrongPolicyDataCannotPolluteScopedCaches() throws Exception {
        var config = new VSSServerConfig(); var memory = new ColumnLodCache(config); var store = new PersistentColumnLodStore(config);
        var full = EncodedColumnData.encode(ColumnFilterFixtures.source(0), 1000L);
        memory.put(Level.OVERWORLD, full); assertNull(memory.get(Level.OVERWORLD, 0, 7));
        assertFalse(store.writeConfirmed(null, Level.OVERWORLD, full));
        var column = column(1000L);
        var foreign = new EncodedColumnData(0, 7, column.compression(), column.rawSize(), column.encodedBytes(), 1000L,
                column.schemaVersion(), false, column.sectionYs(), column.sectionLengths(), column.encodedCrc32c(), new ColumnCoverage("f".repeat(64)));
        memory.put(Level.OVERWORLD, foreign); assertNull(memory.get(Level.OVERWORLD, 0, 7));
        assertFalse(store.writeConfirmed(null, Level.OVERWORLD, foreign));
    }

    @Test void corruptBodyAndFullFlagInsideScopedNamespaceAreRejected() throws Exception {
        var store = new PersistentColumnLodStore(new VSSServerConfig()); var server = server();
        assertTrue(store.writeConfirmed(server, Level.OVERWORLD, column(1000L)));
        Path path = activePath(); byte[] bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
        assertNull(new PersistentColumnLodStore(new VSSServerConfig()).read(server, Level.OVERWORLD, 0, 7, 0L));
        assertTrue(store.writeConfirmed(server, Level.OVERWORLD, column(1000L)));
        bytes = Files.readAllBytes(path); bytes[24] = 1; Files.write(path, bytes);
        assertNull(new PersistentColumnLodStore(new VSSServerConfig()).read(server, Level.OVERWORLD, 0, 7, 0L));
    }

    @Test void dirtyColumnInvalidatesInactiveFullAndOldPolicyEntriesEvenWhenActiveDataIsFresh() throws Exception {
        var store = new PersistentColumnLodStore(new VSSServerConfig()); var server = server();
        assertTrue(store.writeConfirmed(server, Level.OVERWORLD, column(1000L)));
        Path relative = Path.of("minecraft_overworld/0_0/0_7.vcl");
        Path full = world.resolve("data/vss-column-cache").resolve(relative);
        Path oldPolicy = world.resolve("data/vss-column-cache-filtered").resolve("a".repeat(64)).resolve(relative);
        Path freshPolicy = world.resolve("data/vss-column-cache-filtered").resolve("b".repeat(64)).resolve(relative);
        writeOldCopy(full, 100L, true); writeOldCopy(oldPolicy, 200L, false); writeOldCopy(freshPolicy, 1500L, false);
        Files.write(full.getParent().resolve("index.vci"), new byte[] {1});
        store.invalidateOlderThan(server, Level.OVERWORLD, 0, 7, 500L);
        assertTrue(Files.isRegularFile(activePath())); assertFalse(Files.exists(full)); assertFalse(Files.exists(oldPolicy));
        assertFalse(Files.exists(full.getParent().resolve("index.vci"))); assertTrue(Files.isRegularFile(freshPolicy));
        store.invalidate(server, Level.OVERWORLD, 0, 7);
        assertFalse(Files.exists(activePath())); assertFalse(Files.exists(freshPolicy));
    }

    private Path activePath() {
        return world.resolve("data/vss-column-cache-filtered").resolve(ColumnFilters.snapshot().cacheKey())
                .resolve("minecraft_overworld/0_0/0_7.vcl");
    }
    private void writeOldCopy(Path path, long stamp, boolean full) throws Exception {
        byte[] bytes = Files.readAllBytes(activePath()); ByteBuffer.wrap(bytes).putLong(16, stamp); bytes[24] = (byte) (full ? 1 : 0);
        Files.createDirectories(path.getParent()); Files.write(path, bytes);
    }
}

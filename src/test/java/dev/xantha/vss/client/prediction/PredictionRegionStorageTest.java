package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

class PredictionRegionStorageTest {
    @TempDir Path root;

    @Test void newForegroundDemandCancelsCompactionWithoutLosingData() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        byte[] bytes = payload(53, 600_000);
        try (var store = new PredictionRegionStorage(root)) {
            for (int i = 0; i < 10; i++) write(store, key, bytes);
            long before = Files.size(store.path(key));
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            store.compactOne(() -> checks.incrementAndGet() < 4);
            assertEquals(before, Files.size(store.path(key)));
            assertArrayEquals(bytes, store.read(key).bytes());
            assertTrue(store.hasMaintenance(), "aborted work must remain eligible after the quiet period");
            try (var files = Files.list(store.path(key).getParent())) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("region-compact-")));
            }
            store.compactOne();
            assertTrue(Files.size(store.path(key)) < before / 4);
            assertArrayEquals(bytes, store.read(key).bytes());
        }
    }

    @Test void pathReuseIsBoundedAndNeverCachesFilePresence() throws Exception {
        var key = PredictionDiskCache.Key.terrain(-1, -1, 0);
        byte[] bytes = payload(91, 128);
        try (var store = new PredictionRegionStorage(root)) {
            Path path = store.path(key);
            assertSame(path, store.path(PredictionDiskCache.Key.terrain(-32, -32, 0)));
            assertNull(store.read(key));
            Path legacy = store.legacy(key);
            Files.createDirectories(legacy.getParent());
            Files.write(legacy, bytes);
            assertArrayEquals(bytes, store.read(key).bytes());
            assertTrue(store.migrate(key));
            for (int i = 0; i < PredictionRegionStorage.MAX_PATHS * 2; i++)
                store.path(PredictionDiskCache.Key.surface(i * 32, i * 32, i));
            var field = PredictionRegionStorage.class.getDeclaredField("paths");
            field.setAccessible(true);
            assertTrue(((Map<?, ?>) field.get(store)).size() <= PredictionRegionStorage.MAX_PATHS);
            assertEquals(path, store.path(key));
            assertArrayEquals(bytes, store.read(key).bytes());
            store.close();
            assertTrue(((Map<?, ?>) field.get(store)).isEmpty());
            assertArrayEquals(bytes, store.read(key).bytes());
        }
    }

    private byte[] payload(int seed, int size) throws IOException {
        byte[] raw = new byte[size]; new Random(seed).nextBytes(raw);
        var out = new ByteArrayOutputStream();
        try (var zip = new DeflaterOutputStream(out)) { zip.write(raw); }
        return out.toByteArray();
    }
    private void write(PredictionRegionStorage store, PredictionDiskCache.Key key, byte[] data) throws IOException {
        Path temp = Files.createTempFile(root, "input", ".tmp");
        try { Files.write(temp, data); store.write(key, temp); }
        finally { Files.deleteIfExists(temp); }
    }

    @Test void regionRoundTripSeparatesNegativeCoordinatesKindsAndDetails() throws Exception {
        var keys = List.of(PredictionDiskCache.Key.terrain(-1,-1,0), PredictionDiskCache.Key.terrain(-32,-32,0),
                PredictionDiskCache.Key.terrain(0,0,0), PredictionDiskCache.Key.terrain(0,0,1),
                PredictionDiskCache.Key.surface(0,0,0));
        byte[] bytes = payload(1,2048);
        try (var store = new PredictionRegionStorage(root)) {
            for (var key : keys) write(store,key,bytes);
            assertEquals(store.path(keys.get(0)),store.path(keys.get(1)));
            assertNotEquals(store.path(keys.get(1)),store.path(keys.get(2)));
            assertFalse(Files.exists(store.legacy(keys.get(0))));
        }
        try (var store = new PredictionRegionStorage(root)) {
            for (var key : keys) {
                assertArrayEquals(bytes,store.read(key).bytes());
                assertFalse(store.read(key).legacy());
                assertArrayEquals(Arrays.copyOf(new InflaterInputStream(new ByteArrayInputStream(bytes)).readAllBytes(),36),store.header(key));
            }
        }
    }

    @Test void legacyMigrationAndDeletionNeverResurrectOldFile() throws Exception {
        var key = PredictionDiskCache.Key.terrain(1,2,0);
        byte[] bytes = payload(2,1024);
        try (var store = new PredictionRegionStorage(root)) {
            Files.createDirectories(store.legacy(key).getParent()); Files.write(store.legacy(key),bytes);
            assertTrue(store.read(key).legacy());
            assertTrue(store.migrate(key)); assertFalse(store.migrate(key));
            assertFalse(Files.exists(store.legacy(key)));
            store.delete(key);
            Files.write(store.legacy(key),bytes); // Simulate a failed legacy deletion / external old copy.
        }
        try (var store = new PredictionRegionStorage(root)) {
            assertNull(store.read(key));
            assertFalse(store.migrate(key));
            write(store,key,bytes);
            assertArrayEquals(bytes,store.read(key).bytes());
        }
    }

    @Test void appendWithoutIndexPublicationIsIgnoredAndTornIndexIsAMiss() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0,0,0);
        var neighbor = PredictionDiskCache.Key.terrain(1,0,0);
        byte[] old = payload(1,128), fresh = payload(2,256);
        Path path;
        try (var store = new PredictionRegionStorage(root)) {
            write(store,key,old); write(store,neighbor,fresh); path=store.path(key);
            Files.createDirectories(store.legacy(key).getParent()); Files.write(store.legacy(key),old);
        }
        Files.write(path,fresh,StandardOpenOption.APPEND);
        try (var store = new PredictionRegionStorage(root)) { assertArrayEquals(old,store.read(key).bytes()); }
        try (var file = new RandomAccessFile(path.toFile(),"rw")) {
            file.seek(PredictionRegionStorage.HEADER_BYTES+8); file.writeLong(Long.MAX_VALUE);
        }
        try (var store = new PredictionRegionStorage(root)) {
            assertNull(store.read(key));
            assertArrayEquals(fresh,store.read(neighbor).bytes());
            assertFalse(store.migrate(key));
            write(store,key,fresh); assertArrayEquals(fresh,store.read(key).bytes());
        }
    }

    @Test void compactionKeepsCurrentRecordsAndTombstones() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0,0,0);
        var deleted = PredictionDiskCache.Key.terrain(1,0,0);
        byte[] bytes = payload(3,600_000);
        long before;
        try (var store = new PredictionRegionStorage(root)) {
            write(store,deleted,bytes); store.delete(deleted);
            for(int i=0;i<10;i++) write(store,key,bytes);
            assertTrue(store.hasMaintenance());
            before=Files.size(store.path(key));
            store.compactOne();
            assertTrue(Files.size(store.path(key))<before/4);
            assertArrayEquals(bytes,store.read(key).bytes());
            Files.createDirectories(store.legacy(deleted).getParent()); Files.write(store.legacy(deleted),bytes);
        }
        try (var store = new PredictionRegionStorage(root)) {
            assertArrayEquals(bytes,store.read(key).bytes());
            assertNull(store.read(deleted));
        }
    }

    @Test void largeMeshRegionIsDiscoveredAndCompactedAfterReopen() throws Exception {
        var key = PredictionDiskCache.Key.mesh(PredictionDiskCache.Key.terrain(0, 0, 2));
        byte[] bytes = payload(31, 9 * 1024 * 1024);
        Path region;
        long before;
        try (var store = new PredictionRegionStorage(root)) {
            for (int i = 0; i < 3; i++) write(store, key, bytes);
            region = store.path(key);
            before = Files.size(region);
            assertTrue(store.hasMaintenance());
        }
        try (var store = new PredictionRegionStorage(root)) {
            store.discoverMaintenance();
            assertTrue(store.hasMaintenance());
            store.compactOne();
            assertTrue(Files.size(region) < before / 2);
            assertArrayEquals(bytes, store.read(key).bytes());
            assertFalse(store.hasMaintenance());
        }
    }

    @Test void compactionWaitsUntilWasteJustifiesRewritingLiveMeshRecords() throws Exception {
        var a = PredictionDiskCache.Key.mesh(PredictionDiskCache.Key.terrain(0, 0, 2));
        var b = PredictionDiskCache.Key.mesh(PredictionDiskCache.Key.terrain(1, 0, 2));
        var changed = PredictionDiskCache.Key.mesh(PredictionDiskCache.Key.terrain(2, 0, 2));
        byte[] large = payload(41, 8 * 1024 * 1024);
        byte[] small = payload(42, 4 * 1024 * 1024);
        try (var store = new PredictionRegionStorage(root)) {
            write(store, a, large);
            write(store, b, large);
            write(store, changed, small);
            write(store, changed, small);
            assertFalse(store.hasMaintenance());
            write(store, changed, small);
            assertTrue(store.hasMaintenance());
            long before = Files.size(store.path(a));
            store.compactOne();
            assertEquals(before - 2L * small.length, Files.size(store.path(a)));
            assertArrayEquals(large, store.read(a).bytes());
            assertArrayEquals(large, store.read(b).bytes());
            assertArrayEquals(small, store.read(changed).bytes());
        }
    }

    @Test void concurrentReadersNeverObservePartialReplacement() throws Exception {
        var key = PredictionDiskCache.Key.surface(0,0,3);
        byte[] a=payload(7,4096), b=payload(8,4096);
        var pool=Executors.newFixedThreadPool(3);
        try(var store=new PredictionRegionStorage(root)) {
            write(store,key,a);
            var reads=pool.submit(()-> { for(int i=0;i<100;i++) {
                byte[] result=store.read(key).bytes(); assertTrue(Arrays.equals(a,result)||Arrays.equals(b,result));
            } return true; });
            var writes=pool.submit(()-> { for(int i=0;i<20;i++) write(store,key,i%2==0?a:b); return true; });
            assertTrue(reads.get(20,TimeUnit.SECONDS)); assertTrue(writes.get(20,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void truncatedRegionCanRegenerateWithoutRevivingLegacyNeighbors() throws Exception {
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        var neighbor=PredictionDiskCache.Key.terrain(1,0,0);
        byte[] bytes=payload(11,128);
        Path path;
        try(var store=new PredictionRegionStorage(root)) {
            write(store,key,bytes); path=store.path(key);
            Files.createDirectories(store.legacy(neighbor).getParent()); Files.write(store.legacy(neighbor),bytes);
        }
        Files.write(path,new byte[]{1,2,3});
        try(var store=new PredictionRegionStorage(root)) {
            assertThrows(IOException.class,()->store.read(key));
            write(store,key,bytes);
            assertArrayEquals(bytes,store.read(key).bytes());
            assertNull(store.read(neighbor));
        }
    }

    @Test void handleEvictionAndMissingDeletesDoNotCreateRegions() throws Exception {
        byte[] bytes=payload(9,64);
        try(var store=new PredictionRegionStorage(root)) {
            for(int x=0;x<40;x++) write(store,PredictionDiskCache.Key.terrain(x*32,0,0),bytes);
            assertArrayEquals(bytes,store.read(PredictionDiskCache.Key.terrain(0,0,0)).bytes());
            var absent=PredictionDiskCache.Key.surface(9000,9000,99);
            store.delete(absent); assertFalse(Files.exists(store.path(absent)));
            assertNull(store.read(absent));
        }
    }

    @Test void missingReadsHeadersAndRepeatedDeletesAreNormalMisses() throws Exception {
        var key = PredictionDiskCache.Key.terrain(-1, -1, 2);
        try (var store = new PredictionRegionStorage(root)) {
            for (int i = 0; i < 100; i++) {
                assertNull(store.read(key));
                assertNull(store.header(key));
                store.delete(key);
            }
            assertFalse(Files.exists(store.path(key)));
            byte[] data = payload(51, 128);
            write(store, key, data);
            assertNotNull(store.header(key));
            store.delete(key);
            assertNull(store.read(key));
            assertNull(store.header(key));
        }
    }

    @Test void anUnopenedRegionNeverWaitsForAnUnrelatedRegionMutation() throws Exception {
        var a = PredictionDiskCache.Key.terrain(0, 0, 0);
        byte[] bytes = payload(72, 1024);
        try (var store = new PredictionRegionStorage(root)) {
            var method = PredictionRegionStorage.class.getDeclaredMethod("mutationLock", Path.class);
            method.setAccessible(true);
            Object locked = method.invoke(store, store.path(a));
            var b = a;
            for (int x = 1; x < 1024; x++) {
                b = PredictionDiskCache.Key.terrain(x * 32, 0, 0);
                if (method.invoke(store, store.path(b)) != locked) break;
            }
            assertNotSame(locked, method.invoke(store, store.path(b)));
            write(store, b, bytes);
            store.close(); // Force the second task to load its index again.
            var entered = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> slow;
                synchronized (locked) {
                    slow = pool.submit(() -> { entered.countDown(); return store.read(a); });
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    var other = b;
                    assertArrayEquals(bytes, pool.submit(() -> store.read(other).bytes()).get(5, TimeUnit.SECONDS));
                    assertFalse(slow.isDone(), "the blocked region must still be in flight");
                }
                assertNull(slow.get(5, TimeUnit.SECONDS));
            } finally { pool.shutdownNow(); }
        }
    }

    @RepeatedTest(3) void closeAndReopenKeepConcurrentWritesDurable() throws Exception {
        byte[] bytes = payload(73, 256 * 1024);
        var pool = Executors.newFixedThreadPool(3);
        try (var store = new PredictionRegionStorage(root)) {
            var writes = pool.submit(() -> {
                for (int i = 0; i < 24; i++) write(store, PredictionDiskCache.Key.surface(i * 32, 0, 0), bytes);
                return true;
            });
            var closes = pool.submit(() -> { for (int i = 0; i < 30; i++) store.close(); return true; });
            assertTrue(writes.get(30, TimeUnit.SECONDS));
            assertTrue(closes.get(30, TimeUnit.SECONDS));
            for (int i = 0; i < 24; i++) assertArrayEquals(bytes,
                    store.read(PredictionDiskCache.Key.surface(i * 32, 0, 0)).bytes());
        } finally { pool.shutdownNow(); }
    }

    @RepeatedTest(6) void concurrentPositionalReadersSurviveHandleEvictionAndLargeReplacements() throws Exception {
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        byte[] a = payload(61, 2 * 1024 * 1024), b = payload(62, 2 * 1024 * 1024);
        var pool = Executors.newFixedThreadPool(4);
        try (var store = new PredictionRegionStorage(root)) {
            write(store, key, a);
            var readers = new ArrayList<Future<?>>();
            for (int thread = 0; thread < 3; thread++) readers.add(pool.submit(() -> {
                for (int i = 0; i < 40; i++) {
                    var record = store.read(key);
                    assertNotNull(record);
                    assertTrue(Arrays.equals(a, record.bytes()) || Arrays.equals(b, record.bytes()));
                }
                return null;
            }));
            var writer = pool.submit(() -> {
                byte[] small = payload(63, 128);
                for (int i = 0; i < 42; i++) {
                    write(store, PredictionDiskCache.Key.terrain((i + 1) * 32, 0, 0), small);
                    if ((i & 7) == 0) { write(store, key, (i & 8) == 0 ? b : a); store.compactOne(); }
                }
                return null;
            });
            for (var reader : readers) reader.get(30, TimeUnit.SECONDS);
            writer.get(30, TimeUnit.SECONDS);
            assertNotNull(store.read(key));
        } finally { pool.shutdownNow(); }
    }
    @Test void absentVariantBatchUsesDirectoryInventoryAndNeverCreatesEmptyRegions() throws Exception {
        try (var store = new PredictionRegionStorage(root)) {
            Files.createDirectories(root.resolve("1-3"));
            var absent = new ArrayList<PredictionDiskCache.Key>();
            for (int settings = 0; settings < 128; settings++) for (int x = 0; x < 25; x++)
                absent.add(PredictionDiskCache.Key.surface(x, 0, settings));
            assertTrue(store.deleteAll(absent).isEmpty());
            assertTrue(counter(store, "deletionDirectoryScans") <= 2,
                    "missing settings and legacy files must not cause per-key filesystem calls");
            assertEquals(0, counter(store, "deletionForces"));
            try (var paths = Files.walk(root)) { assertEquals(0, paths.filter(Files::isRegularFile).count()); }
        }
    }

    @Test void batchPersistsOneRegionForceAndTombstonesSurviveLegacyReappearance() throws Exception {
        var stored = new ArrayList<PredictionDiskCache.Key>();
        byte[] bytes = payload(412, 128);
        try (var store = new PredictionRegionStorage(root)) {
            for (int x = 0; x < 12; x++) {
                var key = PredictionDiskCache.Key.surface(x, 0, 3);
                write(store, key, bytes);
                stored.add(key);
            }
            var legacy = PredictionDiskCache.Key.surface(20, 0, 3);
            Files.createDirectories(store.legacy(legacy).getParent());
            Files.write(store.legacy(legacy), bytes);
            stored.add(legacy);
            assertTrue(store.deleteAll(stored).isEmpty());
            assertEquals(1, counter(store, "deletionForces"));
            for (var key : stored) {
                assertNull(store.read(key));
                Files.createDirectories(store.legacy(key).getParent());
                Files.write(store.legacy(key), bytes);
            }
        }
        try (var reopened = new PredictionRegionStorage(root)) {
            for (var key : stored) assertNull(reopened.read(key));
            assertTrue(reopened.deleteAll(stored).isEmpty());
            assertEquals(0, counter(reopened, "deletionForces"), "existing tombstones do not need another index write");
            var fresh = PredictionDiskCache.Key.surface(1, 0, 3);
            write(reopened, fresh, bytes);
            assertArrayEquals(bytes, reopened.read(fresh).bytes(), "valid new writes must replace tombstones");
        }
    }

    @Test void aPreviouslyMissingDirectoryCanReceiveLegacyFilesBeforeTheNextBatch() throws Exception {
        var key = PredictionDiskCache.Key.surface(-1, -1, 100);
        byte[] bytes = payload(84, 128);
        try (var store = new PredictionRegionStorage(root)) {
            assertTrue(store.deleteAll(List.of(key)).isEmpty());
            Files.createDirectories(store.legacy(key).getParent());
            Files.write(store.legacy(key), bytes);
            assertTrue(store.deleteAll(List.of(key)).isEmpty());
            assertFalse(Files.exists(store.legacy(key)));
            assertNull(store.read(key));
        }
        try (var reopened = new PredictionRegionStorage(root)) { assertNull(reopened.read(key)); }
    }

    private static long counter(PredictionRegionStorage store, String name) throws Exception {
        var field = PredictionRegionStorage.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(store);
    }

    @Test void aRegionCreatedWhileDeletionWaitsCannotEscapeTheBatchInventory() throws Exception {
        var key = PredictionDiskCache.Key.surface(-1, -1, 100);
        byte[] bytes = payload(85, 128);
        var pool = Executors.newSingleThreadExecutor();
        try (var store = new PredictionRegionStorage(root)) {
            var method = PredictionRegionStorage.class.getDeclaredMethod("mutationLock", Path.class);
            method.setAccessible(true);
            Object locked = method.invoke(store, store.path(key));
            var worker = new java.util.concurrent.atomic.AtomicReference<Thread>();
            Future<Map<PredictionDiskCache.Key, IOException>> deletion;
            synchronized (locked) {
                deletion = pool.submit(() -> { worker.set(Thread.currentThread()); return store.deleteAll(List.of(key)); });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!deletion.isDone() && (worker.get() == null || worker.get().getState() != Thread.State.BLOCKED)
                        && System.nanoTime() < deadline) Thread.sleep(1);
                assertFalse(deletion.isDone(), "even an absent region must wait for its writer's mutation lock");
                assertEquals(Thread.State.BLOCKED, worker.get().getState());
                write(store, key, bytes);
                // Evict the newly created handle so correctness also requires refreshing the directory inventory.
                for (int i = 1; i <= 40; i++) write(store, PredictionDiskCache.Key.surface(i * 32, 0, 100), bytes);
            }
            assertTrue(deletion.get(5, TimeUnit.SECONDS).isEmpty());
            assertNull(store.read(key));
        } finally { pool.shutdownNow(); }
        try (var reopened = new PredictionRegionStorage(root)) { assertNull(reopened.read(key)); }
    }
}

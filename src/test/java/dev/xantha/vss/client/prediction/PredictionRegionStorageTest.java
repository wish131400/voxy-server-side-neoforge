package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionRegionStorageTest {
    @TempDir Path root;

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
            assertThrows(NoSuchFileException.class,()->store.read(key));
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
            assertThrows(NoSuchFileException.class,()->store.read(key));
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
            assertThrows(NoSuchFileException.class,()->store.read(deleted));
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
            assertThrows(NoSuchFileException.class,()->store.read(neighbor));
        }
    }

    @Test void handleEvictionAndMissingDeletesDoNotCreateRegions() throws Exception {
        byte[] bytes=payload(9,64);
        try(var store=new PredictionRegionStorage(root)) {
            for(int x=0;x<40;x++) write(store,PredictionDiskCache.Key.terrain(x*32,0,0),bytes);
            assertArrayEquals(bytes,store.read(PredictionDiskCache.Key.terrain(0,0,0)).bytes());
            var absent=PredictionDiskCache.Key.surface(9000,9000,99);
            store.delete(absent); assertFalse(Files.exists(store.path(absent)));
            assertThrows(NoSuchFileException.class,()->store.read(absent));
        }
    }
}

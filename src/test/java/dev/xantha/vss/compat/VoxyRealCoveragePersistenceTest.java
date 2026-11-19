package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class VoxyRealCoveragePersistenceTest {
    @TempDir Path directory;
    @BeforeEach void reset() throws Exception { VoxyRealCoveragePersistence.reset(); VoxySectionPosition.bind(LegacyKeys.class); }
    @AfterEach void resetKeys() throws Exception { VoxySectionPosition.bind(LegacyKeys.class); }
    public static final class LegacyKeys {
        public static long getWorldSectionId(int lod, int x, int y, int z) {
            return ((long) lod << 60) | ((long) (y & 255) << 52) | ((long) (z & 0xffffff) << 28) | ((long) (x & 0xffffff) << 4);
        }
    }
    public static final class ExtendedKeys {
        public static long getWorldSectionId(int lod, int x, int y, int z) {
            if (y >= -128 && y <= 127) return LegacyKeys.getWorldSectionId(lod, x, y, z);
            return ((long) lod << 60) | ((long) (y & 4095) << 48) | ((long) (z & 0xffffff) << 24) | ((long) (x & 0x7fffff) << 1) | 1;
        }
    }

    public static final class Tracker { public final Object engine; Tracker(Object engine) { this.engine = engine; } }
    public static final class Section {
        public final long key; public final Tracker tracker;
        Section(Object engine, int lod, int x, int y, int z) {
            key = VoxySectionPosition.key(lod, x >> (lod + 1), y >> (lod + 1), z >> (lod + 1));
            tracker = new Tracker(engine);
        }
    }
    record Session(Object storage, Object engine) { }
    Session session(String identity) {
        Session session = new Session(new Object(), new Object());
        VoxyRealCoveragePersistence.register(session.storage, directory, identity);
        assertTrue(VoxyRealCoveragePersistence.bind(session.engine, session.storage));
        return session;
    }
    void image(Session s, int lod, int x, int y, int z, byte[] bytes, boolean saving, boolean success) {
        Object token = VoxyRealCoveragePersistence.begin(s.storage, new Section(s.engine, lod, x, y, z), saving);
        assertNotNull(token);
        if (bytes != null) VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(bytes));
        VoxyRealCoveragePersistence.finish(token, success);
    }
    byte[] image(int variant) { byte[] data = new byte[128]; Arrays.fill(data, (byte) variant); return data; }

    void stage(Object batch, Session session, byte[] bytes) {
        var section = new Section(session.engine, 0, 0, 0, 0);
        Object token = VoxyRealCoveragePersistence.beginBatch(batch, section);
        assertNotNull(token);
        VoxyRealCoveragePersistence.captureCodec(ByteBuffer.wrap(bytes), section, true);
        VoxyRealCoveragePersistence.stageBatch(batch, token, true);
    }

    @Test void batchedCoveragePublishesOnlyAfterSuccessfulNativeCommitAndClearsFailedImages() throws Exception {
        Session source = session("batched");
        VoxyRealCoveragePersistence.committed(source.engine, 0, 0, 0);
        Object batch = new Object();
        assertSame(batch, VoxyRealCoveragePersistence.trackBatch(batch, source.storage));
        stage(batch, source, image(1));
        Session reader = session("batched");
        image(reader, 0, 0, 0, 0, image(1), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(reader.engine, 0, 0, 0, 0));
        VoxyRealCoveragePersistence.completeBatch(batch, false);
        VoxyRealCoveragePersistence.completeBatch(batch, true); // failed data cannot leak into a later commit
        image(reader, 0, 0, 0, 0, image(1), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(reader.engine, 0, 0, 0, 0));
        stage(batch, source, image(2));
        stage(batch, source, image(3)); // one native batch keeps its last staged image for this key
        VoxyRealCoveragePersistence.completeBatch(batch, true);
        Session reopened = session("batched");
        image(reopened, 0, 0, 0, 0, image(2), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(reopened.engine, 0, 0, 0, 0));
        image(reopened, 0, 0, 0, 0, image(3), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(reopened.engine, 0, 0, 0, 0));
    }

    @Test void stagedBatchesReleaseStripeLocksBeforeCollectingAnotherSection() throws Exception {
        Session source = session("unlocked-batch");
        VoxyRealCoveragePersistence.committed(source.engine, 0, 0, 0);
        Object batch = VoxyRealCoveragePersistence.trackBatch(new Object(), source.storage);
        stage(batch, source, image(1));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> stage(batch, source, image(2))).get(3, TimeUnit.SECONDS); }
        finally { worker.shutdownNow(); }
        VoxyRealCoveragePersistence.completeBatch(batch, true);
        Session reader = session("unlocked-batch");
        image(reader, 0, 0, 0, 0, image(2), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(reader.engine, 0, 0, 0, 0));
    }

    @Test void codecObserversIgnoreOtherSectionsAndTheWrongIoDirection() {
        Session source = session("codec-scope");
        VoxyRealCoveragePersistence.committed(source.engine, 0, 0, 0);
        var section = new Section(source.engine, 0, 0, 0, 0);
        Object token = VoxyRealCoveragePersistence.begin(source.storage, section, true);
        VoxyRealCoveragePersistence.captureCodec(ByteBuffer.wrap(image(1)), new Section(source.engine, 0, 0, 0, 0), true);
        VoxyRealCoveragePersistence.captureCodec(ByteBuffer.wrap(image(2)), section, false);
        VoxyRealCoveragePersistence.finish(token, true);
        Session reader = session("codec-scope");
        for (int variant : new int[]{1, 2}) {
            image(reader, 0, 0, 0, 0, image(variant), false, true);
            assertFalse(VoxyRealCoveragePersistence.contains(reader.engine, 0, 0, 0, 0));
        }
    }

    @Test void groupedRuntimeCoverageMatchesIndividualCellsAcrossSignedBoundaries() {
        var engine=new Object();var coverage=VoxyRealCoverage.of(engine);
        for(int y=-40;y<40;y++)if(Math.floorMod(y,5)!=1)coverage.mark(-3,y,5);
        for(int start=-42;start<=42;start++)for(int count=0;count<=32;count++) {
            int expected=0;
            for(int i=0;i<count;i++)if(coverage.containsSection(-3,start+i,5))expected|=1<<i;
            assertEquals(expected,coverage.coverageMask(-3,start,5,count,0));
            assertEquals(0,coverage.coverageMask(-2,start,5,count,0));
        }
    }
    @Test void groupedSavedCoverageMatchesIndividualCellsAcrossAllLods() {
        Session original=session("grouped-masks");
        for(int y=-36;y<36;y++)if(Math.floorMod(y,5)!=1)VoxyRealCoveragePersistence.committed(original.engine,-3,y,5);
        for(int lod=0;lod<=4;lod++)for(int y=-36;y<36;y+=1<<(lod+1))
            image(original,lod,-3,y,5,image(lod),true,true);
        VoxyRealCoveragePersistence.reset();Session restored=session("grouped-masks");
        for(int lod=0;lod<=4;lod++)for(int y=-36;y<36;y+=1<<(lod+1))
            image(restored,lod,-3,y,5,image(lod),false,true);
        for(int lod=0;lod<=4;lod++)for(int start=-42;start<=42;start++)for(int count=0;count<=32;count++) {
            int expected=0;
            for(int i=0;i<count;i++)if(VoxyRealCoveragePersistence.contains(restored.engine,-3,start+i,5,lod))expected|=1<<i;
            assertEquals(expected,VoxyRealCoveragePersistence.coverageMask(restored.engine,-3,start,5,count,lod));
        }
    }

    @Test void reopeningRestoresOnlyTheIndividuallyVerifiedLodAndCovered16Cube() {
        Session a = session("world-overworld");
        assertEquals(31, VoxyRealCoveragePersistence.committed(a.engine, -3, 2, 5));
        assertEquals(0, VoxyRealCoveragePersistence.committed(a.engine, -3, 2, 5));
        for (int lod = 0; lod <= 4; lod++) image(a, lod, -3, 2, 5, image(lod), true, true);
        VoxyRealCoveragePersistence.reset();
        Session b = session("world-overworld");
        for (int lod = 0; lod <= 4; lod++) {
            assertFalse(VoxyRealCoveragePersistence.contains(b.engine, -3, 2, 5, lod));
            image(b, lod, -3, 2, 5, image(lod), false, true);
            assertTrue(VoxyRealCoveragePersistence.contains(b.engine, -3, 2, 5, lod));
            assertFalse(VoxyRealCoveragePersistence.contains(b.engine, -3, 3, 5, lod));
            assertFalse(VoxyRealCoveragePersistence.contains(b.engine, -2, 2, 5, lod));
            assertEquals(1 << 3, VoxyRealCoveragePersistence.coverageMask(b.engine, -3, -1, 5, 8, lod));
            if (lod < 4) assertFalse(VoxyRealCoveragePersistence.contains(b.engine, -3, 2, 5, lod + 1));
        }
    }

    @Test void airNeedsASavedImageAndOldNativeImagesNeverGainNewCoverage() {
        Session a = session("air");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        image(a, 0, 0, 0, 0, image(0), true, true);
        VoxyRealCoveragePersistence.committed(a.engine, 1, 0, 0); // new authority after the saved snapshot
        VoxyRealCoveragePersistence.reset();
        Session b = session("air");
        image(b, 0, 0, 0, 0, image(0), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
        assertFalse(VoxyRealCoveragePersistence.contains(b.engine, 1, 0, 0, 0));
        image(b, 1, 0, 0, 0, null, false, false);
        assertFalse(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 1));
    }

    @Test void nativeWriteFailureMissingCorruptAndDifferentImagesDoNotRestore() {
        Session a = session("fail");
        VoxyRealCoveragePersistence.committed(a.engine, 3, 4, 5);
        image(a, 0, 3, 4, 5, image(1), true, false);
        Session b = session("fail");
        image(b, 0, 3, 4, 5, image(1), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(b.engine, 3, 4, 5, 0));
        image(a, 0, 3, 4, 5, image(1), true, true);
        Session c = session("fail");
        image(c, 0, 3, 4, 5, image(2), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(c.engine, 3, 4, 5, 0));
        image(c, 0, 3, 4, 5, image(1), false, false);
        assertFalse(VoxyRealCoveragePersistence.contains(c.engine, 3, 4, 5, 0));
        image(c, 0, 3, 4, 5, image(1), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(c.engine, 3, 4, 5, 0));
        image(c, 0, 3, 4, 5, null, false, false);
        assertFalse(VoxyRealCoveragePersistence.contains(c.engine, 3, 4, 5, 0));
    }

    @Test void snapshotTakenBeforeSerializationExcludesLaterCommittedCoverage() {
        Session a = session("snapshot");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        Object token = VoxyRealCoveragePersistence.begin(a.storage, new Section(a.engine, 0, 0, 0, 0), true);
        VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(image(4)));
        VoxyRealCoveragePersistence.committed(a.engine, 1, 0, 0);
        VoxyRealCoveragePersistence.finish(token, true);
        Session b = session("snapshot");
        image(b, 0, 0, 0, 0, image(4), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
        assertFalse(VoxyRealCoveragePersistence.contains(b.engine, 1, 0, 0, 0));
    }

    @Test void disconnectRevokesQueriesAndLateLoadsButPreservesNativeShutdownSaves() {
        Session a = session("epochs");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        VoxyRealCoveragePersistence.reset();
        image(a, 0, 0, 0, 0, image(0), true, true); // native shutdown AFTER disconnect
        Session b = session("epochs");
        Object token = VoxyRealCoveragePersistence.begin(b.storage, new Section(b.engine, 0, 0, 0, 0), false);
        VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(image(0)));
        VoxyRealCoveragePersistence.reset();
        VoxyRealCoveragePersistence.finish(token, true);
        assertFalse(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
        Session c = session("epochs");
        image(c, 0, 0, 0, 0, image(0), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(c.engine, 0, 0, 0, 0));
    }

    @Test void reusedStorageAndDifferentWorldsKeepSeparateEngineCoverage() {
        Session a = session("world-a");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        image(a, 0, 0, 0, 0, image(0), true, true);
        Object recreated = new Object();
        assertTrue(VoxyRealCoveragePersistence.bind(recreated, a.storage));
        assertFalse(VoxyRealCoveragePersistence.contains(recreated, 0, 0, 0, 0));
        image(new Session(a.storage, recreated), 0, 0, 0, 0, image(0), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(recreated, 0, 0, 0, 0));
        Session other = session("world-b");
        image(other, 0, 0, 0, 0, image(0), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(other.engine, 0, 0, 0, 0));
    }

    @Test void truncatedTailIsIgnoredAndRepairedOnTheNextSave() throws Exception {
        Session a = session("truncated");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        image(a, 0, 0, 0, 0, image(0), true, true);
        Path file;
        try (var files = Files.walk(directory)) { file = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        Files.write(file, new byte[]{0, 0, 0, 100, 1, 2, 3}, StandardOpenOption.APPEND);
        Session b = session("truncated");
        image(b, 0, 0, 0, 0, image(0), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
        VoxyRealCoveragePersistence.committed(b.engine, 1, 0, 0);
        image(b, 0, 0, 0, 0, image(1), true, true);
        Session c = session("truncated");
        image(c, 0, 0, 0, 0, image(1), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(c.engine, 1, 0, 0, 0));
    }

    @Test void byteBufferPositionIsPreservedAndReusedBuffersAreNotRetained() {
        Session a = session("buffer");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        Object token = VoxyRealCoveragePersistence.begin(a.storage, new Section(a.engine, 0, 0, 0, 0), true);
        ByteBuffer data = ByteBuffer.wrap(image(7)); data.position(16); data.limit(112);
        VoxyRealCoveragePersistence.capture(data);
        assertEquals(16, data.position()); assertEquals(112, data.limit());
        Arrays.fill(data.array(), (byte) 8);
        VoxyRealCoveragePersistence.finish(token, true);
        Session b = session("buffer");
        byte[] original = new byte[96]; Arrays.fill(original, (byte) 7);
        image(b, 0, 0, 0, 0, original, false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
    }

    @Test void coverageQueriesNeverCreateFilesOrGrantHistoricalNativeAir() {
        Session a = session("historic");
        for (int i = 0; i < 10000; i++) {
            assertFalse(VoxyRealCoveragePersistence.contains(a.engine, i, -4, i, 4));
            assertEquals(0, VoxyRealCoveragePersistence.coverageMask(a.engine, i, -4, i, 32, 4));
        }
        assertEquals(0, directory.toFile().list().length);
    }

    @Test void extendedHeightKeysDoNotAliasTheLegacyVerticalPeriod() throws Exception {
        VoxySectionPosition.bind(ExtendedKeys.class);
        Session a = session("extended");
        VoxyRealCoveragePersistence.committed(a.engine, -17, 600, 100);
        for (int lod = 0; lod <= 4; lod++) image(a, lod, -17, 600, 100, image(lod), true, true);
        Session b = session("extended");
        for (int lod = 0; lod <= 4; lod++) {
            image(b, lod, -17, 600, 100, image(lod), false, true);
            assertTrue(VoxyRealCoveragePersistence.contains(b.engine, -17, 600, 100, lod));
            assertFalse(VoxyRealCoveragePersistence.contains(b.engine, -17, 88, 100, lod));
        }
    }

    @Test void compactionRetainsLatestImageAndCorruptRecordsCannotGrantCoverage() throws Exception {
        Session a = session("compact");
        VoxyRealCoveragePersistence.committed(a.engine, 1, 0, 0);
        for (int i = 0; i < 140; i++) image(a, 0, 1, 0, 0, image(i), true, true);
        Path file;
        try (var files = Files.walk(directory)) { file = files.filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        assertTrue(Files.size(file) < 8192, "Repeated active saves are compacted");
        Session b = session("compact");
        image(b, 0, 1, 0, 0, image(139), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 1, 0, 0, 0));
        byte[] bytes = Files.readAllBytes(file); bytes[50] ^= 1; Files.write(file, bytes);
        Session c = session("compact");
        image(c, 0, 1, 0, 0, image(139), false, true);
        assertFalse(VoxyRealCoveragePersistence.contains(c.engine, 1, 0, 0, 0));
        VoxyRealCoveragePersistence.committed(c.engine, 0, 0, 0);
        image(c, 0, 0, 0, 0, image(3), true, true);
        Session d = session("compact");
        image(d, 0, 0, 0, 0, image(3), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(d.engine, 0, 0, 0, 0));
        assertFalse(VoxyRealCoveragePersistence.contains(d.engine, 1, 0, 0, 0));
    }

    @Test void concurrentSavesKeepProofAndActualImageInTheSameOrder() throws Exception {
        Session a = session("concurrent");
        VoxyRealCoveragePersistence.committed(a.engine, 0, 0, 0);
        CountDownLatch firstEntered = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondEntered = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> {
                Object token = VoxyRealCoveragePersistence.begin(a.storage, new Section(a.engine, 0, 0, 0, 0), true);
                try {
                    VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(image(1))); firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("save barrier timed out");
                    VoxyRealCoveragePersistence.finish(token, true);
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            VoxyRealCoveragePersistence.committed(a.engine, 1, 0, 0);
            Future<?> second = workers.submit(() -> {
                Object token = VoxyRealCoveragePersistence.begin(a.storage, new Section(a.engine, 0, 0, 0, 0), true);
                secondEntered.countDown();
                VoxyRealCoveragePersistence.capture(ByteBuffer.wrap(image(2)));
                VoxyRealCoveragePersistence.finish(token, true);
            });
            assertFalse(secondEntered.await(50, TimeUnit.MILLISECONDS));
            releaseFirst.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { releaseFirst.countDown(); workers.shutdownNow(); }
        Session b = session("concurrent");
        image(b, 0, 0, 0, 0, image(2), false, true);
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 0, 0, 0, 0));
        assertTrue(VoxyRealCoveragePersistence.contains(b.engine, 1, 0, 0, 0));
    }
}

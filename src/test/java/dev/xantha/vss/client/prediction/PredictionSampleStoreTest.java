package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionSampleStoreTest {
    @TempDir
    Path directory;

    @Test
    void delayedOldSessionCloseCannotOverwriteNewSessionSamples() {
        Path file = directory.resolve("overlap.smp");
        var older = new PredictionSampleStore(file, 12L);
        var sample = new ClientColumnSample(80, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 80, -32768, -32768, -32768);
        older.put(1L, sample);
        try (var newer = new PredictionSampleStore(file, 12L)) {
            newer.put(2L, sample);
            newer.flush();
            older.close();
        }
        try (var reopened = new PredictionSampleStore(file, 12L)) {
            assertEquals(sample, reopened.get(2L));
            org.junit.jupiter.api.Assertions.assertNull(reopened.get(1L));
        }
    }

    @Test
    void preSurfaceRuleCacheIsNotReused() throws Exception {
        Path file = directory.resolve("old.smp");
        try (var output = new java.io.DataOutputStream(java.nio.file.Files.newOutputStream(file))) {
            output.writeInt(0x56535331);
            output.writeLong(12L);
            output.writeInt(1);
            output.writeLong(99L);
            for (int field = 0; field < 17; field++) output.writeInt(1);
        }
        try (PredictionSampleStore store = new PredictionSampleStore(file, 12L)) {
            assertEquals(0, store.size());
        }
    }

    @Test
    void samplesSurviveStoreReopenAndFingerprintInvalidatesOldData() {
        ClientColumnSample sample = new ClientColumnSample(80, 63, 2, 4, 1,
                2, 48, 9, 1, ClientColumnSample.FLAG_TREE_HERE, 0,
                4, 1, 70, 40, 20, 8);
        Path file = directory.resolve("samples.smp");
        try (PredictionSampleStore store = new PredictionSampleStore(file, 12L)) {
            store.put(99L, sample);
            assertEquals(sample, store.get(99L));
        }
        try (PredictionSampleStore reopened = new PredictionSampleStore(file, 12L)) {
            assertEquals(sample, reopened.get(99L));
        }
        try (PredictionSampleStore changed = new PredictionSampleStore(file, 13L)) {
            org.junit.jupiter.api.Assertions.assertNull(changed.get(99L));
        }
    }
}

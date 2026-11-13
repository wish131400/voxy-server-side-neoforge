package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Closing a tile manager interrupts its capture worker and closes the disk cache while a capture may be inside
 * PredictionCapturedTerrain.save. The drain treats CancellationException as a quiet stop and any other exception as a
 * failure worth a warning, so a save cut short by the close must surface as a cancellation.
 */
class PredictionCaptureCancellationTest {
    @TempDir Path directory;

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @AfterEach void drain() throws Exception {
        Thread.interrupted();
        PredictionCacheTestFiles.awaitBackgroundClose();
    }

    @Test void anInterruptedWorkerCancelsTheSaveInsteadOfFailingIt() {
        try (var disk = new PredictionDiskCache(directory, 77)) {
            var store = new PredictionCapturedTerrain(disk);
            Thread.currentThread().interrupt();
            try {
                assertThrows(CancellationException.class, () -> store.put(0, 0, PredictionEditedTerrainTest.craterSamples()));
            } finally {
                assertTrue(Thread.interrupted(), "the interrupt stays visible to the drain, which returns on it");
            }
        }
    }

    @Test void aClosedCacheCancelsTheSaveInsteadOfFailingIt() {
        var disk = new PredictionDiskCache(directory, 77);
        var store = new PredictionCapturedTerrain(disk);
        assertTrue(store.put(0, 0, PredictionEditedTerrainTest.craterSamples()), "an open cache persists the capture");
        disk.close();
        assertFalse(Thread.currentThread().isInterrupted());
        assertThrows(CancellationException.class, () -> store.put(1, 0, PredictionEditedTerrainTest.craterSamples()));
    }

    @Test void aWriteFailureOnAnOpenCacheIsStillAFailure() throws Exception {
        try (var disk = new PredictionDiskCache(directory, 77)) {
            // The temporary record is written beside the leaf's legacy path; a file in place of that directory fails the write.
            Path parent = disk.file(new PredictionDiskCache.Key(3, 0, 0, 0)).getParent();
            Files.createDirectories(parent.getParent());
            Files.writeString(parent, "not a directory");
            var store = new PredictionCapturedTerrain(disk);
            var failure = assertThrows(IllegalStateException.class, () -> store.put(0, 0, PredictionEditedTerrainTest.craterSamples()));
            assertEquals("capture persistence failed", failure.getMessage());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class PredictionTileManagerTest {
    @Test
    void sampleCropUsesTheInteriorGrid() {
        int sourceGridSize = 66;
        int targetGridSize = 65;
        int[] source = new int[sourceGridSize * sourceGridSize];
        for (int z = 0; z < sourceGridSize; z++) {
            for (int x = 0; x < sourceGridSize; x++) {
                source[z * sourceGridSize + x] = z * sourceGridSize + x;
            }
        }

        int[] cropped = PredictionTileManager.cropMargin(source, sourceGridSize, targetGridSize);

        assertEquals(source[1 * sourceGridSize + 1], cropped[0]);
        assertEquals(source[65 * sourceGridSize + 65], cropped[cropped.length - 1]);
    }

    @Test
    void fullPendingQueueDoesNotForgetAnExistingTask() {
        Set<String> pending = ConcurrentHashMap.newKeySet();

        assertTrue(PredictionTileManager.tryReservePending(pending, "first", 1));
        assertFalse(PredictionTileManager.tryReservePending(pending, "first", 1));
        assertTrue(pending.contains("first"));
        assertFalse(PredictionTileManager.tryReservePending(pending, "second", 1));
        assertTrue(pending.contains("first"));
        assertFalse(pending.contains("second"));
    }
}

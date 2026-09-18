package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PredictionExactCoverageMaskTest {
    @Test void sparseCoverageKeepsMissingNearColumnsAndNegativeCoordinatesIndependent() {
        var mask = PredictionExactCoverageMask.Snapshot.around(-1, -1, 128);
        mask.mark(-100, 20);
        mask.mark(80, -90);
        assertEquals(255, at(mask, -100, 20));
        assertEquals(255, at(mask, 80, -90));
        assertEquals(0, at(mask, -1, -1), "a missing inner column must not alter far ownership");
        assertEquals(0, at(mask, -99, 20));
        mask.mark(mask.originX() - 1, mask.originZ());
        mask.mark(mask.originX() + mask.size(), mask.originZ());
        assertEquals(0, at(mask, mask.originX(), mask.originZ()));
    }

    @Test void viewMovementDoesNotMoveWorldCoordinatesAndAllocationIsBounded() {
        for (int center : new int[]{-64, -1, 0, 31, 32, 64}) {
            var mask = PredictionExactCoverageMask.Snapshot.around(center, center, 128);
            mask.mark(4, -5);
            assertEquals(255, at(mask, 4, -5));
        }
        assertTrue(PredictionExactCoverageMask.Snapshot.around(0, 0, Integer.MAX_VALUE).columns().length < 1_200_000);
    }

    private static int at(PredictionExactCoverageMask.Snapshot mask, int x, int z) {
        return mask.columns()[(z - mask.originZ()) * mask.size() + x - mask.originX()] & 255;
    }
}

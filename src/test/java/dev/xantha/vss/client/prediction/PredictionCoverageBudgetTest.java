package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCoverageBudgetTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void countBudgetSpansPreparationsAndContinuesAtTheNextFrame() {
        var budget = new PredictionCoverageBudget(5, Long.MAX_VALUE, () -> 0);
        budget.beginFrame(1);
        for (int i = 0; i < 5; i++) assertTrue(budget.takeGroup());
        assertFalse(budget.takeGroup()); budget.beginFrame(1); assertFalse(budget.takeGroup());
        budget.beginFrame(2); assertTrue(budget.takeGroup()); assertEquals(1, budget.usedGroups());
    }
    @Test void timeBudgetStopsByTheNextClockBatchWithoutUsingWallClockAssertions() {
        var now = new AtomicLong(); var budget = new PredictionCoverageBudget(4096, 100, now::get);
        budget.beginFrame(1);
        int groups = 0;
        while (budget.takeGroup()) { groups++; now.addAndGet(10); }
        assertEquals(32, groups); assertFalse(budget.takeGroup());
        budget.beginFrame(2); assertTrue(budget.takeGroup());
    }
}

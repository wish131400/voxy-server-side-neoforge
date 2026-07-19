package dev.xantha.vss.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutomaticGenerationSettingsTest {
    @Test
    void settingsStayPositiveAndRespectGlobalConcurrency() {
        int processors = Runtime.getRuntime().availableProcessors();
        int globalConcurrency = 7;

        assertBetweenOneAndGlobal(AutomaticGenerationSettings.packingThreads(processors, globalConcurrency), globalConcurrency);
        assertBetweenOneAndGlobal(AutomaticGenerationSettings.startsPerTick(processors, globalConcurrency), globalConcurrency);
        assertBetweenOneAndGlobal(AutomaticGenerationSettings.completionsPerTick(processors, globalConcurrency), globalConcurrency);
        assertBetweenOneAndGlobal(AutomaticGenerationSettings.packingQueueLimit(processors, globalConcurrency), globalConcurrency);
        assertEquals(300, AutomaticGenerationSettings.TIMEOUT_SECONDS);
    }

    @Test
    void processorCountScalesAutomaticThroughput() {
        assertEquals(1, AutomaticGenerationSettings.packingThreads(1, 128));
        assertEquals(8, AutomaticGenerationSettings.packingThreads(16, 128));
        assertEquals(16, AutomaticGenerationSettings.startsPerTick(16, 128));
        assertEquals(16, AutomaticGenerationSettings.completionsPerTick(16, 128));
        assertEquals(128, AutomaticGenerationSettings.packingQueueLimit(16, 128));
    }

    private static void assertBetweenOneAndGlobal(int value, int globalConcurrency) {
        assertTrue(value >= 1);
        assertTrue(value <= globalConcurrency);
    }
}

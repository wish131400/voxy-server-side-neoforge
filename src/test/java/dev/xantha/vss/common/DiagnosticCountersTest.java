package dev.xantha.vss.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DiagnosticCountersTest {
    @Test
    void accumulatesWholeWindowAndReportsSilenceWithoutReplayingOldCounts() {
        DiagnosticCounters counters = new DiagnosticCounters(() -> true, 5_000_000L);
        assertNull(counters.poll(0L));
        counters.add("sentProbe", 32);
        counters.add("sentProbe", 32);
        counters.record("probePromoted");
        assertNull(counters.poll(4_999_999L));
        String report = counters.poll(5_000_000L);
        assertTrue(report.contains("sentProbe=64"));
        assertTrue(report.contains("probePromoted=1"));
        assertTrue(report.contains("windowMs=5"));
        assertEquals("windowMs=5, events={}", counters.poll(10_000_000L));
    }

    @Test
    void disablingAndSessionResetDiscardOldEvidence() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        DiagnosticCounters counters = new DiagnosticCounters(enabled::get, 5L);
        counters.poll(0);
        counters.record("oldSession");
        enabled.set(false);
        assertNull(counters.poll(10));
        counters.record("disabled");
        enabled.set(true);
        assertNull(counters.poll(20));
        assertEquals("windowMs=0, events={}", counters.poll(25));
        counters.record("beforeReset");
        counters.reset();
        assertNull(counters.poll(30));
        counters.record("newSession");
        assertEquals("windowMs=0, events={newSession=1}", counters.poll(35));
    }

    @Test
    void concurrentCallbacksDoNotLoseCounts() throws Exception {
        DiagnosticCounters counters = new DiagnosticCounters(() -> true, 1L);
        counters.poll(0L);
        Thread first = new Thread(() -> { for (int i = 0; i < 1000; i++) counters.record("callback"); });
        Thread second = new Thread(() -> { for (int i = 0; i < 1000; i++) counters.record("callback"); });
        first.start();
        second.start();
        first.join();
        second.join();
        assertEquals("windowMs=0, events={callback=2000}", counters.poll(1L));
    }
}

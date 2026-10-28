package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoxyPredictionIngestHandoffTest {
    @AfterEach void cleanup() { VoxyIngestTracker.reset(); }

    @Test void realAirNotificationWaitsForAllSectionsAndRunsOnce() {
        try (var bridge = mockStatic(VoxyPredictionBridge.class)) {
            var first = VoxyIngestTracker.begin(new Object(),-2,3);
            var last = VoxyIngestTracker.begin(new Object(),-2,3);
            VoxyIngestTracker.complete(first);
            assertTrue(VoxyIngestTracker.pendingColumn(-2,3)); bridge.verifyNoInteractions();
            bridge.when(()->VoxyPredictionBridge.realColumnSettled(-2,3)).thenAnswer(call->{
                assertFalse(VoxyIngestTracker.pendingColumn(-2,3)); return null;
            });
            VoxyIngestTracker.complete(last); VoxyIngestTracker.complete(last);
            bridge.verify(()->VoxyPredictionBridge.realColumnSettled(-2,3),times(1));
        }
    }
    @Test void lateOldWorldCompletionCannotNotifyTheNewWorld() {
        try (var bridge = mockStatic(VoxyPredictionBridge.class)) {
            var old = VoxyIngestTracker.begin(new Object(),4,-5);
            VoxyIngestTracker.reset();
            var current = VoxyIngestTracker.begin(new Object(),4,-5);
            VoxyIngestTracker.complete(old);
            assertTrue(VoxyIngestTracker.pendingColumn(4,-5)); bridge.verifyNoInteractions();
            VoxyIngestTracker.complete(current);
            bridge.verify(()->VoxyPredictionBridge.realColumnSettled(4,-5),times(1));
        }
    }
}

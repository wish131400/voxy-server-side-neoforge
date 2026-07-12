package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CancelableTaskCallbacksTest {

    @Test
    void finishSealsCallbackRegistration() {
        CancelableTaskCallbacks<String> callbacks = new CancelableTaskCallbacks<>();
        CancelableTaskCallbacks.Token<String> first = callbacks.add("first");

        List<CancelableTaskCallbacks.Token<String>> finished = callbacks.finish();

        assertEquals(List.of(first), finished);
        assertTrue(callbacks.isFinished());
        assertNull(callbacks.add("late"));
    }

    @Test
    void cancellationIsIdempotentAndVisibleToCompletion() {
        CancelableTaskCallbacks<String> callbacks = new CancelableTaskCallbacks<>();
        CancelableTaskCallbacks.Token<String> token = callbacks.add("column");

        assertFalse(token.isCancelled());
        assertTrue(token.cancel());
        assertFalse(token.cancel());
        assertTrue(token.isCancelled());
        assertEquals("column", token.callback());
    }
}

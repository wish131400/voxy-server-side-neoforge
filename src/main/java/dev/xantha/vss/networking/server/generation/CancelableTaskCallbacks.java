package dev.xantha.vss.networking.server.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class CancelableTaskCallbacks<T> {
    private final List<Token<T>> callbacks = new ArrayList<>();
    private boolean finished;

    synchronized Token<T> add(T callback) {
        if (finished) {
            return null;
        }
        Token<T> token = new Token<>(callback);
        callbacks.add(token);
        return token;
    }

    synchronized List<Token<T>> snapshot() {
        return List.copyOf(callbacks);
    }

    synchronized List<Token<T>> finish() {
        finished = true;
        return List.copyOf(callbacks);
    }

    synchronized boolean isFinished() {
        return finished;
    }

    static final class Token<T> {
        private final T callback;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Token(T callback) {
            this.callback = callback;
        }

        T callback() {
            return callback;
        }

        boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }
}

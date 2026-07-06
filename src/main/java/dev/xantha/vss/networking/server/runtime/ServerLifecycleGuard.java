package dev.xantha.vss.networking.server.runtime;

import java.util.concurrent.atomic.AtomicLong;

public final class ServerLifecycleGuard {
    private final AtomicLong epoch = new AtomicLong();
    private volatile boolean stopping;

    public long start() {
        long nextEpoch = epoch.incrementAndGet();
        stopping = false;
        return nextEpoch;
    }

    public long stop() {
        stopping = true;
        return epoch.incrementAndGet();
    }

    public long currentEpoch() {
        return epoch.get();
    }

    public boolean isStopping() {
        return stopping;
    }

    public boolean isStale(long capturedEpoch) {
        return stopping || capturedEpoch != epoch.get();
    }
}

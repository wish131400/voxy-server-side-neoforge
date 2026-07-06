package dev.xantha.vss.networking.server.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerLifecycleGuardTest {

    @Test
    void defaultStateMatchesPreviousNotStoppingLifecycle() {
        ServerLifecycleGuard guard = new ServerLifecycleGuard();

        assertFalse(guard.isStopping());
        assertFalse(guard.isStale(guard.currentEpoch()));
    }

    @Test
    void startClearsStoppingAndAdvancesEpoch() {
        ServerLifecycleGuard guard = new ServerLifecycleGuard();
        long beforeStop = guard.currentEpoch();
        guard.stop();

        long startedEpoch = guard.start();

        assertFalse(guard.isStopping());
        assertNotEquals(beforeStop, startedEpoch);
        assertFalse(guard.isStale(startedEpoch));
    }

    @Test
    void stopMarksCapturedEpochsAsStale() {
        ServerLifecycleGuard guard = new ServerLifecycleGuard();
        long startedEpoch = guard.start();

        long stoppedEpoch = guard.stop();

        assertTrue(guard.isStopping());
        assertTrue(guard.isStale(startedEpoch));
        assertTrue(guard.isStale(stoppedEpoch));
    }

    @Test
    void newerStartInvalidatesOldEpoch() {
        ServerLifecycleGuard guard = new ServerLifecycleGuard();
        long oldEpoch = guard.start();
        guard.stop();

        long newEpoch = guard.start();

        assertTrue(guard.isStale(oldEpoch));
        assertFalse(guard.isStale(newEpoch));
    }
}

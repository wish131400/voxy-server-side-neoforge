package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StrictIngestCompletionTest {
    @BeforeEach void reset() { VoxyIngestTracker.reset(); }

    @Test void detachedSnapshotBindsToItsRawSubmissionAndCompletesOnlyItsOwnTicket() throws Throwable {
        Object original = new Object(), snapshotTask = new Object(), localTask = new Object();
        VoxyIngestTracker.submittedSnapshot(localTask);
        assertEquals(0, VoxyIngestTracker.taskCount());
        assertTrue(VoxyIngestTracker.runRawIngest(original, -7, 9, () -> {
            VoxyIngestTracker.submittedSnapshot(snapshotTask);
            return true;
        }));
        assertTrue(VoxyIngestTracker.pendingColumn(-7, 9));
        VoxyIngestTracker.discarded(localTask);
        assertEquals(1, VoxyIngestTracker.pendingSection(original));
        Object token = VoxyIngestTracker.enterWorker();
        VoxyIngestTracker.workerTask(snapshotTask);
        VoxyIngestTracker.finishWorker(token);
        VoxyIngestTracker.discarded(snapshotTask);
        assertFalse(VoxyIngestTracker.pendingColumn(-7, 9));
        assertEquals(0, VoxyIngestTracker.taskCount());
    }

    @Test void lateCallbacksCannotReleaseAnotherSubmissionOfTheSameSection() throws Throwable {
        Object section = new Object(), oldTask = new Object(), newTask = new Object();
        assertFalse(VoxyIngestTracker.runRawIngest(section, 7, -2, () -> {
            VoxyIngestTracker.submitted(oldTask, section); return false;
        }));
        assertTrue(VoxyIngestTracker.runRawIngest(section, 7, -2, () -> {
            VoxyIngestTracker.submitted(newTask, section); return true;
        }));
        VoxyIngestTracker.discarded(oldTask);
        VoxyIngestTracker.discarded(oldTask);
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        assertTrue(VoxyIngestTracker.pendingColumn(7, -2));
        VoxyIngestTracker.discarded(newTask);
        assertFalse(VoxyIngestTracker.pendingColumn(7, -2));
        assertEquals(0, VoxyIngestTracker.taskCount());
    }

    @Test void trueWithoutPublicationAndExceptionalRawCallsLeaveNoRegistration() throws Throwable {
        Object section = new Object();
        assertTrue(VoxyIngestTracker.runRawIngest(section, 1, 2, () -> true));
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        RuntimeException failure = new RuntimeException("raw failure");
        assertSame(failure, assertThrows(RuntimeException.class, () ->
                VoxyIngestTracker.runRawIngest(section, 1, 2, () -> { throw failure; })));
        assertFalse(VoxyIngestTracker.rawScopeActive());
        assertFalse(VoxyIngestTracker.pendingColumn(1, 2));
    }

    @Test void nestedRawScopesRestoreTheirParentAndDoNotCrossBind() throws Throwable {
        Object section = new Object(), outerTask = new Object(), innerTask = new Object();
        VoxyIngestTracker.runRawIngest(section, 3, 4, () -> {
            VoxyIngestTracker.runRawIngest(section, 3, 4, () -> {
                VoxyIngestTracker.submitted(innerTask, section); return true;
            });
            VoxyIngestTracker.submitted(outerTask, section); return true;
        });
        assertEquals(2, VoxyIngestTracker.pendingSection(section));
        VoxyIngestTracker.discarded(innerTask);
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        VoxyIngestTracker.discarded(outerTask);
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertFalse(VoxyIngestTracker.rawScopeActive());
    }

    @Test void workersMayCompleteBeforeTheRawCallReturns() throws Throwable {
        Object section = new Object(), task = new Object();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        VoxyIngestTracker.runRawIngest(section, 3, 4, () -> {
            VoxyIngestTracker.submitted(task, section);
            Thread worker = new Thread(() -> {
                try {
                    Object token = VoxyIngestTracker.enterWorker();
                    VoxyIngestTracker.workerTask(task);
                    VoxyIngestTracker.workerDone();
                    VoxyIngestTracker.finishWorker(token);
                    assertFalse(VoxyIngestTracker.workerScopeActive());
                } catch (Throwable t) { failure.set(t); }
            });
            worker.start(); worker.join(); return true;
        });
        assertNull(failure.get());
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertEquals(0, VoxyIngestTracker.taskCount());
    }

    @Test void exceptionalWorkersAndWorldResetsCannotConsumeNewTickets() throws Throwable {
        Object section = new Object(), oldTask = new Object(), newTask = new Object();
        VoxyIngestTracker.runRawIngest(section, 0, 0, () -> {
            VoxyIngestTracker.submitted(oldTask, section); return true;
        });
        Object token = VoxyIngestTracker.enterWorker();
        VoxyIngestTracker.workerTask(oldTask);
        VoxyIngestTracker.reset();
        VoxyIngestTracker.runRawIngest(section, 0, 0, () -> {
            VoxyIngestTracker.submitted(newTask, section); return true;
        });
        VoxyIngestTracker.workerFailed(token, new Error("native failure"));
        assertFalse(VoxyIngestTracker.workerScopeActive());
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        VoxyIngestTracker.discarded(newTask);
        assertFalse(VoxyIngestTracker.pendingColumn(0, 0));
    }
}

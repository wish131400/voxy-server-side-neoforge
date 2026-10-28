package dev.xantha.vss.compat;

import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Once-only completion of VSS submissions, keyed by the actual native task. */
public final class VoxyIngestTracker {
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Object, Ticket> TASKS = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Integer> SECTIONS = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> COLUMNS = new ConcurrentHashMap<>();
    private static final ThreadLocal<RawScope> RAW = new ThreadLocal<>();
    private static final ThreadLocal<WorkerScope> WORKER = new ThreadLocal<>();
    private static long generation;

    private VoxyIngestTracker() { }

    public static final class Ticket {
        private final Object section;
        private final long column, generation;
        private Object task;
        private boolean bound, done;

        private Ticket(Object section, long column) {
            this.section = section;
            this.column = column;
            this.generation = VoxyIngestTracker.generation;
        }
    }

    private record RawScope(Ticket ticket) { }

    private static final class WorkerScope {
        final WorkerScope previous;
        Object task;
        boolean done;
        WorkerScope(WorkerScope previous) { this.previous = previous; }
    }

    public static Ticket begin(Object section, int x, int z) {
        Ticket ticket;
        synchronized (LOCK) {
            ticket = new Ticket(section, column(x, z));
            SECTIONS.merge(section, 1, Integer::sum);
            COLUMNS.merge(ticket.column, 1, Integer::sum);
        }
        StrictLodVisibility.workChanged();
        return ticket;
    }

    public static void complete(Ticket ticket) {
        if (ticket == null) return;
        boolean settled;
        synchronized (LOCK) {
            if (ticket.done) return;
            ticket.done = true;
            if (ticket.task != null && TASKS.get(ticket.task) == ticket) TASKS.remove(ticket.task);
            ticket.task = null;
            // A late old-world callback cannot consume a new-world registration.
            if (ticket.generation != generation) return;
            SECTIONS.computeIfPresent(ticket.section, (key, n) -> n == 1 ? null : n - 1);
            settled = COLUMNS.computeIfPresent(ticket.column, (key, n) -> n == 1 ? null : n - 1) == null;
        }
        StrictLodVisibility.workChanged();
        if (settled) VoxyPredictionBridge.realColumnSettled(
                dev.xantha.vss.common.PositionUtil.unpackX(ticket.column),
                dev.xantha.vss.common.PositionUtil.unpackZ(ticket.column));
    }

    static boolean runRawIngest(Object section, int x, int z,
                                VoxyIngestControl.ThrowingBooleanSupplier action) throws Throwable {
        RawScope previous = RAW.get();
        Ticket ticket = begin(section, x, z);
        RAW.set(new RawScope(ticket));
        boolean accepted = false;
        try {
            accepted = action.getAsBoolean();
            return accepted;
        } finally {
            if (previous == null) RAW.remove(); else RAW.set(previous);
            // Includes TRUE-without-job cap drops and all pre-publication exits.
            if (!accepted || !ticket.bound) complete(ticket);
        }
    }

    /** Injected before publication, so even an immediately running worker finds its ticket. */
    public static void submitted(Object task, Object section) {
        RawScope scope = RAW.get();
        if (scope == null || scope.ticket.section != section) return;
        Ticket ticket = scope.ticket;
        synchronized (LOCK) {
            if (ticket.done || ticket.bound || ticket.generation != generation) return;
            ticket.task = task;
            ticket.bound = true;
            TASKS.put(task, ticket);
        }
    }

    /** Only injected at a verified rawIngest0 publication; the native task may contain a copy. */
    public static void submittedSnapshot(Object task) {
        RawScope scope = RAW.get();
        if (scope != null) submitted(task, scope.ticket.section);
    }

    /** Replaced/evicted tasks and actual worker tasks share the same exact-once settlement. */
    public static void discarded(Object task) {
        Ticket ticket;
        synchronized (LOCK) { ticket = TASKS.get(task); }
        complete(ticket);
    }

    public static Object enterWorker() {
        WorkerScope scope = new WorkerScope(WORKER.get());
        WORKER.set(scope);
        return scope;
    }

    public static void workerTask(Object task) {
        WorkerScope scope = WORKER.get();
        if (scope != null) scope.task = task;
    }

    public static void workerDone() { finishWorker(WORKER.get()); }

    public static void finishWorker(Object token) {
        if (!(token instanceof WorkerScope scope) || scope.done) return;
        scope.done = true;
        try {
            discarded(scope.task);
        } finally {
            scope.task = null;
            if (WORKER.get() == scope) {
                if (scope.previous == null) WORKER.remove(); else WORKER.set(scope.previous);
            }
        }
    }

    /** Called outside the native body's handlers/finally; always rethrow the original failure. */
    public static void workerFailed(Object token, Throwable failure) {
        try {
            finishWorker(token);
        } catch (Throwable cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    public static boolean pendingColumn(int x, int z) { return COLUMNS.containsKey(column(x, z)); }

    static int pendingSection(Object section) {
        synchronized (LOCK) { return SECTIONS.getOrDefault(section, 0); }
    }

    static int taskCount() { synchronized (LOCK) { return TASKS.size(); } }
    static boolean rawScopeActive() { return RAW.get() != null; }
    static boolean workerScopeActive() { return WORKER.get() != null; }

    public static void reset() {
        synchronized (LOCK) {
            generation++;
            TASKS.clear(); SECTIONS.clear(); COLUMNS.clear();
        }
        StrictLodVisibility.workChanged();
    }

    private static long column(int x, int z) {
        return dev.xantha.vss.common.PositionUtil.packPosition(x, z);
    }
}

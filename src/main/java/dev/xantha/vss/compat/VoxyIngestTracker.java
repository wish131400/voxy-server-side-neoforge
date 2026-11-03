package dev.xantha.vss.compat;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.ArrayList;

/** Once-only completion of VSS submissions, keyed by the actual native task. */
public final class VoxyIngestTracker {
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Object, Ticket> TASKS = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Long> PUBLICATIONS = new IdentityHashMap<>();
    private static final IdentityHashMap<Ticket, Boolean> ACTIVE_TICKETS = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Integer> SECTIONS = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> COLUMNS = new ConcurrentHashMap<>();
    private static final IdentityHashMap<Object, Map<Long, Integer>> ENGINE_COLUMNS = new IdentityHashMap<>();
    private static final Map<Long, Integer> UNSCOPED_COLUMNS = new HashMap<>();
    private static final ThreadLocal<RawScope> RAW = new ThreadLocal<>();
    private static final ThreadLocal<WorkerScope> WORKER = new ThreadLocal<>();
    private static volatile long generation;
    private static long authorityGeneration;

    private VoxyIngestTracker() { }

    public static final class Ticket {
        private final Object section;
        private final Object engine;
        private final Consumer<Boolean> completion;
        private final long column, generation;
        private Object task;
        private boolean bound, done;

        private Ticket(Object engine, Object section, long column, Consumer<Boolean> completion) {
            this.engine = engine;
            this.section = section;
            this.column = column;
            this.completion = completion;
            this.generation = VoxyIngestTracker.generation;
        }
    }

    private record RawScope(Ticket ticket) { }

    private static final class WorkerScope {
        final WorkerScope previous;
        Object task;
        long generation = -1;
        boolean committed;
        boolean done;
        WorkerScope(WorkerScope previous) { this.previous = previous; }
    }

    public static Ticket begin(Object section, int x, int z) {
        return begin(null, section, x, z, null);
    }

    static Ticket begin(Object engine, Object section, int x, int z, Consumer<Boolean> completion) {
        Ticket ticket;
        synchronized (LOCK) {
            ticket = new Ticket(engine, section, column(x, z), completion);
            ACTIVE_TICKETS.put(ticket, Boolean.TRUE);
            SECTIONS.merge(section, 1, Integer::sum);
            COLUMNS.merge(ticket.column, 1, Integer::sum);
            if (engine != null) ENGINE_COLUMNS.computeIfAbsent(engine, ignored -> new HashMap<>())
                    .merge(ticket.column, 1, Integer::sum);
            else UNSCOPED_COLUMNS.merge(ticket.column, 1, Integer::sum);
        }
        StrictLodVisibility.workChanged();
        return ticket;
    }

    public static void complete(Ticket ticket) {
        complete(ticket, true);
    }

    private static void complete(Ticket ticket, boolean successful) {
        if (ticket == null) return;
        boolean settled;
        synchronized (LOCK) {
            if (ticket.done) return;
            ticket.done = true;
            ACTIVE_TICKETS.remove(ticket);
            if (ticket.task != null && TASKS.get(ticket.task) == ticket) TASKS.remove(ticket.task);
            ticket.task = null;
            // A late old-world callback cannot consume a new-world registration.
            if (ticket.generation != generation) return;
            SECTIONS.computeIfPresent(ticket.section, (key, n) -> n == 1 ? null : n - 1);
            settled = COLUMNS.computeIfPresent(ticket.column, (key, n) -> n == 1 ? null : n - 1) == null;
            if (ticket.engine != null) {
                Map<Long, Integer> columns = ENGINE_COLUMNS.get(ticket.engine);
                settled = columns != null
                        && columns.computeIfPresent(ticket.column, (key, n) -> n == 1 ? null : n - 1) == null;
                if (columns != null && columns.isEmpty()) ENGINE_COLUMNS.remove(ticket.engine);
            } else settled = UNSCOPED_COLUMNS.computeIfPresent(ticket.column,
                    (key, n) -> n == 1 ? null : n - 1) == null;
        }
        StrictLodVisibility.workChanged();
        try {
            if (ticket.completion != null) ticket.completion.accept(successful);
        } finally {
            if (settled) {
                int x = dev.xantha.vss.common.PositionUtil.unpackX(ticket.column);
                int z = dev.xantha.vss.common.PositionUtil.unpackZ(ticket.column);
                if (ticket.engine == null) VoxyPredictionBridge.realColumnSettled(x, z);
                else VoxyPredictionBridge.realColumnSettled(ticket.engine, x, z);
            }
        }
    }

    static boolean runRawIngest(Object section, int x, int z,
                                VoxyIngestControl.ThrowingBooleanSupplier action) throws Throwable {
        return runRawIngest(null, section, x, z, null, action);
    }

    static boolean runRawIngest(Object engine, Object section, int x, int z, Consumer<Boolean> completion,
                                VoxyIngestControl.ThrowingBooleanSupplier action) throws Throwable {
        RawScope previous = RAW.get();
        Ticket ticket = begin(engine, section, x, z, completion);
        RAW.set(new RawScope(ticket));
        boolean accepted = false;
        try {
            accepted = action.getAsBoolean();
            return accepted;
        } finally {
            if (previous == null) RAW.remove(); else RAW.set(previous);
            // Includes TRUE-without-job cap drops and all pre-publication exits.
            if (!accepted || !ticket.bound) complete(ticket, false);
        }
    }

    /** Injected before publication, so even an immediately running worker finds its ticket. */
    public static void submitted(Object task, Object section) {
        published(task);
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
        published(task);
        RawScope scope = RAW.get();
        if (scope != null) submitted(task, scope.ticket.section);
    }

    /** Replaced/evicted tasks and actual worker tasks share the same exact-once settlement. */
    public static void discarded(Object task) {
        Ticket ticket;
        synchronized (LOCK) { ticket = TASKS.get(task); PUBLICATIONS.remove(task); }
        complete(ticket, false);
    }

    /** Native local and VSS submissions share the same world-session lifetime. */
    public static void published(Object task) {
        if (task != null) synchronized (LOCK) { PUBLICATIONS.put(task, authorityGeneration); }
    }

    public static Object enterWorker() {
        WorkerScope scope = new WorkerScope(WORKER.get());
        WORKER.set(scope);
        return scope;
    }

    public static void workerTask(Object task) {
        WorkerScope scope = WORKER.get();
        if (scope != null) {
            scope.task = task;
            synchronized (LOCK) { scope.generation = PUBLICATIONS.getOrDefault(task, -1L); }
        }
    }

    private record Position(java.lang.reflect.Field x, java.lang.reflect.Field y, java.lang.reflect.Field z) { }
    private static final ClassValue<Position> POSITIONS = new ClassValue<>() {
        @Override protected Position computeValue(Class<?> type) {
            try { return new Position(type.getField("x"),type.getField("y"),type.getField("z")); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported Voxy voxel position",failure); }
        }
    };

    /** Injected only after WorldUpdater.insertUpdate returns successfully. */
    public static void committed(Object engine,Object voxels) {
        WorkerScope scope=WORKER.get();
        if(scope==null)return;
        int x,y,z,saves;
        boolean changed;
        synchronized(LOCK) {
            if(scope.generation!=authorityGeneration)return;
            scope.committed=true;
            try {
                Position position=POSITIONS.get(voxels.getClass());
                x=position.x.getInt(voxels);y=position.y.getInt(voxels);z=position.z.getInt(voxels);
            } catch(IllegalAccessException failure) { throw new IllegalStateException("Cannot read committed Voxy position",failure); }
            changed=VoxyRealCoverage.of(engine).mark(x,y,z);
            saves=VoxyRealIngestAccess.committed(engine,x,y,z);
        }
        if(changed)VoxyPredictionBridge.realSectionCommitted(engine,x,y,z);
        VoxyRealIngestAccess.save(engine,x,y,z,saves);
    }

    public static void workerDone() { finishWorker(WORKER.get()); }

    public static void finishWorker(Object token) {
        finishWorker(token, true);
    }

    private static void finishWorker(Object token, boolean successful) {
        if (!(token instanceof WorkerScope scope) || scope.done) return;
        scope.done = true;
        try {
            Ticket ticket;
            synchronized (LOCK) { ticket = TASKS.get(scope.task); }
            complete(ticket, successful && scope.committed);
            synchronized (LOCK) { PUBLICATIONS.remove(scope.task); }
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
            finishWorker(token, false);
        } catch (Throwable cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    public static boolean pendingColumn(int x, int z) { return COLUMNS.containsKey(column(x, z)); }

    static boolean pendingColumn(Object engine, int x, int z) {
        synchronized (LOCK) {
            Map<Long, Integer> columns = ENGINE_COLUMNS.get(engine);
            long column = column(x, z);
            return UNSCOPED_COLUMNS.containsKey(column) || columns != null && columns.containsKey(column);
        }
    }

    static int pendingSection(Object section) {
        synchronized (LOCK) { return SECTIONS.getOrDefault(section, 0); }
    }

    static int taskCount() { synchronized (LOCK) { return TASKS.size(); } }
    static boolean rawScopeActive() { return RAW.get() != null; }
    static boolean workerScopeActive() { return WORKER.get() != null; }
    static long generation() { synchronized (LOCK) { return generation; } }

    public static void reset() {
        ArrayList<Ticket> retired;
        synchronized (LOCK) {
            generation++;
            retired = new ArrayList<>(ACTIVE_TICKETS.keySet());
            for (Ticket ticket : retired) { ticket.done = true; ticket.task = null; }
            ACTIVE_TICKETS.clear();
            TASKS.clear(); SECTIONS.clear(); COLUMNS.clear(); ENGINE_COLUMNS.clear(); UNSCOPED_COLUMNS.clear();
        }
        StrictLodVisibility.workChanged();
        // A same-world renderer reset must not strand native timestamp entries as completed.
        for (Ticket ticket : retired) if (ticket.completion != null) ticket.completion.accept(false);
    }

    /** Renderer resets retire queue readiness only; a world disconnect retires real ownership too. */
    static void resetWorld() {
        synchronized (LOCK) {
            authorityGeneration++;
            PUBLICATIONS.clear();
            VoxyRealCoverage.reset();
            VoxyRealCoveragePersistence.reset();
        }
        reset();
    }

    private static long column(int x, int z) {
        return dev.xantha.vss.common.PositionUtil.packPosition(x, z);
    }
}

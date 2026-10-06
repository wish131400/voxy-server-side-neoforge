package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.common.processing.EncodedColumnData;
import java.util.LinkedHashMap;
import java.util.Map;

/** A lazy area cursor with no admission quota; background readers claim positions as they run. */
final class ChunkyWorkQueue {
    enum Stage { READING, GENERATION, GENERATING, PERSIST, WRITING }

    static final class Work {
        final int requestId;
        final int chunkX;
        final int chunkZ;
        Stage stage = Stage.READING;
        int generationAttempts;
        int writeAttempts;
        boolean reused;
        EncodedColumnData columnData;

        Work(int requestId, int chunkX, int chunkZ) {
            this.requestId = requestId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private final ChunkyArea area;
    private final Map<Integer, Work> inFlight = new LinkedHashMap<>();
    private long nextIndex;
    private long completed;
    private long reused;
    private long failed;
    private boolean closed;

    ChunkyWorkQueue(ChunkyArea area) { this.area = area; }

    synchronized Work reserve() {
        if (closed || nextIndex >= area.columnCount()) return null;
        long index = nextIndex++;
        Work work = new Work((int) index + 1, area.chunkX(index), area.chunkZ(index));
        inFlight.put(work.requestId, work);
        return work;
    }

    synchronized Work get(int requestId) { return inFlight.get(requestId); }
    synchronized int inFlightCount() { return inFlight.size(); }
    synchronized long completed() { return completed; }
    synchronized long reused() { return reused; }
    synchronized long failed() { return failed; }
    synchronized long processed() { return completed + failed; }
    synchronized boolean allReserved() { return nextIndex == area.columnCount(); }
    synchronized boolean finished() { return allReserved() && inFlight.isEmpty(); }

    synchronized void close() {
        closed = true;
        inFlight.values().forEach(work -> work.columnData = null);
        inFlight.clear();
    }

    synchronized void finish(Work work, boolean success) {
        // A late or repeated callback cannot complete a replacement task.
        if (!inFlight.remove(work.requestId, work)) return;
        work.columnData = null;
        if (success) {
            completed++;
            if (work.reused) reused++;
        } else {
            failed++;
        }
    }
}

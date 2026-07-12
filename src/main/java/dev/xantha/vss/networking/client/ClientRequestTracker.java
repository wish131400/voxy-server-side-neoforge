package dev.xantha.vss.networking.client;

import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

final class ClientRequestTracker {
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final Long2IntOpenHashMap positionToRequestId = new Long2IntOpenHashMap();
    private final Int2LongOpenHashMap requestIdToPosition = new Int2LongOpenHashMap();
    private final Long2LongOpenHashMap requestSendTimes = new Long2LongOpenHashMap();
    private final Int2LongOpenHashMap requestDeadlineTimes = new Int2LongOpenHashMap();
    private final PriorityQueue<RequestDeadline> requestDeadlines = new PriorityQueue<>();
    private final LongOpenHashSet generationInFlight = new LongOpenHashSet();
    private final LongOpenHashSet dirtyRefreshInFlight = new LongOpenHashSet();
    private final IntConsumer cancelSender;

    private int nextRequestId;
    private int deadlineTombstones;

    ClientRequestTracker() {
        this(ClientRequestTracker::sendCancelPacket);
    }

    ClientRequestTracker(IntConsumer cancelSender) {
        this.cancelSender = cancelSender;
        positionToRequestId.defaultReturnValue(-1);
        requestIdToPosition.defaultReturnValue(Long.MIN_VALUE);
        requestSendTimes.defaultReturnValue(0L);
        requestDeadlineTimes.defaultReturnValue(Long.MIN_VALUE);
    }

    int track(long packed, boolean generationRequest, boolean dirtyRefreshRequest, long timeoutNanos, long nowNanos) {
        int requestId = allocateRequestId();
        inFlight.add(packed);
        if (generationRequest) {
            generationInFlight.add(packed);
        }
        if (dirtyRefreshRequest) {
            dirtyRefreshInFlight.add(packed);
        }
        positionToRequestId.put(packed, requestId);
        requestIdToPosition.put(requestId, packed);
        requestSendTimes.put(packed, nowNanos);
        long timeoutAtNanos = saturatedAdd(nowNanos, Math.max(0L, timeoutNanos));
        requestDeadlineTimes.put(requestId, timeoutAtNanos);
        requestDeadlines.add(new RequestDeadline(packed, requestId, timeoutAtNanos));
        return requestId;
    }

    void refreshDeadline(int requestId, long timeoutNanos, long nowNanos) {
        long packed = requestIdToPosition.get(requestId);
        if (packed == Long.MIN_VALUE || positionToRequestId.get(packed) != requestId) {
            return;
        }
        long timeoutAtNanos = saturatedAdd(nowNanos, Math.max(0L, timeoutNanos));
        requestDeadlineTimes.put(requestId, timeoutAtNanos);
        requestDeadlines.add(new RequestDeadline(packed, requestId, timeoutAtNanos));
        deadlineTombstones++;
        compactDeadlineHeapIfNeeded();
    }

    long remove(int requestId) {
        long packed = requestIdToPosition.remove(requestId);
        if (packed == Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        removeTrackedPosition(packed);
        return packed;
    }

    void cancel(long packed) {
        int requestId = removeTrackedPosition(packed);
        if (requestId != -1) {
            cancelSender.accept(requestId);
        }
    }

    void cancelAll() {
        var iterator = requestIdToPosition.keySet().intIterator();
        while (iterator.hasNext()) {
            int requestId = iterator.nextInt();
            cancelSender.accept(requestId);
        }
    }

    List<TimedOutRequest> drainTimedOut(long now) {
        ArrayList<TimedOutRequest> timedOut = new ArrayList<>();
        while (!requestDeadlines.isEmpty() && requestDeadlines.peek().isDue(now)) {
            RequestDeadline deadline = requestDeadlines.poll();
            long packed = deadline.packed();
            if (positionToRequestId.get(packed) != deadline.requestId()
                    || requestIdToPosition.get(deadline.requestId()) != packed
                    || !requestSendTimes.containsKey(packed)
                    || requestDeadlineTimes.get(deadline.requestId()) != deadline.timeoutAtNanos()) {
                deadlineTombstones = Math.max(0, deadlineTombstones - 1);
                continue;
            }
            boolean generationRequest = generationInFlight.contains(packed);
            boolean dirtyRefreshRequest = dirtyRefreshInFlight.contains(packed);
            int requestId = removeTrackedPosition(packed);
            if (requestId != -1) {
                cancelSender.accept(requestId);
            }
            timedOut.add(new TimedOutRequest(packed, generationRequest, dirtyRefreshRequest));
        }
        compactDeadlineHeapIfNeeded();
        return timedOut;
    }

    boolean contains(long packed) {
        return inFlight.contains(packed);
    }

    boolean isDirtyRefreshRequest(int requestId) {
        long packed = requestIdToPosition.get(requestId);
        return packed != Long.MIN_VALUE && dirtyRefreshInFlight.contains(packed);
    }

    long positionFor(int requestId) {
        return requestIdToPosition.get(requestId);
    }

    boolean matches(int requestId, long packed) {
        return requestIdToPosition.get(requestId) == packed
                && positionToRequestId.get(packed) == requestId;
    }

    int requestIdFor(long packed) {
        return positionToRequestId.get(packed);
    }

    void cancelRequest(int requestId) {
        long packed = requestIdToPosition.get(requestId);
        if (packed == Long.MIN_VALUE || !matches(requestId, packed)) {
            return;
        }
        removeTrackedPosition(packed);
        cancelSender.accept(requestId);
    }

    boolean isGenerationRequest(int requestId) {
        long packed = requestIdToPosition.get(requestId);
        return packed != Long.MIN_VALUE && generationInFlight.contains(packed);
    }

    boolean isDirtyRefreshPosition(long packed) {
        return dirtyRefreshInFlight.contains(packed);
    }

    int size() {
        return inFlight.size();
    }

    int dirtyRefreshSize() {
        return dirtyRefreshInFlight.size();
    }

    int generationSize() {
        return generationInFlight.size();
    }

    int nextRequestId() {
        return nextRequestId;
    }

    void restoreNextRequestId(int nextRequestId) {
        this.nextRequestId = nextRequestId;
    }

    void forEachInFlight(LongConsumer consumer) {
        var iterator = inFlight.longIterator();
        while (iterator.hasNext()) {
            consumer.accept(iterator.nextLong());
        }
    }

    void forEachGenerationInFlight(LongConsumer consumer) {
        var iterator = generationInFlight.longIterator();
        while (iterator.hasNext()) {
            consumer.accept(iterator.nextLong());
        }
    }

    int deadlineHeapSize() {
        return requestDeadlines.size();
    }

    void clear() {
        inFlight.clear();
        positionToRequestId.clear();
        requestIdToPosition.clear();
        requestSendTimes.clear();
        requestDeadlineTimes.clear();
        requestDeadlines.clear();
        generationInFlight.clear();
        dirtyRefreshInFlight.clear();
        deadlineTombstones = 0;
    }

    private int allocateRequestId() {
        int candidate = nextRequestId < 0 ? 0 : nextRequestId;
        int start = candidate;
        do {
            if (!requestIdToPosition.containsKey(candidate)) {
                nextRequestId = candidate == Integer.MAX_VALUE ? 0 : candidate + 1;
                return candidate;
            }
            candidate = candidate == Integer.MAX_VALUE ? 0 : candidate + 1;
        } while (candidate != start);
        throw new IllegalStateException("No free VSS request ids");
    }

    private static long saturatedAdd(long value, long increment) {
        return increment > 0L && value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE
                : value + increment;
    }

    private int removeTrackedPosition(long packed) {
        int requestId = positionToRequestId.remove(packed);
        if (requestId != -1) {
            requestIdToPosition.remove(requestId);
            requestDeadlineTimes.remove(requestId);
        }
        requestSendTimes.remove(packed);
        if (inFlight.remove(packed)) {
            deadlineTombstones++;
        }
        generationInFlight.remove(packed);
        dirtyRefreshInFlight.remove(packed);
        compactDeadlineHeapIfNeeded();
        return requestId;
    }

    private void compactDeadlineHeapIfNeeded() {
        if (deadlineTombstones <= 0 || deadlineTombstones * 2 < requestDeadlines.size()) {
            return;
        }
        PriorityQueue<RequestDeadline> compacted = new PriorityQueue<>(Math.max(1, requestDeadlines.size()));
        for (RequestDeadline deadline : requestDeadlines) {
            long packed = deadline.packed();
            if (positionToRequestId.get(packed) == deadline.requestId()
                    && requestIdToPosition.get(deadline.requestId()) == packed
                    && requestSendTimes.containsKey(packed)
                    && requestDeadlineTimes.get(deadline.requestId()) == deadline.timeoutAtNanos()) {
                compacted.add(deadline);
            }
        }
        requestDeadlines.clear();
        requestDeadlines.addAll(compacted);
        deadlineTombstones = 0;
    }

    private static void sendCancelPacket(int requestId) {
        try {
            VSSClientNetworking.sendCancelRequest(new CancelRequestC2SPayload(requestId));
        } catch (Exception ignored) {
        }
    }

    record TimedOutRequest(long packed, boolean generationRequest, boolean dirtyRefreshRequest) {
    }

    private record RequestDeadline(long packed, int requestId, long timeoutAtNanos) implements Comparable<RequestDeadline> {
        private boolean isDue(long nowNanos) {
            return timeoutAtNanos - nowNanos <= 0L;
        }

        @Override
        public int compareTo(RequestDeadline other) {
            return Long.compare(timeoutAtNanos, other.timeoutAtNanos);
        }
    }
}

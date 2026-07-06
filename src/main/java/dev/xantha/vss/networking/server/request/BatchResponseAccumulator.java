package dev.xantha.vss.networking.server.request;

import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;

public final class BatchResponseAccumulator {
    private final byte[] responseTypes;
    private final int[] requestIds;
    private int count;

    public BatchResponseAccumulator(int capacity) {
        int clampedCapacity = Math.max(0, capacity);
        responseTypes = new byte[clampedCapacity];
        requestIds = new int[clampedCapacity];
    }

    public void add(byte responseType, int requestId) {
        if (count >= responseTypes.length) {
            return;
        }
        responseTypes[count] = responseType;
        requestIds[count] = requestId;
        count++;
    }

    public boolean hasResponses() {
        return count > 0;
    }

    public int count() {
        return count;
    }

    public BatchResponseS2CPayload toPayload() {
        byte[] trimmedTypes = new byte[count];
        int[] trimmedIds = new int[count];
        System.arraycopy(responseTypes, 0, trimmedTypes, 0, count);
        System.arraycopy(requestIds, 0, trimmedIds, 0, count);
        return new BatchResponseS2CPayload(trimmedTypes, trimmedIds, count);
    }
}

package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.LongBinaryOperator;

/** Bounded, per-tick availability notices using the existing versioned refresh
 * protocol. These are persisted versions, not new dirty versions: announcing a
 * finished column must never invalidate the data that the client is about to read. */
public final class ChunkyColumnAvailability {
    static final int BATCH_SIZE = Math.min(1024, VSSConstants.MAX_DIRTY_COLUMN_POSITIONS);
    private final LinkedHashMap<Long, Long> pending = new LinkedHashMap<>();
    private final Consumer<DirtyColumnsS2CPayload> send;

    public ChunkyColumnAvailability(Consumer<DirtyColumnsS2CPayload> send) { this.send = send; }

    public void available(int cx, int cz, long persistedVersion) {
        if (persistedVersion <= 0) return;
        pending.merge(PositionUtil.packPosition(cx, cz), persistedVersion, Math::max);
        if (pending.size() >= BATCH_SIZE) flush();
    }

    /** Called every tick, including the tick completing the last column. */
    public void flush() {
        if (pending.isEmpty()) return;
        long[] positions = new long[pending.size()], versions = new long[pending.size()];
        int index = 0;
        for (var entry : pending.entrySet()) {
            positions[index] = entry.getKey(); versions[index++] = entry.getValue();
        }
        pending.clear();
        send.accept(new DirtyColumnsS2CPayload(positions, versions));
    }

    public void clear() { pending.clear(); }

    /** Distance/dimension are checked by the caller. Only notify clients whose
     * acknowledged version is older; absence (zero) must also receive the notice. */
    public static DirtyColumnsS2CPayload missingForPlayer(DirtyColumnsS2CPayload batch,
            int playerCx, int playerCz, int maxDistance, LongBinaryOperator knownVersion) {
        long[] positions = new long[batch.dirtyPositions().length];
        long[] versions = new long[positions.length];
        int count = 0;
        for (int i = 0; i < positions.length; i++) {
            long packed = batch.dirtyPositions()[i], version = batch.dirtyTimestamps()[i];
            int cx = PositionUtil.unpackX(packed), cz = PositionUtil.unpackZ(packed);
            if (PositionUtil.chebyshevDistance(cx, cz, playerCx, playerCz) > maxDistance
                    || knownVersion.applyAsLong(cx, cz) >= version) continue;
            positions[count] = packed; versions[count++] = version;
        }
        return new DirtyColumnsS2CPayload(java.util.Arrays.copyOf(positions, count), java.util.Arrays.copyOf(versions, count));
    }
}

package dev.xantha.vss.networking.server.state;


import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import dev.xantha.vss.common.PositionUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PreloadColumnFrontier {
    private static final Comparator<BucketedColumn> COLUMN_ORDER = Comparator
            .comparingLong(BucketedColumn::distanceSquared)
            .thenComparingInt(column -> column.column().chunkX())
            .thenComparingInt(column -> column.column().chunkZ());

    private final Map<Long, PlayerRequestState.PreloadColumn> columns = new HashMap<>();
    private final Set<Long> positions = new HashSet<>();
    private final TreeMap<Integer, NavigableSet<BucketedColumn>> columnsByRing = new TreeMap<>();
    private final TreeMap<Integer, Integer> regionScansInFlight = new TreeMap<>();
    private int readsInFlight;
    private int frontierRing = -1;
    private int frontierPlayerCx = Integer.MIN_VALUE;
    private int frontierPlayerCz = Integer.MIN_VALUE;

    public synchronized void addColumns(ArrayList<PersistentColumnLodStore.ExistingColumn> existingColumns) {
        for (PersistentColumnLodStore.ExistingColumn column : existingColumns) {
            addColumn(new PlayerRequestState.PreloadColumn(column.chunkX(), column.chunkZ(), column.timestamp()));
        }
    }

    public synchronized void addColumn(PlayerRequestState.PreloadColumn column) {
        long packed = PositionUtil.packPosition(column.chunkX(), column.chunkZ());
        PlayerRequestState.PreloadColumn existing = columns.get(packed);
        if (existing != null) {
            if (column.timestamp() > existing.timestamp()) {
                columns.put(packed, column);
                if (hasFrontierCenter()) {
                    removeBucketedColumn(existing);
                    addBucketedColumn(column);
                }
            }
            return;
        }
        if (positions.add(packed)) {
            columns.put(packed, column);
            if (hasFrontierCenter()) {
                addBucketedColumn(column);
            }
        }
    }

    public synchronized PlayerRequestState.PreloadColumn pollFrontierColumn(int playerCx, int playerCz, int ringSlack) {
        if (columns.isEmpty()) {
            return null;
        }
        ensureFrontierCenter(playerCx, playerCz);

        Integer nearestRing = nearestColumnRing();
        if (nearestRing == null) {
            return null;
        }
        int safeSlack = Math.max(0, ringSlack);
        if (frontierRing < nearestRing
                || (readsInFlight <= 0 && !hasColumnWithinRing(frontierRing))) {
            frontierRing = nearestRing + safeSlack;
        }

        int allowedRing = frontierRing;
        int closestRegionScan = closestRegionScanDistance();
        if (closestRegionScan != Integer.MAX_VALUE) {
            allowedRing = Math.min(allowedRing, closestRegionScan + safeSlack);
        }

        PlayerRequestState.PreloadColumn column = pollBestColumnWithinRing(allowedRing);
        if (column != null) {
            return column;
        }
        if (readsInFlight > 0 || (closestRegionScan != Integer.MAX_VALUE && closestRegionScan + safeSlack < nearestRing)) {
            return null;
        }

        frontierRing = nearestRing + safeSlack;
        return pollBestColumnWithinRing(frontierRing);
    }

    public synchronized void beginColumnRead() {
        readsInFlight++;
    }

    public synchronized void finishColumnRead() {
        readsInFlight = Math.max(0, readsInFlight - 1);
    }

    public synchronized void beginRegionScan(int minimumChunkDistance) {
        regionScansInFlight.merge(Math.max(0, minimumChunkDistance), 1, Integer::sum);
    }

    public synchronized void finishRegionScan(int minimumChunkDistance) {
        int key = Math.max(0, minimumChunkDistance);
        Integer count = regionScansInFlight.get(key);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            regionScansInFlight.remove(key);
        } else {
            regionScansInFlight.put(key, count - 1);
        }
    }

    public synchronized int count() {
        return columns.size();
    }

    public synchronized void clear() {
        columns.clear();
        positions.clear();
        columnsByRing.clear();
        regionScansInFlight.clear();
        readsInFlight = 0;
        frontierRing = -1;
        frontierPlayerCx = Integer.MIN_VALUE;
        frontierPlayerCz = Integer.MIN_VALUE;
    }

    private void ensureFrontierCenter(int playerCx, int playerCz) {
        if (playerCx == frontierPlayerCx && playerCz == frontierPlayerCz && !columnsByRing.isEmpty()) {
            return;
        }
        frontierPlayerCx = playerCx;
        frontierPlayerCz = playerCz;
        frontierRing = -1;
        rebuildBuckets();
    }

    private boolean hasFrontierCenter() {
        return frontierPlayerCx != Integer.MIN_VALUE && frontierPlayerCz != Integer.MIN_VALUE;
    }

    private void rebuildBuckets() {
        columnsByRing.clear();
        for (PlayerRequestState.PreloadColumn column : columns.values()) {
            addBucketedColumn(column);
        }
    }

    private void addBucketedColumn(PlayerRequestState.PreloadColumn column) {
        int dx = column.chunkX() - frontierPlayerCx;
        int dz = column.chunkZ() - frontierPlayerCz;
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        long distanceSquared = (long) dx * dx + (long) dz * dz;
        columnsByRing.computeIfAbsent(ring, ignored -> new TreeSet<>(COLUMN_ORDER))
                .add(new BucketedColumn(column, distanceSquared));
    }

    private void removeBucketedColumn(PlayerRequestState.PreloadColumn column) {
        int dx = column.chunkX() - frontierPlayerCx;
        int dz = column.chunkZ() - frontierPlayerCz;
        int ring = Math.max(Math.abs(dx), Math.abs(dz));
        NavigableSet<BucketedColumn> bucket = columnsByRing.get(ring);
        if (bucket == null) {
            return;
        }
        long distanceSquared = (long) dx * dx + (long) dz * dz;
        bucket.remove(new BucketedColumn(column, distanceSquared));
        if (bucket.isEmpty()) {
            columnsByRing.remove(ring);
        }
    }

    private Integer nearestColumnRing() {
        return columnsByRing.isEmpty() ? null : columnsByRing.firstKey();
    }

    private boolean hasColumnWithinRing(int maxRing) {
        if (maxRing < 0) {
            return false;
        }
        return columnsByRing.floorKey(maxRing) != null;
    }

    private int closestRegionScanDistance() {
        return regionScansInFlight.isEmpty() ? Integer.MAX_VALUE : regionScansInFlight.firstKey();
    }

    private PlayerRequestState.PreloadColumn pollBestColumnWithinRing(int maxRing) {
        if (maxRing < 0) {
            return null;
        }
        Map.Entry<Integer, NavigableSet<BucketedColumn>> entry = columnsByRing.firstEntry();
        if (entry == null || entry.getKey() > maxRing) {
            return null;
        }
        BucketedColumn best = entry.getValue().pollFirst();
        if (entry.getValue().isEmpty()) {
            columnsByRing.pollFirstEntry();
        }
        PlayerRequestState.PreloadColumn column = best.column();
        long packed = PositionUtil.packPosition(column.chunkX(), column.chunkZ());
        columns.remove(packed);
        positions.remove(packed);
        return column;
    }

    private record BucketedColumn(PlayerRequestState.PreloadColumn column, long distanceSquared) {
    }
}

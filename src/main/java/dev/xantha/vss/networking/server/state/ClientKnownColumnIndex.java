package dev.xantha.vss.networking.server.state;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ClientKnownColumnIndex {
    private final Map<Object, Long2LongOpenHashMap> columnsByDimension = new HashMap<>();

    public synchronized void updateFromPresence(ResourceKey<Level> dimension, RegionPresenceC2SPayload payload) {
        updateEntries(dimension, payload.reset(), payload.entries());
    }

    public synchronized void updateEntries(
            Object dimension,
            boolean reset,
            List<RegionPresenceC2SPayload.RegionEntry> entries) {
        Long2LongOpenHashMap known = knownColumns(dimension);
        if (reset) {
            known.clear();
        }
        for (RegionPresenceC2SPayload.RegionEntry entry : entries) {
            if (!isValidRegionSnapshot(entry)) {
                continue;
            }
            int baseX = entry.regionX() * RegionPresenceC2SPayload.REGION_SIZE;
            int baseZ = entry.regionZ() * RegionPresenceC2SPayload.REGION_SIZE;
            long[] bitmap = entry.bitmap();
            for (int slot = 0; slot < RegionPresenceC2SPayload.REGION_SLOT_COUNT; slot++) {
                if ((bitmap[slot >>> 6] & (1L << (slot & 63))) == 0L) {
                    int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
                    int cz = baseZ + (slot >>> 5);
                    known.remove(PositionUtil.packPosition(cx, cz));
                }
            }
            int[] slots = entry.slots();
            long[] timestamps = entry.timestamps();
            int count = entry.count();
            for (int i = 0; i < count; i++) {
                int slot = slots[i];
                long timestamp = timestamps[i];
                if (slot < 0 || slot >= RegionPresenceC2SPayload.REGION_SLOT_COUNT || timestamp <= 0L) {
                    continue;
                }
                int cx = baseX + (slot & (RegionPresenceC2SPayload.REGION_SIZE - 1));
                int cz = baseZ + (slot >>> 5);
                known.put(PositionUtil.packPosition(cx, cz), timestamp);
            }
        }
    }

    private static boolean isValidRegionSnapshot(RegionPresenceC2SPayload.RegionEntry entry) {
        if (entry == null
                || entry.count() < 0
                || entry.count() > RegionPresenceC2SPayload.REGION_SLOT_COUNT
                || entry.bitmap() == null
                || entry.bitmap().length < RegionPresenceC2SPayload.REGION_BITMAP_LONGS
                || entry.slots() == null
                || entry.timestamps() == null
                || entry.slots().length < entry.count()
                || entry.timestamps().length < entry.count()) {
            return false;
        }

        long[] expectedBitmap = new long[RegionPresenceC2SPayload.REGION_BITMAP_LONGS];
        for (int i = 0; i < entry.count(); i++) {
            int slot = entry.slots()[i];
            long timestamp = entry.timestamps()[i];
            if (slot < 0 || slot >= RegionPresenceC2SPayload.REGION_SLOT_COUNT || timestamp <= 0L) {
                return false;
            }
            long mask = 1L << (slot & 63);
            int word = slot >>> 6;
            if ((expectedBitmap[word] & mask) != 0L) {
                return false;
            }
            expectedBitmap[word] |= mask;
        }
        for (int i = 0; i < RegionPresenceC2SPayload.REGION_BITMAP_LONGS; i++) {
            if (entry.bitmap()[i] != expectedBitmap[i]) {
                return false;
            }
        }
        return true;
    }

    public synchronized void markKnown(ResourceKey<Level> dimension, long packed, long timestamp) {
        markKnown((Object) dimension, packed, timestamp);
    }

    public synchronized void markKnown(Object dimension, long packed, long timestamp) {
        if (timestamp <= 0L) {
            return;
        }
        Long2LongOpenHashMap known = knownColumns(dimension);
        known.put(packed, Math.max(known.get(packed), timestamp));
    }

    public synchronized boolean isCurrent(ResourceKey<Level> dimension, int cx, int cz, long serverTimestamp) {
        return isCurrent((Object) dimension, cx, cz, serverTimestamp);
    }

    public synchronized boolean isCurrent(Object dimension, int cx, int cz, long serverTimestamp) {
        if (serverTimestamp <= 0L) {
            return false;
        }
        long timestamp = knownTimestamp(dimension, cx, cz);
        return timestamp >= serverTimestamp;
    }

    public synchronized long knownTimestamp(ResourceKey<Level> dimension, int cx, int cz) {
        return knownTimestamp((Object) dimension, cx, cz);
    }

    public synchronized long knownTimestamp(Object dimension, int cx, int cz) {
        Long2LongOpenHashMap known = columnsByDimension.get(dimension);
        return known != null ? known.get(PositionUtil.packPosition(cx, cz)) : 0L;
    }

    public synchronized void clear() {
        columnsByDimension.clear();
    }

    private Long2LongOpenHashMap knownColumns(Object dimension) {
        return columnsByDimension.computeIfAbsent(dimension, ignored -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
    }
}

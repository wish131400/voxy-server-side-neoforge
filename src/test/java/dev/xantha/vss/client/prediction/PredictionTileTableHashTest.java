package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionTileTableHashTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void pagingRetainsImmutableUpdatesAndMatchesAMapAcrossNegativeCoordinatesAndLods() {
        var table = new PredictionTileTable<Integer>(Level.OVERWORLD);
        var expected = new HashMap<PredictionTileManager.PredictionTileKey, Integer>();
        var random = new Random(284);
        for (int round = 0; round < 30; round++) {
            var upserts = new HashMap<PredictionTileManager.PredictionTileKey, Integer>();
            var removed = new ArrayList<PredictionTileManager.PredictionTileKey>();
            for (int i = 0; i < 60; i++) {
                var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,
                        random.nextInt(60) - 30, random.nextInt(60) - 30, random.nextInt(8));
                upserts.put(key, random.nextInt());
            }
            expected.keySet().stream().limit(10).forEach(removed::add);
            var old = table;
            var before = new HashMap<>(expected);
            table = table.changed(upserts, removed);
            removed.forEach(expected::remove); expected.putAll(upserts);
            assertEquals(before, old);
            assertEquals(expected, table);
            for (var entry : expected.entrySet()) assertEquals(entry.getValue(),
                    table.at(entry.getKey().tileX(), entry.getKey().tileZ(), entry.getKey().lod()));
        }
    }

    @Test void copiedPageKeysNoLongerShareTheLowBitsUsedForTheirInitialSlots() throws Exception {
        var entries = new HashMap<PredictionTileManager.PredictionTileKey, Integer>();
        for (int x = -20; x < 20; x++) for (int z = -20; z < 20; z++)
            entries.put(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, x, z, 0), x * 100 + z);
        var table = new PredictionTileTable<Integer>(Level.OVERWORLD).changed(entries, List.of());
        var field = PredictionTileTable.class.getDeclaredField("pages");
        field.setAccessible(true);
        Object[] pages = (Object[]) field.get(table);
        long probes = 0, count = 0;
        int diverse = 0;
        for (Object page : pages) {
            if (page == null) continue;
            var type = page.getClass();
            var keyField = type.getDeclaredField("key");
            var maskField = type.getDeclaredField("mask");
            keyField.setAccessible(true); maskField.setAccessible(true);
            long[] keys = (long[]) keyField.get(page);
            int mask = maskField.getInt(page);
            var lowBits = new HashSet<Integer>();
            for (int at = 0; at <= mask; at++) {
                long key = keys[at];
                if (key == 0) continue;
                long hash = it.unimi.dsi.fastutil.HashCommon.mix(key);
                lowBits.add((int) hash & 31);
                probes += ((at - ((int) hash & mask)) & mask) + 1;
                count++;
            }
            if (lowBits.size() > 1) diverse++;
        }
        assertTrue(diverse > 28);
        assertTrue((double) probes / count < 4, "representative resident pages must not collapse into one low-bit chain");
        System.out.printf(Locale.ROOT, "RESIDENT_PAGE_PROBES entries=%d mean=%.3f%n", count, (double) probes / count);
    }
}

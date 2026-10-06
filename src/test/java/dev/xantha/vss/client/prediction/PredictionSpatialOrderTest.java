package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionSpatialOrderTest {
    @Test void heightAwareOrderingSeparatesStackedBuildingsWithoutMoreCommands() {
        int[] words = new int[2048 * 12];
        for (int q = 0; q < 2048; q++) quad(words, q, q / 8 % 16, q / 8 / 16, q % 8 * 16, 0);
        var before = new PredictionMeshletBounds(words);
        PredictionSpatialOrder.arrange(words, new int[]{0}, new int[]{2048}, new boolean[256]);
        var after = new PredictionMeshletBounds(words);
        assertEquals(before.values.length, after.values.length);
        double a = 0, b = 0;
        for (int i = 0; i < before.values.length; i += 6) { a += before.values[i + 4] - before.values[i + 1]; b += after.values[i + 4] - after.values[i + 1]; }
        assertTrue(b < a * .5, "XYZ ordering should tighten tall meshlets: " + b + "/" + a);
    }

    @Test void spatialOrderingTightensRowStripsWithoutChangingRecordsOrWater() {
        int count = 64 * 64;
        int[] words = new int[(count + 2) * 12];
        for (int i = 0; i < count + 2; i++) quad(words, i, i % 64, i / 64, 80, 0);
        int[] before = words.clone();
        double oldSpan = meanSpan(new PredictionMeshletBounds(Arrays.copyOf(words, count * 12)).values);
        int[][] retained = PredictionSpatialOrder.arrange(words, new int[]{0}, new int[]{count}, new boolean[256]);
        double span = meanSpan(new PredictionMeshletBounds(Arrays.copyOf(words, count * 12)).values);
        assertTrue(span <= oldSpan * .4, "Morton clusters must fix the full-width row strips: " + span + "/" + oldSpan);
        assertArrayEquals(Arrays.copyOfRange(before, count * 12, before.length), Arrays.copyOfRange(words, count * 12, words.length));
        Set<Integer> seen = new HashSet<>();
        for (int q = 0; q < count; q++) {
            int original = words[q * 12 + 8];
            assertTrue(seen.add(original));
            assertArrayEquals(Arrays.copyOfRange(before, original * 12, (original + 1) * 12), Arrays.copyOfRange(words, q * 12, (q + 1) * 12));
        }
        assertEquals(count, retained[3][0]);
        int[] sorted = words.clone();
        PredictionSpatialOrder.arrange(words, new int[]{0}, new int[]{count}, new boolean[256]);
        assertArrayEquals(sorted, words, "warm cache restore must be idempotent");
    }

    @Test void aquaticPrefixesKeepCompleteStalksAndAllNonAquaticGeometry() {
        int count = 32 * 32 * 3;
        int[] words = new int[(count * 2 + 1) * 12];
        for (int face = 0; face < 2; face++) for (int i = 0; i < count; i++) {
            int column = i / 3, height = i % 3;
            quad(words, face * count + i, column % 32, column / 32, 40 + height,
                    (height == 2 ? 2 : 1) | PredictionPackedMesh.FLAG_UNSHADED | ((face + 1) << 24));
        }
        quad(words, count * 2, 7, 7, 50, 1); // Same texture on a solid is never thinned.
        boolean[] aquatic = new boolean[256]; aquatic[1] = aquatic[2] = true;
        int[][] retained = PredictionSpatialOrder.arrange(words, new int[]{0, count}, new int[]{count, count + 1}, aquatic);
        for (int tier = 0; tier < 4; tier++) {
            int expected = count / (1 << (tier * 2));
            assertEquals(expected, retained[tier][0]);
            assertEquals(expected + 1, retained[tier][1]);
            var stalks = new HashMap<Integer, Integer>();
            for (int face = 0; face < 2; face++) for (int q = face * count; q < face * count + retained[tier][face]; q++) {
                int id = words[q * 12 + 8];
                if (id == count * 2) continue;
                stalks.merge((id % count) / 3, 1, Integer::sum);
            }
            assertTrue(stalks.values().stream().allMatch(n -> n == 6), "both faces and all Y segments must survive together");
        }
        assertEquals(3, PredictionSpatialOrder.retainedThrough(1 | PredictionPackedMesh.FLAG_UNSHADED
                | PredictionPackedMesh.FLAG_MODEL_UV, aquatic, 7.5, 7.5));
    }

    @Test void displaySelectionHasHysteresisAndImmediateNearAndScopeRestoration() {
        var lod = new PredictionAquaticLod();
        assertFalse(lod.update(540, 512, false));
        assertTrue(lod.update(545, 512, false)); assertEquals(1, lod.tier());
        assertFalse(lod.update(510, 512, false));
        assertTrue(lod.update(479, 512, false)); assertEquals(0, lod.tier());
        assertTrue(lod.update(5000, 512, false)); assertEquals(3, lod.tier());
        assertTrue(lod.update(5000, 512, true)); assertEquals(0, lod.tier());
        assertEquals(0, PredictionAquaticLod.distance(-96, -96, -128, -128, 64));
        assertEquals(64, PredictionAquaticLod.distance(0, -96, -128, -128, 64));
    }

    static void quad(int[] words, int q, int x, int z, int y, int flags) {
        int p = q * 12;
        words[p] = x | ((x + 1) << 16); words[p + 1] = (x + 1) | (x << 16);
        words[p + 2] = z | (z << 16); words[p + 3] = (z + 1) | ((z + 1) << 16);
        int yy = 32768 + y * 4; words[p + 4] = words[p + 5] = yy | (yy << 16);
        words[p + 6] = flags; words[p + 7] = q * 17; words[p + 8] = q;
        words[p + 9] = q * 31; words[p + 10] = q * 13; words[p + 11] = q * 7;
    }

    static double meanSpan(float[] values) {
        double sum = 0;
        for (int i = 0; i < values.length; i += 6) sum += Math.max(values[i + 3] - values[i], values[i + 5] - values[i + 2]);
        return sum / (values.length / 6);
    }
}

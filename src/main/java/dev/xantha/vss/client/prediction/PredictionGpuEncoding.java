package dev.xantha.vss.client.prediction;

/** Lossless GPU color/light dictionary. Coordinates, quad order and every flag remain exact.
 * Built on publishing/restore workers; no hash table construction in the draw loop.
 */
final class PredictionGpuEncoding {
    private PredictionGpuEncoding() { }
    record Encoded(int[] words, int paletteBaseTexel) { }

    /** Select one pass without decoding an already compact CPU payload. The two opaque
     * spans straddle canonical water; appended display geometry belongs to opaque. */
    static Encoded selectPass(int[] source, int paletteBase, int quads,
                              int waterFirst, int waterCount, boolean water, boolean compact) {
        if (waterFirst < 0 || waterCount < 0 || waterFirst + waterCount > quads)
            throw new IllegalArgumentException("Invalid water interval");
        int count = water ? waterCount : quads - waterCount;
        if (count == 0) return new Encoded(new int[0], 0);
        if (!water && waterCount == 0) return new Encoded(source, paletteBase);
        int stride = paletteBase == 0 ? 12 : 8;
        int[] records = new int[count * stride];
        if (water) System.arraycopy(source, waterFirst * stride, records, 0, records.length);
        else {
            System.arraycopy(source, 0, records, 0, waterFirst * stride);
            System.arraycopy(source, (waterFirst + waterCount) * stride, records,
                    waterFirst * stride, (quads - waterFirst - waterCount) * stride);
        }
        if (paletteBase == 0) return compact ? encode(records) : new Encoded(records, 0);
        int paletteStart = paletteBase * 4;
        int[] remap = new int[(source.length - paletteStart) / 4];
        java.util.Arrays.fill(remap, -1);
        int colors = 0;
        for (int p = 7; p < records.length; p += 8) {
            int id = records[p] >>> 16;
            if (id >= remap.length) throw new IllegalArgumentException("Invalid pass palette id");
            if (remap[id] < 0) remap[id] = colors++;
            records[p] = (records[p] & 65535) | (remap[id] << 16);
        }
        int[] result = java.util.Arrays.copyOf(records, records.length + colors * 4);
        for (int id = 0; id < remap.length; id++) if (remap[id] >= 0)
            System.arraycopy(source, paletteStart + id * 4, result, records.length + remap[id] * 4, 4);
        return new Encoded(result, records.length / 4);
    }

    static Encoded encode(int[] source) {
        if (source.length % 12 != 0) throw new IllegalArgumentException("Invalid quad words");
        int quads = source.length / 12;
        // Small seams retain the legacy layout. Require at least 12.5% payload savings.
        if (quads < 64 || source.length > PredictionMeshCompression.MAX_BYTES / 4)
            return new Encoded(source, 0);
        int maxColors = Math.min(65536, quads * 5 / 8);
        int capacity = 1;
        while (capacity < maxColors * 2) capacity <<= 1;
        int[] representatives = new int[capacity], paletteIds = new int[capacity];
        int colors = 0;
        for (int offset = 0; offset < source.length; offset += 12) {
            if ((source[offset + 8] & 0xffff0000) != 0) return new Encoded(source, 0);
            int slot = slot(source, offset, representatives);
            if (representatives[slot] == 0) {
                if (colors == maxColors) return new Encoded(source, 0);
                representatives[slot] = offset + 1;
                paletteIds[slot] = colors++;
            }
        }
        int paletteStart = quads * 8;
        int[] packed = new int[paletteStart + colors * 4];
        for (int offset = 0, target = 0; offset < source.length; offset += 12, target += 8) {
            int color = paletteIds[slot(source, offset, representatives)];
            System.arraycopy(source, offset, packed, target, 7);
            packed[target + 7] = source[offset + 8] | (color << 16);
        }
        for (int slot = 0; slot < capacity; slot++) if (representatives[slot] != 0) {
            int offset = representatives[slot] - 1, target = paletteStart + paletteIds[slot] * 4;
            packed[target] = source[offset + 7];
            System.arraycopy(source, offset + 9, packed, target + 1, 3);
        }
        return new Encoded(packed, paletteStart / 4);
    }

    private static int slot(int[] words, int offset, int[] table) {
        int hash = words[offset + 7];
        hash = hash * 31 + words[offset + 9];
        hash = hash * 31 + words[offset + 10];
        hash = hash * 31 + words[offset + 11];
        hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
        int slot = hash & (table.length - 1);
        while (table[slot] != 0) {
            int previous = table[slot] - 1;
            if (words[previous + 7] == words[offset + 7]
                    && words[previous + 9] == words[offset + 9]
                    && words[previous + 10] == words[offset + 10]
                    && words[previous + 11] == words[offset + 11]) return slot;
            slot = (slot + 1) & (table.length - 1);
        }
        return slot;
    }

    /** Canonical words for disk caches and CPU consumers; never used by the render loop. */
    static int[] decode(int[] stored, int paletteBaseTexel, int quads) {
        if (paletteBaseTexel == 0) return stored;
        if (quads < 0 || (long)quads * 2 != paletteBaseTexel
                || (long)paletteBaseTexel * 4 >= stored.length || (stored.length & 3) != 0)
            throw new IllegalArgumentException("Invalid GPU palette layout");
        int[] words = new int[Math.multiplyExact(quads, 12)];
        for (int input = 0, output = 0; output < words.length; input += 8, output += 12) {
            int color = paletteBaseTexel * 4 + (stored[input + 7] >>> 16) * 4;
            if (color > stored.length - 4) throw new IllegalArgumentException("Invalid GPU palette index");
            System.arraycopy(stored, input, words, output, 7);
            words[output + 7] = stored[color];
            words[output + 8] = stored[input + 7] & 0xffff;
            System.arraycopy(stored, color + 1, words, output + 9, 3);
        }
        return words;
    }
}

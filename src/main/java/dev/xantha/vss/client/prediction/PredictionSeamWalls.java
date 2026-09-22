package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.DataOutputStream;
import java.io.IOException;

/** Immutable random-access seam records. No decompression or allocation during queries. */
final class PredictionSeamWalls {
    private final int[] words, endpoints;
    private final int stride, heightBase;

    private PredictionSeamWalls(int[] words, int[] endpoints, int stride, int heightBase) {
        this.words = words;
        this.endpoints = endpoints;
        this.stride = stride;
        this.heightBase = heightBase;
    }

    static PredictionSeamWalls encode(int[] source) {
        if (source.length % 5 != 0) throw new IllegalArgumentException("Invalid seam wall records");
        int count = source.length / 5;
        if (count < 64 || Boolean.getBoolean("vss.disableCompactSeams")) return raw(source);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int at = 0; at < source.length; at += 5) {
            min = Math.min(min, Math.min(source[at + 2], source[at + 3]));
            max = Math.max(max, Math.max(source[at + 2], source[at + 3]));
        }
        if ((long) max - min > 65535) return raw(source);

        // Dictionary must save at least 12.5% versus height packing alone.
        // The transient map has at most 65536 entries regardless of mesh size.
        int limit = Math.min(65536, count / 2);
        var ids = new Int2IntOpenHashMap(Math.min(limit, 256));
        ids.defaultReturnValue(-1);
        boolean dictionary = true;
        outer: for (int at = 0; at < source.length; at += 5) {
            for (int i = 0; i < 2; i++) if (!ids.containsKey(source[at + i])) {
                if (ids.size() == limit) { dictionary = false; break outer; }
                ids.put(source[at + i], ids.size());
            }
        }
        int stride = dictionary ? 3 : 4;
        int[] packed = new int[count * stride], endpoints = null;
        if (dictionary) {
            endpoints = new int[ids.size()];
            for (var entry : ids.int2IntEntrySet()) endpoints[entry.getIntValue()] = entry.getIntKey();
        }
        for (int at = 0, to = 0; at < source.length; at += 5, to += stride) {
            if (dictionary) packed[to] = ids.get(source[at]) | ids.get(source[at + 1]) << 16;
            else { packed[to] = source[at]; packed[to + 1] = source[at + 1]; }
            packed[to + stride - 2] = (source[at + 2] - min) | (source[at + 3] - min) << 16;
            packed[to + stride - 1] = source[at + 4];
        }
        return new PredictionSeamWalls(packed, endpoints, stride, min);
    }

    static PredictionSeamWalls raw(int[] words) { return new PredictionSeamWalls(words, null, 5, 0); }
    int count() { return words.length / stride; }
    int stride() { return stride; }
    int[] words() { return words; }
    int[] endpoints() { return endpoints; }
    int heightBase() { return heightBase; }
    long retainedHeapBytes() { return 48L + words.length * 4L + (endpoints == null ? 0 : 16L + endpoints.length * 4L); }
    int firstBits(int record) { int at = record * stride; return stride == 3 ? endpoints[words[at] & 65535] : words[at]; }
    int lastBits(int record) { int at = record * stride; return stride == 3 ? endpoints[words[at] >>> 16] : words[at + 1]; }
    int flags(int record) { return words[record * stride + stride - 1]; }
    int bottom(int record) { int at = record * stride; return stride == 5 ? words[at + 2] : heightBase + (words[at + stride - 2] & 65535); }
    int top(int record) { int at = record * stride; return stride == 5 ? words[at + 3] : heightBase + (words[at + stride - 2] >>> 16); }

    /** Preserve the existing disk format without allocating an expanded array. */
    void writeCache(DataOutputStream out) throws IOException {
        out.writeInt(count() * 5);
        if (stride == 5) { for (int word : words) out.writeInt(word); return; }
        for (int record = 0; record < count(); record++) {
            out.writeInt(firstBits(record)); out.writeInt(lastBits(record));
            out.writeInt(bottom(record)); out.writeInt(top(record)); out.writeInt(flags(record));
        }
    }
}

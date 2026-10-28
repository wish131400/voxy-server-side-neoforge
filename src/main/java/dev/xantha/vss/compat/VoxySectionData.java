package dev.xantha.vss.compat;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/** Immutable run storage for disposable render snapshots. Values include light and biome bits. */
public final class VoxySectionData {
    private final long[] values;
    private final int[] ends;
    private WeakReference<long[]> expanded;

    private VoxySectionData(long[] values, int[] ends, long[] original) {
        this.values = values; this.ends = ends;
        expanded = new WeakReference<>(original);
    }

    public static VoxySectionData compact(long[] data) {
        return compact(data, false);
    }

    /** The caller may immediately reuse its scratch array after this method returns. */
    public static VoxySectionData compactCopy(long[] data) {
        return compact(data, true);
    }

    private static VoxySectionData compact(long[] data, boolean copy) {
        if (data.length != 32768) throw new IllegalArgumentException("A native section has 32768 voxels");
        int runs = 1;
        for (int i = 1; i < data.length; i++) if (data[i] != data[i - 1]) runs++;
        if (runs * 12L >= data.length * 8L) {
            long[] owned = copy ? data.clone() : data;
            return new VoxySectionData(owned, null, owned);
        }
        long[] values = new long[runs]; int[] ends = new int[runs]; int at = 0;
        for (int i = 1; i <= data.length; i++) if (i == data.length || data[i] != data[i - 1]) {
            values[at] = data[i - 1]; ends[at++] = i;
        }
        return new VoxySectionData(values, ends, copy ? null : data);
    }

    public long bytes() { return 80L + values.length * 8L + (ends == null ? 0 : 16L + ends.length * 4L); }

    public void copyTo(long[] target) {
        if (ends == null) { System.arraycopy(values, 0, target, 0, values.length); return; }
        int start = 0;
        for (int i = 0; i < ends.length; i++) { Arrays.fill(target, start, ends[i], values[i]); start = ends[i]; }
    }

    /** Reads a plane directly from runs; never materializes a 256 KiB expansion. */
    public void copyFace(int face,long[] target) {
        int run=0;
        for(int i=0;i<1024;i++) {
            int index=VoxyFaceData.index(face,i);
            if(ends!=null)while(index>=ends[run])run++;
            target[i]=values[ends==null ? index : run];
        }
    }

    /** Compatibility readers may share a live expansion; the cache itself retains only compact data. */
    public synchronized long[] array() {
        if (ends == null) return values;
        long[] data = expanded.get();
        if (data == null) { data = new long[32768]; copyTo(data); expanded = new WeakReference<>(data); }
        return data;
    }
}

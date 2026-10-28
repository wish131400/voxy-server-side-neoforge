package dev.xantha.vss.compat;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/** Immutable run storage for disposable render snapshots. Values include light and biome bits. */
public final class VoxySectionData {
    // Only run boundaries, never voxel/state/world references. Avoid a second 32K scan
    // for ordinary terrain without keeping another dense section beside the cache.
    private static final int MAX_BOUNDARIES = 21846; // Dense sections become cheaper at this run count.
    private static final ThreadLocal<int[]> BOUNDARIES = ThreadLocal.withInitial(() -> new int[2048]);
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

    /** Immutable neighbor plane, detached from the worker's reusable scratch. */
    public static VoxySectionData compactFaceCopy(long[] data) {
        if (data.length != 1024) throw new IllegalArgumentException("A native face has 1024 voxels");
        return compactRuns(data,true);
    }

    private static VoxySectionData compact(long[] data, boolean copy) {
        if (data.length != 32768) throw new IllegalArgumentException("A native section has 32768 voxels");
        return compactRuns(data,copy);
    }

    private static VoxySectionData compactRuns(long[] data, boolean copy) {
        int[] boundaries = BOUNDARIES.get();
        int runs = 1;
        for (int i = 1; i < data.length; i++) if (data[i] != data[i - 1]) {
            if (runs == boundaries.length) {
                boundaries = Arrays.copyOf(boundaries, Math.min(MAX_BOUNDARIES, boundaries.length * 2));
                BOUNDARIES.set(boundaries);
            }
            boundaries[runs-1] = i;
            runs++;
            // A dense representation is already smaller; no need to count the tail.
            if (runs * 12L >= data.length * 8L) {
                long[] owned = copy ? data.clone() : data;
                return new VoxySectionData(owned, null, owned);
            }
        }
        if (runs * 12L >= data.length * 8L) {
            long[] owned = copy ? data.clone() : data;
            return new VoxySectionData(owned, null, owned);
        }
        long[] values = new long[runs]; int[] ends = new int[runs];
        boundaries[runs-1] = data.length;
        System.arraycopy(boundaries, 0, ends, 0, runs);
        for (int i = 0; i < runs; i++) values[i] = data[ends[i]-1];
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
        if (data == null) { data = new long[ends[ends.length-1]]; copyTo(data); expanded = new WeakReference<>(data); }
        return data;
    }
}

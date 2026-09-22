package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Frozen pre-compaction implementation for differential query tests and timing. */
final class PredictionSeamMeshBaseline {
    private static final int WALL_WORDS = 5;
    private static final int LOCAL_COVERAGE = 1;
    private static final int TERRAIN_WALL = 2;
    private static final LongAdder BUILDS = new LongAdder();
    private final float[] topY;
    private final int[] topColors;
    private final boolean[] tops;
    private final long[] planes;
    private final int[] offsets;
    // Along-plane endpoints retain their original float bits; heights are already integral.
    private final int[] walls;

    void materialRows(java.util.BitSet rows) {
        for (int color : topColors) { int row = color >>> 24; if (row > 0 && row < 255) rows.set(row); }
    }
    void remapMaterials(int[] rows) throws java.io.IOException {
        for (int i = 0; i < topColors.length; i++) {
            int row = topColors[i] >>> 24;
            if (rows[row] < 0) throw new java.io.IOException("unresolved seam material");
            topColors[i] = (topColors[i] & 0xffffff) | rows[row] << 24;
        }
    }

    void writeCache(java.io.DataOutputStream out) throws java.io.IOException {
        PredictionMeshCodec.floats(out, topY); PredictionMeshCodec.ints(out, topColors);
        out.writeInt(tops.length); for (boolean value : tops) out.writeBoolean(value);
        out.writeInt(planes.length); for (long value : planes) out.writeLong(value);
        PredictionMeshCodec.ints(out, offsets); PredictionMeshCodec.ints(out, walls);
    }

    PredictionSeamMeshBaseline(java.nio.ByteBuffer in, int axis) throws java.io.IOException {
        topY = PredictionMeshCodec.floats(in); topColors = PredictionMeshCodec.ints(in);
        int n = PredictionMeshCodec.count(in, 1); tops = new boolean[n];
        for (int i = 0; i < n; i++) tops[i] = in.get() != 0;
        n = PredictionMeshCodec.count(in, 8); planes = new long[n];
        in.asLongBuffer().get(planes); in.position(in.position() + n * 8);
        offsets = PredictionMeshCodec.ints(in); walls = PredictionMeshCodec.ints(in);
        if (topY.length != axis * axis || topColors.length != topY.length || tops.length != topY.length
                || offsets.length != planes.length + 1 || walls.length % WALL_WORDS != 0
                || offsets[0] != 0 || offsets[offsets.length - 1] != walls.length / WALL_WORDS)
            throw new java.io.IOException("invalid seam dimensions");
        for (float y : topY) if (!Float.isFinite(y)) throw new java.io.IOException("invalid seam height");
        for (int i = 1; i < offsets.length; i++) if (offsets[i] < offsets[i-1]) throw new java.io.IOException("invalid seam offsets");
        for (int i = 1; i < planes.length; i++) if (planes[i] <= planes[i-1]) throw new java.io.IOException("invalid seam planes");
    }

    PredictionSeamMeshBaseline(PredictionQuadMesh source) {
        int cells = source.cellAxis() * source.cellAxis();
        topY = new float[cells]; topColors = new int[cells]; tops = new boolean[cells];
        for (int cell = 0; cell < cells; cell++) {
            int q = source.quadForCell(cell);
            if (q < 0) continue;
            tops[cell] = true;
            topY[cell] = source.y(q, 0);
            topColors[cell] = source.color(q, 0);
        }
        var cursors = new Long2IntOpenHashMap();
        int total = 0;
        for (int q = 0; q < source.quadCount(); q++) {
            long plane = opaquePlane(source, q);
            if (plane != Long.MIN_VALUE) { cursors.addTo(plane, 1); total++; }
        }
        planes = cursors.keySet().toLongArray();
        Arrays.sort(planes);
        offsets = new int[planes.length + 1];
        for (int i = 0; i < planes.length; i++) {
            offsets[i + 1] = offsets[i] + cursors.get(planes[i]);
            cursors.put(planes[i], offsets[i]);
        }
        walls = new int[total * WALL_WORDS];
        for (int q = 0; q < source.quadCount(); q++) {
            long plane = opaquePlane(source, q);
            if (plane == Long.MIN_VALUE) continue;
            int at = cursors.addTo(plane, 1) * WALL_WORDS;
            boolean xNormal = (plane & 2) == 0;
            float a = xNormal ? source.z(q, 0) : source.x(q, 0);
            float b = xNormal ? source.z(q, 1) : source.x(q, 1);
            walls[at] = Float.floatToRawIntBits(Math.min(a, b));
            walls[at + 1] = Float.floatToRawIntBits(Math.max(a, b));
            walls[at + 2] = Math.round(source.y(q, 2));
            walls[at + 3] = Math.round(source.y(q, 0));
            walls[at + 4] = (source.coverageCell(q) << 2)
                    | (source.coverageUsesLocalPosition(q) ? LOCAL_COVERAGE : 0)
                    | (source.terrainWall(q) ? TERRAIN_WALL : 0);
        }
        BUILDS.increment();
    }

    private static long opaquePlane(PredictionQuadMesh mesh, int q) {
        int nx = Math.round(mesh.normalX(q, 0)), nz = Math.round(mesh.normalZ(q, 0));
        if (mesh.normalY(q, 0) != 0 || Math.abs(nx) + Math.abs(nz) != 1) return Long.MIN_VALUE;
        int sprite = mesh.color(q, 0) >>> 24;
        if (VssLodSpriteTable.modelFace(sprite) || VssLodSpriteTable.isCutout(sprite)) return Long.MIN_VALUE;
        float plane = nx != 0 ? mesh.x(q, 0) : mesh.z(q, 0);
        for (int c = 0; c < 4; c++) {
            float v = nx != 0 ? mesh.x(q, c) : mesh.z(q, c);
            if (v != plane || mesh.y(q, c) != Math.rint(mesh.y(q, c))) return Long.MIN_VALUE;
        }
        if (plane != Math.rint(plane) || mesh.y(q, 0) != mesh.y(q, 1)
                || mesh.y(q, 2) != mesh.y(q, 3) || mesh.y(q, 0) <= mesh.y(q, 2)) return Long.MIN_VALUE;
        return planeKey((int) plane, nx, nz);
    }

    private static long planeKey(int plane, int nx, int nz) {
        return (long) plane << 3 | (nx != 0 ? 0 : 2) | (nx + nz > 0 ? 1 : 0);
    }

    boolean hasTop(int cell) { return cell >= 0 && cell < tops.length && tops[cell]; }
    float topY(int cell) { return topY[cell]; }
    int topColor(int cell) { return topColors[cell]; }
    static long builds() { return BUILDS.sum(); }
    long retainedHeapBytes() {
        return 256L + tops.length + topY.length * 4L + topColors.length * 4L
                + planes.length * 8L + offsets.length * 4L + walls.length * 4L;
    }

    void subtract(List<PredictionLodSeams.HeightSpan> gaps, PredictionLodSeams.Surface surface,
                  int wx, int wz, int length, int nx, int nz) {
        subtract(gaps, surface, wx, wz, length, nx, nz, null);
    }

    void subtract(List<PredictionLodSeams.HeightSpan> gaps, PredictionLodSeams.Surface surface,
                  int wx, int wz, int length, int nx, int nz, byte[] replaced) {
        var tile = surface.tile();
        int lx = wx - tile.baseBlockX(), lz = wz - tile.baseBlockZ();
        int plane = Arrays.binarySearch(planes, planeKey(nx != 0 ? lx : lz, nx, nz));
        if (plane < 0) return;
        int start = nx != 0 ? lz : lx;
        for (int candidate = offsets[plane]; candidate < offsets[plane + 1]; candidate++) {
            int at = candidate * WALL_WORDS;
            if (Float.intBitsToFloat(walls[at]) > start || Float.intBitsToFloat(walls[at + 1]) < start + length) continue;
            int owner = walls[at + 4] >> 2;
            if ((walls[at + 4] & LOCAL_COVERAGE) != 0) {
                int along = Math.floorDiv(start + length / 2, tile.spacingBlocks());
                owner = nx != 0 ? along * tile.cellAxis() + owner % tile.cellAxis()
                        : owner / tile.cellAxis() * tile.cellAxis() + along;
            }
            if (owner < 0 || owner >= surface.allowed().length || !surface.allowed()[owner]) continue;
            if ((walls[at + 4] & TERRAIN_WALL) != 0 && PredictionBoundaryWalls.replaced(replaced, owner, tile.cellAxis(), tile.spacingBlocks(),
                    nx != 0 ? lx : lz, nx != 0)) continue;
            int bottom = walls[at + 2], top = walls[at + 3];
            for (int i = gaps.size() - 1; i >= 0; i--) {
                var gap = gaps.get(i);
                if (top <= gap.bottom() || bottom >= gap.top()) continue;
                gaps.remove(i);
                if (gap.bottom() < bottom) gaps.add(new PredictionLodSeams.HeightSpan(gap.bottom(), bottom));
                if (top < gap.top()) gaps.add(new PredictionLodSeams.HeightSpan(top, gap.top()));
            }
        }
    }
}

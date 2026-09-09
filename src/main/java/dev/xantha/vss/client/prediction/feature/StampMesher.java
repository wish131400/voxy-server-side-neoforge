package dev.xantha.vss.client.prediction.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Greedy exposed-face mesher for feature stamps, including sky-light AO. */
public final class StampMesher {
    private static final int MAX_EXTENT = 512;
    private static final int[] NEIGHBOURS = {
            1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1};

    private StampMesher() { }

    public static FeatureStamp mesh(Map<BlockPos, BlockState> placed, int originY,
                                    Ground ground, int floorY) {
        if (placed == null || placed.isEmpty()) return FeatureStamp.EMPTY;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            BlockPos p = entry.getKey();
            if (p.getY() < originY || !FeatureSimulator.isSolid(entry.getValue())) continue;
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX > maxX) return FeatureStamp.EMPTY;
        minY = Math.min(minY, Math.max(floorY, maxY - MAX_EXTENT + 1));
        if (maxX - minX >= MAX_EXTENT || maxY - minY >= MAX_EXTENT || maxZ - minZ >= MAX_EXTENT) {
            return FeatureStamp.EMPTY;
        }
        Mesher mesher = new Mesher(ground == null ? (x, z) -> floorY : ground,
                minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        ArrayList<FeatureStamp.StampBlock> blocks = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            BlockPos p = entry.getKey();
            BlockState state = entry.getValue();
            if (p.getY() < originY || !FeatureSimulator.isSolid(state)) continue;
            mesher.grid[mesher.index(p.getX() - minX, p.getY() - minY, p.getZ() - minZ)] = state;
            blocks.add(new FeatureStamp.StampBlock(p.getX(), p.getY() - originY, p.getZ(), state));
        }
        mesher.propagateSkyLight();
        ArrayList<FeatureStamp.StampQuad> quads = new ArrayList<>();
        mesher.faces(quads, originY);
        return new FeatureStamp(List.copyOf(quads), List.copyOf(blocks), maxY - originY + 1,
                mesher.groundLight());
    }

    @FunctionalInterface
    public interface Ground { int surfaceY(int x, int z); }

    private static final class Mesher {
        private final Ground ground;
        private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
        private final BlockState[] grid;
        private final byte[] light;

        private Mesher(Ground ground, int minX, int minY, int minZ,
                       int sizeX, int sizeY, int sizeZ) {
            this.ground = ground; this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
            this.grid = new BlockState[sizeX * sizeY * sizeZ];
            this.light = new byte[grid.length];
        }

        private int index(int x, int y, int z) { return (y * sizeZ + z) * sizeX + x; }
        private boolean inGrid(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
        }
        private BlockState at(int x, int y, int z) {
            return inGrid(x, y, z) ? grid[index(x, y, z)] : null;
        }
        private boolean groundAt(int x, int y, int z) {
            return minY + y < ground.surfaceY(minX + x, minZ + z);
        }
        private boolean occupied(int x, int y, int z) { return at(x, y, z) != null || groundAt(x, y, z); }
        private boolean opaque(int x, int y, int z) {
            BlockState state = at(x, y, z);
            return state == null ? groundAt(x, y, z)
                    : state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 15;
        }
        private double shade(int x, int y, int z) {
            BlockState state = at(x, y, z);
            return state == null ? (groundAt(x, y, z) ? 0.2D : 1.0D)
                    : state.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        }
        private int lightAt(int x, int y, int z) {
            return inGrid(x, y, z) ? light[index(x, y, z)] : (groundAt(x, y, z) ? 0 : 15);
        }

        private void propagateSkyLight() {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
                for (int y = sizeY - 1; y >= 0; y--) {
                    BlockState state = grid[index(x, y, z)];
                    if (state != null && !state.propagatesSkylightDown(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) break;
                    setLight(queue, x, y, z, 15);
                }
            }
            for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
                for (int y = 0; y < sizeY; y++) {
                    if (x > 0 && x + 1 < sizeX && z > 0 && z + 1 < sizeZ && y > 0 && y + 1 < sizeY) continue;
                    for (int d = 0; d < NEIGHBOURS.length; d += 3) {
                        int nx = x + NEIGHBOURS[d], ny = y + NEIGHBOURS[d + 1], nz = z + NEIGHBOURS[d + 2];
                        if (!inGrid(nx, ny, nz) && !groundAt(nx, ny, nz)) setLight(queue, x, y, z, 15);
                    }
                }
            }
            while (!queue.isEmpty()) {
                int cell = queue.removeFirst();
                int value = light[cell] & 0xFF;
                if (value <= 1) continue;
                int x = cell % sizeX, rest = cell / sizeX, z = rest % sizeZ, y = rest / sizeZ;
                for (int d = 0; d < NEIGHBOURS.length; d += 3) {
                    int nx = x + NEIGHBOURS[d], ny = y + NEIGHBOURS[d + 1], nz = z + NEIGHBOURS[d + 2];
                    if (inGrid(nx, ny, nz)) enter(queue, nx, ny, nz, value);
                }
            }
        }

        private void setLight(ArrayDeque<Integer> queue, int x, int y, int z, int value) {
            int cell = index(x, y, z);
            if ((light[cell] & 0xFF) < value) { light[cell] = (byte) value; queue.add(cell); }
        }
        private void enter(ArrayDeque<Integer> queue, int x, int y, int z, int from) {
            BlockState state = grid[index(x, y, z)];
            int cost = state == null ? (groundAt(x, y, z) ? 15 : 1)
                    : Math.max(1, state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
            setLight(queue, x, y, z, from - cost);
        }

        private FeatureStamp.GroundLight groundLight() {
            byte[] values = new byte[sizeX * sizeZ];
            for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
                int y = ground.surfaceY(minX + x, minZ + z) - minY;
                int value = y < 0 || y >= sizeY ? 15
                        : (grid[index(x, y, z)] != null && opaque(x, y, z) ? 0 : brightness(lightAt(x, y, z)));
                values[z * sizeX + x] = (byte) value;
            }
            return new FeatureStamp.GroundLight(minX, minZ, sizeX, sizeZ, values);
        }

        private int corner(int fx, int fy, int fz, int ax, int ay, int az, int bx, int by, int bz) {
            boolean sideA = opaque(fx + ax, fy + ay, fz + az);
            boolean sideB = opaque(fx + bx, fy + by, fz + bz);
            double shadeA = shade(fx + ax, fy + ay, fz + az);
            double shadeB = shade(fx + bx, fy + by, fz + bz);
            double shadeC = sideA && sideB ? shadeA : shade(fx + ax + bx, fy + ay + by, fz + az + bz);
            int lightF = lightAt(fx, fy, fz), lightA = lightAt(fx + ax, fy + ay, fz + az);
            int lightB = lightAt(fx + bx, fy + by, fz + bz);
            int lightC = sideA && sideB ? lightA : lightAt(fx + ax + bx, fy + ay + by, fz + az + bz);
            if (lightA == 0) lightA = lightF; if (lightB == 0) lightB = lightF; if (lightC == 0) lightC = lightF;
            double occlusion = (shade(fx, fy, fz) + shadeA + shadeB + shadeC) * 0.25D;
            return (int) Math.round(occlusion * brightness((lightF + lightA + lightB + lightC) * 0.25D));
        }

        private int quadLight(int fx, int fy, int fz, int ux, int uy, int uz,
                              int vx, int vy, int vz, int w, int h) {
            int ex = ux * (w - 1), ey = uy * (w - 1), ez = uz * (w - 1);
            int gx = vx * (h - 1), gy = vy * (h - 1), gz = vz * (h - 1);
            int c0 = corner(fx, fy, fz, -ux, -uy, -uz, -vx, -vy, -vz);
            int c1 = corner(fx + ex, fy + ey, fz + ez, ux, uy, uz, -vx, -vy, -vz);
            int c2 = corner(fx + ex + gx, fy + ey + gy, fz + ez + gz, ux, uy, uz, vx, vy, vz);
            int c3 = corner(fx + gx, fy + gy, fz + gz, -ux, -uy, -uz, vx, vy, vz);
            return packLight(c0, c1, c2, c3);
        }

        private void faces(List<FeatureStamp.StampQuad> out, int originY) {
            for (int y = 0; y < sizeY; y++) {
                int layer = y;
                plane(out, sizeX, sizeZ, (a, b) -> exposed(a, layer, b, 0, 1, 0),
                        (a, b, w, h, state) -> new FeatureStamp.StampQuad(0, minX + a,
                                minY + layer + 1 - originY, minZ + b, w, h, state,
                                quadLight(a, layer + 1, b, 1, 0, 0, 0, 0, 1, w, h)));
            }
            for (int z = 0; z < sizeZ; z++) {
                final int depth = z;
                plane(out, sizeX, sizeY, (a, b) -> exposed(a, b, depth, 0, 0, -1),
                        (a, b, w, h, state) -> new FeatureStamp.StampQuad(1, minX + a, minY + b - originY,
                                minZ + depth, w, h, state, quadLight(a, b, depth - 1, 1, 0, 0, 0, 1, 0, w, h)));
                plane(out, sizeX, sizeY, (a, b) -> exposed(a, b, depth, 0, 0, 1),
                        (a, b, w, h, state) -> new FeatureStamp.StampQuad(2, minX + a, minY + b - originY,
                                minZ + depth + 1, w, h, state, quadLight(a, b, depth + 1, 1, 0, 0, 0, 1, 0, w, h)));
            }
            for (int x = 0; x < sizeX; x++) {
                final int column = x;
                plane(out, sizeZ, sizeY, (a, b) -> exposed(column, b, a, -1, 0, 0),
                        (a, b, w, h, state) -> new FeatureStamp.StampQuad(3, minX + column, minY + b - originY,
                                minZ + a, w, h, state, quadLight(column - 1, b, a, 0, 0, 1, 0, 1, 0, w, h)));
                plane(out, sizeZ, sizeY, (a, b) -> exposed(column, b, a, 1, 0, 0),
                        (a, b, w, h, state) -> new FeatureStamp.StampQuad(4, minX + column + 1, minY + b - originY,
                                minZ + a, w, h, state, quadLight(column + 1, b, a, 0, 0, 1, 0, 1, 0, w, h)));
            }
        }

        private BlockState exposed(int x, int y, int z, int dx, int dy, int dz) {
            BlockState state = at(x, y, z);
            return state == null || occupied(x + dx, y + dy, z + dz) ? null : state;
        }

        private static void plane(List<FeatureStamp.StampQuad> out, int sizeA, int sizeB,
                                  Cell cell, QuadFactory factory) {
            boolean[] used = new boolean[sizeA * sizeB];
            for (int b = 0; b < sizeB; b++) for (int a = 0; a < sizeA; a++) {
                if (used[b * sizeA + a]) continue;
                BlockState state = cell.state(a, b);
                if (state == null) continue;
                int width = 1;
                while (a + width < sizeA && !used[b * sizeA + a + width] && state == cell.state(a + width, b)) width++;
                int height = 1;
                while (b + height < sizeB && rowMatches(cell, used, sizeA, a, b + height, width, state)) height++;
                for (int db = 0; db < height; db++) for (int da = 0; da < width; da++) used[(b + db) * sizeA + a + da] = true;
                out.add(factory.create(a, b, width, height, state));
            }
        }
        private static boolean rowMatches(Cell cell, boolean[] used, int sizeA, int a, int b,
                                           int width, BlockState state) {
            for (int da = 0; da < width; da++) if (used[b * sizeA + a + da] || state != cell.state(a + da, b)) return false;
            return true;
        }
    }

    private interface Cell { BlockState state(int a, int b); }
    private interface QuadFactory { FeatureStamp.StampQuad create(int a, int b, int w, int h, BlockState state); }

    static int brightness(double sky) {
        double linear = Math.max(0.0D, Math.min(15.0D, sky)) / 15.0D;
        double curved = linear / (4.0D - 3.0D * linear);
        double gamma = 1.0D - Math.pow(1.0D - curved, 4.0D);
        return (int) Math.round(15.0D * (0.5D * curved + 0.5D * gamma));
    }

    static int packLight(int a, int b, int c, int d) {
        return (a & 0xF) | (b & 0xF) << 4 | (c & 0xF) << 8 | (d & 0xF) << 12;
    }
}

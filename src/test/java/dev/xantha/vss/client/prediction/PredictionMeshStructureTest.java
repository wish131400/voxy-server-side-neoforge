package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Structural contract for the block-level column topology.  The mesh must
 * look like Minecraft blocks at any LOD: one flat quad per column at the
 * column's own height, vertical wall faces wherever a neighbour is lower,
 * and never an interpolated slope triangle.
 */
class PredictionMeshStructureTest {
    private static final int GRID = PredictionTileManager.GRID_SIZE;
    private static final int CELL_AXIS = GRID - 1;

    private static ClientColumnSample column(int surfaceY) {
        return new ClientColumnSample(surfaceY, surfaceY, 0,
                ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0, 0, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    /** Staircase in x: column height 64 + 2x, constant along z. */
    private static ClientColumnSample[] staircase() {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                samples[z * GRID + x] = column(64 + 2 * x);
            }
        }
        return samples;
    }

    @Test
    void staircaseRendersAsSteppedBlockColumns() {
        ClientColumnSample[] samples = staircase();
        int step = 1;
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, step, GRID);

        // ---- tops: exactly one flat quad per column, at the column's height.
        Map<Long, Integer> topsByCell = new HashMap<>();
        int wallTriangles = 0;
        int slopeTriangles = 0;
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            int b = a + 1;
            int c = a + 2;
            float ya = mesh.y(a);
            float yb = mesh.y(b);
            float yc = mesh.y(c);
            int distinct = (ya == yb ? 0 : 1) + (yb == yc ? 0 : 1) + (ya == yc ? 0 : 1);
            if (distinct > 2) {
                slopeTriangles++;
                continue;
            }
            if (ya == yb && yb == yc) {
                // horizontal face: must be the column top with an up normal
                if (mesh.normalY(a) != 1.0F) {
                    continue;
                }
                int cellX = (int) Math.floor(Math.min(Math.min(mesh.x(a), mesh.x(b)), mesh.x(c)) / step);
                int cellZ = (int) Math.floor(Math.min(Math.min(mesh.z(a), mesh.z(b)), mesh.z(c)) / step);
                if (cellX >= 0 && cellX < GRID && cellZ >= 0 && cellZ < GRID) {
                    topsByCell.merge(((long) cellZ << 16) | cellX, 1, Integer::sum);
                    if (ya != 64 + 2 * cellX) {
                        fail("top face at column (" + cellX + "," + cellZ + ") sits at y=" + ya
                                + " instead of the column height " + (64 + 2 * cellX));
                    }
                }
            } else {
                wallTriangles++;
            }
        }
        assertEquals(GRID * GRID - 2 * GRID + 1, topsByCell.values().stream().mapToInt(v -> v / 2).sum(),
                "every one of the " + ((GRID - 1) * (GRID - 1)) + " in-tile columns must own exactly one"
                        + " top quad (the 17th border column belongs to the neighbouring tile)");
        assertEquals(0, slopeTriangles, "no triangle may interpolate three distinct heights (slope)");

        // ---- walls: x-facing walls on every interior x edge, none along z.
        Map<Integer, int[]> xWalls = new HashMap<>(); // plane -> {minY, maxY, triCount}
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            if (mesh.y(a) == mesh.y(a + 1) || mesh.normalX(a) == 0.0F
                    || mesh.normalY(a) != 0.0F) {
                continue;
            }
            float plane = mesh.x(a);
            int p = Math.round(plane / step);
            int top = Math.round(Math.max(Math.max(mesh.y(a), mesh.y(a + 1)), mesh.y(a + 2)));
            int bottom = Math.round(Math.min(Math.min(mesh.y(a), mesh.y(a + 1)), mesh.y(a + 2)));
            int[] span = xWalls.computeIfAbsent(p, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE, 0});
            span[0] = Math.min(span[0], bottom);
            span[1] = Math.max(span[1], top);
            span[2]++;
        }
        assertEquals(CELL_AXIS, xWalls.size(),
                "walls must exist on every interior x plane (one per differing edge)");
        for (int p = 1; p <= CELL_AXIS; p++) {
            int[] span = xWalls.get(p);
            int higher = Math.max(64 + 2 * (p - 1), 64 + 2 * p);
            int lower = Math.min(64 + 2 * (p - 1), 64 + 2 * p);
            assertEquals(higher, span[1], "wall on plane " + p + " must reach the higher column top");
            assertEquals(lower, span[0], "wall on plane " + p + " must stop at the lower column top");
        }
        assertTrue(wallTriangles > 0, "the staircase must expose wall faces");

        // ---- every vertex lands on the integer block grid of its column.
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertEquals(0.0F, mesh.x(v) % step, 0.0001F, "vertex x must align to the block grid");
            assertEquals(0.0F, mesh.z(v) % step, 0.0001F, "vertex z must align to the block grid");
            assertEquals(Math.round(mesh.y(v)), mesh.y(v), 0.0001F, "vertex y must be an integer block height");
        }
    }

    @Test
    void sameBlockTopologyAtCoarseSpacing() {
        ClientColumnSample[] samples = staircase();
        int step = 16;
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, step, GRID);
        // The column count, wall placement and heights are identical to the
        // detail grid; only the horizontal scale (step) changes.
        int tops = 0;
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            if (mesh.y(a) == mesh.y(a + 1) && mesh.y(a + 1) == mesh.y(a + 2)
                    && mesh.normalY(a) == 1.0F) {
                int cellX = Math.round(mesh.x(a) / step);
                if (mesh.y(a) == 64 + 2 * cellX) {
                    tops++;
                }
            }
        }
        assertEquals((GRID - 1) * (GRID - 1), tops / 2,
                "coarse tiles keep one flat quad per in-tile column");
        for (int v = 0; v < mesh.vertexCount(); v++) {
            assertEquals(0.0F, mesh.x(v) % step, 0.0001F);
            assertEquals(0.0F, mesh.z(v) % step, 0.0001F);
        }
    }

    /** Cliff taller than three blocks must render its wall over the full
     * height difference, not stop three blocks below the top. */
    @Test
    void cliffWallSpansTheFullHeightDifference() {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                samples[z * GRID + x] = column(x < 8 ? 100 : 40);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, 1, GRID);

        // The shared plane between the plateau (100) and the plain (40).
        int plane = 8;
        int minWallY = Integer.MAX_VALUE;
        int wallTrianglesOnPlane = 0;
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            if (mesh.normalX(a) == 0.0F || mesh.normalY(a) != 0.0F) {
                continue;
            }
            if (Math.round(mesh.x(a)) != plane) {
                continue;
            }
            wallTrianglesOnPlane++;
            minWallY = Math.min(minWallY, Math.round(Math.min(Math.min(
                    mesh.y(a), mesh.y(a + 1)), mesh.y(a + 2))));
        }
        assertTrue(wallTrianglesOnPlane > 0, "the cliff must render wall faces on plane " + plane);
        assertTrue(minWallY <= 41,
                "the wall must reach down to the plain (min wall y=" + minWallY
                        + "), not stop three blocks under the plateau top");
    }

    /** The 17th border column is owned by the neighbouring tile: it must not
     * emit a top here, and its inward seam wall must be emitted exactly once
     * (no double-drawn coincident quads at tile boundaries). */
    @Test
    void borderColumnEmitsNoTopAndOneSeamWall() {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                // Raised east border column: its west wall belongs to this tile.
                samples[z * GRID + x] = column(x == GRID - 1 ? 96 : 64);
            }
        }
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, 1, GRID);

        int topsOnBorderColumn = 0;
        int westWallsOnBorderPlane = 0;
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            if (mesh.y(a) == mesh.y(a + 1) && mesh.y(a + 1) == mesh.y(a + 2)
                    && mesh.normalY(a) == 1.0F) {
                int cellX = Math.round(Math.min(Math.min(mesh.x(a), mesh.x(a + 1)), mesh.x(a + 2)));
                if (cellX == GRID - 1) {
                    topsOnBorderColumn++;
                }
            }
            if (mesh.normalX(a) == -1.0F && mesh.normalY(a) == 0.0F
                    && Math.round(mesh.x(a)) == GRID - 1) {
                westWallsOnBorderPlane++;
            }
        }
        assertEquals(0, topsOnBorderColumn / 2,
                "the border column top belongs to the neighbouring tile");
        assertTrue(westWallsOnBorderPlane > 0,
                "the raised border column must still close its seam with a west wall");
        // The seam wall is emitted once by this tile; a matching east-facing
        // copy would be a double draw.
        int eastWallsOnBorderPlane = 0;
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            if (mesh.normalX(a) == 1.0F && mesh.normalY(a) == 0.0F
                    && Math.round(mesh.x(a)) == GRID - 1) {
                eastWallsOnBorderPlane++;
            }
        }
        assertEquals(0, eastWallsOnBorderPlane,
                "the seam wall must not be double-drawn from both sides");
    }

    @Test
    void packedBorderCornerDoesNotCreateOutsideTileCoverage() {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                samples[z * GRID + x] = column(x == GRID - 1 && z == GRID - 1 ? 96 : 64);
            }
        }

        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, 1, GRID);

        // The diagonal sample belongs to the neighbouring tile. Packing this
        // tile must remain valid even when that corner is higher and would
        // otherwise tempt the seam code to emit an outside-facing wall.
        assertTrue(mesh.packed().quadCount() > 0);
    }

    /** Renders the z=8 cross-section straight from the mesh vertex stream. */
    @Test
    void printStaircaseCrossSectionForInspection() {
        ClientColumnSample[] samples = staircase();
        PredictionMesh mesh = PredictionMeshBuilder.build(samples, null,
                Integer.MIN_VALUE, 0xB22D78C5, 1, GRID);
        int row = 8;
        int minY = 60;
        int maxY = 98;
        char[][] raster = new char[maxY - minY + 1][GRID];
        for (char[] line : raster) {
            java.util.Arrays.fill(line, ' ');
        }
        for (int t = 0; t * 3 + 2 < mesh.vertexCount(); t++) {
            int a = t * 3;
            int b = a + 1;
            int c = a + 2;
            float zMin = Math.min(Math.min(mesh.z(a), mesh.z(b)), mesh.z(c));
            float zMax = Math.max(Math.max(mesh.z(a), mesh.z(b)), mesh.z(c));
            boolean inRow = zMin >= row && zMax <= row + 1;
            if (!inRow) {
                continue;
            }
            if (mesh.y(a) == mesh.y(b) && mesh.y(b) == mesh.y(c) && mesh.normalY(a) == 1.0F) {
                int x = Math.round(Math.min(Math.min(mesh.x(a), mesh.x(b)), mesh.x(c)));
                int y = Math.round(mesh.y(a));
                if (y >= minY && y <= maxY && x >= 0 && x < GRID) {
                    raster[y - minY][x] = '#';
                }
            } else if (mesh.normalX(a) != 0.0F && mesh.normalY(a) == 0.0F) {
                // x-facing wall: owner is the higher column on one side
                int plane = Math.round(mesh.x(a));
                int top = Math.round(Math.max(Math.max(mesh.y(a), mesh.y(b)), mesh.y(c)));
                int bottom = Math.round(Math.min(Math.min(mesh.y(a), mesh.y(b)), mesh.y(c)));
                int owner = plane; // plane p sits between p-1 and p; the higher column owns it
                for (int y = bottom + 1; y <= top; y++) {
                    if (y >= minY && y <= maxY && owner >= 0 && owner < GRID) {
                        raster[y - minY][owner] = '|';
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder("mesh cross-section at z=" + row + " (row 8):\n");
        for (int y = maxY - minY; y >= 0; y--) {
            out.append(String.format("%3d ", y + minY)).append(raster[y]).append('\n');
        }
        out.append("    ");
        for (int x = 0; x < GRID; x++) {
            out.append(x % 10 == 0 ? '+' : '-');
        }
        System.out.println(out);
        assertTrue(mesh.vertexCount() > 0);
    }
}

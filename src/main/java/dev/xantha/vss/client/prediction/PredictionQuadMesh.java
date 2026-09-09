package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Packed quad view of a prediction mesh.  This is the VSS-owned equivalent of
 * the packed LodQuadWriter output: one record contains the four corner
 * positions, a face group and a material colour.  Keeping it separate from
 * the legacy triangle arrays lets old callers and tests continue to work. Cell
 * and origin lookups are precomputed so cache rebuilds never scan all quads
 * for every cell.
 */
public final class PredictionQuadMesh {
    public static final int TOP = 0;
    public static final int WATER_TOP = 1;
    private static final int LOD_TEXTURE_SCALE = 0x40;

    private final float[] positions;
    private final float[] normals;
    private final int[] colors;
    private final int[] cells;
    private final byte[] groups;
    private final int[] originCells;
    private final short[] widths;
    private final short[] heights;
    private final int[] cellLookup;
    private final boolean[] originLookup;
    private final int quadCount;
    private final float[] waterPositions;
    private final float[] waterNormals;
    private final int[] waterColors;
    private final int[] waterCells;
    private final short[] waterWidths;
    private final short[] waterHeights;
    private final int[] waterLookup;
    private final boolean[] waterOriginLookup;
    private final int waterQuadCount;
    private final int cellAxis;

    long retainedHeapBytes() {
        long floats = (long) positions.length + normals.length + waterPositions.length + waterNormals.length;
        long ints = (long) colors.length + cells.length + originCells.length + cellLookup.length
                + waterColors.length + waterCells.length + waterLookup.length;
        long shorts = (long) widths.length + heights.length + waterWidths.length + waterHeights.length;
        return 768L + (floats + ints) * 4L + shorts * 2L
                + groups.length + originLookup.length + waterOriginLookup.length;
    }

    private PredictionQuadMesh(float[] positions, float[] normals, int[] colors, int[] cells, byte[] groups,
                               int[] originCells, short[] widths, short[] heights,
                               int quadCount, float[] waterPositions, float[] waterNormals, int[] waterColors,
                               int[] waterCells, short[] waterWidths, short[] waterHeights,
                               int waterQuadCount, int cellAxis) {
        this.positions = positions;
        this.normals = normals;
        this.colors = colors;
        this.cells = cells;
        this.groups = groups;
        this.originCells = originCells;
        this.widths = widths;
        this.heights = heights;
        this.cellAxis = cellAxis;
        this.cellLookup = buildLookup(originCells, widths, heights, quadCount);
        this.originLookup = buildOriginLookup(originCells, quadCount);
        this.quadCount = quadCount;
        this.waterPositions = waterPositions;
        this.waterNormals = waterNormals;
        this.waterColors = waterColors;
        this.waterCells = waterCells;
        this.waterWidths = waterWidths;
        this.waterHeights = waterHeights;
        this.waterLookup = buildWaterLookup(waterCells, waterWidths, waterHeights, waterQuadCount);
        this.waterOriginLookup = buildOriginLookup(waterCells, waterQuadCount);
        this.waterQuadCount = waterQuadCount;
    }

    static PredictionQuadMesh from(PredictionMesh mesh) {
        int cellCount = mesh.cellCount();
        int cellAxis = mesh.cellAxis();
        QuadStorage terrain = new QuadStorage(cellCount);
        QuadStorage water = new QuadStorage(cellCount);
        int[] topKeys = new int[cellCount];
        java.util.Arrays.fill(topKeys, Integer.MIN_VALUE);
        int[] waterKeys = new int[cellCount];
        java.util.Arrays.fill(waterKeys, Integer.MIN_VALUE);
        java.util.Map<TopSignature, Integer> topMaterials = new java.util.HashMap<>();
        java.util.Map<Long, Integer> waterMaterials = new java.util.HashMap<>();
        boolean[] waterTop = new boolean[cellCount];
        boolean[] completeTops = new boolean[cellCount];
        int spacing = mesh.spacingBlocks > 0 ? mesh.spacingBlocks : mesh.vertexCount > 1
                ? Math.max(1, Math.round(mesh.x(1) - mesh.x(0)))
                : mesh.waterVertexCount > 1
                ? Math.max(1, Math.round(mesh.waterX(1) - mesh.waterX(0))) : 1;
        int nextMaterial = 1;
        for (int cell = 0; cell < cellCount; cell++) {
            int first = mesh.cellOffsets == null ? cell * 6 : mesh.cellOffsets[cell];
            int count = mesh.cellCounts == null ? 6 : mesh.cellCounts[cell];
            if (count >= 6 && first + 5 < mesh.vertexCount) {
                // The alpha byte carries the the sprite key for the
                // terrain top.  It is part of the greedy-merge material key;
                // merging two equal-colour columns with different sprites
                // would make one block texture cover the entire rectangle.
                int c0 = mesh.color(first);
                int c1 = mesh.color(first + 1);
                int c2 = mesh.color(first + 2);
                int c3 = mesh.color(first + 5);
                int y0 = Float.floatToRawIntBits(mesh.y(first));
                boolean flat = Float.floatToRawIntBits(mesh.y(first + 1)) == y0
                        && Float.floatToRawIntBits(mesh.y(first + 2)) == y0
                        && Float.floatToRawIntBits(mesh.y(first + 5)) == y0;
                completeTops[cell] = mesh.normalY(first) == 1
                        && mesh.x(first) == (cell % cellAxis) * spacing
                        && mesh.z(first) == (cell / cellAxis) * spacing
                        && mesh.x(first + 1) == (cell % cellAxis + 1) * spacing
                        && mesh.z(first + 5) == (cell / cellAxis + 1) * spacing;
                // Only perfectly flat, equal-colour cells are merged. Sloped
                // cells retain their own quad so the height field stays exact.
                if (completeTops[cell] && flat && c0 == c1 && c0 == c2 && c0 == c3) {
                    // A repeated gradient cannot be stretched across a merged
                    // rectangle without changing its interpolated colours.
                    TopSignature signature = new TopSignature(y0, c0, c1, c2, c3);
                    final int materialId = nextMaterial++;
                    topKeys[cell] = topMaterials.computeIfAbsent(signature,
                            ignored -> materialId);
                } else {
                    topKeys[cell] = Integer.MIN_VALUE + 1 + cell;
                }
            }
            int waterFirst = mesh.waterOffsets == null ? cell * 6 : mesh.waterOffsets[cell];
            int waterCount = mesh.waterCounts == null ? 6 : mesh.waterCounts[cell];
            if (waterCount >= 6 && waterFirst + 5 < mesh.waterVertexCount
                    && cell < mesh.waterCells.length && mesh.waterCells[cell]
                    && mesh.waterNormalX(waterFirst) == 0 && mesh.waterNormalZ(waterFirst) == 0) {
                waterTop[cell] = true;
                // The fluid kind rides in the water normal's Y component and
                // must survive merging so a quad never blends two fluids.
                long signature = ((long) (mesh.waterColor(waterFirst) & 0xFFFFFF) << 32)
                        ^ (Float.floatToRawIntBits(mesh.waterY(waterFirst)) & 0xFFFFFFFFL)
                        ^ Float.floatToRawIntBits(mesh.waterNormalY(waterFirst));
                final int materialId = nextMaterial++;
                boolean completeTop = mesh.waterX(waterFirst) == (cell % cellAxis) * spacing
                        && mesh.waterZ(waterFirst) == (cell / cellAxis) * spacing
                        && mesh.waterX(waterFirst + 1) == (cell % cellAxis + 1) * spacing
                        && mesh.waterZ(waterFirst + 5) == (cell / cellAxis + 1) * spacing;
                // Clipping a structure can leave several subrectangles. The
                // first one is not a full grid cell and must never be stretched
                // by greedy merging into neighbouring cells.
                waterKeys[cell] = completeTop ? waterMaterials.computeIfAbsent(signature,
                        ignored -> materialId) : Integer.MIN_VALUE + 1 + cell;
            }
        }
        // Keep the representative-material footprint bounded in world space.
        // At fine spacing the cap is larger than this fixed tile and has no
        // effect; coarse LODs are split before a grass/sand average becomes a
        // screen-sized solid rectangle.
        int maxMergedCells = Math.max(1, (256 + spacing - 1) / spacing);
        java.util.List<VssLodGreedyMesher.Rectangle> topRects =
                VssLodGreedyMesher.merge(topKeys, cellAxis, maxMergedCells, maxMergedCells);
        for (VssLodGreedyMesher.Rectangle rect : topRects) {
            if (rect.material() == Integer.MIN_VALUE) continue;
            int cell = rect.z() * cellAxis + rect.x();
            int first = mesh.cellOffsets == null ? cell * 6 : mesh.cellOffsets[cell];
            // The second/fourth source corners already sit one cell from the
            // origin, so add only the remaining cell intervals.
            int stepX = (rect.width() - 1) * Math.round(mesh.x(first + 1) - mesh.x(first));
            int stepZ = (rect.height() - 1) * Math.round(mesh.z(first + 5) - mesh.z(first));
            terrain.append(mesh, first, first + 1, first + 2, first + 5,
                    cell, rect.width(), rect.height(), TOP | (completeTops[cell] ? LOD_TEXTURE_SCALE : 0), stepX, stepZ);
        }
        java.util.List<VssLodGreedyMesher.Rectangle> waterRects =
                VssLodGreedyMesher.merge(waterKeys, cellAxis, maxMergedCells, maxMergedCells);
        for (VssLodGreedyMesher.Rectangle rect : waterRects) {
            if (rect.material() == Integer.MIN_VALUE) continue;
            int cell = rect.z() * cellAxis + rect.x();
            int waterFirst = mesh.waterOffsets == null ? cell * 6 : mesh.waterOffsets[cell];
            int stepX = (rect.width() - 1) * Math.round(mesh.waterX(waterFirst + 1) - mesh.waterX(waterFirst));
            int stepZ = (rect.height() - 1) * Math.round(mesh.waterZ(waterFirst + 5) - mesh.waterZ(waterFirst));
            water.appendWater(mesh, waterFirst, waterFirst + 1, waterFirst + 2,
                    waterFirst + 5, cell, rect.width(), rect.height(), WATER_TOP,
                    stepX, stepZ);
        }

        // The rich mesh keeps every exposed wall band in the legacy triangle
        // stream so CPU callers retain exact cell ranges. During GPU conversion,
        // contiguous, coplanar bands with identical material/light data become
        // one rectangle.  This is deliberately conservative; feature sides,
        // lower spans and reverse-oriented fluid edges remain individual quads.
        java.util.List<WallQuad> terrainWalls = new java.util.ArrayList<>();
        java.util.List<WallQuad> waterWalls = new java.util.ArrayList<>();

        // The first six vertices of a rich cell are its top quad.  Everything
        // after that is still real geometry: cliff bands, lower spans,
        // structures, tree stamps, ground features and fluid banks.  The old
        // packed path silently discarded this tail, which made the GPU tile
        // fundamentally different from the CPU mesh used to build it.  All
        // current emitters write complete triangle pairs, so each pair can be
        // represented by the same four-corner payload as a top quad.
        for (int cell = 0; cell < cellCount; cell++) {
            int first = mesh.cellOffsets == null ? cell * 6 : mesh.cellOffsets[cell];
            int count = mesh.cellCounts == null ? 6 : mesh.cellCounts[cell];
            int topEnd = Math.min(first + 6, first + count);
            for (int source = topEnd; source + 5 < first + count; source += 6) {
                WallQuad wall = wallQuad(mesh, source, cell, false);
                if (wall != null) {
                    terrainWalls.add(wall);
                } else {
                    int group = VssLodFaceGroup.ofNormal(mesh.normalX(source),
                            mesh.normalY(source), mesh.normalZ(source));
                    terrain.append(mesh, source, source + 1, source + 2, source + 5,
                            cell, 1, 1, group, 0, 0);
                }
            }
            int waterFirst = mesh.waterOffsets == null ? cell * 6 : mesh.waterOffsets[cell];
            int waterCount = mesh.waterCounts == null ? 6 : mesh.waterCounts[cell];
            if (cell >= mesh.waterCells.length || !mesh.waterCells[cell]) continue;
            int waterTopEnd = waterTop[cell] ? Math.min(waterFirst + 6, waterFirst + waterCount) : waterFirst;
            for (int source = waterTopEnd; source + 5 < waterFirst + waterCount; source += 6) {
                WallQuad wall = wallQuad(mesh, source, cell, true);
                if (wall != null) {
                    waterWalls.add(wall);
                } else {
                    int group = VssLodFaceGroup.ofFluidNormal(mesh.waterNormalX(source),
                            mesh.waterNormalY(source), mesh.waterNormalZ(source));
                    water.appendWater(mesh, source, source + 1, source + 2, source + 5,
                            cell, 1, 1, group, 0, 0);
                }
            }
        }
        appendWallRuns(mesh, terrain, terrainWalls, false);
        appendWallRuns(mesh, water, waterWalls, true);
        return new PredictionQuadMesh(
                terrain.positions(), terrain.normals(), terrain.colors(), terrain.cells(),
                terrain.groups(), terrain.originCells(), terrain.widths(), terrain.heights(),
                terrain.count(),
                water.positions(), water.normals(), water.colors(), water.cells(),
                water.widths(), water.heights(), water.count(), cellAxis);
    }

    private record TopSignature(int y, int c0, int c1, int c2, int c3) {
    }

    private record WallKey(int top, int bottom, int normalX, int normalY, int normalZ,
                           int c0, int c1, int c2, int c3, int group, int unitLength,
                           boolean alongX) {
    }

    private record WallQuad(int source, int cell, int plane, int alongStart,
                            int length, WallKey key) {
    }

    /**
     * Recognizes the top-first, axis-aligned wall shape emitted by terrain and
     * fluid banks.  Feature boxes intentionally use the opposite corner order,
     * so they do not get folded into terrain walls by this optimization.
     */
    private static WallQuad wallQuad(PredictionMesh mesh, int source, int cell,
                                     boolean water) {
        if (!water && VssLodSpriteTable.modelFace((mesh.color(source) >>> 24) & 255)) return null;
        float y0 = water ? mesh.waterY(source) : mesh.y(source);
        float y1 = water ? mesh.waterY(source + 1) : mesh.y(source + 1);
        float y2 = water ? mesh.waterY(source + 2) : mesh.y(source + 2);
        float y3 = water ? mesh.waterY(source + 5) : mesh.y(source + 5);
        if (y0 <= y2 || Float.compare(y0, y1) != 0 || Float.compare(y2, y3) != 0) {
            return null;
        }

        float nx = water ? mesh.waterNormalX(source) : mesh.normalX(source);
        float ny = water ? mesh.waterNormalY(source) : mesh.normalY(source);
        float nz = water ? mesh.waterNormalZ(source) : mesh.normalZ(source);
        if (Math.abs(nx) < 0.9F && Math.abs(nz) < 0.9F) {
            return null;
        }
        for (int corner = 1; corner < 4; corner++) {
            float cornerX = water ? mesh.waterNormalX(source + corner)
                    : mesh.normalX(source + corner);
            float cornerY = water ? mesh.waterNormalY(source + corner)
                    : mesh.normalY(source + corner);
            float cornerZ = water ? mesh.waterNormalZ(source + corner)
                    : mesh.normalZ(source + corner);
            if (Float.compare(nx, cornerX) != 0 || Float.compare(ny, cornerY) != 0
                    || Float.compare(nz, cornerZ) != 0) {
                return null;
            }
        }

        int x0 = rounded(water ? mesh.waterX(source) : mesh.x(source));
        int x1 = rounded(water ? mesh.waterX(source + 1) : mesh.x(source + 1));
        int x2 = rounded(water ? mesh.waterX(source + 2) : mesh.x(source + 2));
        int x3 = rounded(water ? mesh.waterX(source + 5) : mesh.x(source + 5));
        int z0 = rounded(water ? mesh.waterZ(source) : mesh.z(source));
        int z1 = rounded(water ? mesh.waterZ(source + 1) : mesh.z(source + 1));
        int z2 = rounded(water ? mesh.waterZ(source + 2) : mesh.z(source + 2));
        int z3 = rounded(water ? mesh.waterZ(source + 5) : mesh.z(source + 5));
        boolean alongX = Math.abs(nx) < 0.9F;
        int plane;
        int alongStart;
        int length;
        if (alongX) {
            if (z0 != z1 || z2 != z3 || z0 != z2 || x0 != x3 || x1 != x2
                    || x1 <= x0) {
                return null;
            }
            plane = z0;
            alongStart = x0;
            length = x1 - x0;
        } else {
            if (x0 != x1 || x2 != x3 || x0 != x2 || z0 != z3 || z1 != z2
                    || z1 <= z0) {
                return null;
            }
            plane = x0;
            alongStart = z0;
            length = z1 - z0;
        }
        if (length <= 0) {
            return null;
        }
        int[] colors = new int[4];
        for (int corner = 0; corner < 4; corner++) {
            int sourceCorner = corner == 3 ? 5 : corner;
            colors[corner] = water ? mesh.waterColor(source + sourceCorner)
                    : mesh.color(source + sourceCorner);
        }
        int group = water ? VssLodFaceGroup.ofFluidNormal(nx, ny, nz)
                : VssLodFaceGroup.ofNormal(nx, ny, nz);
        WallKey key = new WallKey(rounded(y0), rounded(y2),
                Float.floatToRawIntBits(nx), Float.floatToRawIntBits(ny),
                Float.floatToRawIntBits(nz), colors[0], colors[1], colors[2],
                colors[3], group, length, alongX);
        return new WallQuad(source, cell, plane, alongStart, length, key);
    }

    private static int rounded(float value) {
        return Math.round(value);
    }

    private static void appendWallRuns(PredictionMesh mesh, QuadStorage target,
                                       java.util.List<WallQuad> walls, boolean water) {
        walls.sort(java.util.Comparator.comparingInt(WallQuad::plane)
                .thenComparing(WallQuad::key, PredictionQuadMesh::compareWallKeys)
                .thenComparingInt(WallQuad::alongStart));
        WallQuad first = null;
        int runLength = 0;
        for (WallQuad wall : walls) {
            if (first != null && first.plane() == wall.plane()
                    && first.key().equals(wall.key())
                    && first.key().c0() == first.key().c1()
                    && first.key().c2() == first.key().c3()
                    && wall.alongStart() == first.alongStart() + runLength
                    && runLength + wall.length() <= 256) {
                runLength += wall.length();
                continue;
            }
            if (first != null) {
                appendWallRun(mesh, target, first, runLength, water);
            }
            first = wall;
            runLength = wall.length();
        }
        if (first != null) {
            appendWallRun(mesh, target, first, runLength, water);
        }
    }

    private static int compareWallKeys(WallKey first, WallKey second) {
        int result = Integer.compare(first.group(), second.group());
        if (result != 0) return result;
        result = Integer.compare(first.top(), second.top());
        if (result != 0) return result;
        result = Integer.compare(first.bottom(), second.bottom());
        if (result != 0) return result;
        result = Integer.compare(first.normalX(), second.normalX());
        if (result != 0) return result;
        result = Integer.compare(first.normalY(), second.normalY());
        if (result != 0) return result;
        result = Integer.compare(first.normalZ(), second.normalZ());
        if (result != 0) return result;
        result = Integer.compare(first.c0(), second.c0());
        if (result != 0) return result;
        result = Integer.compare(first.c1(), second.c1());
        if (result != 0) return result;
        result = Integer.compare(first.c2(), second.c2());
        if (result != 0) return result;
        result = Integer.compare(first.c3(), second.c3());
        if (result != 0) return result;
        result = Integer.compare(first.unitLength(), second.unitLength());
        if (result != 0) return result;
        return Boolean.compare(first.alongX(), second.alongX());
    }

    private static void appendWallRun(PredictionMesh mesh, QuadStorage target,
                                      WallQuad first, int runLength, boolean water) {
        int cells = Math.max(1, runLength / first.key().unitLength());
        int width = first.key().alongX() ? cells : 1;
        int height = first.key().alongX() ? 1 : cells;
        int extendX = first.key().alongX() ? runLength - first.length() : 0;
        int extendZ = first.key().alongX() ? 0 : runLength - first.length();
        target.appendWall(mesh, first.source(), first.cell(), width, height,
                first.key().group() | LOD_TEXTURE_SCALE, extendX, extendZ, water);
    }

    public int quadCount() { return quadCount; }
    public int waterQuadCount() { return waterQuadCount; }
    public int cellAxis() { return cellAxis; }
    public int cell(int quad) { return cells[quad]; }
    public int waterCell(int quad) { return waterCells[quad]; }

    float x(int quad, int corner) {
        return positions[quad * 12 + corner * 3];
    }

    float y(int quad, int corner) {
        return positions[quad * 12 + corner * 3 + 1];
    }

    float z(int quad, int corner) {
        return positions[quad * 12 + corner * 3 + 2];
    }

    float normalX(int quad, int corner) {
        return normals[quad * 12 + corner * 3];
    }

    float normalY(int quad, int corner) {
        return normals[quad * 12 + corner * 3 + 1];
    }

    float normalZ(int quad, int corner) {
        return normals[quad * 12 + corner * 3 + 2];
    }

    int color(int quad, int corner) {
        return colors[quad * 4 + corner];
    }

    boolean usesLodTextureScale(int quad) {
        return (groups[quad] & LOD_TEXTURE_SCALE) != 0;
    }

    /** Cell index used by non-merged geometry at coverage boundaries. */
    int coverageCell(int quad) {
        return cells[quad];
    }

    /** Merged quads resolve coverage along their surface from local position. */
    boolean coverageUsesLocalPosition(int quad) {
        return widths[quad] > 1 || heights[quad] > 1;
    }

    /** Merged fluid quads resolve coverage along their surface from local position. */
    boolean waterCoverageUsesLocalPosition(int quad) {
        return waterWidths[quad] > 1 || waterHeights[quad] > 1;
    }

    float waterX(int quad, int corner) {
        return waterPositions[quad * 12 + corner * 3];
    }

    float waterY(int quad, int corner) {
        return waterPositions[quad * 12 + corner * 3 + 1];
    }

    float waterZ(int quad, int corner) {
        return waterPositions[quad * 12 + corner * 3 + 2];
    }

    float waterNormalX(int quad, int corner) {
        return waterNormals[quad * 12 + corner * 3];
    }

    float waterNormalY(int quad, int corner) {
        return waterNormals[quad * 12 + corner * 3 + 1];
    }

    float waterNormalZ(int quad, int corner) {
        return waterNormals[quad * 12 + corner * 3 + 2];
    }

    int waterColor(int quad, int corner) {
        return waterColors[quad * 4 + corner];
    }

    public boolean isQuadOriginForCell(int cell) {
        return cell >= 0 && cell < originLookup.length && originLookup[cell];
    }

    public boolean coversAllowed(int quad, boolean[] allowed) {
        if (quad < 0 || quad >= quadCount) return false;
        int origin = originCells[quad];
        int ox = origin % cellAxis;
        int oz = origin / cellAxis;
        for (int z = oz; z < oz + heights[quad]; z++) {
            for (int x = ox; x < ox + widths[quad]; x++) {
                if (!allowed[z * cellAxis + x]) return false;
            }
        }
        return true;
    }

    public int quadForCell(int cell) {
        return cell < 0 || cell >= cellLookup.length ? -1 : cellLookup[cell];
    }

    public int waterQuadForCell(int cell) {
        return cell < 0 || cell >= waterLookup.length ? -1 : waterLookup[cell];
    }

    public boolean isWaterQuadOriginForCell(int cell) {
        return cell >= 0 && cell < waterOriginLookup.length && waterOriginLookup[cell];
    }

    public boolean waterCoversAllowed(int quad, boolean[] allowed) {
        if (quad < 0 || quad >= waterQuadCount) return false;
        int origin = waterCells[quad];
        int ox = origin % cellAxis;
        int oz = origin / cellAxis;
        for (int z = oz; z < oz + waterHeights[quad]; z++) {
            for (int x = ox; x < ox + waterWidths[quad]; x++) {
                if (!allowed[z * cellAxis + x]) return false;
            }
        }
        return true;
    }

    public void emitTop(PoseStack poseStack, VertexConsumer consumer, int quad,
                        double cameraX, double cameraY, double cameraZ) {
        emit(poseStack, consumer, positions, normals, colors, quad, cameraX, cameraY, cameraZ);
    }

    public void emitWaterTop(PoseStack poseStack, VertexConsumer consumer, int quad,
                             double cameraX, double cameraY, double cameraZ) {
        emit(poseStack, consumer, waterPositions, waterNormals, waterColors, quad, cameraX, cameraY, cameraZ);
    }

    private static void emit(PoseStack poseStack, VertexConsumer consumer, float[] positions,
                             float[] normals, int[] colors, int quad,
                             double cameraX, double cameraY, double cameraZ) {
        int p = quad * 12;
        int c = quad * 4;
        for (int corner = 0; corner < 4; corner++) {
            int offset = p + corner * 3;
            consumer.addVertex(poseStack.last(),
                            (float) (positions[offset] - cameraX),
                            (float) (positions[offset + 1] - cameraY),
                            (float) (positions[offset + 2] - cameraZ))
                    .setColor(colors[c + corner])
                    .setNormal(normals[offset], normals[offset + 1], normals[offset + 2]);
        }
    }

    /** Primitive growable storage used while converting the triangle mesh. */
    private static final class QuadStorage {
        private float[] positions;
        private float[] normals;
        private int[] colors;
        private int[] cells;
        private byte[] groups;
        private int[] originCells;
        private short[] widths;
        private short[] heights;
        private int count;

        private QuadStorage(int initialCapacity) {
            int capacity = Math.max(16, initialCapacity);
            positions = new float[capacity * 12];
            normals = new float[capacity * 12];
            colors = new int[capacity * 4];
            cells = new int[capacity];
            groups = new byte[capacity];
            originCells = new int[capacity];
            widths = new short[capacity];
            heights = new short[capacity];
        }

        private int count() {
            return count;
        }

        private void append(PredictionMesh mesh, int c0, int c1, int c2, int c3,
                            int cell, int width, int height, int group,
                            int extendX, int extendZ) {
            ensure(count + 1);
            int out = count * 12;
            copyTerrainCorner(mesh, c0, out, 0, 0, 0);
            copyTerrainCorner(mesh, c1, out + 3, 1, extendX, 0);
            copyTerrainCorner(mesh, c2, out + 6, 2, extendX, extendZ);
            copyTerrainCorner(mesh, c3, out + 9, 3, 0, extendZ);
            finish(cell, width, height, group);
        }

        private void appendWater(PredictionMesh mesh, int c0, int c1, int c2, int c3,
                                 int cell, int width, int height, int group,
                                 int extendX, int extendZ) {
            ensure(count + 1);
            int out = count * 12;
            copyWaterCorner(mesh, c0, out, 0, 0, 0);
            copyWaterCorner(mesh, c1, out + 3, 1, extendX, 0);
            copyWaterCorner(mesh, c2, out + 6, 2, extendX, extendZ);
            copyWaterCorner(mesh, c3, out + 9, 3, 0, extendZ);
            finish(cell, width, height, group);
        }

        private void appendWall(PredictionMesh mesh, int source, int cell,
                                int width, int height, int group,
                                int extendX, int extendZ, boolean water) {
            ensure(count + 1);
            // Walls are start-top, end-top, end-bottom, start-bottom.
            // Extend both end corners along the run, for either horizontal axis.
            for (int corner = 0; corner < 4; corner++) {
                boolean end = corner == 1 || corner == 2;
                int input = source + (corner == 3 ? 5 : corner);
                int out = count * 12 + corner * 3;
                if (water) {
                    copyWaterCorner(mesh, input, out, corner,
                            end ? extendX : 0, end ? extendZ : 0);
                } else {
                    copyTerrainCorner(mesh, input, out, corner,
                            end ? extendX : 0, end ? extendZ : 0);
                }
            }
            finish(cell, width, height, group);
        }

        private void copyTerrainCorner(PredictionMesh mesh, int source, int out,
                                       int corner, int extendX, int extendZ) {
            positions[out] = mesh.x(source) + extendX;
            positions[out + 1] = mesh.y(source);
            positions[out + 2] = mesh.z(source) + extendZ;
            normals[out] = mesh.normalX(source);
            normals[out + 1] = mesh.normalY(source);
            normals[out + 2] = mesh.normalZ(source);
            colors[count * 4 + corner] = mesh.color(source);
        }

        private void copyWaterCorner(PredictionMesh mesh, int source, int out,
                                     int corner, int extendX, int extendZ) {
            positions[out] = mesh.waterX(source) + extendX;
            positions[out + 1] = mesh.waterY(source);
            positions[out + 2] = mesh.waterZ(source) + extendZ;
            normals[out] = mesh.waterNormalX(source);
            normals[out + 1] = mesh.waterNormalY(source);
            normals[out + 2] = mesh.waterNormalZ(source);
            colors[count * 4 + corner] = mesh.waterColor(source);
        }

        private void finish(int cell, int width, int height, int group) {
            cells[count] = cell;
            groups[count] = (byte) group;
            originCells[count] = cell;
            widths[count] = (short) width;
            heights[count] = (short) height;
            count++;
        }

        private void ensure(int required) {
            if (required <= cells.length) return;
            int capacity = Math.max(required, cells.length * 2);
            positions = java.util.Arrays.copyOf(positions, capacity * 12);
            normals = java.util.Arrays.copyOf(normals, capacity * 12);
            colors = java.util.Arrays.copyOf(colors, capacity * 4);
            cells = java.util.Arrays.copyOf(cells, capacity);
            groups = java.util.Arrays.copyOf(groups, capacity);
            originCells = java.util.Arrays.copyOf(originCells, capacity);
            widths = java.util.Arrays.copyOf(widths, capacity);
            heights = java.util.Arrays.copyOf(heights, capacity);
        }

        private float[] positions() { return java.util.Arrays.copyOf(positions, count * 12); }
        private float[] normals() { return java.util.Arrays.copyOf(normals, count * 12); }
        private int[] colors() { return java.util.Arrays.copyOf(colors, count * 4); }
        private int[] cells() { return java.util.Arrays.copyOf(cells, count); }
        private byte[] groups() { return java.util.Arrays.copyOf(groups, count); }
        private int[] originCells() { return java.util.Arrays.copyOf(originCells, count); }
        private short[] widths() { return java.util.Arrays.copyOf(widths, count); }
        private short[] heights() { return java.util.Arrays.copyOf(heights, count); }
    }

    private int[] buildLookup(int[] origins, short[] widths, short[] heights, int count) {
        int[] lookup = new int[cellAxis * cellAxis];
        java.util.Arrays.fill(lookup, -1);
        for (int index = 0; index < count; index++) {
            int origin = origins[index];
            int ox = origin % cellAxis;
            int oz = origin / cellAxis;
            // Remainder geometry can touch a tile edge, but it must never
            // make the coverage lookup address the neighbouring tile.  The
            // mesh builder rejects outside-corner walls; clamp here as a
            // final guard so malformed/cached geometry cannot discard the
            // entire prediction tile during packing.
            int minX = Math.max(0, ox);
            int maxX = Math.min(cellAxis, ox + Math.max(0, widths[index]));
            int minZ = Math.max(0, oz);
            int maxZ = Math.min(cellAxis, oz + Math.max(0, heights[index]));
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    // Top quads are emitted before remainder geometry. Keep
                    // the top mapping for morph deltas and use remainder
                    // geometry only as a fallback for cells without a top.
                    int cell = z * cellAxis + x;
                    if (lookup[cell] < 0) lookup[cell] = index;
                }
            }
        }
        return lookup;
    }

    private int[] buildWaterLookup(int[] origins, short[] widths, short[] heights, int count) {
        return buildLookup(origins, widths, heights, count);
    }

    private boolean[] buildOriginLookup(int[] origins, int count) {
        boolean[] lookup = new boolean[cellAxis * cellAxis];
        for (int index = 0; index < count; index++) {
            int cell = origins[index];
            if (cell >= 0 && cell < lookup.length) lookup[cell] = true;
        }
        return lookup;
    }
}

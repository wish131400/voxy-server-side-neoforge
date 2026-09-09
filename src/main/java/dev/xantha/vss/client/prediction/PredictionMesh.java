package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Immutable CPU mesh produced on a prediction worker. The render thread walks
 * these arrays only when a tile or its exact-coverage mask changes, uploads a
 * static GPU buffer, and reuses that buffer on subsequent frames.
 */
public final class PredictionMesh {
    final float[] positions;
    final float[] normals;
    final int[] colors;
    final float[] waterPositions;
    final float[] waterNormals;
    final int[] waterColors;
    final boolean[] waterCells;
    final int vertexCount;
    final int waterVertexCount;
    final int cellCount;
    final int[] cellOffsets;
    final int[] cellCounts;
    final int[] waterOffsets;
    final int[] waterCounts;
    private final int cellAxis;
    final int spacingBlocks;
    /** Lazily materialised Packed top-face quad buffer. */
    private volatile PredictionQuadMesh packed;
    private volatile PredictionPackedMesh gpuPayload;

    /** Worker-only packing, completed before this mesh enters render residency. */
    void prepareGpuPayload(PredictionTileManager.PredictionTile tile) {
        gpuPayload = PredictionPackedMesh.pack(tile);
    }

    PredictionPackedMesh gpuPayload() { return gpuPayload; }

    PredictionMesh(float[] positions, float[] normals, int[] colors,
                   float[] waterPositions, float[] waterNormals, int[] waterColors,
                   boolean[] waterCells,
                   int vertexCount, int waterVertexCount, int cellCount) {
        this(positions, normals, colors, waterPositions, waterNormals, waterColors,
                waterCells, vertexCount, waterVertexCount, cellCount,
                null, null, null, null, axisFor(cellCount));
    }

    PredictionMesh(float[] positions, float[] normals, int[] colors,
                   float[] waterPositions, float[] waterNormals, int[] waterColors,
                   boolean[] waterCells,
                   int vertexCount, int waterVertexCount, int cellCount,
                   int[] cellOffsets, int[] cellCounts,
                   int[] waterOffsets, int[] waterCounts) {
        this(positions, normals, colors, waterPositions, waterNormals, waterColors,
                waterCells, vertexCount, waterVertexCount, cellCount,
                cellOffsets, cellCounts, waterOffsets, waterCounts, axisFor(cellCount));
    }

    PredictionMesh(float[] positions, float[] normals, int[] colors,
                   float[] waterPositions, float[] waterNormals, int[] waterColors,
                   boolean[] waterCells,
                   int vertexCount, int waterVertexCount, int cellCount,
                   int[] cellOffsets, int[] cellCounts,
                   int[] waterOffsets, int[] waterCounts, int cellAxis) {
        this(positions, normals, colors, waterPositions, waterNormals, waterColors,
                waterCells, vertexCount, waterVertexCount, cellCount,
                cellOffsets, cellCounts, waterOffsets, waterCounts, cellAxis, 0);
    }

    PredictionMesh(float[] positions, float[] normals, int[] colors,
                   float[] waterPositions, float[] waterNormals, int[] waterColors,
                   boolean[] waterCells, int vertexCount, int waterVertexCount, int cellCount,
                   int[] cellOffsets, int[] cellCounts, int[] waterOffsets, int[] waterCounts,
                   int cellAxis, int spacingBlocks) {
        this.spacingBlocks = spacingBlocks;
        this.positions = positions;
        this.normals = normals;
        this.colors = colors;
        this.waterPositions = waterPositions;
        this.waterNormals = waterNormals;
        this.waterColors = waterColors;
        this.waterCells = waterCells;
        this.vertexCount = vertexCount;
        this.waterVertexCount = waterVertexCount;
        this.cellCount = cellCount;
        this.cellOffsets = cellOffsets;
        this.cellCounts = cellCounts;
        this.waterOffsets = waterOffsets;
        this.waterCounts = waterCounts;
        if (cellAxis < 1 || cellAxis * cellAxis != cellCount) {
            throw new IllegalArgumentException("cellAxis must square to cellCount");
        }
        this.cellAxis = cellAxis;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int cellCount() {
        return cellCount;
    }

    public int cellAxis() {
        return cellAxis;
    }

    public int waterVertexCount() {
        return waterVertexCount;
    }

    /**
     * Returns a compact quad representation of the top faces.  The legacy
     * triangle arrays remain available for walls and for protocol tests, but
     * the renderer uses this buffer for the dominant terrain surface so each
     * cell is submitted as four vertices instead of two duplicated triangles.
     */
    public PredictionQuadMesh packed() {
        PredictionQuadMesh result = packed;
        if (result == null) {
            result = PredictionQuadMesh.from(this);
            packed = result;
        }
        return result;
    }

    /** Worker-only handoff retaining packed rendering and counts, without triangle access. */
    PredictionMesh compactForRendering() {
        PredictionQuadMesh quads = packed();
        PredictionMesh compact = new PredictionMesh(new float[0], new float[0], new int[0],
                new float[0], new float[0], new int[0], new boolean[0],
                vertexCount, waterVertexCount, cellCount, null, null, null, null, cellAxis);
        compact.packed = quads;
        return compact;
    }

    long retainedHeapBytes() {
        long floats = (long) positions.length + normals.length + waterPositions.length + waterNormals.length;
        long ints = (long) colors.length + waterColors.length
                + (cellOffsets == null ? 0 : cellOffsets.length)
                + (cellCounts == null ? 0 : cellCounts.length)
                + (waterOffsets == null ? 0 : waterOffsets.length)
                + (waterCounts == null ? 0 : waterCounts.length);
        return 512L + (floats + ints) * 4L + waterCells.length
                + (packed == null ? 0L : packed.retainedHeapBytes());
    }

    public int cellIndexForVertex(int vertexIndex) {
        return vertexIndex / 6;
    }

    public float x(int vertexIndex) {
        return positions[vertexIndex * 3];
    }

    public float y(int vertexIndex) {
        return positions[vertexIndex * 3 + 1];
    }

    public float z(int vertexIndex) {
        return positions[vertexIndex * 3 + 2];
    }

    public float normalX(int vertexIndex) {
        return normals[vertexIndex * 3];
    }

    public float normalY(int vertexIndex) {
        return normals[vertexIndex * 3 + 1];
    }

    public float normalZ(int vertexIndex) {
        return normals[vertexIndex * 3 + 2];
    }

    public int color(int vertexIndex) {
        return colors[vertexIndex];
    }

    public int waterColor(int vertexIndex) {
        return waterColors[vertexIndex];
    }

    float waterX(int vertexIndex) {
        return waterPositions[vertexIndex * 3];
    }

    float waterY(int vertexIndex) {
        return waterPositions[vertexIndex * 3 + 1];
    }

    float waterZ(int vertexIndex) {
        return waterPositions[vertexIndex * 3 + 2];
    }

    /**
     * Fluid normals carry the the fluid kind in their Y component
     * (1 water, 2 lava, 3 ice) and the bank direction in X/Z, so the shader
     * pass can pick the fluid texture without an extra vertex attribute.
     */
    float waterNormalX(int vertexIndex) {
        return waterNormals[vertexIndex * 3];
    }

    float waterNormalY(int vertexIndex) {
        return waterNormals[vertexIndex * 3 + 1];
    }

    float waterNormalZ(int vertexIndex) {
        return waterNormals[vertexIndex * 3 + 2];
    }

    public void emit(PoseStack poseStack, VertexConsumer consumer, double cameraX, double cameraY, double cameraZ) {
        for (int index = 0; index < vertexCount; index++) {
            emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    public void emitCell(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                         double cameraX, double cameraY, double cameraZ) {
        int firstVertex = cellOffsets == null ? cellIndex * 6 : cellOffsets[cellIndex];
        int count = cellCounts == null ? 6 : cellCounts[cellIndex];
        int lastVertex = Math.min(firstVertex + count, vertexCount);
        for (int index = firstVertex; index < lastVertex; index++) {
            emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    /** Emits the non-top geometry (walls, banks and canopy) for one cell. */
    public void emitCellRemainder(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                                  double cameraX, double cameraY, double cameraZ) {
        int firstVertex = cellOffsets == null ? cellIndex * 6 : cellOffsets[cellIndex];
        int count = cellCounts == null ? 6 : cellCounts[cellIndex];
        int start = Math.min(firstVertex + Math.min(6, count), vertexCount);
        int lastVertex = Math.min(firstVertex + count, vertexCount);
        for (int index = start; index < lastVertex; index++) {
            emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    /** Emits only the six top-face vertices for a cell (fallback when a merged
     * quad crosses an authoritative/predicted coverage boundary). */
    public void emitCellTop(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                            double cameraX, double cameraY, double cameraZ) {
        int firstVertex = cellOffsets == null ? cellIndex * 6 : cellOffsets[cellIndex];
        int count = cellCounts == null ? 6 : cellCounts[cellIndex];
        int lastVertex = Math.min(firstVertex + Math.min(6, count), vertexCount);
        for (int index = firstVertex; index < lastVertex; index++) {
            emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    /** Emits a terrain cell morphed toward its cached parent tile. */
    public void emitCellMorph(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                              double cameraX, double cameraY, double cameraZ,
                              PredictionTileManager.PredictionTile child,
                              PredictionTileManager.PredictionTile parent, float amount) {
        int firstVertex = cellOffsets == null ? cellIndex * 6 : cellOffsets[cellIndex];
        int count = cellCounts == null ? 6 : cellCounts[cellIndex];
        int lastVertex = Math.min(firstVertex + count, vertexCount);
        for (int index = firstVertex; index < lastVertex; index++) {
            int worldX = child.baseBlockX() + Math.round(x(index));
            int worldZ = child.baseBlockZ() + Math.round(z(index));
            int parentY = parent.heightAt(worldX - parent.baseBlockX(), worldZ - parent.baseBlockZ());
            int y = Math.round(y(index));
            int morphed = Math.round(y + (parentY - y) * amount);
            emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ, morphed);
        }
    }

    public void emitWaterCell(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                              double cameraX, double cameraY, double cameraZ) {
        if (cellIndex < 0 || cellIndex >= waterCells.length || !waterCells[cellIndex]) {
            return;
        }
        int firstVertex = waterOffsets == null ? cellIndex * 6 : waterOffsets[cellIndex];
        int count = waterCounts == null ? 6 : waterCounts[cellIndex];
        int lastVertex = Math.min(firstVertex + count, waterVertexCount);
        for (int index = firstVertex; index < lastVertex; index++) {
            emitWaterVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    /** Emits fluid banks and side faces, excluding the packed top quad. */
    public void emitWaterRemainder(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                                   double cameraX, double cameraY, double cameraZ) {
        if (cellIndex < 0 || cellIndex >= waterCells.length || !waterCells[cellIndex]) {
            return;
        }
        int firstVertex = waterOffsets == null ? cellIndex * 6 : waterOffsets[cellIndex];
        int count = waterCounts == null ? 6 : waterCounts[cellIndex];
        int start = Math.min(firstVertex + Math.min(6, count), waterVertexCount);
        int lastVertex = Math.min(firstVertex + count, waterVertexCount);
        for (int index = start; index < lastVertex; index++) {
            emitWaterVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    public void emitWaterTop(PoseStack poseStack, VertexConsumer consumer, int cellIndex,
                             double cameraX, double cameraY, double cameraZ) {
        if (cellIndex < 0 || cellIndex >= waterCells.length || !waterCells[cellIndex]) return;
        int firstVertex = waterOffsets == null ? cellIndex * 6 : waterOffsets[cellIndex];
        int count = waterCounts == null ? 6 : waterCounts[cellIndex];
        int lastVertex = Math.min(firstVertex + Math.min(6, count), waterVertexCount);
        for (int index = firstVertex; index < lastVertex; index++) {
            emitWaterVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ);
        }
    }

    private void emitVertex(PoseStack poseStack, VertexConsumer consumer, int index,
                            double cameraX, double cameraY, double cameraZ) {
        emitVertex(poseStack, consumer, index, cameraX, cameraY, cameraZ, Math.round(y(index)));
    }

    private void emitVertex(PoseStack poseStack, VertexConsumer consumer, int index,
                            double cameraX, double cameraY, double cameraZ, int yOverride) {
        consumer.addVertex(
                        poseStack.last(),
                        (float) (x(index) - cameraX),
                        (float) (yOverride - cameraY),
                        (float) (z(index) - cameraZ))
                .setColor(color(index))
                .setNormal(normalX(index), normalY(index), normalZ(index));
    }

    private void emitWaterVertex(PoseStack poseStack, VertexConsumer consumer, int index,
                                 double cameraX, double cameraY, double cameraZ) {
        int position = index * 3;
        consumer.addVertex(
                        poseStack.last(),
                        (float) (waterPositions[position] - cameraX),
                        (float) (waterPositions[position + 1] - cameraY),
                        (float) (waterPositions[position + 2] - cameraZ))
                .setColor(waterColors[index])
                .setNormal(waterNormals[position], waterNormals[position + 1], waterNormals[position + 2]);
    }

    private static int axisFor(int cellCount) {
        int axis = (int) Math.round(Math.sqrt(Math.max(1, cellCount)));
        if (axis * axis != cellCount) {
            throw new IllegalArgumentException("cellCount must be square");
        }
        return axis;
    }
}

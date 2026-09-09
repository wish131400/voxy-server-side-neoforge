package dev.xantha.vss.client.prediction;

import java.util.Arrays;

/** Builds a compact triangle grid from a 17x17 height sample. */
public final class PredictionMeshBuilder {
    private static final int GRID_SIZE = 17;
    private static final int CELL_COUNT = (GRID_SIZE - 1) * (GRID_SIZE - 1);
    private static final int VERTICES_PER_CELL = 6;

    /**
     * the renders sampled fluids at eight ninths height, lowering the
     * surface by 1.001 - 8/9 blocks so predicted water meets vanilla's
     * still-water line at the shoreline.
     */
    static final float FLUID_SURFACE_DROP = 1.001F - 8.0F / 9.0F;
    /** Percentage of sampler-marked cells that actually grow a ground
     *  plant; vanilla ground cover is patchy, not one cross per cell. */

    private PredictionMeshBuilder() {
    }

    public static PredictionMesh build(int[] heights, int stepBlocks) {
        return build(heights, heights, Integer.MIN_VALUE, stepBlocks);
    }

    /** Builds terrain plus a separate sea-level surface for fully submerged cells. */
    public static PredictionMesh build(int[] surfaceHeights, int[] groundHeights,
                                       int seaLevel, int stepBlocks) {
        return build(surfaceHeights, groundHeights, seaLevel, 0xB22D78C5, stepBlocks);
    }

    public static PredictionMesh build(int[] surfaceHeights, int[] groundHeights,
                                       int seaLevel, int fluidColor, int stepBlocks) {
        return build(surfaceHeights, groundHeights, seaLevel, fluidColor, null, stepBlocks);
    }

    public static PredictionMesh build(int[] surfaceHeights, int[] groundHeights,
                                       int seaLevel, int fluidColor, int[] materialColors,
                                       int stepBlocks) {
        if (surfaceHeights == null || surfaceHeights.length != GRID_SIZE * GRID_SIZE
                || groundHeights == null || groundHeights.length != GRID_SIZE * GRID_SIZE) {
            throw new IllegalArgumentException("Prediction mesh requires a 17x17 height sample");
        }
        if (stepBlocks <= 0) {
            throw new IllegalArgumentException("stepBlocks must be positive");
        }

        int vertexCount = CELL_COUNT * VERTICES_PER_CELL;
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        int[] colors = new int[vertexCount];
        float[] waterPositions = new float[vertexCount * 3];
        float[] waterNormals = new float[vertexCount * 3];
        int[] waterColors = new int[vertexCount];
        boolean[] waterCells = new boolean[CELL_COUNT];
        int out = 0;
        int waterVertexCount = 0;
        for (int z = 0; z < GRID_SIZE - 1; z++) {
            for (int x = 0; x < GRID_SIZE - 1; x++) {
                int h00 = surfaceHeights[index(x, z)];
                int h10 = surfaceHeights[index(x + 1, z)];
                int h01 = surfaceHeights[index(x, z + 1)];
                int h11 = surfaceHeights[index(x + 1, z + 1)];
                int y00 = seaLevel != Integer.MIN_VALUE && h00 < seaLevel
                        ? groundHeights[index(x, z)] : h00;
                int y10 = seaLevel != Integer.MIN_VALUE && h10 < seaLevel
                        ? groundHeights[index(x + 1, z)] : h10;
                int y01 = seaLevel != Integer.MIN_VALUE && h01 < seaLevel
                        ? groundHeights[index(x, z + 1)] : h01;
                int y11 = seaLevel != Integer.MIN_VALUE && h11 < seaLevel
                        ? groundHeights[index(x + 1, z + 1)] : h11;
                int materialColor = materialColors == null ? 0 : averageMaterialColor(materialColors,
                        index(x, z), index(x + 1, z), index(x, z + 1), index(x + 1, z + 1));
                int cellColor = colorFor(y00, y10, y01, y11, materialColor);

                float[] n00 = normal(y00, y10, y01, stepBlocks);
                float[] n10 = normal(y00, y10, y11, stepBlocks);
                float[] n11 = normal(y10, y11, y01, stepBlocks);
                float[] n01 = normal(y00, y11, y01, stepBlocks);
                out = writeVertex(positions, normals, colors, out, x * stepBlocks, y00, z * stepBlocks, n00, cellColor);
                out = writeVertex(positions, normals, colors, out, (x + 1) * stepBlocks, y10, z * stepBlocks, n10, cellColor);
                out = writeVertex(positions, normals, colors, out, (x + 1) * stepBlocks, y11, (z + 1) * stepBlocks, n11, cellColor);
                out = writeVertex(positions, normals, colors, out, x * stepBlocks, y00, z * stepBlocks, n00, cellColor);
                out = writeVertex(positions, normals, colors, out, (x + 1) * stepBlocks, y11, (z + 1) * stepBlocks, n11, cellColor);
                out = writeVertex(positions, normals, colors, out, x * stepBlocks, y01, (z + 1) * stepBlocks, n01, cellColor);

                if (seaLevel != Integer.MIN_VALUE && h00 < seaLevel && h10 < seaLevel
                        && h01 < seaLevel && h11 < seaLevel) {
                    int cell = z * (GRID_SIZE - 1) + x;
                    waterCells[cell] = true;
                    int waterColor = fluidColor;
                    float[] waterNormal = new float[]{0.0F, 1.0F, 0.0F};
                    int waterOut = cell * 6;
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            x * stepBlocks, seaLevel, z * stepBlocks, waterNormal, waterColor);
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            (x + 1) * stepBlocks, seaLevel, z * stepBlocks, waterNormal, waterColor);
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            (x + 1) * stepBlocks, seaLevel, (z + 1) * stepBlocks, waterNormal, waterColor);
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            x * stepBlocks, seaLevel, z * stepBlocks, waterNormal, waterColor);
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            (x + 1) * stepBlocks, seaLevel, (z + 1) * stepBlocks, waterNormal, waterColor);
                    waterOut = writeVertex(waterPositions, waterNormals, waterColors, waterOut,
                            x * stepBlocks, seaLevel, (z + 1) * stepBlocks, waterNormal, waterColor);
                    waterVertexCount += 6;
                }
            }
        }
        return new PredictionMesh(
                Arrays.copyOf(positions, out * 3),
                Arrays.copyOf(normals, out * 3),
                Arrays.copyOf(colors, out),
                waterPositions,
                waterNormals,
                waterColors,
                waterCells,
                out,
                waterVertexCount,
                CELL_COUNT);
    }

    /**
     * Builds the richer Packed-quad mesh from complete column samples. The
     * legacy height overload above remains intentionally small for protocol
     * and unit-test compatibility; this path emits exposed vertical walls,
     * lower density spans and fluid banks with per-cell ranges.
     */
    public static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                       int seaLevel, int fluidColor, int stepBlocks) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, GRID_SIZE, true);
    }

    /** Builds a The finest-resolution mesh. The legacy overload remains 17x17 for API compatibility. */
    public static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                       int seaLevel, int fluidColor, int stepBlocks,
                                       int gridSize) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, gridSize, true);
    }

    public static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                       int seaLevel, int fluidColor, int stepBlocks,
                                       int gridSize, boolean treesEnabled) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, gridSize,
                treesEnabled, null);
    }

    static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                int seaLevel, int fluidColor, int stepBlocks,
                                int gridSize, boolean treesEnabled,
                                PredictionFeatureStampCache featureStamps) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, gridSize,
                treesEnabled, featureStamps, null);
    }

    /**
     * @param foliageColors per-column {@code 0xFFRRGGBB} foliage tint (or
     *        null) so feature stamps take the column biome's leaf colour
     *        instead of the spawn-biome registry tint.
     */
    static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                int seaLevel, int fluidColor, int stepBlocks,
                                int gridSize, boolean treesEnabled,
                                PredictionFeatureStampCache featureStamps,
                                int[] foliageColors) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, gridSize,
                treesEnabled, featureStamps, foliageColors, null, 0, 0);
    }

    static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                int seaLevel, int fluidColor, int stepBlocks, int gridSize,
                                boolean treesEnabled, PredictionFeatureStampCache featureStamps,
                                int[] foliageColors, int[] waterColors, int baseX, int baseZ) {
        return build(samples, materialColors, seaLevel, fluidColor, stepBlocks, gridSize,
                treesEnabled, featureStamps, foliageColors, waterColors, baseX, baseZ, null);
    }

    static PredictionMesh build(ClientColumnSample[] samples, int[] materialColors,
                                int seaLevel, int fluidColor, int stepBlocks, int gridSize,
                                boolean treesEnabled, PredictionFeatureStampCache featureStamps,
                                int[] foliageColors, int[] waterColors, int baseX, int baseZ,
                                PredictionVegetation.Tile vegetation) {
        if (gridSize < 2 || gridSize > 257) {
            throw new IllegalArgumentException("gridSize outside supported range: " + gridSize);
        }
        if (samples == null || samples.length != gridSize * gridSize) {
            throw new IllegalArgumentException("Prediction mesh requires a square column sample");
        }
        if (stepBlocks <= 0) {
            throw new IllegalArgumentException("stepBlocks must be positive");
        }
        if (materialColors != null && materialColors.length != samples.length) {
            throw new IllegalArgumentException("materialColors must match samples");
        }
        if (waterColors != null && waterColors.length != samples.length) {
            throw new IllegalArgumentException("waterColors must match samples");
        }
        // the stores one-sample margins on all sides (66 samples for a
        // 64-quad tile). Crop those margins before meshing so the output has
        // exactly the 64x64 cell topology while retaining the border samples
        // for interpolation at the worker boundary.
        // Runtime tiles use 66, 34, 18 or 10 samples (one border sample
        // on each side). Keep the legacy 17x17 overload un-cropped.
        if ((gridSize == 10 || gridSize >= VssLodLayout.TILE_QUADS / 4 + 2)
                && (gridSize & 1) == 0) {
            int croppedGrid = gridSize - 1;
            ClientColumnSample[] cropped = new ClientColumnSample[croppedGrid * croppedGrid];
            int[] croppedColors = materialColors == null ? null : new int[cropped.length];
            int[] croppedFoliage = foliageColors == null ? null : new int[cropped.length];
            int[] croppedWater = waterColors == null ? null : new int[cropped.length];
            for (int z = 0; z < croppedGrid; z++) {
                for (int x = 0; x < croppedGrid; x++) {
                    int source = (z + 1) * gridSize + (x + 1);
                    int target = z * croppedGrid + x;
                    cropped[target] = samples[source];
                    if (croppedColors != null) croppedColors[target] = materialColors[source];
                    if (croppedFoliage != null) croppedFoliage[target] = foliageColors[source];
                    if (croppedWater != null) croppedWater[target] = waterColors[source];
                }
            }
            return build(cropped, croppedColors, seaLevel, fluidColor, stepBlocks, croppedGrid,
                    treesEnabled, featureStamps, croppedFoliage, croppedWater, baseX, baseZ, vegetation);
        }
        int cellAxis = gridSize - 1;
        int cellCount = cellAxis * cellAxis;
        // A normal cell is two triangles (six vertices). Walls and feature
        // hints grow this dynamically; over-reserving five times that amount
        // for every 64x64 tile creates avoidable multi-megabyte spikes.
        VertexAccumulator terrain = new VertexAccumulator(cellCount * 8);
        VertexAccumulator water = new VertexAccumulator(cellCount * 8);
        boolean[] waterCells = new boolean[cellCount];
        int[] cellOffsets = new int[cellCount];
        int[] cellCounts = new int[cellCount];
        int[] waterOffsets = new int[cellCount];
        int[] waterCounts = new int[cellCount];
        // Precompute the the height field once so corner lighting,
        // wall exposure and the median filter all see the same values.
        int[] cornerHeights = new int[samples.length];
        for (int i = 0; i < samples.length; i++) {
            cornerHeights[i] = terrainHeight(samples[i], seaLevel);
        }
        stabilizeGeneratedSurface(samples, cornerHeights, stepBlocks, gridSize);
        // the reference UseAverage path: at spacing >= 4 each column's top quad
        // carries per-corner colours averaged over the four columns meeting
        // at that corner (topCorner), so the hardware's triangle interpolation
        // yields smooth per-fragment colour across the grid instead of flat
        // per-column patches.  Fine spacing keeps the flat colour path that
        // the real-texture detail pass replaces anyway.
        boolean columnBlend = stepBlocks >= 4 && materialColors != null;
        int[] columnBlendColors = columnBlend ? materialColors : null;
        var fluidOcclusion = new PredictionFluidOcclusion(vegetation, cellAxis, stepBlocks, samples);
        var surfaceEdits = new PredictionSurfaceEdits(vegetation, cellAxis, stepBlocks, cornerHeights);
        for (int z = 0; z < cellAxis; z++) {
            for (int x = 0; x < cellAxis; x++) {
                int cell = z * cellAxis + x;
                cellOffsets[cell] = terrain.vertexCount();
                ClientColumnSample s00 = samples[index(x, z, gridSize)];
                ClientColumnSample s10 = samples[index(x + 1, z, gridSize)];
                ClientColumnSample s01 = samples[index(x, z + 1, gridSize)];
                ClientColumnSample s11 = samples[index(x + 1, z + 1, gridSize)];
                int h00 = cornerHeights[index(x, z, gridSize)];
                int h10 = cornerHeights[index(x + 1, z, gridSize)];
                int h01 = cornerHeights[index(x, z + 1, gridSize)];
                int h11 = cornerHeights[index(x + 1, z + 1, gridSize)];
                int c00 = materialColors == null ? 0 : materialColors[index(x, z, gridSize)];
                int c10 = materialColors == null ? 0 : materialColors[index(x + 1, z, gridSize)];
                int c01 = materialColors == null ? 0 : materialColors[index(x, z + 1, gridSize)];
                int c11 = materialColors == null ? 0 : materialColors[index(x + 1, z + 1, gridSize)];
                // Block-level topology: this cell emits only its own
                // north-west column; the other three corners belong to the
                // neighbouring cells, so a dense grid emits every column
                // exactly once.
                if (surfaceEdits.affects(cell)) {
                    addEditedGround(terrain, samples, cornerHeights, materialColors, surfaceEdits,
                            x, z, stepBlocks, gridSize, seaLevel);
                } else {
                    addColumnBlock(terrain, samples, cornerHeights, x, z, stepBlocks, gridSize,
                            h00, c00, seaLevel, columnBlendColors);
                }
                // The east and south margin columns belong to the
                // neighbouring tiles, so they get no top here.  Only their
                // inward-facing seam wall joins this cell's vertex range: the
                // GPU path draws per-cell ranges, so a wall appended outside
                // every cell would never render, and a top would be drawn
                // twice (once here, once by the owner tile).  The higher side
                // of each seam emits the wall exactly once; the opposite case
                // is covered by the neighbouring tile's own column wall.
                if (x == cellAxis - 1 && !surfaceEdits.affects(cell)) {
                    emitColumnWall(terrain, samples, cornerHeights, x + 1, z, stepBlocks, gridSize,
                            cornerHeights[index(x + 1, z, gridSize)],
                            materialColors == null ? 0 : materialColors[index(x + 1, z, gridSize)],
                            seaLevel, -1, 0);
                }
                if (z == cellAxis - 1 && !surfaceEdits.affects(cell)) {
                    emitColumnWall(terrain, samples, cornerHeights, x, z + 1, stepBlocks, gridSize,
                            cornerHeights[index(x, z + 1, gridSize)],
                            materialColors == null ? 0 : materialColors[index(x, z + 1, gridSize)],
                            seaLevel, 0, -1);
                }
                // Prediction supplies the exterior surface only. Underground
                // ceilings, overhang undersides, and structures belong to
                // exact terrain.  Structure hints remain in the sample so a
                // future structure-aware predictor can consume them, but a
                // generic box cannot represent either an above-ground or an
                // underground structure without producing false geometry.
                int foliageTint = foliageColors == null
                        ? 0 : foliageColors[index(x, z, gridSize)];
                if (vegetation != null) {
                    addPlacedVegetation(terrain, vegetation, cell, h00, foliageTint, surfaceEdits);
                }

                boolean fluid = s00.hasFluid();
                waterOffsets[cell] = water.vertexCount();
                if (vegetation != null) {
                    int tint = waterColors == null ? 0 : waterColors[index(x, z, gridSize)];
                    addPlacedFluids(water, vegetation, cell, tint == 0 ? fluidColor : tint);
                }
                if (fluid) {
                    int fluidY = s00.fluidY();
                    int kind = s00.ice() ? 3 : s00.fluid() == 2 ? 2 : 1;
                    int sampledWater = waterColors == null ? 0 : waterColors[index(x, z, gridSize)];
                    int fluidTint = kind == 1 && sampledWater != 0
                            ? sampledWater : fluidSurfaceColor(kind, fluidColor);
                    // the renders sampled fluids eight-ninths full, which
                    // drops the predicted surface onto vanilla's still-water
                    // line instead of sitting a tenth of a block proud of the
                    // shoreline. Ice keeps the exact captured surface.
                    float surfaceY = kind == 3 ? fluidY : fluidY - FLUID_SURFACE_DROP;
                    var face = new PredictionFluidOcclusion.Rect(x * stepBlocks, z * stepBlocks,
                            (x + 1) * stepBlocks, (z + 1) * stepBlocks);
                    for (var rect : fluidOcclusion.visible(cell, 0, surfaceY, face))
                        addFluidRectangle(water, 0, surfaceY, rect, fluidTint, kind, 1);
                    addFluidWalls(water, samples, x, z, stepBlocks, surfaceY, fluidTint,
                            gridSize, kind, fluidOcclusion, cell);
                }
                waterCounts[cell] = water.vertexCount() - waterOffsets[cell];
                waterCells[cell] = waterCounts[cell] > 0;
                cellCounts[cell] = terrain.vertexCount() - cellOffsets[cell];
            }
        }
        return new PredictionMesh(terrain.positions(), terrain.normals(), terrain.colors(),
                water.positions(), water.normals(), water.colors(), waterCells,
                terrain.vertexCount(), water.vertexCount(), cellCount,
                cellOffsets, cellCounts, waterOffsets, waterCounts, cellAxis, stepBlocks);
    }

    private static int terrainHeight(ClientColumnSample sample, int seaLevel) {
        return seaLevel != Integer.MIN_VALUE && sample.fluid() != 0
                ? sample.surfaceY() : sample.surfaceY();
    }

    /**
     * Suppresses isolated height noise only within matching material strata.
     * Distinct surfaces and spans retain their sampled geometry and appearance.
     */
    private static void stabilizeGeneratedSurface(ClientColumnSample[] samples, int[] heights,
                                                   int step, int gridSize) {
        // A one-block sample is the finest surface, not a coarse height hint.
        if (step <= 1 || step > 4 || gridSize < 3) {
            return;
        }
        int[] replacements = new int[samples.length];
        java.util.Arrays.fill(replacements, Integer.MIN_VALUE);
        int[] neighbors = new int[8];
        for (int z = 1; z < gridSize - 1; z++) {
            for (int x = 1; x < gridSize - 1; x++) {
                int center = index(x, z, gridSize);
                ClientColumnSample sample = samples[center];
                if (sample.captured() || sample.surfaceOnly() || !sample.hasSurface() || sample.fluid() != 0
                        || sample.floating() || sample.hasLowerSpan()) continue;
                int cursor = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) continue;
                        neighbors[cursor++] = index(x + dx, z + dz, gridSize);
                    }
                }
                for (int a = 1; a < neighbors.length; a++) {
                    int candidate = neighbors[a];
                    int b = a - 1;
                    while (b >= 0 && heights[neighbors[b]] > heights[candidate]) {
                        neighbors[b + 1] = neighbors[b];
                        b--;
                    }
                    neighbors[b + 1] = candidate;
                }
                int replacement = neighbors[neighbors.length / 2];
                int median = heights[replacement];
                int support = 0;
                for (int neighbor : neighbors) {
                    ClientColumnSample adjacent = samples[neighbor];
                    if (Math.abs(heights[neighbor] - median) <= 4
                            && sameSurfaceMaterial(sample, adjacent)
                            && sample.underBlockIndex() == adjacent.underBlockIndex()
                            && sample.deepBlockIndex() == adjacent.deepBlockIndex()
                            && !adjacent.surfaceOnly() && !adjacent.floating() && !adjacent.hasLowerSpan()) {
                        support++;
                    }
                }
                int minimumDelta = Math.max(12, step * 2);
                if (support < 5 || Math.abs(heights[center] - median) < minimumDelta) {
                    continue;
                }
                replacements[center] = median;
            }
        }
        for (int i = 0; i < replacements.length; i++) {
            if (replacements[i] != Integer.MIN_VALUE) heights[i] = replacements[i];
        }
    }

    private static boolean sameSurfaceMaterial(ClientColumnSample a, ClientColumnSample b) {
        return a.topBlockIndex() == b.topBlockIndex() && a.fluid() == b.fluid()
                && a.snow() == b.snow() && a.ice() == b.ice();
    }

    /**
     * Emits the two-triangle top face with the per-corner vertex
     * lighting.  Each corner colour is the column's own material tint
     * multiplied by the ambient-occlusion factor derived from the three
     * neighbouring heights around that corner.  Shared corners therefore
     * interpolate smoothly instead of one flat average per cell.
     */
    /**
     * Emits the block-style top face: one flat quad at the cell's own
     * height, exactly like the {@code emitCell}.  the never
     * triangulates the four corner heights into a slope; height differences
     * between neighbouring columns are shown by the wall faces, which is
     * what makes the LOD read as individual blocks instead of a smooth
     * height field.  The cell renders at its minimum corner so a higher
     * neighbour column exposes its wall down to this surface.
     */
    /**
     * One Minecraft-block-style column: a flat top quad at the column's own
     * height over its step-sized footprint plus one wall face for every
     * direction where the neighbouring column is lower.  This replaces the
     * interpolated height-field triangle pair: slopes become stepped block
     * faces, which is what makes prediction LOD read like real blocks the
     * way the column quads do.
     */
    private static void addColumnBlock(VertexAccumulator out, ClientColumnSample[] samples,
                                       int[] heights, int x, int z, int step, int gridSize,
                                       int height, int color, int seaLevel,
                                       int[] blendColors) {
        if (x < 0 || z < 0 || x >= gridSize || z >= gridSize || height == Integer.MIN_VALUE) {
            return;
        }
        if (!samples[index(x, z, gridSize)].hasSurface()) return;
        float[] up = {0.0F, 1.0F, 0.0F};
        int x0 = x * step;
        int z0 = z * step;
        if (blendColors != null) {
            // UseAverage path: each corner's colour is the average of the
            // four columns meeting at that corner, shaded by that corner's
            // own occluder count (the top-corner rule).  Flat regions still
            // carry equal corner colours so greedy merging survives.
            int vA = cornerColor(samples, heights, blendColors, x, z, x, z, height, gridSize);
            int vB = cornerColor(samples, heights, blendColors, x, z, x + 1, z, height, gridSize);
            int vC = cornerColor(samples, heights, blendColors, x, z, x + 1, z + 1, height, gridSize);
            int vD = cornerColor(samples, heights, blendColors, x, z, x, z + 1, height, gridSize);
            int sprite = topSprite(samples[index(x, z, gridSize)]);
            vA = packSprite(vA, sprite);
            vB = packSprite(vB, sprite);
            vC = packSprite(vC, sprite);
            vD = packSprite(vD, sprite);
            out.triangle(x0, height, z0, up, vA,
                    x0 + step, height, z0, up, vB,
                    x0 + step, height, z0 + step, up, vC);
            out.triangle(x0, height, z0, up, vA,
                    x0 + step, height, z0 + step, up, vC,
                    x0, height, z0 + step, up, vD);
        } else {
            // Detail path: vanilla-style per-corner ambient occlusion.  Each
            // corner darkens by the neighbouring columns meeting there, so
            // blocks against a slope or cliff shade smoothly like real chunk
            // geometry instead of one flat tone per column.
            int vA = detailCorner(heights, x, z, x, z, height, color, gridSize);
            int vB = detailCorner(heights, x, z, x + 1, z, height, color, gridSize);
            int vC = detailCorner(heights, x, z, x + 1, z + 1, height, color, gridSize);
            int vD = detailCorner(heights, x, z, x, z + 1, height, color, gridSize);
            int sprite = topSprite(samples[index(x, z, gridSize)]);
            vA = packSprite(vA, sprite);
            vB = packSprite(vB, sprite);
            vC = packSprite(vC, sprite);
            vD = packSprite(vD, sprite);
            out.triangle(x0, height, z0, up, vA,
                    x0 + step, height, z0, up, vB,
                    x0 + step, height, z0 + step, up, vC);
            out.triangle(x0, height, z0, up, vA,
                    x0 + step, height, z0 + step, up, vC,
                    x0, height, z0 + step, up, vD);
        }
        // Walls toward lower neighbours; tile-border columns skip the face
        // pointing outward (the neighbouring tile owns that seam).
        emitColumnWall(out, samples, heights, x, z, step, gridSize, height, color, seaLevel, 1, 0);
        emitColumnWall(out, samples, heights, x, z, step, gridSize, height, color, seaLevel, -1, 0);
        emitColumnWall(out, samples, heights, x, z, step, gridSize, height, color, seaLevel, 0, 1);
        emitColumnWall(out, samples, heights, x, z, step, gridSize, height, color, seaLevel, 0, -1);
    }

    /**
     * A column-quad corner colour: average of the material colours of the
     * matching-material columns meeting at grid point (cornerX, cornerZ), multiplied by
     * the corner's ambient-occlusion factor.  Our own column (x, z) is one
     * of the four; the other three occlude the corner when they are higher.
     */
    private static int cornerColor(ClientColumnSample[] samples, int[] heights, int[] colors, int x, int z,
                                   int cornerX, int cornerZ, int height, int gridSize) {
        int r = 0;
        int g = 0;
        int b = 0;
        int count = 0;
        int occluders = 0;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int sx = cornerX + dx;
                int sz = cornerZ + dz;
                if (sx < 0 || sz < 0 || sx >= gridSize || sz >= gridSize) {
                    continue;
                }
                if ((sx != x || sz != z) && heights[sz * gridSize + sx] > height) {
                    occluders++;
                }
                int c = colors[sz * gridSize + sx];
                if (c == 0 || !sameSurfaceMaterial(samples[z * gridSize + x],
                        samples[sz * gridSize + sx])) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return vertexColor(heights, x, z, height, 0, gridSize);
        }
        float factor = PredictionLighting.combine(occluders, 15) / 15.0F;
        int cr = Math.round(r / (float) count * factor);
        int cg = Math.round(g / (float) count * factor);
        int cb = Math.round(b / (float) count * factor);
        return 0xFF000000 | cr << 16 | cg << 8 | cb;
    }

    /**
     * Vanilla-style per-corner AO for the detail path: the corner darkens by
     * the occluders among the three neighbouring columns meeting there, while
     * the colour stays the column's own (no cross-column averaging — the
     * sprite carries the texture detail at this spacing).
     */
    private static int detailCorner(int[] heights, int x, int z,
                                    int cornerX, int cornerZ, int height, int color,
                                    int gridSize) {
        int occluders = 0;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int sx = cornerX + dx;
                int sz = cornerZ + dz;
                if (sx < 0 || sz < 0 || sx >= gridSize || sz >= gridSize) {
                    continue;
                }
                if ((sx != x || sz != z) && heights[sz * gridSize + sx] > height) {
                    occluders++;
                }
            }
        }
        float factor = PredictionLighting.combine(occluders, 15) / 15.0F;
        int r = Math.round(((color == 0 ? 82 : (color >> 16) & 0xFF)) * factor);
        int g = Math.round(((color == 0 ? 116 : (color >> 8) & 0xFF)) * factor);
        int b = Math.round(((color == 0 ? 64 : (color & 0xFF))) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * The block id whose sprites represent a column's surface: tree columns
     * keep grass under their canopy — the forest's leaf representative only
     * feeds the coarse averaged colour path. Snow covers only the up face;
     * wall bands resolve the underlying ground material separately.
     */
    static int surfaceSpriteBlock(ClientColumnSample sample) {
        return PredictionMaterialPalette.surfaceBlock(sample);
    }

    private static int topSprite(ClientColumnSample sample) {
        int block = surfaceSpriteBlock(sample);
        return block == ClientColumnSample.NO_BLOCK
                ? VssLodSpriteTable.FLAT
                : VssLodSpriteTable.indexForBlock(block);
    }

    /** One wall face from the column top down to a lower neighbour. */
    private static void emitColumnWall(VertexAccumulator out, ClientColumnSample[] samples,
                                       int[] heights, int x, int z, int step, int gridSize,
                                       int height, int color, int seaLevel,
                                       int nx, int nz) {
        if (!samples[index(x, z, gridSize)].hasSurface()) return;
        int neighbourX = x + nx;
        int neighbourZ = z + nz;
        if (neighbourX < 0 || neighbourZ < 0
                || neighbourX >= gridSize || neighbourZ >= gridSize) {
            return;
        }
        int neighbour = heights[index(neighbourX, neighbourZ, gridSize)];
        if (neighbour == Integer.MIN_VALUE || height <= neighbour) {
            return;
        }
        // The wall spans the full height difference down to the neighbour's
        // surface; below that the neighbour's own column occludes the face,
        // so nothing is left open on tall cliffs.
        int bottom = neighbour;
        ClientColumnSample sample = samples[index(x, z, gridSize)];
        int surfaceColor = color;
        // Preserve each stratum's material. Only grass approximates the
        // vanilla tinted rim with a gradient into its dirt side.
        int topBlock = PredictionMaterialPalette.groundBlock(sample);
        boolean grass = topBlock == PredictionMaterialPalette.grassBlockIndex();
        int underBlock = sample.underBlockIndex() == ClientColumnSample.NO_BLOCK
                ? (grass || topBlock == ClientColumnSample.NO_BLOCK
                    ? PredictionMaterialPalette.dirtIndex() : topBlock)
                : sample.underBlockIndex();
        int deepBlock = sample.deepBlockIndex() == ClientColumnSample.NO_BLOCK
                ? (grass || topBlock == ClientColumnSample.NO_BLOCK
                    ? PredictionMaterialPalette.stoneIndex() : underBlock)
                : sample.deepBlockIndex();
        int face = wallFace(nx, nz);
        int topColor = withSideSprite(PredictionMaterialPalette.colorForIndex(
                topBlock, surfaceColor, face), topBlock, face);
        int topBandBottom = grass ? withSideSprite(PredictionMaterialPalette.colorForIndex(
                underBlock, surfaceColor, face),
                topBlock, face) : topColor;
        int underColor = withSideSprite(PredictionMaterialPalette.colorForIndex(
                underBlock, surfaceColor, face),
                underBlock, face);
        int deepColor = withSideSprite(PredictionMaterialPalette.colorForIndex(
                deepBlock, surfaceColor, face),
                deepBlock, face);
        // Wall plane coordinates: the face sits on the shared edge.
        int ax = nx > 0 ? x + 1 : x;
        int az = nz > 0 ? z + 1 : z;
        int bx = ax + (nz != 0 ? 1 : 0);
        int bz = az + (nx != 0 ? 1 : 0);
        float[] normal = {nx, 0.0F, nz};
        // the strata: the top band is [top-1, top], the under band
        // [top-2, top-1], and everything below is deep material down to the
        // neighbour surface.  Clamping against the bottom keeps short walls
        // degenerate instead of inverted.
        int underStart = Math.max(bottom, height - 1);
        int deepStart = Math.max(bottom, height - 2);
        emitBandGradient(out, ax, az, bx, bz, height, underStart,
                topColor, topBandBottom, normal, step);
        emitBand(out, ax, az, bx, bz, underStart, deepStart, underColor, normal, step);
        emitBand(out, ax, az, bx, bz, deepStart, bottom, deepColor, normal, step);
    }

    /** A vertical quad between two column-edge points and a depth range. */
    private static void emitBand(VertexAccumulator out, int ax, int az, int bx, int bz,
                                 int top, int bottom, int color, float[] normal, int step) {
        if (bottom >= top) {
            return;
        }
        out.triangle(ax * step, top, az * step, normal, color,
                bx * step, top, bz * step, normal, color,
                bx * step, bottom, bz * step, normal, color);
        out.triangle(ax * step, top, az * step, normal, color,
                bx * step, bottom, bz * step, normal, color,
                ax * step, bottom, az * step, normal, color);
    }

    /**
     * Wall band with a vertical colour gradient — the top edge keeps the
     * surface tint while the bottom edge sinks into the under-stratum
     * colour, approximating vanilla's grass-side rim.
     */
    private static void emitBandGradient(VertexAccumulator out, int ax, int az, int bx, int bz,
                                         int top, int bottom, int cTop, int cBottom,
                                         float[] normal, int step) {
        if (bottom >= top) {
            return;
        }
        out.triangle(ax * step, top, az * step, normal, cTop,
                bx * step, top, bz * step, normal, cTop,
                bx * step, bottom, bz * step, normal, cBottom);
        out.triangle(ax * step, top, az * step, normal, cTop,
                bx * step, bottom, bz * step, normal, cBottom,
                ax * step, bottom, az * step, normal, cBottom);
    }

    /** Converts the column-neighbour direction to the face id. */
    private static int wallFace(int nx, int nz) {
        if (nx > 0) return 4; // east
        if (nx < 0) return 3; // west
        return nz > 0 ? 2 : 1; // south / north
    }

    /**
     * the per-corner light: count the occluders among the edge
     * neighbours and the diagonal around the corner, then combine with the
     * sampled ground brightness.  The 4-bit corner light maps onto a colour
     * multiplier so neighbouring cells shade consistently and corners
     * interpolate smoothly across cell borders.
     */
    private static int vertexColor(int[] heights, int x, int z, int height,
                                   int materialColor, int gridSize) {
        boolean sideX = heightAt(heights, x - 1, z, gridSize) > height
                || heightAt(heights, x + 1, z, gridSize) > height;
        boolean sideZ = heightAt(heights, x, z - 1, gridSize) > height
                || heightAt(heights, x, z + 1, gridSize) > height;
        boolean diagonal = heightAt(heights, x - 1, z - 1, gridSize) > height
                || heightAt(heights, x + 1, z + 1, gridSize) > height
                || heightAt(heights, x - 1, z + 1, gridSize) > height
                || heightAt(heights, x + 1, z - 1, gridSize) > height;
        int occluders = PredictionLighting.occluders(sideX, sideZ, diagonal);
        int ground = PredictionLighting.combine(occluders, 15);
        float factor = ground / 15.0F;
        int r = Math.round(((materialColor == 0 ? 82 : (materialColor >> 16) & 0xFF)) * factor);
        int g = Math.round(((materialColor == 0 ? 116 : (materialColor >> 8) & 0xFF)) * factor);
        int b = Math.round(((materialColor == 0 ? 64 : (materialColor & 0xFF))) * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Clamped height lookup with out-of-grid fallback so border corners
     * degrade to ambient light instead of throwing. */
    private static int heightAt(int[] heights, int x, int z, int gridSize) {
        if (x < 0 || z < 0 || x >= gridSize || z >= gridSize) {
            return Integer.MIN_VALUE;
        }
        return heights[index(x, z, gridSize)];
    }

    /** Wall band tint from the column material, preserving alpha. */
    private static int colorForWall(int color, float factor) {
        int r = Math.round(((color == 0 ? 82 : (color >> 16) & 0xFF)) * factor);
        int g = Math.round(((color == 0 ? 116 : (color >> 8) & 0xFF)) * 0.82F * factor);
        int b = Math.round(((color == 0 ? 64 : (color & 0xFF))) * 0.75F * factor);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Subdivide only cells touching a surface cut. Keep their existing GPU cell ownership. */
    private static void addEditedGround(VertexAccumulator out, ClientColumnSample[] samples, int[] heights,
                                        int[] colors, PredictionSurfaceEdits edits,
                                        int cx, int cz, int step, int grid, int seaLevel) {
        int axis = step + 2;
        var local = new ClientColumnSample[axis * axis];
        int[] ys = new int[local.length], cs = new int[local.length];
        for (int z = 0; z < axis; z++) for (int x = 0; x < axis; x++) {
            int px = cx * step + x - 1, pz = cz * step + z - 1;
            int source = Math.clamp(Math.floorDiv(pz, step), 0, grid - 1) * grid
                    + Math.clamp(Math.floorDiv(px, step), 0, grid - 1);
            var s = samples[source];
            int original = heights[source], y = edits.floor(px, pz, original), i = z * axis + x;
            int color = colors == null ? 0 : colors[source];
            if (y < original) {
                int block = original - y < 4 ? s.underBlockIndex() : s.deepBlockIndex();
                if (block == ClientColumnSample.NO_BLOCK) block = PredictionMaterialPalette.dirtIndex();
                s = new ClientColumnSample(y, s.fluidY(), s.biomeIndex(), block, 0, 0, 0, 0,
                        s.fluid(), s.flags() & ~(ClientColumnSample.FLAG_TREE_HERE | ClientColumnSample.FLAG_SNOW),
                        0, s.underBlockIndex(), s.deepBlockIndex(), ClientColumnSample.NO_SPAN,
                        ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
                color = PredictionMaterialPalette.colorForIndex(block, color, 0);
            }
            local[i] = s; ys[i] = y; cs[i] = color;
        }
        out.offsetX = cx * step - 1; out.offsetZ = cz * step - 1;
        try {
            for (int z = 1; z <= step; z++) for (int x = 1; x <= step; x++) {
                int i = z * axis + x;
                addColumnBlock(out, local, ys, x, z, 1, axis, ys[i], cs[i], seaLevel, null);
                if (cx == grid - 2 && x == step)
                    emitColumnWall(out, local, ys, x + 1, z, 1, axis, ys[i + 1], cs[i + 1], seaLevel, -1, 0);
                if (cz == grid - 2 && z == step)
                    emitColumnWall(out, local, ys, x, z + 1, 1, axis, ys[i + axis], cs[i + axis], seaLevel, 0, -1);
            }
        } finally { out.offsetX = 0; out.offsetZ = 0; }
    }

    private static void addPlacedFluids(VertexAccumulator out, PredictionVegetation.Tile tile, int cell, int waterTint) {
        for (var voxel : tile.cell(cell)) {
            var state = voxel.state();
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) continue;
            var above = tile.blocks().get(new net.minecraft.core.BlockPos(tile.baseX() + voxel.x(),
                    voxel.y() + 1, tile.baseZ() + voxel.z()));
            if (above != null && (!above.getFluidState().isEmpty() || PredictionSurfaceShapes.occludes(above))) continue;
            int kind = state.is(net.minecraft.world.level.block.Blocks.LAVA) ? 2 : 1;
            float height = voxel.y() + state.getFluidState().getOwnHeight();
            addFluidRectangle(out, 0, height, new PredictionFluidOcclusion.Rect(voxel.x(), voxel.z(),
                    voxel.x() + 1, voxel.z() + 1), kind == 1 ? waterTint : fluidSurfaceColor(2, waterTint), kind, 1);
        }
    }

    private static void addPlacedVegetation(VertexAccumulator out, PredictionVegetation.Tile tile,
                                            int cell, int surfaceY, int foliageTint, PredictionSurfaceEdits edits) {
        for (var voxel : tile.cell(cell)) {
            int x = voxel.x(), z = voxel.z(), size = voxel.size();
            if (!PredictionVegetation.renderable(voxel.state())) continue;
            int floor = edits.floor(x, z, surfaceY);
            int bottom = Math.max(floor, voxel.y());
            int top = voxel.y() + size;
            if (bottom >= top) continue;
            var state = voxel.state();
            if (state.is(net.minecraft.world.level.block.Blocks.BAMBOO)) {
                var position = new net.minecraft.core.BlockPos(tile.baseX() + x, voxel.y(), tile.baseZ() + z);
                for (var face : PredictionBlockModel.bamboo(state, position, foliageTint)) {
                    float[] p = face.positions();
                    int[] order = {0, 1, 2, 0, 2, 3};
                    for (int c : order) out.vertex(x + p[c * 3], voxel.y() + p[c * 3 + 1],
                            z + p[c * 3 + 2], face.normal(), face.color());
                }
                continue;
            }
            int color = PredictionMaterialPalette.colorForState(state, 0xFF65934A, foliageTint);
            if (!PredictionVegetation.solid(state)) {
                int plant = packSprite(color, spriteOf(state, 1));
                // This is the packed format's cross-plane marker, not a
                // geometric normal. It keeps cutouts double-sided with vertical UVs.
                float[] nx = {1, 1, 0}, nz = {0, 1, 1};
                out.triangle(x, bottom, z, nx, plant, x + 1, bottom, z + 1, nx, plant,
                        x + 1, top, z + 1, nx, plant);
                out.triangle(x, bottom, z, nx, plant, x + 1, top, z + 1, nx, plant,
                        x, top, z, nx, plant);
                out.triangle(x + 1, bottom, z, nz, plant, x, bottom, z + 1, nz, plant,
                        x, top, z + 1, nz, plant);
                out.triangle(x + 1, bottom, z, nz, plant, x, top, z + 1, nz, plant,
                        x + 1, top, z, nz, plant);
                continue;
            }
            for (var shape : PredictionSurfaceShapes.boxes(state, size)) {
                float x0 = x + (float) shape.minX, x1 = x + (float) shape.maxX;
                float z0 = z + (float) shape.minZ, z1 = z + (float) shape.maxZ;
                float y0 = Math.max(floor, voxel.y() + (float) shape.minY);
                float y1 = voxel.y() + (float) shape.maxY;
                if (y0 >= y1) continue;
                int roof = packSprite(color, spriteOf(state, 0));
                if (shape.maxY < size || !tile.occupied(x, top, z))
                    addFeatureTop(out, x0, z0, y1, x1 - x0, z1 - z0, roof, roof, roof, roof);
                for (int face = 1; face <= 4; face++) {
                    int dx = face == 3 ? -size : face == 4 ? size : 0;
                    int dz = face == 1 ? -size : face == 2 ? size : 0;
                    boolean boundary = switch (face) {
                        case 1 -> shape.minZ == 0; case 2 -> shape.maxZ == size;
                        case 3 -> shape.minX == 0; default -> shape.maxX == size;
                    };
                    if (boundary && tile.occupied(x + dx, voxel.y(), z + dz)) continue;
                    int sideColor = PredictionMaterialPalette.colorForState(state, 0xFF888888, foliageTint, face);
                    int side = packSprite(sideColor, spriteOf(state, face));
                    if (face <= 2) addFeatureZ(out, x0, face == 2 ? z1 : z0, y0,
                            x1 - x0, y1 - y0, side, side, side, side, face == 1 ? -1 : 1);
                    else addFeatureX(out, face == 4 ? x1 : x0, z0, y0,
                            z1 - z0, y1 - y0, side, side, side, side, face == 3 ? -1 : 1);
                }
            }
        }
    }

    private static int withSideSprite(int color, int blockId, int face) {
        return packSprite(color, blockId == ClientColumnSample.NO_BLOCK
                ? VssLodSpriteTable.FLAT
                : VssLodSpriteTable.sideIndexForBlock(blockId, face));
    }

    private static int spriteOf(net.minecraft.world.level.block.state.BlockState state,
                                int face) {
        if (state == null) return VssLodSpriteTable.FLAT;
        return VssLodSpriteTable.indexForState(state, face);
    }

    private static int packSprite(int color, int sprite) {
        return sprite == VssLodSpriteTable.FLAT ? color
                : (color & 0x00FFFFFF) | (sprite & 0xFF) << 24;
    }

    private static void addFeatureTop(VertexAccumulator out, float x, float z, float y,
                                      float width, float depth, int c0, int c1, int c2, int c3) {
        float[] n = {0, 1, 0};
        out.triangle(x, y, z, n, c0, x + width, y, z, n, c1,
                x + width, y, z + depth, n, c2);
        out.triangle(x, y, z, n, c0, x + width, y, z + depth, n, c2,
                x, y, z + depth, n, c3);
    }

    private static void addFeatureZ(VertexAccumulator out, float x, float z, float y,
                                    float width, float height, int c0, int c1, int c2, int c3,
                                    int direction) {
        float[] n = {0, 0, direction};
        // StampMesher already stores the actual face plane: north is z and
        // south is z + 1. Adding one for the negative face moved the face
        // into the block and made cutout plants/snow overlap their ground.
        float zz = z;
        out.triangle(x, y, zz, n, c0, x + width, y, zz, n, c1,
                x + width, y + height, zz, n, c2);
        out.triangle(x, y, zz, n, c0, x + width, y + height, zz, n, c2,
                x, y + height, zz, n, c3);
    }

    private static void addFeatureX(VertexAccumulator out, float x, float z, float y,
                                    float width, float height, int c0, int c1, int c2, int c3,
                                    int direction) {
        float[] n = {direction, 0, 0};
        // As with Z faces, the stamp coordinate is already the world plane;
        // west is x and east is x + 1.
        float xx = x;
        out.triangle(xx, y, z, n, c0, xx, y, z + width, n, c1,
                xx, y + height, z + width, n, c2);
        out.triangle(xx, y, z, n, c0, xx, y + height, z + width, n, c2,
                xx, y + height, z, n, c3);
    }

    private static void addHorizontal(VertexAccumulator out, int x, int z, int step,
                                      float y, int color, int kind) {
        // The fluid kind is encoded as kind/3 in the normal's Y magnitude so
        // it survives the signed-normalized-byte round trip to the shader.
        float[] normal = new float[]{0.0F, kind / 3.0F, 0.0F};
        out.triangle(x * step, y, z * step, normal, color,
                (x + 1) * step, y, z * step, normal, color,
                (x + 1) * step, y, (z + 1) * step, normal, color);
        out.triangle(x * step, y, z * step, normal, color,
                (x + 1) * step, y, (z + 1) * step, normal, color,
                x * step, y, (z + 1) * step, normal, color);
    }

    private static void addFluidWalls(VertexAccumulator out, ClientColumnSample[] samples,
                                      int x, int z, int step, float topY, int color,
                                      int gridSize, int kind, PredictionFluidOcclusion occlusion, int cell) {
        if (step >= 64) {
            return;
        }
        int[][] edges = {{x, z, x + 1, z, 0, -1}, {x + 1, z, x + 1, z + 1, 1, 0},
                {x + 1, z + 1, x, z + 1, 0, 1}, {x, z + 1, x, z, -1, 0}};
        for (int[] edge : edges) {
            int nx = x + edge[4], nz = z + edge[5];
            // Missing neighbours are not shorelines. East/south margin samples
            // are valid too; a continuous ocean must never get tile-sized water walls.
            if (nx < 0 || nz < 0 || nx >= gridSize || nz >= gridSize) continue;
            ClientColumnSample neighbor = samples[index(nx, nz, gridSize)];
            if (neighbor.fluid() != 0 && neighbor.fluidY() >= topY) continue;
            // The kind rides as kind/3 in the normal's Y magnitude; the
            // horizontal part stays the bank direction the shader uses for
            // the flow texture. Both survive the byte normal round trip.
            float bottom = Math.max((float) Math.ceil(topY) - 1.0F, neighbor.surfaceY());
            if (bottom >= topY) continue;
            int axis = edge[4] == 0 ? 2 : 1;
            float plane = (axis == 1 ? edge[0] : edge[1]) * step;
            int u0 = Math.min(axis == 1 ? edge[1] : edge[0], axis == 1 ? edge[3] : edge[2]) * step;
            var face = new PredictionFluidOcclusion.Rect(u0, bottom, u0 + step, topY);
            for (var rect : occlusion.visible(cell, axis, plane, face))
                addFluidRectangle(out, axis, plane, rect, color, kind, axis == 1 ? edge[4] : edge[5]);
        }
    }

    private static void addFluidRectangle(VertexAccumulator out, int axis, float plane,
            PredictionFluidOcclusion.Rect rect, int color, int kind, int direction) {
        float u0 = (float) rect.u0(), v0 = (float) rect.v0();
        float u1 = (float) rect.u1(), v1 = (float) rect.v1();
        float[] normal = {axis == 1 ? direction : 0, kind / 3F, axis == 2 ? direction : 0};
        // Keep top-first wall order used by the greedy wall-run collector.
        float[] p = axis == 0 ? new float[]{u0,plane,v0, u1,plane,v0, u1,plane,v1, u0,plane,v1}
                : axis == 1 ? new float[]{plane,v1,u0, plane,v1,u1, plane,v0,u1, plane,v0,u0}
                : new float[]{u0,v1,plane, u1,v1,plane, u1,v0,plane, u0,v0,plane};
        for (int c : new int[]{0, 1, 2, 0, 2, 3})
            out.vertex(p[c * 3], p[c * 3 + 1], p[c * 3 + 2], normal, color);
    }

    private static int fluidSurfaceColor(int kind, int fallback) {
        if (kind == 1) return fallback;
        int block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(
                kind == 3 ? net.minecraft.world.level.block.Blocks.ICE
                        : net.minecraft.world.level.block.Blocks.LAVA);
        int color = PredictionMaterialPalette.colorForIndex(block,
                kind == 3 ? 0xFFB7D7EC : 0xFFD9572B);
        return (fallback & 0xFF000000) | (color & 0xFFFFFF);
    }

    private static final class VertexAccumulator {
        private float[] positions;
        private float[] normals;
        private int[] colors;
        private int count;
        private int offsetX, offsetZ;

        private VertexAccumulator(int capacity) {
            positions = new float[Math.max(18, capacity * 3)];
            normals = new float[positions.length];
            colors = new int[Math.max(6, capacity)];
        }

        int vertexCount() { return count; }

        void triangle(float x0, float y0, float z0, float[] n0, int c0,
                      float x1, float y1, float z1, float[] n1, int c1,
                      float x2, float y2, float z2, float[] n2, int c2) {
            vertex(x0, y0, z0, n0, c0); vertex(x1, y1, z1, n1, c1); vertex(x2, y2, z2, n2, c2);
        }

        void vertex(float x, float y, float z, float[] normal, int color) {
            ensure(count + 1);
            int p = count * 3;
            positions[p] = x + offsetX; positions[p + 1] = y; positions[p + 2] = z + offsetZ;
            normals[p] = normal[0]; normals[p + 1] = normal[1]; normals[p + 2] = normal[2];
            colors[count++] = color;
        }

        private void ensure(int required) {
            if (required > 262_144) {
                throw new PredictionMemoryBudget.MeshLimitException();
            }
            if (required * 3 <= positions.length && required <= colors.length) return;
            int newCapacity = Math.min(262_144, Math.max(required, count * 2 + 6));
            positions = java.util.Arrays.copyOf(positions, newCapacity * 3);
            normals = java.util.Arrays.copyOf(normals, newCapacity * 3);
            colors = java.util.Arrays.copyOf(colors, newCapacity);
        }

        float[] positions() { return java.util.Arrays.copyOf(positions, count * 3); }
        float[] normals() { return java.util.Arrays.copyOf(normals, count * 3); }
        int[] colors() { return java.util.Arrays.copyOf(colors, count); }
    }

    private static int writeVertex(float[] positions, float[] normals, int[] colors, int vertex,
                                   int x, int y, int z, float[] normal, int color) {
        int position = vertex * 3;
        positions[position] = x;
        positions[position + 1] = y;
        positions[position + 2] = z;
        normals[position] = normal[0];
        normals[position + 1] = normal[1];
        normals[position + 2] = normal[2];
        colors[vertex] = color;
        return vertex + 1;
    }

    private static float[] normal(int h0, int h1, int h2, int step) {
        float dx = (h1 - h0) / (float) Math.max(1, step);
        float dz = (h2 - h0) / (float) Math.max(1, step);
        float nx = -dx;
        float ny = 1.0F;
        float nz = -dz;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new float[]{nx / length, ny / length, nz / length};
    }

    private static int colorFor(int h00, int h10, int h01, int h11, int materialColor) {
        float average = (h00 + h10 + h01 + h11) * 0.25F;
        float slope = Math.min(1.0F, (Math.abs(h10 - h00) + Math.abs(h01 - h00)) / 32.0F);
        int baseR = materialColor == 0 ? 82 : materialColor >> 16 & 0xFF;
        int baseG = materialColor == 0 ? 116 : materialColor >> 8 & 0xFF;
        int baseB = materialColor == 0 ? 64 : materialColor & 0xFF;
        float shade = 0.82F + Math.min(0.22F, Math.max(-0.12F, average / 512.0F));
        int r = clamp((int) (baseR * shade + slope * 24.0F), 36, 210);
        int g = clamp((int) (baseG * shade - slope * 14.0F), 42, 220);
        int b = clamp((int) (baseB * shade - slope * 8.0F), 32, 220);
        // VertexConsumer.setColor(int) consumes FastColor.ARGB32, not RGBA.
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int index(int x, int z) {
        return z * GRID_SIZE + x;
    }

    private static int index(int x, int z, int gridSize) {
        return z * gridSize + x;
    }

    private static int averageMaterialColor(int[] colors, int a, int b, int c, int d) {
        int count = (colors[a] == 0 ? 0 : 1) + (colors[b] == 0 ? 0 : 1)
                + (colors[c] == 0 ? 0 : 1) + (colors[d] == 0 ? 0 : 1);
        int r = channelIfPresent(colors[a], 16) + channelIfPresent(colors[b], 16)
                + channelIfPresent(colors[c], 16) + channelIfPresent(colors[d], 16);
        int g = channelIfPresent(colors[a], 8) + channelIfPresent(colors[b], 8)
                + channelIfPresent(colors[c], 8) + channelIfPresent(colors[d], 8);
        int blue = channelIfPresent(colors[a], 0) + channelIfPresent(colors[b], 0)
                + channelIfPresent(colors[c], 0) + channelIfPresent(colors[d], 0);
        return count == 0 ? 0 : (r / count << 16) | (g / count << 8) | blue / count;
    }

    private static int channelIfPresent(int color, int shift) {
        return color == 0 ? 0 : (color >> shift) & 0xFF;
    }

    /** Per-corner average overload used by lower spans and feature geometry. */
    private static int averageMaterialColor(int a, int b, int c, int d) {
        int count = (a == 0 ? 0 : 1) + (b == 0 ? 0 : 1)
                + (c == 0 ? 0 : 1) + (d == 0 ? 0 : 1);
        int r = (a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF);
        int g = (a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF);
        int blue = (a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF);
        return count == 0 ? 0 : (r / count << 16) | (g / count << 8) | blue / count;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

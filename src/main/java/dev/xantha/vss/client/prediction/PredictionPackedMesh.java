package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Packed quad payload for one prediction tile.
 *
 * <p>Every rendered quad becomes twelve unsigned 32-bit words (48 bytes —
 * still substantially smaller than the old per-vertex stream) that the terrain program
 * expands into four vertices from {@code gl_VertexID}, so coverage flips no
 * longer rebuild any vertex data. Layout per quad:</p>
 *
 * <pre>
 * i0: x0 | x1      i1: x2 | x3      (scaled tile-local blocks, 16-bit each)
 * i2: z0 | z1      i3: z2 | z3
 * i4: y0 | y1      i5: y2 | y3      (quarter/sixteenth blocks, +32768 biased)
 * i6: sprite (16)  | XZ shift (4) | fine (1) | model UV/fluid fine Y (1) | fluid (2) | axis (2) | cutout | unshaded
 *                  | uvYPos | bank/down | positive face | LOD texture scale
 * i7: corner 0 rgb (24) | skylight loss (bits 28-31)
 * i8: source cell index
 * i9: corner 1 rgb (24) | source-cell coverage (bit 24) | skylight loss (bits 28-31)
 * i10: corner 2 rgb (24) + skylight loss     i11: corner 3 rgb (24) + skylight loss
 * </pre>
 *
 * <p>Terrain quads come first, grouped by {@link VssLodFaceGroup} so the
 * renderer can draw each visible face group as one indexed range; water quads
 * follow, grouped the same way for the blended pass.</p>
 */
final class PredictionPackedMesh {
    static final int STRIDE_INTS = 12;
    static final int FLAG_SPRITE_MASK = 0xFFFF;
    static final int XZ_SHIFT_BITS = 16;
    static final int FLAG_FINE_COORDINATES = 1 << 20;
    static final int FLAG_MODEL_UV = 1 << 21;
    // Fluid quads do not use baked model UVs. Reuse that bit for Y precision
    // independently of X/Z scale, including tiles wider than 4096 blocks.
    static final int FLAG_FLUID_FINE_Y = 1 << 21;
    static final int FLAG_CUTOUT = 1 << 26;
    static final int FLAG_UNSHADED = 1 << 27;
    static final int FLAG_UV_Y_POS = 1 << 28;
    static final int FLAG_BANK = 1 << 29;
    /** Positive sign for a side normal; axis bits identify X/Z. */
    static final int FLAG_FACE_POSITIVE = 1 << 30;
    static final int FLAG_LOD_TEXTURE_SCALE = 1 << 31;
    static final int FLAGS_FLUID_SHIFT = 22;
    static final int FLAGS_AXIS_SHIFT = 24;
    private static final int Y_BIAS = 32_768;
    private static final int Y_SCALE = 4;
    /**
     * X/Z use unsigned 16-bit coordinates with a power-of-two scale for wide
     * tiles. Saturation remains a last-resort guard for malformed geometry;
     * valid endpoints must neither wrap to zero nor be shortened to 65535.
     */
    private static final AtomicInteger COORDINATE_WARNINGS = new AtomicInteger();

    /** Boundary patches already use the production packed-quad contract. */
    static PredictionPackedMesh terrainRecords(int[] words, int cellAxis) {
        int[] first = new int[VssLodFaceGroup.COUNT], count = new int[VssLodFaceGroup.COUNT];
        int quads = words.length / STRIDE_INTS;
        for (int i = 0; i < quads; i++) count[recordGroup(words[i * STRIDE_INTS + 6])]++;
        ranges(first, count, 0);
        int[] sorted = new int[words.length], cursor = first.clone();
        for (int i = 0; i < quads; i++) {
            int group = recordGroup(words[i * STRIDE_INTS + 6]);
            System.arraycopy(words, i * STRIDE_INTS, sorted, cursor[group]++ * STRIDE_INTS, STRIDE_INTS);
        }
        return new PredictionPackedMesh(sorted, cellAxis, quads, first, count,
                new int[VssLodFaceGroup.COUNT], new int[VssLodFaceGroup.COUNT], false, 0);
    }

    private static int recordGroup(int flags) {
        boolean positive = (flags & FLAG_FACE_POSITIVE) != 0;
        return switch ((flags >>> FLAGS_AXIS_SHIFT) & 3) {
            case 1 -> positive ? VssLodFaceGroup.EAST : VssLodFaceGroup.WEST;
            case 2 -> positive ? VssLodFaceGroup.SOUTH : VssLodFaceGroup.NORTH;
            default -> VssLodFaceGroup.HORIZONTAL;
        };
    }

    private final int[] quads;
    private final int cellAxis;
    private final int terrainQuadCount;
    private final int spriteQuadCount;
    private final int[] terrainRangeFirst;
    private final int[] terrainRangeCount;
    private final int[] waterRangeFirst;
    private final int[] waterRangeCount;
    private final boolean downFaces;

    private PredictionPackedMesh(int[] quads, int cellAxis,
                                 int terrainQuadCount, int[] terrainFirst, int[] terrainCount,
                                 int[] waterFirst, int[] waterCount, boolean downFaces,
                                 int spriteQuadCount) {
        this.quads = quads;
        this.cellAxis = cellAxis;
        this.terrainQuadCount = terrainQuadCount;
        this.terrainRangeFirst = terrainFirst;
        this.terrainRangeCount = terrainCount;
        this.waterRangeFirst = waterFirst;
        this.waterRangeCount = waterCount;
        this.downFaces = downFaces;
        this.spriteQuadCount = spriteQuadCount;
    }

    int quadCount() {
        return quads.length / STRIDE_INTS;
    }

    int terrainQuadCount() {
        return terrainQuadCount;
    }

    int spriteQuadCount() {
        return spriteQuadCount;
    }

    int cellAxis() {
        return cellAxis;
    }

    int[] quads() {
        return quads;
    }

    boolean downFaces() {
        return downFaces;
    }

    int terrainRangeFirst(int group) {
        return terrainRangeFirst[group];
    }

    int terrainRangeCount(int group) {
        return terrainRangeCount[group];
    }

    int waterRangeFirst(int group) {
        return waterRangeFirst[group];
    }

    int waterRangeCount(int group) {
        return waterRangeCount[group];
    }

    /** Packs sampled voxel geometry; coverage handover never displaces faces. */
    static PredictionPackedMesh pack(PredictionTileManager.PredictionTile tile) {
        PredictionQuadMesh src = tile.mesh().packed();
        int cellAxis = src.cellAxis();
        int terrainQuads = src.quadCount();
        int waterQuads = src.waterQuadCount();
        int horizontalShift = 0;
        while ((tile.spanBlocks() >> horizontalShift) > 0xFFFF) horizontalShift++;
        if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging) {
            validateCoordinates(tile, src);
        }

        int[] flat = new int[(terrainQuads + waterQuads) * STRIDE_INTS];
        int[] terrainFirst = new int[VssLodFaceGroup.COUNT];
        int[] terrainCount = new int[VssLodFaceGroup.COUNT];
        int[] waterFirst = new int[VssLodFaceGroup.COUNT];
        int[] waterCount = new int[VssLodFaceGroup.COUNT];
        for (int q = 0; q < terrainQuads; q++) terrainCount[group(src, q, false)]++;
        for (int q = 0; q < waterQuads; q++) waterCount[group(src, q, true)]++;
        ranges(terrainFirst, terrainCount, 0);
        ranges(waterFirst, waterCount, terrainQuads);
        int[] terrainCursor = terrainFirst.clone(), waterCursor = waterFirst.clone();
        // One scratch record replaces two tiny arrays per quad and the
        // intermediate array-of-arrays counting sort. The final GPU payload
        // is filled in stable face-group order directly.
        int[] record = new int[STRIDE_INTS];
        int spriteQuads = 0;
        boolean downFaces = false;
        for (int q = 0; q < terrainQuads + waterQuads; q++) {
            boolean water = q >= terrainQuads;
            int source = water ? q - terrainQuads : q;
            packQuad(src, source, water, horizontalShift, record);
            if (!water) {
                packWaterLight(record, tile, src, source);
                downFaces |= (record[6] & FLAG_BANK) != 0;
            }
            int at = (water ? waterCursor : terrainCursor)[group(src, source, water)]++;
            System.arraycopy(record, 0, flat, at * STRIDE_INTS, STRIDE_INTS);
            if ((record[6] & FLAG_SPRITE_MASK) != 0) spriteQuads++;
        }
        return new PredictionPackedMesh(flat, cellAxis, terrainQuads,
                terrainFirst, terrainCount, waterFirst, waterCount, downFaces, spriteQuads);
    }

    private static void packQuad(PredictionQuadMesh src, int quad, boolean water, int horizontalShift,
                                 int[] packed) {
        Arrays.fill(packed, 0);
        boolean fractional = false, fitsFine = true, fitsFineY = true;
        for (int c = 0; c < 4; c++) {
            float x = water ? src.waterX(quad, c) : src.x(quad, c);
            float z = water ? src.waterZ(quad, c) : src.z(quad, c);
            float y = water ? src.waterY(quad, c) : src.y(quad, c);
            fractional |= x != Math.rint(x) || z != Math.rint(z) || y * 4 != Math.rint(y * 4);
            fitsFine &= x >= 0 && x <= 4095 && z >= 0 && z <= 4095 && y >= -2048 && y < 2048;
            fitsFineY &= y >= -2048 && y < 2048;
        }
        boolean fine = fractional && fitsFine;
        if (fine) horizontalShift = 0;
        float horizontalScale = fine ? 16F : 1F / (1 << horizontalShift);
        for (int corner = 0; corner < 4; corner++) {
            float fx = water ? src.waterX(quad, corner) : src.x(quad, corner);
            float fz = water ? src.waterZ(quad, corner) : src.z(quad, corner);
            float fy = water ? src.waterY(quad, corner) : src.y(quad, corner);
            // Wide terrain remains exact on its sampling lattice; scaling
            // preserves the outer edge too, including at a 65536-block span.
            int x = saturateUnsigned16(fx * horizontalScale);
            int z = saturateUnsigned16(fz * horizontalScale);
            float encodedY = fy * (fine || water && fitsFineY ? 16 : Y_SCALE) + Y_BIAS;
            // Very tall custom dimensions use quarter blocks. Never round a
            // liquid surface upward onto the solid block's top plane.
            if (water && !fitsFineY && fy != Math.rint(fy)) encodedY = (float) Math.floor(encodedY);
            int y = saturateUnsigned16(encodedY);
            int xyOut = corner < 2 ? 0 : 1;
            int zOut = corner < 2 ? 2 : 3;
            int yOut = corner < 2 ? 4 : 5;
            int half = (corner & 1) << 4;
            packed[xyOut] |= x << half;
            packed[zOut] |= z << half;
            packed[yOut] |= y << half;
        }
        int color = water ? src.waterColor(quad, 0) : src.color(quad, 0);
        float nx = water ? src.waterNormalX(quad, 0) : src.normalX(quad, 0);
        float ny = water ? src.waterNormalY(quad, 0) : src.normalY(quad, 0);
        float nz = water ? src.waterNormalZ(quad, 0) : src.normalZ(quad, 0);
        // The mesh's alpha byte is the sprite key with 255 as the "flat
        // colour" sentinel.  Passing the sentinel through would sample an
        // out-of-range sprite row and read black; map it to "no sprite" (0).
        int alphaKey = (color >>> 24) & 0xFF;
        // The mesh encodes the fluid kind as kind/3 in the normal's Y
        // magnitude, exactly like the old vertex shader decoded it; the
        // bank direction in X/Z picks the wall shade.
        float kind = Math.abs(ny) * 3.0F;
        int fluid = kind >= 2.5F ? 3 : kind >= 1.5F ? 2 : 1;
        // Fluids sample the real water/lava/ice sprites so the animated
        // texture (and water's baked translucency) ride the normal sprite
        // path; terrain keeps the mesh's own sprite key.
        int sprite = water
                ? VssLodSpriteTable.fluidSpriteIndex(fluid)
                : alphaKey == VssLodSpriteTable.FLAT ? 0 : alphaKey;
        boolean cross = !water && ((Math.abs(nx) > 0.9D && Math.abs(ny) > 0.9D)
                || (Math.abs(nz) > 0.9D && Math.abs(ny) > 0.9D));
        int axisClass;
        boolean uvYPos;
        if (water) {
            // Fluid Y carries kind/3, not a geometric normal component.
            axisClass = Math.abs(nx) > 0.5F ? 1 : Math.abs(nz) > 0.5F ? 2 : 0;
            uvYPos = false;
        } else if (cross) {
            axisClass = Math.abs(nx) > Math.abs(nz) ? 1 : 2;
            uvYPos = true;
        } else if (Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz)) {
            axisClass = 0;
            uvYPos = false;
        } else if (Math.abs(nx) >= Math.abs(nz)) {
            axisClass = 1;
            uvYPos = false;
        } else {
            axisClass = 2;
            uvYPos = false;
        }
        int packedFlags = axisClass << FLAGS_AXIS_SHIFT | horizontalShift << XZ_SHIFT_BITS;
        if (water || src.usesLodTextureScale(quad)) {
            packedFlags |= FLAG_LOD_TEXTURE_SCALE;
        }
        if (water) {
            packedFlags |= fluid << FLAGS_FLUID_SHIFT;
            if (nx > 0.5F || nz > 0.5F) {
                packedFlags |= FLAG_FACE_POSITIVE;
            }
            if (Math.abs(nx) + Math.abs(nz) > 0.5F) {
                packedFlags |= FLAG_BANK;
            }
        } else {
            if ((Math.abs(nx) > 0.5F && nx > 0.0F)
                    || (Math.abs(nz) > 0.5F && nz > 0.0F)) {
                packedFlags |= FLAG_FACE_POSITIVE;
            }
            // Enable alpha testing only for sprites that actually
            // contain transparent pixels. Treating every real texture as a
            // cutout makes opaque grass/snow faces participate in alpha
            // discard and magnifies atlas sampling artefacts into streaks.
            if (sprite != 0 && sprite != VssLodSpriteTable.FLAT
                    && VssLodSpriteTable.isCutout(sprite)) {
                packedFlags |= FLAG_CUTOUT;
            }
            if (ny < -0.5F) {
                // Bottom-facing geometry: overhang undersides and lower
                // spans.  Bottom-facing geometry keeps the tile's horizontal faces
                // alive for these even when the camera is above.
                packedFlags |= FLAG_BANK;
            }
        }
        if (cross) {
            packedFlags |= FLAG_UNSHADED;
        }
        if (!water && VssLodSpriteTable.modelFace(sprite)) {
            packedFlags |= FLAG_MODEL_UV;
            if (!VssLodSpriteTable.modelShade(sprite)) packedFlags |= FLAG_UNSHADED;
        }
        if (uvYPos) {
            packedFlags |= FLAG_UV_Y_POS;
        }
        if (fine) packedFlags |= FLAG_FINE_COORDINATES;
        if (water && fitsFineY) packedFlags |= FLAG_FLUID_FINE_Y;
        packed[6] = (sprite & FLAG_SPRITE_MASK)
                | (packedFlags & 0xFFFF0000);
        packed[7] = color & 0x00FFFFFF;
        packed[8] = water ? src.waterCell(quad) : src.coverageCell(quad);
        int sourceCoverage = water
                ? (src.waterCoverageUsesLocalPosition(quad) ? 0 : 1)
                : (src.coverageUsesLocalPosition(quad) ? 0 : 1);
        for (int corner = 1; corner < 4; corner++) {
            packed[8 + corner] = (water ? src.waterColor(quad, corner)
                    : src.color(quad, corner)) & 0x00FFFFFF;
        }
        packed[9] |= sourceCoverage << 24;
    }

    private static void packWaterLight(int[] words, PredictionTileManager.PredictionTile tile,
                                       PredictionQuadMesh src, int quad) {
        if (tile.samples() == null || tile.samples().length == 0) return;
        for (int corner = 0; corner < 4; corner++) {
            // Sample just inside this face's owning column, including walls
            // on a tile edge. Each water block removes one skylight level.
            double x = src.x(quad, corner) - src.normalX(quad, corner) * .01;
            double z = src.z(quad, corner) - src.normalZ(quad, corner) * .01;
            int cx = Math.clamp((int) Math.floor(x / tile.spacingBlocks()), 0, tile.cellAxis());
            int cz = Math.clamp((int) Math.floor(z / tile.spacingBlocks()), 0, tile.cellAxis());
            int index = cz * (tile.cellAxis() + 1) + cx;
            if (index >= tile.samples().length) continue;
            ClientColumnSample sample = tile.samples()[index];
            int loss = waterLightLoss(sample, src.y(quad, corner));
            words[corner == 0 ? 7 : 8 + corner] |= loss << 28;
        }
    }

    static int waterLightLoss(ClientColumnSample sample, double y) {
        return sample != null && sample.fluid() == 1 && !sample.ice()
                ? Math.clamp((int) Math.floor(sample.fluidY() - y), 0, 15) : 0;
    }

    private static int saturateUnsigned16(float value) {
        if (!Float.isFinite(value)) {
            warnCoordinate("non-finite=" + value);
            return 0;
        }
        int rounded = Math.round(value);
        if (rounded < 0 || rounded > 0xFFFF) {
            warnCoordinate("value=" + value + ", rounded=" + rounded);
            return Math.max(0, Math.min(0xFFFF, rounded));
        }
        return rounded;
    }

    private static void validateCoordinates(PredictionTileManager.PredictionTile tile,
                                            PredictionQuadMesh mesh) {
        int bad = 0;
        float maxEdge = 0.0F;
        for (int quad = 0; quad < mesh.quadCount(); quad++) {
            for (int corner = 0; corner < 4; corner++) {
                float x = mesh.x(quad, corner);
                float z = mesh.z(quad, corner);
                float y = mesh.y(quad, corner);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                        || x < 0.0F || z < 0.0F || x > tile.spanBlocks() || z > tile.spanBlocks()
                        || y * Y_SCALE + Y_BIAS < 0.0F
                        || y * Y_SCALE + Y_BIAS > 65535.0F) {
                    bad++;
                }
                if (corner > 0) {
                    float px = mesh.x(quad, corner - 1);
                    float pz = mesh.z(quad, corner - 1);
                    maxEdge = Math.max(maxEdge, Math.max(Math.abs(x - px), Math.abs(z - pz)));
                }
            }
        }
        for (int quad = 0; quad < mesh.waterQuadCount(); quad++) {
            for (int corner = 0; corner < 4; corner++) {
                float x = mesh.waterX(quad, corner);
                float z = mesh.waterZ(quad, corner);
                float y = mesh.waterY(quad, corner);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                        || x < 0.0F || z < 0.0F || x > tile.spanBlocks() || z > tile.spanBlocks()
                        || y * Y_SCALE + Y_BIAS < 0.0F
                        || y * Y_SCALE + Y_BIAS > 65535.0F) {
                    bad++;
                }
            }
        }
        if (bad > 0 || maxEdge > tile.spanBlocks() + 2.0F) {
            warnCoordinate("tile=" + tile.key() + ", span=" + tile.spanBlocks()
                    + ", badVertices=" + bad + ", maxLocalEdge=" + maxEdge
                    + ", terrainQuads=" + mesh.quadCount()
                    + ", waterQuads=" + mesh.waterQuadCount());
        }
    }

    private static void warnCoordinate(String message) {
        if (dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging
                && COORDINATE_WARNINGS.getAndIncrement() < 24) {
            // Coordinate guards are intentionally diagnostic.  A normal
            // client should never receive one line per malformed vertex;
            // keep them behind the existing debugLogging switch and let the
            // saturating encoder keep rendering safely.
            dev.xantha.vss.common.VSSLogger.debug(
                    "VSS packed mesh coordinate guard: " + message);
        }
    }

    private static int group(PredictionQuadMesh src, int quad, boolean water) {
        return water ? VssLodFaceGroup.ofFluidNormal(src.waterNormalX(quad, 0),
                src.waterNormalY(quad, 0), src.waterNormalZ(quad, 0))
                : VssLodFaceGroup.ofNormal(src.normalX(quad, 0), src.normalY(quad, 0), src.normalZ(quad, 0));
    }

    private static void ranges(int[] first, int[] counts, int base) {
        for (int group = 0; group < VssLodFaceGroup.COUNT; group++) {
            first[group] = counts[group] == 0 ? 0 : base;
            base += counts[group];
        }
    }
}

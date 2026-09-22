package dev.xantha.vss.client.prediction;

/** Replaces stale heightfield margin walls only where a selected neighbour can close the edge. */
final class PredictionBoundaryWalls {
    private PredictionBoundaryWalls() { }

    static byte[] build(PredictionLodSeams.Surface surface, PredictionLodSeams.Index index) {
        var tile = surface.tile();
        int axis = tile.cellAxis(), step = tile.spacingBlocks();
        byte[] mask = new byte[axis * axis];
        for (int cell = 0; cell < mask.length; cell++) {
            if (!surface.allowed()[cell]) continue;
            mask[cell] = (byte) 128; // High bit retains the existing R8 visibility test.
            int x = cell % axis, z = cell / axis;
            if (x > 0 && z > 0 && x + 1 < axis && z + 1 < axis
                    && surface.allowed()[cell - 1] && surface.allowed()[cell + 1]
                    && surface.allowed()[cell - axis] && surface.allowed()[cell + axis]) continue;
            if (!exterior(surface, cell)) continue;
            for (int direction = 0; direction < 4; direction++) {
                int nx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
                int nz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                int ax = x + nx, az = z + nz;
                if (ax >= 0 && az >= 0 && ax < axis && az < axis && surface.allowed()[az * axis + ax]) continue;
                int wx = tile.baseBlockX() + x * step, wz = tile.baseBlockZ() + z * step;
                int along = nx != 0 ? wz : wx, end = along + step;
                boolean complete = true;
                int segments = 0;
                while (along < end) {
                    int px = nx == 0 ? along : wx + (nx < 0 ? -1 : step);
                    int pz = nz == 0 ? along : wz + (nz < 0 ? -1 : step);
                    var neighbor = index.at(px, pz);
                    if (++segments > 256 || neighbor == null || neighbor.tile() == tile
                            || !exterior(neighbor, PredictionLodSeams.cellAt(neighbor.tile(), px, pz))) {
                        complete = false;
                        break;
                    }
                    int spacing = neighbor.tile().spacingBlocks();
                    along = Math.min(end, (Math.floorDiv(along, spacing) + 1) * spacing);
                }
                if (complete) mask[cell] |= (byte) (1 << direction);
            }
        }
        return mask;
    }

    private static boolean exterior(PredictionLodSeams.Surface surface, int cell) {
        var tile = surface.tile();
        var sample = tile.samples()[PredictionGpuTile.sampleIndexForCell(cell, tile.cellAxis())];
        return sample != null && sample.hasSurface() && !PredictionExteriorColumns.interiorVolume(sample)
                && (PredictionExteriorColumns.profiled(sample) || !PredictionWallEvidence.hasInterior(sample, tile.spacingBlocks())) && tile.mesh().seamMesh().hasTop(cell);
    }

    static boolean replaced(byte[] mask, int cell, int axis, int step, int plane, boolean xNormal) {
        if (mask == null || cell < 0 || cell >= mask.length) return false;
        int origin = (xNormal ? cell % axis : cell / axis) * step;
        int direction = plane == origin ? (xNormal ? 0 : 2)
                : plane == origin + step ? (xNormal ? 1 : 3) : -1;
        return direction >= 0 && (mask[cell] & 1 << direction) != 0;
    }
}

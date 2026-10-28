package dev.xantha.vss.client.prediction;

import java.util.Map;
import java.util.Set;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Column mip of four immutable child grids. No world access, mesh decoding or GPU work. */
final class PredictionParentLod {
    private PredictionParentLod() { }

    record Source(PredictionTileKey key, long revision, long captureEpoch) { }
    record Proof(Source a, Source b, Source c, Source d, double error, boolean sealedEdges) {
        Source child(int i) { return switch (i) { case 0 -> a; case 1 -> b; case 2 -> c; case 3 -> d;
            default -> throw new IndexOutOfBoundsException(i); }; }
        boolean current(Map<PredictionTileKey, PredictionTile> tiles, Set<PredictionTileKey> dirty,
                        Map<PredictionTileKey, Long> captures) {
            for (int i = 0; i < 4; i++) {
                Source source = child(i);
                PredictionTile tile = tiles.get(source.key());
                if (tile == null || tile.terrainRevision() != source.revision() || dirty.contains(source.key())
                        || captures.getOrDefault(source.key(), 0L) != source.captureEpoch()) return false;
            }
            return true;
        }
        boolean describes(PredictionTile[] children) {
            for (int i = 0; i < 4; i++) if (children[i] == null
                    || !child(i).key().equals(children[i].key()) || child(i).revision() != children[i].terrainRevision()) return false;
            return true;
        }
    }
    record Input(PredictionTileKey parent, int axis, PredictionTile[] children, Source[] sources) {
        boolean uses(PredictionTileKey key) {
            for (var source : sources) if (source.key().equals(key)) return true;
            return false;
        }
    }
    record Result(ClientColumnSample[] samples, Proof proof) { }

    /** Caller captures references under the manager lock, then reduces outside it. */
    static Input capture(PredictionTileKey parent, int axis, VssLodLayout layout,
                         Map<PredictionTileKey, PredictionTile> tiles, Set<PredictionTileKey> dirty,
                         Map<PredictionTileKey, Long> captures) {
        if (parent.lod() <= 0 || parent.lod() >= layout.levelCount() || axis < 2
                || (axis & (axis - 1)) != 0 || axis > layout.cellAxis(parent.lod())) return null;
        PredictionTile[] children = new PredictionTile[4];
        Source[] sources = new Source[4];
        int span = layout.tileBlocks(parent.lod()), step = span / axis;
        for (int i = 0; i < 4; i++) {
            var key = new PredictionTileKey(parent.dimension(), parent.tileX() * 2 + (i & 1),
                    parent.tileZ() * 2 + (i >> 1), parent.lod() - 1);
            var child = tiles.get(key);
            if (child == null || child.scopeOnly() || dirty.contains(key) || child.spanBlocks() != span / 2
                    || child.spacingBlocks() > step || step % child.spacingBlocks() != 0
                    || child.samples().length != (child.cellAxis() + 1) * (child.cellAxis() + 1)) return null;
            children[i] = child;
            sources[i] = new Source(key, child.terrainRevision(), captures.getOrDefault(key, 0L));
        }
        return new Input(parent, axis, children, sources);
    }

    static Result reduce(Input input) {
        // Validate columns on the worker too. The manager's capture lock only
        // protects references/versions, never a full-grid walk.
        // This is a DISPLAY reduction, not admission to the exact sample cache.
        // Preview/display/capture mips are already drawable children. Rejecting
        // them here after capture admitted them repeatedly resamples the parent.
        // aggregate preserves provenance and always adds FLAG_PARENT_LOD, so
        // these columns remain unusable for exact terrain and decoration.
        for (var child : input.children()) for (var sample : child.samples()) if (sample == null) return null;
        int axis = input.axis(), grid = axis + 2;
        int span = input.children()[0].spanBlocks() * 2, step = span / axis;
        ClientColumnSample[] result = new ClientColumnSample[grid * grid];
        for (int z = 0; z < grid; z++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < grid; x++) {
                // The west/north halo is used only for normals. Never invent another source read.
                int bx = Math.max(0, (x - 1) * step), bz = Math.max(0, (z - 1) * step);
                result[z * grid + x] = aggregate(input.children(), bx, bz, span, step);
            }
        }
        // Bound the reduction error against EVERY retained fine column, not just mip corners.
        double error = 0, inherited = 0;
        boolean sealedEdges = true;
        for (int i = 0; i < 4; i++) {
            var child = input.children()[i];
            if (child.data().parentLod != null) inherited = Math.max(inherited, child.data().parentLod.error());
            int childGrid = child.cellAxis() + 1;
            for (int z = 0; z < childGrid; z++) for (int x = 0; x < childGrid; x++) {
                int bx = (i & 1) * span / 2 + x * child.spacingBlocks();
                int bz = (i >> 1) * span / 2 + z * child.spacingBlocks();
                int px = Math.min(axis, bx / step), pz = Math.min(axis, bz / step);
                var fine = child.samples()[z * childGrid + x];
                var coarse = result[(pz + 1) * grid + px + 1];
                if ((bx == 0 || bz == 0 || bx == span || bz == span)
                        && (fine.surfaceY() != coarse.surfaceY() || fine.fluidY() != coarse.fluidY()
                        || !java.util.Objects.equals(fine.volume(), coarse.volume()))) sealedEdges = false;
                // Every interpolated coarse point lies inside its corner envelope.
                // Comparing only the lower corner misses a fine plateau whose
                // height drops at the next coarse corner.
                for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++) {
                    var corner = result[(Math.min(axis, pz + dz) + 1) * grid + Math.min(axis, px + dx) + 1];
                    error = Math.max(error, Math.abs((double) fine.surfaceY() - corner.surfaceY()));
                    error = Math.max(error, Math.abs((double) fine.fluidY() - corner.fluidY()));
                    if (!java.util.Objects.equals(fine.volume(), corner.volume()) || fine.floating() || corner.floating()
                            || fine.hasLowerSpan() || corner.hasLowerSpan()) error = Double.POSITIVE_INFINITY;
                    boolean alongEdge = (bz == 0 || bz == span) && dz == 0 && bx % step != 0
                            || (bx == 0 || bx == span) && dx == 0 && bz % step != 0;
                    if (alongEdge && (fine.surfaceY() != corner.surfaceY() || fine.fluidY() != corner.fluidY()
                            || !java.util.Objects.equals(fine.volume(), corner.volume()))) sealedEdges = false;
                }
            }
        }
        // Horizontal displacement matters for cliffs even when their vertical samples agree.
        error = Math.hypot(error, step) + inherited;
        Source[] s = input.sources();
        return new Result(result, new Proof(s[0], s[1], s[2], s[3], error, sealedEdges));
    }

    private static ClientColumnSample aggregate(PredictionTile[] children, int x, int z, int span, int step) {
        ClientColumnSample selected = sample(children, x, z, span);
        int stride = step;
        for (var child : children) stride = Math.min(stride, child.spacingBlocks());
        for (int dz = 0; x != 0 && z != 0 && x != span && z != span && dz < step && z + dz < span; dz += stride)
            for (int dx = 0; dx < step && x + dx < span; dx += stride) {
                var candidate = sample(children, x + dx, z + dz, span);
                // Stable opaque/top preference, like voxel mips, preserves narrow ridges.
                if (candidate.hasSurface() && (!selected.hasSurface() || candidate.surfaceY() > selected.surfaceY()))
                    selected = candidate;
            }
        var s = selected;
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(), s.structureIndex(),
                s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(),
                (s.flags() & ~ClientColumnSample.FLAG_CAPTURED) | ClientColumnSample.FLAG_PARENT_LOD,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(),
                s.lowerTop(), s.lowerBottom(), s.spanFloor(), s.volume());
    }

    private static ClientColumnSample sample(PredictionTile[] children, int x, int z, int span) {
        int half = span / 2, dx = x >= half ? 1 : 0, dz = z >= half ? 1 : 0;
        var tile = children[dx | dz << 1];
        int sx = Math.min(tile.cellAxis(), (x - dx * half) / tile.spacingBlocks());
        int sz = Math.min(tile.cellAxis(), (z - dz * half) / tile.spacingBlocks());
        return tile.samples()[sz * (tile.cellAxis() + 1) + sx];
    }
}

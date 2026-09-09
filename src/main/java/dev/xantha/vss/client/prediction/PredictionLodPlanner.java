package dev.xantha.vss.client.prediction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Projected-size quadtree planner.  A tile is subdivided only
 * while its screen-space footprint is larger than the configured threshold;
 * distant roots therefore provide immediate coarse coverage while nearby
 * terrain converges to LOD 0.
 */
final class PredictionLodPlanner {
    private static final int ROOT_RADIUS = 3;
    private static final int MAX_DESIRED = 512;
    private static final int MAX_EXPANDED = MAX_DESIRED * 4;
    /** Keep a complete fine ring so the first frames have recognizable nearby terrain. */
    private static final int FINE_RADIUS_CHUNKS = 64;
    private static final int FINE_BUDGET = 128;
    private static final int MAX_DISTANCE_CHUNKS = 16_384;
    private static final double PIXEL_THRESHOLD = 2.0D;
    private static final int MAX_RUNTIME_LEAVES = 1024;
    private static final int MAX_SCOPED_LEAVES = 2048;
    static final int MAX_BAND_LEAVES = 640;
    // Bound the traversal itself, not only the returned list. A focused root
    // can otherwise expand into thousands of internal nodes before the final
    // list is truncated, delaying the first coarse horizon tiles.
    private static final int MAX_RUNTIME_EXPANDED = MAX_SCOPED_LEAVES * 2;

    private PredictionLodPlanner() {
    }

    static List<PredictionTileManager.PredictionTileKey> plan(
            ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ) {
        int rootSpan = PredictionTileManager.TILE_CHUNKS << PredictionTileManager.MAX_LOD_LEVEL;
        int rootX = Math.floorDiv(centerChunkX, rootSpan);
        int rootZ = Math.floorDiv(centerChunkZ, rootSpan);
        List<Node> leaves = new ArrayList<>();
        int[] expanded = {0};
        for (int dz = -ROOT_RADIUS; dz <= ROOT_RADIUS; dz++) {
            for (int dx = -ROOT_RADIUS; dx <= ROOT_RADIUS; dx++) {
                collect(dimension, rootX + dx, rootZ + dz,
                        PredictionTileManager.MAX_LOD_LEVEL, centerChunkX, centerChunkZ, leaves, expanded);
            }
        }
        // the keeps the projected-size leaves ordered by level, but a
        // hard global cap must not discard the fine ring around the player.
        // Reserve that ring first, then spend the remaining budget on coarse
        // leaves that provide the distant horizon.
        List<Node> fine = new ArrayList<>();
        List<Node> coarse = new ArrayList<>();
        for (Node node : leaves) {
            (node.key.lod() <= 1 ? fine : coarse).add(node);
        }
        fine.sort(Comparator.comparingLong(node -> node.distanceSquared));
        coarse.sort(Comparator.comparingInt((Node node) -> -node.key.lod())
                .thenComparingLong(node -> node.distanceSquared));
        List<PredictionTileManager.PredictionTileKey> result = new ArrayList<>(MAX_DESIRED);
        int fineCount = Math.min(FINE_BUDGET, fine.size());
        for (int index = 0; index < fineCount; index++) {
            result.add(fine.get(index).key);
        }
        int coarseCount = Math.min(MAX_DESIRED - result.size(), coarse.size());
        for (int index = 0; index < coarseCount; index++) {
            result.add(coarse.get(index).key);
        }
        return List.copyOf(result);
    }

    /** the layout-driven planner used by the live client. */
    static List<PredictionTileManager.PredictionTileKey> plan(
            ResourceKey<Level> dimension, double cameraX, double cameraY, double cameraZ,
            VssLodLayout layout, VssLodFocus focus, double pixelsPerBlock) {
        return plan(dimension, cameraX, cameraY, cameraZ, layout, focus, pixelsPerBlock,
                -64.0D, 320.0D);
    }

    static List<PredictionTileManager.PredictionTileKey> plan(
            ResourceKey<Level> dimension, double cameraX, double cameraY, double cameraZ,
            VssLodLayout layout, VssLodFocus focus, double pixelsPerBlock,
            double minBuildY, double maxBuildY) {
        return plan(dimension, cameraX, cameraY, cameraZ, layout, focus, pixelsPerBlock, minBuildY, maxBuildY, 768);
    }

    static List<PredictionTileManager.PredictionTileKey> plan(
            ResourceKey<Level> dimension, double cameraX, double cameraY, double cameraZ,
            VssLodLayout layout, VssLodFocus focus, double pixelsPerBlock,
            double minBuildY, double maxBuildY, int surfaceRadius) {
        return plan(dimension, cameraX, cameraY, cameraZ, layout, focus, pixelsPerBlock,
                minBuildY, maxBuildY, surfaceRadius, key -> true);
    }

    static List<PredictionTileManager.PredictionTileKey> plan(
            ResourceKey<Level> dimension, double cameraX, double cameraY, double cameraZ,
            VssLodLayout layout, VssLodFocus focus, double pixelsPerBlock,
            double minBuildY, double maxBuildY, int surfaceRadius,
            java.util.function.Predicate<PredictionTileManager.PredictionTileKey> needsSurface) {
        if (layout == null) {
            throw new IllegalArgumentException("layout is required");
        }
        int top = layout.levelCount() - 1;
        int topBlocks = layout.tileBlocks(top);
        int centerTileX = Math.floorDiv((int) Math.floor(cameraX), topBlocks);
        int centerTileZ = Math.floorDiv((int) Math.floor(cameraZ), topBlocks);
        int reach = layout.maxDistanceBlocks() / topBlocks + 2;
        java.util.Map<PredictionTileManager.PredictionTileKey, RuntimeNode> leaves = new java.util.HashMap<>();
        var candidates = new java.util.PriorityQueue<RuntimeNode>(
                Comparator.comparingInt((RuntimeNode node) -> node.distanceSquared() <= 256D * 256 ? 0 : node.scopedDetail() ? 1 : 2)
                        .thenComparingDouble(node -> node.scopedDetail() ? node.focusDistanceSquared() : 0)
                        .thenComparing(Comparator.comparingDouble(RuntimeNode::projected).reversed())
                        .thenComparingDouble(RuntimeNode::distanceSquared)
                        .thenComparingInt(node -> node.key().tileZ())
                        .thenComparingInt(node -> node.key().tileX()));
        for (int tz = centerTileZ - reach; tz <= centerTileZ + reach; tz++) {
            for (int tx = centerTileX - reach; tx <= centerTileX + reach; tx++) {
                RuntimeNode root = runtimeNode(dimension, tx, tz, top, cameraX, cameraY, cameraZ,
                        layout, focus, pixelsPerBlock, minBuildY, maxBuildY, surfaceRadius, needsSurface);
                if (root != null) {
                    leaves.put(root.key(), root);
                    candidates.add(root);
                }
            }
        }
        // Reserve a bounded radial skeleton before view-driven refinement.
        // Otherwise a distant root can span all three quality bands at once.
        int rootCount = leaves.size();
        var bandQueue = new java.util.PriorityQueue<RuntimeNode>(Comparator
                .comparingInt((RuntimeNode node) -> -node.key().lod())
                .thenComparingDouble(RuntimeNode::distanceSquared)
                .thenComparingInt(node -> node.key().tileZ())
                .thenComparingInt(node -> node.key().tileX()));
        bandQueue.addAll(leaves.values());
        while (!bandQueue.isEmpty()) {
            RuntimeNode parent = bandQueue.remove();
            int span = layout.tileBlocks(parent.key().lod());
            double minX = parent.key().tileX() * (double) span;
            double minZ = parent.key().tileZ() * (double) span;
            double far = Math.hypot(Math.max(Math.abs(minX - cameraX), Math.abs(minX + span - cameraX)),
                    Math.max(Math.abs(minZ - cameraZ), Math.abs(minZ + span - cameraZ)));
            double near = Math.sqrt(PredictionWorkOrder.distanceSquared(parent.key(), layout, cameraX, cameraZ));
            if (!PredictionDetailBands.needsBandSplit(near, far, span, layout.maxDistanceBlocks())) continue;
            List<RuntimeNode> children = new ArrayList<>(4);
            for (var childKey : PredictionTransitionPlan.children(parent.key())) {
                var child = runtimeNode(dimension, childKey.tileX(), childKey.tileZ(), childKey.lod(),
                        cameraX, cameraY, cameraZ, layout, focus, pixelsPerBlock, minBuildY, maxBuildY,
                        surfaceRadius, needsSurface);
                if (child != null) children.add(child);
            }
            if (leaves.size() - 1 + children.size() > MAX_BAND_LEAVES) break;
            leaves.remove(parent.key());
            for (var child : children) {
                leaves.put(child.key(), child);
                bandQueue.add(child);
            }
        }
        candidates.clear();
        candidates.addAll(leaves.values());
        // Spend the remaining bounded budget on the largest screen-space error first.
        // Replace a parent only when all of its visible children fit; every
        // point retains an owner even when refinement runs out of budget.
        int expanded = 0;
        // The radial skeleton must not consume the existing nearby/scoped
        // subdivision allowance. Its extra leaves remain independently bounded.
        int leafBudget = (focus == null ? MAX_RUNTIME_LEAVES : MAX_SCOPED_LEAVES) + leaves.size() - rootCount;
        while (!candidates.isEmpty() && expanded < MAX_RUNTIME_EXPANDED) {
            RuntimeNode parent = candidates.remove();
            if (parent.projected() <= layout.pixelThreshold()) continue;
            if (parent.key().lod() == 0) continue;
            List<RuntimeNode> children = new ArrayList<>(4);
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                RuntimeNode child = runtimeNode(dimension, parent.key().tileX() * 2 + dx,
                        parent.key().tileZ() * 2 + dz, parent.key().lod() - 1,
                        cameraX, cameraY, cameraZ, layout, focus, pixelsPerBlock, minBuildY, maxBuildY, surfaceRadius, needsSurface);
                if (child != null) children.add(child);
            }
            if (leaves.size() - 1 + children.size() > leafBudget) break;
            leaves.remove(parent.key());
            for (RuntimeNode child : children) {
                leaves.put(child.key(), child);
                candidates.add(child);
            }
            expanded++;
        }
        List<PredictionTileManager.PredictionTileKey> selected = leaves.values().stream()
                .sorted(Comparator.comparingInt((RuntimeNode node) -> node.key().lod() >= top - 1 ? 1 : 0)
                        .thenComparingDouble(RuntimeNode::distanceSquared)
                        .thenComparingInt(node -> node.key().lod())
                        .thenComparingInt(node -> node.key().tileZ())
                        .thenComparingInt(node -> node.key().tileX()))
                .map(RuntimeNode::key).toList();
        return PredictionTransitionPlan.balance(selected, layout.levelCount()).stream()
                .sorted(Comparator.comparingInt((PredictionTileManager.PredictionTileKey key) -> key.lod() >= top - 1 ? 1 : 0)
                        .thenComparingDouble(key -> PredictionWorkOrder.distanceSquared(key, layout, cameraX, cameraZ))
                        .thenComparingInt(PredictionTileManager.PredictionTileKey::lod)
                        .thenComparingInt(PredictionTileManager.PredictionTileKey::tileZ)
                        .thenComparingInt(PredictionTileManager.PredictionTileKey::tileX)).toList();
    }

    private record RuntimeNode(PredictionTileManager.PredictionTileKey key,
                               double distanceSquared, double projected, boolean scopedDetail, double focusDistanceSquared) {
    }

    private static RuntimeNode runtimeNode(ResourceKey<Level> dimension, int tileX, int tileZ, int level,
                                       double cameraX, double cameraY, double cameraZ,
                                       VssLodLayout layout, VssLodFocus focus,
                                       double pixelsPerBlock, double minBuildY, double maxBuildY, int surfaceRadius,
                                       java.util.function.Predicate<PredictionTileManager.PredictionTileKey> needsSurface) {
        int tileBlocks = layout.tileBlocks(level);
        double minX = tileX * (double) tileBlocks;
        double minZ = tileZ * (double) tileBlocks;
        double maxX = minX + tileBlocks;
        double maxZ = minZ + tileBlocks;
        double dx = cameraX < minX ? minX - cameraX : cameraX > maxX ? cameraX - maxX : 0.0D;
        double dz = cameraZ < minZ ? minZ - cameraZ : cameraZ > maxZ ? cameraZ - maxZ : 0.0D;
        // The configured horizon is a horizontal world radius. Altitude only
        // affects projected detail, not which ground regions can be generated.
        if (dx * dx + dz * dz > (double) layout.maxDistanceBlocks() * layout.maxDistanceBlocks()) return null;
        double vertical = Math.max(0.0D, Math.max(minBuildY - cameraY, cameraY - maxBuildY));
        double distance = Math.sqrt(dx * dx + dz * dz + vertical * vertical);
        boolean focused = focus != null && focus.intersects(minX, minZ, maxX, maxZ);
        double scale = focused ? focus.selectionScale(pixelsPerBlock) : pixelsPerBlock;
        double projected = VssLodProjection.projectedSize(tileBlocks, Math.max(1.0D, distance), scale);
        // Normal surface detail is a radius, never the crosshair ray. The scoped
        // target alone forces distant terrain down to the same plant-capable grid.
        VssLodFocus surfaceFocus = PredictionWorkOrder.surfaceFocus(focus);
        boolean surfaceFocused = surfaceFocus != null && surfaceFocus.intersects(minX, minZ, maxX, maxZ);
        // Every target inside the horizon can reach block detail. Reserve the
        // bounded telescope patch before spending the remaining planning slots.
        if (level > 0 && surfaceFocused) projected = Math.max(projected, layout.pixelThreshold() * (32.0D + level));
        if (level > 1 && (surfaceRadius > 0 && distance <= surfaceRadius || surfaceFocused)
                && needsSurface.test(new PredictionTileManager.PredictionTileKey(dimension, tileX, tileZ, level))) {
            projected = Math.max(projected, layout.pixelThreshold() * (2.0D + level));
        }
        return new RuntimeNode(new PredictionTileManager.PredictionTileKey(dimension, tileX, tileZ, level),
                distance * distance, projected, surfaceFocused && level > 0,
                surfaceFocused ? PredictionWorkOrder.distanceSquared(
                        new PredictionTileManager.PredictionTileKey(dimension, tileX, tileZ, level), layout, focus.x(), focus.z()) : 0);
    }

    private static void collect(ResourceKey<Level> dimension, int tileX, int tileZ, int lod,
                                int centerChunkX, int centerChunkZ, List<Node> leaves,
                                int[] expanded) {
        int span = PredictionTileManager.TILE_CHUNKS << lod;
        int minX = tileX * span;
        int minZ = tileZ * span;
        int maxX = minX + span;
        int maxZ = minZ + span;
        long dx = distanceToRange(centerChunkX, minX, maxX);
        long dz = distanceToRange(centerChunkZ, minZ, maxZ);
        long distanceSquared = dx * dx + dz * dz;
        if (Math.sqrt(distanceSquared) > MAX_DISTANCE_CHUNKS) {
            return;
        }
        // The Minecraft client projects block size to pixels. Both terms are
        // kept in chunk-space units here, so the ratio is invariant under the
        // block-to-chunk conversion used by the renderer.
        boolean fineRing = dx <= FINE_RADIUS_CHUNKS && dz <= FINE_RADIUS_CHUNKS;
        boolean projected = VssLodProjection.shouldSubdivide(
                span, Math.sqrt(distanceSquared), 1.0D, PIXEL_THRESHOLD);
        if (lod > 0 && (fineRing || projected)
                && (fineRing || expanded[0]++ < MAX_EXPANDED)) {
            int childX = tileX << 1;
            int childZ = tileZ << 1;
            collect(dimension, childX, childZ, lod - 1, centerChunkX, centerChunkZ, leaves, expanded);
            collect(dimension, childX + 1, childZ, lod - 1, centerChunkX, centerChunkZ, leaves, expanded);
            collect(dimension, childX, childZ + 1, lod - 1, centerChunkX, centerChunkZ, leaves, expanded);
            collect(dimension, childX + 1, childZ + 1, lod - 1, centerChunkX, centerChunkZ, leaves, expanded);
        } else {
            leaves.add(new Node(new PredictionTileManager.PredictionTileKey(
                    dimension, tileX, tileZ, lod), distanceSquared));
        }
    }

    private static long distanceToRange(int value, int minInclusive, int maxExclusive) {
        if (value < minInclusive) return (long) minInclusive - value;
        if (value >= maxExclusive) return (long) value - maxExclusive + 1L;
        return 0L;
    }

    private record Node(PredictionTileManager.PredictionTileKey key, long distanceSquared) {
    }
}

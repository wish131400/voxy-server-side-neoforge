package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Complete planning over immutable inputs. Does not read chunks or mutate the manager. */
final class PredictionPlanPreparation {
    record Input(ResourceKey<Level> dimension, VssLodLayout layout, PredictionWorkView view, VssLodFocus focus,
                 double x, double y, double z, double pixels, int vanillaRadius, int surfaceExtension,
                 double minY, double maxY, boolean vegetation, List<Integer> initialAxes, int settings, long revision, long removalRevision,
                 PredictionTileManager.RenderSnapshot residents, Map<PredictionTileKey, PredictionRelief> relief,
                 Set<Long> edited, boolean mixedEdits, Set<PredictionTileKey> ownershipCandidates, Set<PredictionTileKey> previousOwned,
                 Set<PredictionTileKey> uploaded,
                 int voxyRadius, int mediumBias, PredictionVisibilityFeedback.Snapshot visibility,
                 Set<PredictionTileKey> dirty, Map<PredictionTileKey, Integer> viewBands,
                 Set<PredictionTileKey> viewDependencies, Map<PredictionTileKey, Long> failed,
                 Map<PredictionTileKey, Long> deferred, PredictionIdleRefinement idle,
                 boolean ordinaryBusy, boolean cpuReady, boolean memoryReady, boolean idlePending, long now, boolean nativeDemand) { }
    record Result(Input input, List<PredictionTileKey> leaves, List<PredictionTileKey> plan,
                  Set<PredictionTileKey> surfaces, Set<PredictionTileKey> owned, int surfaceRadius,
                  Map<PredictionTileKey, Integer> ordinary, Map<PredictionTileKey, Integer> terrain,
                  Map<PredictionTileKey, Integer> transitions, PredictionMediumCoverage medium,
                  Map<PredictionTileKey, Integer> idleTargets, PredictionIdleRefinement idle,
                  boolean idleAllowed, String idleState) { }

    /** Coverage acquisition is injected by the pipeline; this algorithm never queries live global state. */
    static Result build(Input i, PredictionExactCoverageIndex.View exact) {
        return new Calculation().build(i, exact);
    }

    private record Topology(List<PredictionTileKey> leaves, List<PredictionTileKey> plan,
            Set<PredictionTileKey> surfaces, Set<PredictionTileKey> owned, int surfaceRadius,
            Map<PredictionTileKey, Integer> ordinary, Map<PredictionTileKey, Integer> targets,
            Map<PredictionTileKey, Integer> axes, PredictionMediumCoverage medium) { }

    /** One cache per serial planning lane. Only time-independent topology is
     * reused; retry eligibility, settling and promotions run for every input. */
    static final class Calculation {
        private Input previous;
        private PredictionExactCoverageIndex.View coverage;
        private Topology topology;
        private final CoverageQueries queries = new CoverageQueries();
        private volatile long topologyBuilds, topologyHits;

        Result build(Input i, PredictionExactCoverageIndex.View exact) {
            if (topology == null || !sameTopology(previous, i) || !exact.sameCoverage(coverage)) {
                queries.begin(i, exact);
                topology = topology(i, exact, queries); topologyBuilds++;
                queries.prune();
            } else topologyHits++;
            previous = i; coverage = exact;
            var leaves = topology.leaves();
            var plan = new ArrayList<>(topology.plan());
            var surfaces = topology.surfaces();
            var owned = topology.owned();
            int surfaceRadius = topology.surfaceRadius();
            var ordinary = topology.ordinary();
            var targets = new HashMap<>(topology.targets());
            var axes = topology.axes();
            var medium = topology.medium();
            if (i.nativeDemand()) return new Result(i, leaves, List.copyOf(plan), Set.copyOf(surfaces),
                    Set.copyOf(owned), surfaceRadius, Map.copyOf(ordinary), Map.copyOf(targets), Map.of(), medium,
                    Map.of(), i.idle(), false, "voxy-demand");
            var idle = i.idle();
            boolean available = !i.ordinaryBusy() && i.cpuReady() && i.memoryReady();
            boolean idleAllowed = idle.settled(i.now(), available, i.x(), i.z());
            String idleState = i.ordinaryBusy() ? "ordinary-work" : !i.cpuReady() ? "cpu-budget"
                    : !i.memoryReady() ? "memory-budget" : "settling";
            double fine = PredictionDetailBands.fineRadius(i.layout().maxDistanceBlocks(), i.dimension());
            var idleTargets = idle.update(Set.copyOf(leaves), targets, axes, i.layout(),
                    key -> !surfaces.contains(key) && !owned.contains(key) && retry(i, key)
                            && (i.view() == null || relevant(i, key))
                            && PredictionWorkOrder.distanceSquared(key, i.layout(), i.x(), i.z()) > fine * fine,
                    key -> idleScore(i, key, axes.getOrDefault(key, 64)), idleAllowed && !i.idlePending(), i.now());
            if (idleAllowed) idleState = idle.state();
            var desired = new HashSet<>(plan);
            for (var entry : idleTargets.entrySet()) {
                if (desired.add(entry.getKey())) plan.add(entry.getKey());
                targets.merge(entry.getKey(), entry.getValue(), Math::max);
            }
            var terrainLeaves = new HashSet<>(leaves); terrainLeaves.addAll(idleTargets.keySet());
            var transitions = PredictionTransitionPlan.exposedTargets(desired, terrainLeaves, axes,
                    i.layout().levelCount(), targets);
            return new Result(i, leaves, List.copyOf(plan), Set.copyOf(surfaces), Set.copyOf(owned), surfaceRadius,
                    Map.copyOf(ordinary), Map.copyOf(targets), transitions, medium, idleTargets, idle, idleAllowed, idleState);
        }
        long topologyBuilds() { return topologyBuilds; }
        long topologyHits() { return topologyHits; }
        long coverageBuilds() { return queries.builds; }
        long coverageHits() { return queries.hits; }
    }

    /** Column authority is independent of camera geometry. Keep only queries
     * visited by the last topology; ownership/radius and projection still run
     * exactly for every moving view. Owned negatives invalidate with coverage. */
    private static final class CoverageQueries {
        private record Query(PredictionTileKey key, boolean expanded) { }
        final Map<Query, Boolean> facts = new HashMap<>();
        final Set<Query> used = new HashSet<>();
        Input input;
        PredictionExactCoverageIndex.View coverage;
        volatile long builds, hits;
        void begin(Input next, PredictionExactCoverageIndex.View exact) {
            if (input == null || !input.dimension().equals(next.dimension()) || !input.layout().equals(next.layout())
                    || !input.edited().equals(next.edited()) || input.mixedEdits() != next.mixedEdits()
                    || !exact.sameCoverage(coverage)) facts.clear();
            input = next; coverage = exact; used.clear();
        }
        boolean authority(PredictionTileKey key, boolean expanded) {
            var query = new Query(key, expanded); used.add(query);
            Boolean known = facts.get(query);
            if (known != null) { hits++; return known; }
            boolean value;
            if (!expanded) value = fullyAuthoritative(key, input, coverage);
            else {
                int span = input.layout().tileBlocks(key.lod()) / 16;
                int x = key.tileX() * span, z = key.tileZ() * span;
                value = coverage.ownsBox(x - 1, z - 1, x + span, z + span);
            }
            facts.put(query, value); builds++; return value;
        }
        void prune() { facts.keySet().retainAll(used); used.clear(); }
    }

    private static boolean sameTopology(Input a, Input b) {
        return a != null && a.dimension().equals(b.dimension()) && a.layout().equals(b.layout())
                && a.nativeDemand() == b.nativeDemand()
                && Objects.equals(a.focus(), b.focus())
                && (a.nativeDemand() || (a.view() == null ? b.view() == null : a.view().sameGeometry(b.view())))
                && a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && (a.nativeDemand() || a.pixels() == b.pixels())
                && a.vanillaRadius() == b.vanillaRadius() && a.surfaceExtension() == b.surfaceExtension()
                && a.minY() == b.minY() && a.maxY() == b.maxY() && a.vegetation() == b.vegetation()
                && a.initialAxes().equals(b.initialAxes()) && a.settings() == b.settings()
                && a.revision() == b.revision() && a.removalRevision() == b.removalRevision()
                && a.residents() == b.residents() && a.relief().equals(b.relief())
                && a.edited().equals(b.edited()) && a.mixedEdits() == b.mixedEdits()
                && a.ownershipCandidates().equals(b.ownershipCandidates()) && a.previousOwned().equals(b.previousOwned())
                && a.uploaded().equals(b.uploaded()) && a.voxyRadius() == b.voxyRadius()
                && a.mediumBias() == b.mediumBias() && a.dirty().equals(b.dirty())
                && a.visibility().sameHints(b.visibility(), a.now(), b.now());
    }

    private static Topology topology(Input i, PredictionExactCoverageIndex.View exact, CoverageQueries queries) {
        int surfaceRadius = PredictionWorkOrder.surfaceRadius(i.x(), i.z(), i.vanillaRadius(),
                i.surfaceExtension(), i.layout().maxDistanceBlocks(), (x, z) -> edited(i, x, z) || exact.owns(x, z));
        var authoritative = new HashMap<PredictionTileKey, Boolean>();
        java.util.function.Predicate<PredictionTileKey> needsSurface = key -> authoritative.computeIfAbsent(key,
                candidate -> !queries.authority(candidate, false));
        var tiles = i.residents().tiles();
        var leaves = i.nativeDemand() ? nativeCoverage(i.dimension(), i.x(), i.z(), i.layout(), i.mediumBias())
                : PredictionLodPlanner.plan(i.dimension(), i.x(), i.y(), i.z(), i.layout(), i.focus(),
                Math.max(.01, i.pixels()), i.minY(), i.maxY() + (i.view() == null ? 0 : 64), i.vegetation() ? surfaceRadius : 0, needsSurface,
                key -> { var error = i.relief().get(key); return error != null && error.needsRefinement(); }, i.view(),
                key -> !PredictionWorkOrder.scoped(key, i.layout(), i.focus())
                        && i.visibility().hidden(key, i.layout(), i.view(), i.now(), tiles, i.residents().epochs(), i.dirty()));
        var plan = new ArrayList<>(PredictionLodPlanner.withCoarseCoverage(leaves, i.layout()));
        var candidates = new HashSet<>(i.ownershipCandidates()); candidates.addAll(plan);
        candidates.addAll(tiles.keySet());
        var owned = new HashSet<PredictionTileKey>();
        int radius = PredictionVoxyOwnership.radiusBlocks(i.voxyRadius());
        if (radius > 0) for (var key : candidates) {
            int margin = i.previousOwned().contains(key) ? 32 : 64;
            if (insideVoxyRadius(key, i, Math.max(0, radius - margin)) && queries.authority(key, true)) owned.add(key);
        }
        leaves = leaves.stream().filter(key -> !owned.contains(key)).toList();
        plan.removeIf(owned::contains);
        var surfaces = new HashSet<PredictionTileKey>();
        var targets = new HashMap<PredictionTileKey, Integer>();
        for (var key : leaves) {
            boolean surface = !i.nativeDemand() && i.vegetation() && needsSurface.test(key) && surfaceEligible(i, key, surfaceRadius);
            if (surface) surfaces.add(key);
            // Decoration attaches to resident columns; asking for trees is not
            // a request to resample every terrain column at the maximum axis.
            targets.put(key, i.nativeDemand() ? Math.max(32, initialAxis(i, key)) : ordinaryAxis(i, key));
        }
        var ordinary = new HashMap<PredictionTileKey, Integer>();
        for (var key : plan) ordinary.put(key, targets.getOrDefault(key, initialAxis(i, key)));
        var medium = PredictionMediumCoverage.plan(leaves, i.layout(), i.mediumBias());
        var axes = new HashMap<PredictionTileKey, Integer>();
        tiles.forEach((key, tile) -> axes.put(key, tile.cellAxis()));
        return new Topology(List.copyOf(leaves), List.copyOf(plan), Set.copyOf(surfaces), Set.copyOf(owned),
                surfaceRadius, Map.copyOf(ordinary), Map.copyOf(targets), Map.copyOf(axes), medium);
    }

    /** View-independent cover: at most roughly eight tiles per horizon radius.
     * Precision and vegetation are subsequently requested by visible native nodes. */
    static List<PredictionTileKey> bootstrap(ResourceKey<Level> dimension, double x, double z,
                                              VssLodLayout layout, int bias) {
        int level = PredictionMediumCoverage.level(layout, bias), span = layout.tileBlocks(level);
        double radius = layout.maxDistanceBlocks();
        int minX = (int)Math.floor((x-radius)/span), maxX = (int)Math.floor((x+radius)/span);
        int minZ = (int)Math.floor((z-radius)/span), maxZ = (int)Math.floor((z+radius)/span);
        var leaves = new ArrayList<PredictionTileKey>();
        for (int tz=minZ;tz<=maxZ;tz++) for (int tx=minX;tx<=maxX;tx++) {
            double dx=Math.max(Math.max(tx*(double)span-x,0),x-(tx+1D)*span);
            double dz=Math.max(Math.max(tz*(double)span-z,0),z-(tz+1D)*span);
            if (dx*dx+dz*dz<=radius*radius) leaves.add(new PredictionTileKey(dimension,tx,tz,level));
        }
        return List.copyOf(leaves);
    }

    /** A small local cover is independent of GPU demand. After relocation native
     * empty nodes may not request detail until the horizon publication arrives.
     * Supply this bounded middle band directly, with its ancestor paths, so the
     * coarse horizon is never the sole source of those initial requests. */
    static List<PredictionTileKey> nativeCoverage(ResourceKey<Level> dimension, double x, double z,
                                                  VssLodLayout layout, int bias) {
        var keys = new LinkedHashSet<>(bootstrap(dimension, x, z, layout, bias));
        int level = Math.min(4, PredictionMediumCoverage.level(layout, bias));
        int span = layout.tileBlocks(level);
        double radius = Math.min(2048, layout.maxDistanceBlocks());
        for (int tz = (int)Math.floor((z-radius)/span); tz <= (int)Math.floor((z+radius)/span); tz++)
            for (int tx = (int)Math.floor((x-radius)/span); tx <= (int)Math.floor((x+radius)/span); tx++) {
                double dx = Math.max(0, Math.max(tx*(double)span-x, x-(tx+1D)*span));
                double dz = Math.max(0, Math.max(tz*(double)span-z, z-(tz+1D)*span));
                if (dx*dx+dz*dz <= radius*radius) keys.add(new PredictionTileKey(dimension,tx,tz,level));
            }
        return List.copyOf(keys);
    }

    private static int initialAxis(Input i, PredictionTileKey key) {
        return i.initialAxes().get(key.lod());
    }
    static boolean fullyVoxyOwned(PredictionTileKey key, Input i, PredictionExactCoverageIndex.View exact, int radius) {
        if (!insideVoxyRadius(key, i, radius)) return false;
        int span = i.layout().tileBlocks(key.lod());
        int cx = key.tileX() * (span / 16), cz = key.tileZ() * (span / 16);
        // Keep a transition column around a complete volume, as the GPU mask does.
        return exact.ownsBox(cx - 1, cz - 1, cx + span / 16, cz + span / 16);
    }
    private static boolean insideVoxyRadius(PredictionTileKey key, Input i, int radius) {
        // Captured on the client thread. The worker must never query Voxy's
        // mutable GPU node index, and stored columns alone cannot retire demand.
        if (!i.uploaded().contains(key)) return false;
        if (!i.dimension().equals(key.dimension()) || key.lod() < 0 || key.lod() >= i.layout().levelCount()) return false;
        int span = i.layout().tileBlocks(key.lod());
        double x = key.tileX() * (double) span, z = key.tileZ() * (double) span;
        return PredictionVoxyOwnership.containsBox(i.x(), i.y(), i.z(), radius,
                x, i.minY(), z, x + span, i.maxY(), z + span);
    }
    static boolean fullyAuthoritative(PredictionTileKey key, Input i, PredictionExactCoverageIndex.View exact) {
        if (!i.dimension().equals(key.dimension()) || key.lod() < 0 || key.lod() >= i.layout().levelCount()) return false;
        int span = Math.max(1, i.layout().tileBlocks(key.lod()) / 16), x = key.tileX() * span, z = key.tileZ() * span;
        if (i.edited().isEmpty()) return exact.ownsBox(x, z, x + span - 1, z + span - 1);
        for (int dz = 0; dz < span; dz++) for (int dx = 0; dx < span; dx++)
            if (!edited(i, x + dx, z + dz) && !exact.owns(x + dx, z + dz)) return false;
        return true;
    }
    private static boolean edited(Input i, int x, int z) {
        long key = pack(x, z); return i.edited().contains(i.mixedEdits() ? it.unimi.dsi.fastutil.HashCommon.mix(key) : key);
    }
    private static long pack(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    private static boolean surfaceEligible(Input i, PredictionTileKey key, int radius) {
        return PredictionWorkOrder.surfaceEligible(key, i.layout(), i.view(), i.focus(),
                i.x(), i.y(), i.z(), radius, i.pixels(), i.minY(), i.maxY() + 64);
    }
    private static int ordinaryAxis(Input i, PredictionTileKey key) {
        return ordinaryAxis(i.layout(), i.view(), i.focus(), key, i.x(), i.y(), i.z(),
                i.pixels(), i.minY(), i.maxY());
    }

    static int ordinaryAxis(VssLodLayout layout, PredictionWorkView view, VssLodFocus focus,
                            PredictionTileKey key, double x, double y, double z, double pixels,
                            double minY, double maxY) {
        if (view == null) return PredictionDetailBands.cellAxis(key, layout, x, y, z, focus, pixels, minY, maxY);
        if (PredictionWorkOrder.scoped(key, layout, focus)) return layout.cellAxis(key.lod());
        return PredictionScreenDetail.targetAxis(PredictionScreenDetail.projected(view, key, layout,
                x, y, z, pixels, minY, maxY + 64), layout);
    }
    private static boolean retry(Input i, PredictionTileKey key) {
        return i.now() - i.deferred().getOrDefault(key, Long.MIN_VALUE / 2) >= 0
                && i.now() - i.failed().getOrDefault(key, Long.MIN_VALUE / 2) > 30_000_000_000L;
    }
    private static boolean relevant(Input i, PredictionTileKey key) {
        Integer known = i.viewBands().get(key);
        int band = known == null ? i.view().band(key, i.layout(), i.focus(), i.minY(), i.maxY() + 64) : known;
        return band <= PredictionWorkView.BUFFER || i.viewDependencies().contains(key);
    }
    private static double idleScore(Input i, PredictionTileKey key, int axis) {
        double cell = PredictionScreenDetail.projected(i.view(), key, i.layout(), i.x(), i.y(), i.z(), i.pixels(),
                i.minY(), i.maxY() + 64) / axis;
        if (cell <= (i.view() == null ? Math.max(.5, i.layout().pixelThreshold() / 128)
                : i.layout().pixelThreshold() / VssLodLayout.TILE_QUADS)) return 0;
        var error = i.relief().get(key);
        return cell * (i.view() == null || relevant(i, key) ? 4 : 1) * (error != null && error.needsRefinement() ? 1.5 : 1);
    }
}

package dev.xantha.vss.client.prediction;

import dev.xantha.vss.config.PredictionVegetationDensity;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded immutable display results; source references do not pin retired chunk maps. */
final class PredictionVegetationDisplayCache {
    static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    record Source(long key, Map<BlockPos, BlockState> blocks) { }
    private record Key(int x, int z, int span, int spacing, boolean trees, PredictionVegetationDensity density) { }
    private record DensityKey(long chunk, PredictionVegetationDensity density) { }
    private record DensitySource(WeakReference<Map<BlockPos, BlockState>> original,
                                 Map<BlockPos, BlockState> selected, long generation, long bytes) { }
    private static final long MAX_DENSITY_BYTES = 8L * 1024 * 1024;
    private final Map<DensityKey, DensitySource> densitySources = new LinkedHashMap<>(16, .75F, true);
    private long densityBytes;
    private static final class Entry {
        final long[] sources, captures;
        final List<WeakReference<Map<BlockPos, BlockState>>> maps;
        final long generation, bytes;
        final PredictionVegetation.Tile tile;

        Entry(List<Source> source, long[] captures, PredictionVegetation.Tile tile, long generation) {
            sources = new long[source.size()];
            maps = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                sources[i] = source.get(i).key(); maps.add(new WeakReference<>(source.get(i).blocks()));
            }
            this.captures = captures;
            this.tile = tile;
            this.generation = generation;
            // Includes cells, voxels, map entries and cached signature bytes.
            bytes = 512L + tile.blocks().size() * 192L + tile.cells().size() * 96L
                    + sources.length * 48L + captures.length * 8L;
        }

        boolean matches(List<Source> source, long[] mask) {
            if (generation != PredictionVegetationTraits.generation() || sources.length != source.size()
                    || !Arrays.equals(captures, mask)) return false;
            for (int i = 0; i < sources.length; i++)
                if (sources[i] != source.get(i).key() || maps.get(i).get() != source.get(i).blocks()) return false;
            return true;
        }
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, .75F, true);
    private final Set<Build> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final LongAdder hits = new LongAdder(), builds = new LongAdder();
    private long bytes;

    final class Build implements AutoCloseable {
        private final Key key;
        private boolean valid = true;
        Build(Key key) { this.key = key; }
        @Override public void close() { synchronized (entries) { active.remove(this); } }
    }

    Build begin(int x, int z, int span, int spacing, boolean trees) {
        return begin(x, z, span, spacing, trees, PredictionVegetationDensity.HIGH);
    }

    Build begin(int x, int z, int span, int spacing, boolean trees, PredictionVegetationDensity density) {
        synchronized (entries) {
            if (active.size() >= 256) throw new PredictionWorkDeferred();
            var build = new Build(new Key(x, z, span, spacing, trees, density));
            active.add(build);
            return build;
        }
    }

    PredictionVegetation.Tile tile(int x, int z, int span, int spacing, boolean trees,
            List<Source> sources, LongPredicate captured) {
        try (var build = begin(x, z, span, spacing, trees)) { return tile(build, sources, captured); }
    }

    PredictionVegetation.Tile tile(Build build, List<Source> sources, LongPredicate captured) {
        var key = build.key;
        int x = key.x, z = key.z, span = key.span, spacing = key.spacing;
        int size = Math.max(1, spacing / 2);
        var mask = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int cz = Math.floorDiv(z - size, 16); cz <= Math.floorDiv(z + span + size - 1, 16); cz++)
            for (int cx = Math.floorDiv(x - size, 16); cx <= Math.floorDiv(x + span + size - 1, 16); cx++) {
                long chunk = chunkKey(cx, cz);
                if (captured.test(chunk)) mask.add(chunk);
            }
        long[] captures = mask.toLongArray(); Arrays.sort(captures);
        long generation = PredictionVegetationTraits.generation();
        synchronized (entries) {
            requireValid(build);
            var previous = entries.get(key);
            if (previous != null && previous.matches(sources, captures)) { hits.increment(); return previous.tile; }
        }
        var displaySources = key.trees ? selectSources(build, sources) : sources;
        var tile = merge(x, z, span, spacing, key.trees, displaySources,
                pos -> !mask.isEmpty() && mask.contains(chunkKey(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16))));
        var result = new Entry(sources, captures, tile, generation);
        builds.increment();
        synchronized (entries) {
            requireValid(build);
            if (generation != PredictionVegetationTraits.generation() || result.bytes > MAX_BYTES) return tile;
            var previous = entries.put(key, result);
            bytes += result.bytes - (previous == null ? 0 : previous.bytes);
            while (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
                var iterator = entries.values().iterator();
                bytes -= iterator.next().bytes; iterator.remove();
            }
        }
        return tile;
    }

    private List<Source> selectSources(Build build, List<Source> sources) {
        var density = build.key.density;
        if (density == PredictionVegetationDensity.HIGH) return sources;
        var selected = new ArrayList<Source>(sources.size());
        for (var source : sources) {
            long generation = PredictionVegetationTraits.generation();
            var key = new DensityKey(source.key(), density);
            DensitySource cached;
            synchronized (entries) {
                requireValid(build);
                cached = densitySources.get(key);
            }
            Map<BlockPos, BlockState> blocks;
            if (cached != null && cached.generation() == generation
                    && cached.original().get() == source.blocks()) blocks = cached.selected();
            else {
                blocks = PredictionVegetationSelection.select(source.blocks(), density);
                long cost = 128L + blocks.size() * 96L;
                synchronized (entries) {
                    requireValid(build);
                    if (generation == PredictionVegetationTraits.generation() && cost <= MAX_DENSITY_BYTES) {
                        var previous = densitySources.put(key, new DensitySource(
                                new WeakReference<>(source.blocks()), blocks, generation, cost));
                        densityBytes += cost - (previous == null ? 0 : previous.bytes());
                        while (densitySources.size() > 128 || densityBytes > MAX_DENSITY_BYTES) {
                            var iterator = densitySources.values().iterator();
                            densityBytes -= iterator.next().bytes(); iterator.remove();
                        }
                    }
                }
            }
            selected.add(new Source(source.key(), blocks));
        }
        return selected;
    }

    /** The legacy per-column capture API shares exactly the production merge and reduction. */
    static PredictionVegetation.Tile merge(int x, int z, int span, int spacing, boolean trees,
            List<Source> sources, Predicate<BlockPos> captured) {
        int size = Math.max(1, spacing / 2);
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (var source : sources) for (var entry : source.blocks().entrySet()) {
            var pos = entry.getKey(); var state = entry.getValue();
            if (pos.getX() < x - size || pos.getX() >= x + span + size
                    || pos.getZ() < z - size || pos.getZ() >= z + span + size
                    || captured.test(pos)) continue;
            if (PredictionVegetation.vegetation(state) && !trees || !PredictionVegetation.solid(state) && spacing > 2) continue;
            blocks.put(pos, state);
        }
        return PredictionVegetation.boundedTile(blocks, x, z, span, spacing, size);
    }

    void invalidate(int chunkX, int chunkZ) {
        synchronized (entries) {
            densitySources.entrySet().removeIf(entry -> {
                long source = entry.getKey().chunk();
                boolean overlaps = Math.abs((long) (int) (source >> 32) - chunkX) <= 2
                        && Math.abs((long) (int) source - chunkZ) <= 2;
                if (overlaps) densityBytes -= entry.getValue().bytes();
                return overlaps;
            });
            for (var build : active) if (affected(build.key, chunkX, chunkZ)) build.valid = false;
            entries.entrySet().removeIf(entry -> {
                boolean overlaps = affected(entry.getKey(), chunkX, chunkZ);
                if (overlaps) bytes -= entry.getValue().bytes;
                return overlaps;
            });
        }
    }

    private static boolean affected(Key key, int chunkX, int chunkZ) {
        // A display request includes one source-chunk border. Each source can
        // read two more chunks beyond its own origin during feature placement.
        return Math.floorDiv((long) key.x, 16) - 1 <= chunkX + 2L
                && Math.floorDiv((long) key.x + key.span - 1, 16) + 1 >= chunkX - 2L
                && Math.floorDiv((long) key.z, 16) - 1 <= chunkZ + 2L
                && Math.floorDiv((long) key.z + key.span - 1, 16) + 1 >= chunkZ - 2L;
    }

    private void requireValid(Build build) {
        if (!build.valid || !active.contains(build) || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("prediction display source changed");
    }

    String diagnostics() {
        synchronized (entries) { return "displayCache={hits=" + hits.sum() + ",builds=" + builds.sum()
                + ",entries=" + entries.size() + ",bytes=" + bytes
                + ",densityEntries=" + densitySources.size() + ",densityBytes=" + densityBytes + "}"; }
    }
    static long chunkKey(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
}

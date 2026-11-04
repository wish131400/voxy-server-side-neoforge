package dev.xantha.vss.client.prediction;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.apache.commons.lang3.mutable.MutableObject;

/** Sampler-owned exact occupancy; mutable interpolation is bounded and owned by one worker request. */
final class FreeTerraForgedExterior implements AutoCloseable {
    // A 66-sample grid at step 4 spans up to 18 chunks on an axis. Keep a frontier's
    // working set through its neighbour pass without retaining the complete tile or world.
    private static final int REQUEST_CONTEXTS = 32;
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final LevelHeightAccessor heights;
    private final NoiseSettings noise;
    private final Aquifer.FluidPicker fluids;
    private final int width, minY, maxY, capacity;
    private final boolean batch;
    private final LinkedHashMap<Long, Entry> cells = new LinkedHashMap<>(64, 0.75f, true);
    private final ThreadLocal<Request> request = new ThreadLocal<>();
    private volatile boolean closed;
    private final LongAdder builds = new LongAdder(), columns = new LongAdder(), hits = new LongAdder();
    private final LongAdder waits = new LongAdder(), rejected = new LongAdder();
    private final LongAdder contexts = new LongAdder(), evaluated = new LongAdder(), reused = new LongAdder();
    private final LongAdder contextHits = new LongAdder(), repositions = new LongAdder(), evictions = new LongAdder();

    FreeTerraForgedExterior(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor heights) {
        this.generator = generator; this.random = random; this.heights = heights;
        noise = generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(heights);
        width = noise.getCellWidth();
        minY = Math.floorDiv(noise.minY(), noise.getCellHeight()) * noise.getCellHeight();
        maxY = minY + noise.height() / noise.getCellHeight() * noise.getCellHeight();
        // At most 8 MiB of occupancy and conservative entry/array overhead per world sampler.
        long entryBytes = 256L + width * width * (48L + ((maxY - minY + 63L) / 64) * 8);
        capacity = (int) Math.max(1, Math.min(2048, (8L << 20) / entryBytes));
        batch = FreeTerraForgedExteriorOrder.canBatch(random.router());
        try { fluids = (Aquifer.FluidPicker) ((Supplier<?>) Access.FLUIDS.invokeExact(generator)).get(); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("FTF exterior fluid picker", failure); }
    }

    boolean batchesCells() { return batch; }
    long builds() { return builds.sum(); }
    long columns() { return columns.sum(); }
    long contexts() { return contexts.sum(); }
    long evaluatedBlocks() { return evaluated.sum(); }
    String diagnostics() {
        int size; synchronized (cells) { size = cells.size(); }
        return "mode=" + (batch ? "cellBatch" : "orderedColumns") + ",builds=" + builds.sum()
                + ",columns=" + columns.sum() + ",hits=" + hits.sum() + ",waits=" + waits.sum()
                + ",rejectedRoofs=" + rejected.sum() + ",cells=" + size + ",capacity=" + capacity
                + ",contexts=" + contexts.sum() + ",evaluatedBlocks=" + evaluated.sum() + ",reusedBlocks=" + reused.sum()
                + ",contextHits=" + contextHits.sum() + ",sliceRepositions=" + repositions.sum() + ",evictions=" + evictions.sum();
    }

    ClientTerrainSampler.SamplingScope request() {
        if (request.get() != null) return () -> {};
        Request active = new Request();
        request.set(active);
        return active;
    }

    boolean[][] sample(int[] xs, int[] zs, int step, int[] bottoms, int[] tops, int count, BooleanSupplier valid) {
        try (var ignored = request()) {
            return sampleRequest(xs, zs, step, bottoms, tops, count, valid);
        }
    }

    private boolean[][] sampleRequest(int[] xs, int[] zs, int step, int[] bottoms, int[] tops, int count, BooleanSupplier valid) {
        if (step != 1 && step != 2 && step != 4) throw new IllegalArgumentException("exterior footprint step");
        check(valid);
        boolean[][] result = new boolean[count][];
        Map<Long, Range> anchors = new HashMap<>();
        for (int i = 0; i < count; i++)
            if (tops[i] > bottoms[i] && bottoms[i] >= noise.minY() && tops[i] <= maxY)
                anchors.merge(key(xs[i], zs[i]), new Range(bottoms[i], tops[i], tops[i] - 1), Range::merge);
        Map<Long, Column> loaded = load(anchors, valid);
        Map<Long, Range> neighbours = new HashMap<>();
        for (int i = 0; i < count; i++) {
            Column anchor = loaded.get(key(xs[i], zs[i]));
            if (tops[i] <= bottoms[i] || bottoms[i] < noise.minY() || tops[i] > maxY || anchor == null) continue;
            if (!anchor.occupied(tops[i] - 1)) { rejected.increment(); continue; }
            boolean[] occupied = new boolean[tops[i] - bottoms[i]];
            result[i] = occupied;
            boolean complete = union(occupied, anchor, bottoms[i]);
            // Flat solid columns retain the original one-column early exit.
            if (complete) continue;
            if (batch) for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++)
                if (dx != 0 || dz != 0) add(neighbours, xs[i] + dx, zs[i] + dz, bottoms[i], tops[i]);
        }
        if (!batch) {
            // Opaque graphs keep the original per-column order and solid-footprint early exit.
            for (int i = 0; i < count; i++) {
                if (result[i] == null) continue;
                boolean complete = true;
                for (boolean value : result[i]) complete &= value;
                for (int dz = 0; dz < step && !complete; dz++) for (int dx = 0; dx < step && !complete; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    Map<Long, Range> request = new HashMap<>();
                    add(request, xs[i] + dx, zs[i] + dz, bottoms[i], tops[i]);
                    Column column = load(request, valid).get(key(xs[i] + dx, zs[i] + dz));
                    complete = union(result[i], column, bottoms[i]);
                }
            }
            check(valid);
            return result;
        }
        loaded = load(neighbours, valid);
        for (int i = 0; i < count; i++) {
            if (result[i] == null) continue;
            check(valid);
            for (int dz = 0; dz < step; dz++) for (int dx = 0; dx < step; dx++) {
                Column column = loaded.get(key(xs[i] + dx, zs[i] + dz));
                if (column != null) union(result[i], column, bottoms[i]);
            }
        }
        check(valid);
        return result;
    }

    private static boolean union(boolean[] target, Column source, int bottom) {
        boolean complete = true;
        for (int i = 0; i < target.length; i++) { target[i] |= source.occupied(bottom + i); complete &= target[i]; }
        return complete;
    }
    private record Range(int low, int high, int rejectAt) {
        Range merge(Range b) {
            return new Range(Math.min(low, b.low), Math.max(high, b.high), rejectAt == b.rejectAt ? rejectAt : Integer.MIN_VALUE);
        }
    }
    private static void add(Map<Long, Range> map, int x, int z, int bottom, int top) {
        map.merge(key(x, z), new Range(bottom, top, Integer.MIN_VALUE), Range::merge);
    }

    private Map<Long, Column> load(Map<Long, Range> requests, BooleanSupplier valid) {
        Request active = request.get();
        active.clearScratch();
        Map<Long, Range[]> groups = active.groups;
        for (var request : requests.entrySet()) {
            int x = x(request.getKey()), z = z(request.getKey());
            long cell = key(Math.floorDiv(x, width) * width, Math.floorDiv(z, width) * width);
            Range[] ranges = groups.computeIfAbsent(cell, ignored -> active.ranges());
            ranges[Math.floorMod(x, width) * width + Math.floorMod(z, width)] = request.getValue();
        }
        Map<Long, Column> result = active.result;
        var ordered = active.ordered;
        ordered.addAll(groups.entrySet());
        if (batch) ordered.sort(java.util.Comparator
                .comparingInt((Map.Entry<Long, Range[]> g) -> Math.floorDiv(x(g.getKey()), 16))
                .thenComparingInt(g -> Math.floorDiv(z(g.getKey()), 16))
                .thenComparingInt(g -> x(g.getKey())).thenComparingInt(g -> z(g.getKey())));
        for (var group : ordered) {
            check(valid);
            Entry entry;
            synchronized (cells) {
                check(valid);
                entry = cells.computeIfAbsent(group.getKey(), ignored -> new Entry(width * width));
                while (cells.size() > capacity) { cells.remove(cells.keySet().iterator().next()); evictions.increment(); }
            }
            lock(entry, valid);
            try {
                int bx = x(group.getKey()), bz = z(group.getKey());
                Range[] wanted = group.getValue(), missing = active.missing;
                Arrays.fill(missing, null);
                boolean compute = false;
                for (int i = 0; i < wanted.length; i++) {
                    if (wanted[i] == null) continue;
                    Column previous = entry.columns[i];
                    boolean rejectedRoof = previous != null && wanted[i].rejectAt != Integer.MIN_VALUE
                            && previous.low <= wanted[i].rejectAt && previous.high > wanted[i].rejectAt
                            && !previous.occupied(wanted[i].rejectAt);
                    if (rejectedRoof || previous != null && previous.low <= wanted[i].low && previous.high >= wanted[i].high) hits.increment();
                    else {
                        Range range = previous == null ? wanted[i] : new Range(Math.min(wanted[i].low, previous.low),
                                Math.max(wanted[i].high, previous.high), wanted[i].rejectAt);
                        missing[i] = batch ? range : new Range(range.low, maxY, range.rejectAt);
                        compute = true;
                    }
                }
                if (compute) {
                    // Publish only a completed immutable result; cancellation cannot poison an entry.
                    Column[] computed = batch ? buildCell(bx, bz, missing, entry.columns, active, valid)
                            : buildOrdered(bx, bz, missing, valid);
                    check(valid);
                    for (int i = 0; i < missing.length; i++) if (computed[i] != null) entry.columns[i] = computed[i];
                }
                for (int i = 0; i < wanted.length; i++) if (wanted[i] != null)
                    result.put(key(bx + i / width, bz + i % width), entry.columns[i]);
            } finally { entry.lock.unlock(); }
        }
        return result;
    }

    private Column[] buildCell(int x, int z, Range[] ranges, Column[] previous, Request batchWork, BooleanSupplier valid) {
        int low = maxY, high = minY, height = noise.getCellHeight();
        Column[] output = batchWork.output;
        Arrays.fill(output, null);
        for (int i = 0; i < ranges.length; i++) if (ranges[i] != null) {
            low = Math.min(low, ranges[i].low); high = Math.max(high, ranges[i].high);
            output[i] = new Column(ranges[i].low, ranges[i].high); columns.increment();
            if (previous[i] != null) output[i].copy(previous[i]);
        }
        Work work = batchWork.at(x, z, valid);
        builds.increment();
        long evaluatedBlocks = 0, reusedBlocks = 0;
        for (int cy = Math.floorDiv(high - 1, height); cy >= Math.floorDiv(low, height); cy--) {
            check(valid);
            work.selectCellYZ(cy - Math.floorDiv(minY, height), batchWork.cellZ(z));
            for (int y = Math.min(high, (cy + 1) * height) - 1; y >= Math.max(low, cy * height); y--) {
                work.updateForY(y, (double) Math.floorMod(y, height) / height);
                for (int dx = 0; dx < width; dx++) {
                    work.updateForX(x + dx, (double) dx / width);
                    for (int dz = 0; dz < width; dz++) {
                        Column column = output[dx * width + dz];
                        if (column == null || y < column.low || y >= column.high) continue;
                        Column cached = previous[dx * width + dz];
                        if (cached != null && y >= cached.low && y < cached.high) { reusedBlocks++; continue; }
                        work.updateForZ(z + dz, (double) dz / width);
                        BlockState state = work.state();
                        evaluatedBlocks++;
                        if (!(state == null ? generator.generatorSettings().value().defaultBlock() : state).isAir()) column.set(y);
                        else if (ranges[dx * width + dz].rejectAt == y) {
                            output[dx * width + dz] = column.trimLow(y);
                            // All remaining requests may now finish above the original floor.
                            low = maxY;
                            for (Column active : output) if (active != null) low = Math.min(low, active.low);
                        }
                    }
                }
            }
        }
        evaluated.add(evaluatedBlocks); reused.add(reusedBlocks);
        return output;
    }

    /** The flood's frontiers and anchor/neighbour passes share a bounded set of exclusive chunk graphs. */
    private final class Request implements ClientTerrainSampler.SamplingScope {
        private final LinkedHashMap<Long, Cursor> chunks = new LinkedHashMap<>(8, .75f, true);
        final Map<Long, Range[]> groups = new HashMap<>();
        final Map<Long, Column> result = new HashMap<>();
        final java.util.ArrayList<Map.Entry<Long, Range[]>> ordered = new java.util.ArrayList<>();
        final java.util.ArrayDeque<Range[]> pool = new java.util.ArrayDeque<>();
        final Range[] missing = new Range[width * width];
        final Column[] output = new Column[width * width];

        Range[] ranges() { return pool.isEmpty() ? new Range[width * width] : pool.removeFirst(); }
        void clearScratch() {
            for (Range[] ranges : groups.values()) {
                Arrays.fill(ranges, null);
                if (pool.size() < 128) pool.addLast(ranges);
            }
            groups.clear(); result.clear(); ordered.clear();
        }
        Work at(int x, int z, BooleanSupplier valid) {
            check(valid);
            long key = key(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            Cursor cursor = chunks.get(key);
            if (cursor == null) {
                if (chunks.size() == REQUEST_CONTEXTS) {
                    var oldest = chunks.entrySet().iterator();
                    oldest.next().getValue().close(); oldest.remove();
                }
                cursor = new Cursor(Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
                chunks.put(key, cursor);
            } else contextHits.increment();
            return cursor.at(x);
        }
        int cellZ(int z) { return Math.floorMod(z, 16) / width; }
        @Override public void close() {
            try { chunks.values().forEach(Cursor::close); }
            finally { chunks.clear(); request.remove(); }
        }
    }

    private final class Cursor implements AutoCloseable {
        private final Work work;
        private final int baseX;
        private int cellX = -1;
        private boolean interpolating;
        Cursor(int x, int z) {
            baseX = x;
            work = new Work(16 / width, random, x, z, noise, generator.generatorSettings().value(), fluids);
            contexts.increment();
        }
        Work at(int x) {
            if (!interpolating) {
                work.initializeForFirstCellX(); interpolating = true;
            }
            int next = (x - baseX) / width;
            if (next != cellX) {
                if (next == cellX + 1) {
                    if (cellX >= 0) work.swapSlices();
                } else {
                    // Fill the requested left slice, then its right slice. Do not restart interpolation:
                    // its monotonically increasing counters invalidate CacheOnce/C2ME memoized values.
                    work.advanceCellX(next - 1); work.swapSlices(); repositions.increment();
                }
                work.advanceCellX(next); cellX = next;
            }
            return work;
        }
        @Override public void close() {
            if (interpolating) { interpolating = false; work.stopInterpolation(); }
        }
    }

    private Column[] buildOrdered(int x, int z, Range[] ranges, BooleanSupplier valid) {
        Column[] result = new Column[ranges.length];
        for (int i = 0; i < ranges.length; i++) if (ranges[i] != null) {
            check(valid);
            Column column = new Column(ranges[i].low, maxY);
            int[] y = {maxY - 1};
            int rejectAt = ranges[i].rejectAt;
            boolean[] badRoof = {false};
            Predicate<BlockState> probe = state -> {
                int current = y[0]--;
                if ((current & 15) == 0) check(valid);
                if (!state.isAir()) column.set(current);
                else if (current == rejectAt) badRoof[0] = true;
                return badRoof[0] || current <= column.low;
            };
            try { OptionalInt ignored = (OptionalInt) Access.ITERATE.invokeExact(generator, heights, random,
                    x + i / width, z + i % width, (MutableObject<?>) null, probe); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("FTF original exterior iterator", failure); }
            builds.increment(); columns.increment(); result[i] = badRoof[0] ? column.trimLow(rejectAt) : column;
        }
        return result;
    }

    private void lock(Entry entry, BooleanSupplier valid) {
        if (entry.lock.tryLock()) return;
        waits.increment();
        try { do { check(valid); } while (!entry.lock.tryLock(10, TimeUnit.MILLISECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    private void check(BooleanSupplier valid) {
        if (closed || Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new CancellationException();
    }
    @Override public void close() { closed = true; synchronized (cells) { cells.clear(); } }
    private static long key(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    private static int x(long key) { return (int) (key >> 32); }
    private static int z(long key) { return (int) key; }
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock(); final Column[] columns;
        Entry(int size) { columns = new Column[size]; }
    }
    private static final class Column {
        final int low, high; final long[] bits;
        Column(int low, int high) { this.low = low; this.high = high; bits = new long[(high - low + 63) >>> 6]; }
        void set(int y) { int at = y - low; bits[at >>> 6] |= 1L << (at & 63); }
        Column trimLow(int low) {
            Column result = new Column(low, high);
            for (int y = low; y < high; y++) if (occupied(y)) result.set(y);
            return result;
        }
        boolean occupied(int y) { int at = y - low; return y >= low && y < high && (bits[at >>> 6] & 1L << (at & 63)) != 0; }
        void copy(Column source) {
            for (int y = Math.max(low, source.low); y < Math.min(high, source.high); y++) if (source.occupied(y)) set(y);
        }
    }
    private static final class Work extends NoiseChunk {
        Work(int count, RandomState random, int x, int z, NoiseSettings noise, NoiseGeneratorSettings settings, Aquifer.FluidPicker fluids) {
            super(count, random, x, z, noise, NO_STRUCTURES, settings, fluids, Blender.empty());
        }
        BlockState state() { return getInterpolatedState(); }
    }
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES = new DensityFunctions.BeardifierOrMarker() {
        @Override public double compute(DensityFunction.FunctionContext point) { return 0; }
        @Override public double minValue() { return 0; }
        @Override public double maxValue() { return 0; }
    };
    private static final class Access {
        static final MethodHandle FLUIDS, ITERATE;
        static {
            try {
                var type = NoiseBasedChunkGenerator.class;
                var field = Arrays.stream(type.getDeclaredFields()).filter(f -> f.getType() == Supplier.class).findFirst().orElseThrow();
                field.setAccessible(true);
                FLUIDS = MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(Supplier.class, type));
                Class<?>[] parameters = {LevelHeightAccessor.class, RandomState.class, int.class, int.class, MutableObject.class, Predicate.class};
                var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getReturnType() == OptionalInt.class
                        && Arrays.equals(m.getParameterTypes(), parameters)).findFirst().orElseThrow();
                method.setAccessible(true); ITERATE = MethodHandles.lookup().unreflect(method);
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
}

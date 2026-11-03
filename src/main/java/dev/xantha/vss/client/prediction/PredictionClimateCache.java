package dev.xantha.vss.client.prediction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/** World-owned, exact X/Z inputs. Workers never share mutable Java rows. */
final class PredictionClimateCache implements AutoCloseable {
    static final int CAPACITY = 16384, MAX_INPUTS = 16;
    private static final long WORLD_BYTES = 8L << 20, PROCESS_BYTES = 64L << 20;
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    private static final Cleaner CLEANER = Cleaner.create();
    private final AtomicLong bytes = new AtomicLong();
    private final ConcurrentLinkedQueue<WeakReference<Worker>> retained = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Worker> workers = ThreadLocal.withInitial(() -> {
        var worker = new Worker();
        retained.add(new WeakReference<>(worker));
        return worker;
    });
    private volatile boolean closed;
    private volatile NativeInputs nativeInputs;
    private int inputs;

    int reserve() { return inputs < MAX_INPUTS ? inputs++ : -1; }
    int inputCount() { return inputs; }
    private static final class Worker { volatile Table table; boolean unavailable; }
    private static final class Table {
        final long[] keys = new long[CAPACITY];
        final int[] masks = new int[CAPACITY];
        final double[] values;
        final ByteBuffer input = ByteBuffer.allocateDirect(16 * 8).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer output = ByteBuffer.allocateDirect(16 * 56).order(ByteOrder.LITTLE_ENDIAN);
        final Cleaner.Cleanable reservation;
        NativeInputs binding;
        long hits, misses, batches, copied;
        Table(int inputs, AtomicLong owner, long bytes) {
            values = new double[CAPACITY * inputs];
            reservation = CLEANER.register(this, () -> { LIVE_BYTES.addAndGet(-bytes); owner.addAndGet(-bytes); });
        }
        int prepare(int x, int z) {
            long key = (long) x << 32 | z & 0xffffffffL;
            long hash = key * 0x9e3779b97f4a7c15L;
            hash ^= hash >>> 32;
            hash *= 0xbf58476d1ce4e5b9L;
            int slot = (int) (hash ^ hash >>> 32) & (CAPACITY - 1);
            if (keys[slot] != key) masks[slot] = 0;
            keys[slot] = key;
            return slot;
        }
    }
    private synchronized Table allocate() {
        if (closed || inputs == 0) return null;
        long required = (long) CAPACITY * (12 + inputs * 8) + 1024;
        if (bytes.get() + required > WORLD_BYTES) return null;
        long current;
        do {
            current = LIVE_BYTES.get();
            if (current + required > PROCESS_BYTES) return null;
        } while (!LIVE_BYTES.compareAndSet(current, current + required));
        bytes.addAndGet(required);
        try { return new Table(inputs, bytes, required); }
        catch (RuntimeException | Error failure) {
            LIVE_BYTES.addAndGet(-required); bytes.addAndGet(-required); throw failure;
        }
    }
    double get(int lane, int x, int z, DoubleSupplier original) {
        if (closed) return original.getAsDouble();
        Worker worker = workers.get();
        Table table = worker.table;
        if (table == null) {
            if (worker.unavailable) return original.getAsDouble();
            table = allocate();
            if (table == null) { worker.unavailable = true; return original.getAsDouble(); }
            worker.table = table;
            if (closed) { worker.table = null; table.reservation.clean(); return original.getAsDouble(); }
        }
        NativeInputs binding = nativeInputs;
        if (table.binding != binding) {
            Arrays.fill(table.masks, 0);
            table.binding = binding;
        }
        int slot = table.prepare(x, z), bit = 1 << lane;
        if ((table.masks[slot] & bit) != 0) { table.hits++; return table.values[slot * inputs + lane]; }
        table.misses++;
        if (binding != null && binding.lanes[lane] >= 0 && (table.masks[slot] & (1 << MAX_INPUTS)) == 0) {
            binding.copy(table, x, z, inputs);
            slot = table.prepare(x, z);
            if ((table.masks[slot] & bit) != 0) return table.values[slot * inputs + lane];
        }
        double value = original.getAsDouble();
        // Recursive evaluation can collide with this row; claim it again before publishing.
        slot = table.prepare(x, z);
        table.values[slot * inputs + lane] = value;
        table.masks[slot] |= bit;
        return value;
    }

    /** Binding is installed only by the matching native-world construction, after graph identity checks. */
    synchronized void bind(long world, JsonElement[] expressions, long seed, boolean fp32) {
        detach();
        if (closed || inputs == 0 || "off".equals(System.getProperty("vss.climateSharing"))) return;
        try {
            var descriptor = JsonParser.parseString(RustWorldgenBackend.horizontalDescriptor(world)).getAsJsonObject();
            if (descriptor.get("seed").getAsLong() != seed || !descriptor.get("precision").getAsString().equals(fp32 ? "fp32" : "fp64")) return;
            JsonArray aliases = descriptor.getAsJsonArray("lanes");
            int[] lanes = new int[inputs]; Arrays.fill(lanes, -1);
            for (int i = 0; i < inputs; i++) {
                if (expressions[i] == null) continue;
                for (int lane = 0; lane < aliases.size(); lane++) {
                    for (JsonElement alias : aliases.get(lane).getAsJsonArray())
                        if (PredictionClimateIdentity.same(PredictionClimateIdentity.normalize(expressions[i]), PredictionClimateIdentity.normalize(alias))) lanes[i] = lane;
                }
            }
            if (Arrays.stream(lanes).anyMatch(lane -> lane >= 0)) { nativeInputs = new NativeInputs(world, lanes); }
        } catch (RuntimeException | LinkageError unsupported) {
            // Older native libraries and opaque codecs keep the exact Java calculation.
        }
    }
    synchronized void detach() {
        NativeInputs binding = nativeInputs;
        nativeInputs = null;
        if (binding != null) binding.close();
    }
    private static final class NativeInputs {
        private long world;
        final int[] lanes;
        NativeInputs(long world, int[] lanes) { this.world = world; this.lanes = lanes; }
        synchronized void close() { world = 0; }
        synchronized void copy(Table table, int x, int z, int inputs) {
            if (world == 0) return;
            int count = (x & 3) == 0 && (z & 3) == 0 ? 16 : 1;
            int ox = Math.floorDiv(x, 16) * 16, oz = Math.floorDiv(z, 16) * 16;
            table.input.clear();
            for (int i = 0; i < count; i++) table.input.putInt(count == 1 ? x : ox + i / 4 * 4)
                    .putInt(count == 1 ? z : oz + i % 4 * 4);
            if (RustWorldgenBackend.horizontalLookup(world, table.input, table.output, count) != count) return;
            table.batches++;
            for (int i = 0; i < count; i++) {
                int slot = table.prepare(table.input.getInt(i * 8), table.input.getInt(i * 8 + 4));
                int mask = (int) table.output.getLong(i * 56);
                for (int lane = 0; lane < inputs; lane++) {
                    int source = lanes[lane];
                    if (source >= 0 && (mask & (1 << source)) != 0) {
                        table.values[slot * inputs + lane] = table.output.getDouble(i * 56 + 8 + source * 8);
                        table.masks[slot] |= 1 << lane; table.copied++;
                    }
                }
                table.masks[slot] |= 1 << MAX_INPUTS;
            }
        }
    }
    String diagnostics() {
        long hits = 0, misses = 0, batches = 0, copied = 0;
        for (var reference : retained) {
            Worker worker = reference.get(); Table table = worker == null ? null : worker.table;
            if (table != null) { hits += table.hits; misses += table.misses; batches += table.batches; copied += table.copied; }
        }
        return "horizontalInputs=" + inputs + ",horizontalBytes=" + bytes.get() + ",horizontalHits=" + hits
                + ",horizontalMisses=" + misses + ",horizontalBatches=" + batches + ",horizontalCopied=" + copied;
    }
    @Override public synchronized void close() {
        closed = true; detach();
        for (var reference : retained) {
            Worker worker = reference.get();
            if (worker != null) { Table table = worker.table; worker.table = null; if (table != null) table.reservation.clean(); }
        }
        retained.clear(); workers.remove();
    }
}

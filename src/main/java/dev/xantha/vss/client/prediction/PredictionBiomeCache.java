package dev.xantha.vss.client.prediction;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Exact quart answers shared by surface rules, tints and decoration jobs. */
final class PredictionBiomeCache {
    private final BiomeSource source;
    private final Climate.Sampler climate;
    // Store answers, not mostly empty spatial pages. Dispersed surface queries
    // and dense cave neighborhoods share the same bounded 65536-answer budget.
    private static final int STRIPES = 16, BUCKETS = 256, WAYS = 16;
    private final Stripe[] stripes = new Stripe[STRIPES];
    private final ThreadLocal<Local> workers = ThreadLocal.withInitial(Local::new);
    private final java.util.concurrent.atomic.LongAdder localHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sharedHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder loads = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder evictions = new java.util.concurrent.atomic.LongAdder();

    PredictionBiomeCache(BiomeSource source, Climate.Sampler climate) {
        this.source = source; this.climate = climate;
        java.util.Arrays.setAll(stripes, i -> new Stripe());
    }
    Holder<Biome> get(int x, int y, int z) {
        int hash = hash(x, y, z);
        Local local = workers.get(); int slot = hash & (Local.SIZE - 1);
        Holder<Biome> answer = local.get(slot, x, y, z);
        if (answer != null) { localHits.increment(); return answer; }
        Stripe stripe = stripes[hash & (STRIPES - 1)];
        int bucket = (hash >>> 4 & (BUCKETS - 1)) * WAYS;
        synchronized (stripe) { answer = stripe.get(bucket, x, y, z); }
        if (answer != null) sharedHits.increment();
        else {
            // Never hold a cache monitor while running modded climate/noise.
            // Concurrent misses may duplicate evaluation; failures are not cached.
            loads.increment(); answer = sample(source, climate, x, y, z);
            if (answer != null) synchronized (stripe) {
                if (stripe.put(bucket, x, y, z, answer)) evictions.increment();
            }
        }
        if (answer != null) local.store(slot, x, y, z, answer);
        return answer;
    }
    private static int hash(int x, int y, int z) {
        int h = x * 0x9E3779B9 ^ Integer.rotateLeft(y * 0x85EBCA6B, 11) ^ z * 0xC2B2AE35;
        h = (h ^ h >>> 16) * 0x7FEB352D;
        h = (h ^ h >>> 15) * 0x846CA68B;
        return h ^ h >>> 16;
    }
    static Holder<Biome> sample(BiomeSource source, Climate.Sampler climate, int x, int y, int z) {
        try { return source.getNoiseBiome(x, y, z, climate); }
        catch (PredictionWorkDeferred | java.util.concurrent.CancellationException deferred) { throw deferred; }
        catch (RuntimeException failure) { throw new LookupFailure(x, y, z, failure); }
    }
    static final class LookupFailure extends IllegalStateException {
        LookupFailure(int x, int y, int z, RuntimeException cause) {
            super("Prediction biome lookup failed at quart " + x + "," + y + "," + z, cause);
        }
    }
    String diagnostics() {
        return "localHits=" + localHits.sum() + ",sharedHits=" + sharedHits.sum()
                + ",loads=" + loads.sum() + ",evictions=" + evictions.sum()
                + ",pageEvictions=0,answerCapacity=" + STRIPES * BUCKETS * WAYS;
    }
    private static class Points {
        final int[] xs, ys, zs;
        final Holder<Biome>[] values;
        @SuppressWarnings("unchecked") Points(int size) {
            xs = new int[size]; ys = new int[size]; zs = new int[size];
            values = (Holder<Biome>[]) new Holder<?>[size];
        }
        Holder<Biome> get(int at, int x, int y, int z) {
            return values[at] != null && xs[at] == x && ys[at] == y && zs[at] == z ? values[at] : null;
        }
        void store(int at, int x, int y, int z, Holder<Biome> value) {
            xs[at] = x; ys[at] = y; zs[at] = z; values[at] = value;
        }
    }
    private static final class Local extends Points {
        static final int SIZE = 256;
        Local() { super(SIZE); }
    }
    private static final class Stripe extends Points {
        final boolean[] referenced = new boolean[BUCKETS * WAYS];
        final byte[] next = new byte[BUCKETS];
        Stripe() { super(BUCKETS * WAYS); }
        @Override Holder<Biome> get(int bucket, int x, int y, int z) {
            for (int at = bucket; at < bucket + WAYS; at++) {
                Holder<Biome> value = super.get(at, x, y, z);
                if (value != null) { referenced[at] = true; return value; }
            }
            return null;
        }
        boolean put(int bucket, int x, int y, int z, Holder<Biome> answer) {
            if (get(bucket, x, y, z) != null) return false;
            int b = bucket / WAYS, at;
            // A bounded second-chance clock favors repeated answers without
            // linked entries, page scans or allocation on the query path.
            for (;;) {
                at = bucket + next[b]; next[b] = (byte)((next[b] + 1) & (WAYS - 1));
                if (values[at] == null || !referenced[at]) break;
                referenced[at] = false;
            }
            boolean evicted = values[at] != null;
            store(at, x, y, z, answer); referenced[at] = true;
            return evicted;
        }
    }
}

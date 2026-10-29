package dev.xantha.vss.client.prediction;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Exact quart-coordinate reuse for surface rules, tints and decoration reads. */
final class PredictionBiomeCache {
    private final BiomeSource source;
    private final Climate.Sampler climate;
    // A page follows the spatial neighborhood used by adjacent decoration jobs.
    // 128 shared pages retain at most 65536 exact quart answers. A bounded
    // point cache retains sparsely populated retired pages across worker handoffs;
    // workers hold only eight page references, rather than duplicate point tables.
    private static final int PAGE_BITS = 3, PAGE_AXIS = 1 << PAGE_BITS;
    private static final int STRIPES = 16, PAGES_PER_STRIPE = 8;
    private final ThreadLocal<Local> workers = ThreadLocal.withInitial(Local::new);
    private final Stripe[] stripes = new Stripe[STRIPES];
    private final java.util.concurrent.atomic.LongAdder localHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder sharedHits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder loads = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder evictions = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder pageEvictions = new java.util.concurrent.atomic.LongAdder();

    PredictionBiomeCache(BiomeSource source, Climate.Sampler climate) {
        this.source = source;
        this.climate = climate;
        java.util.Arrays.setAll(stripes, i -> new Stripe());
    }

    Holder<Biome> get(int quartX, int quartY, int quartZ) {
        int px = quartX >> PAGE_BITS, py = quartY >> PAGE_BITS, pz = quartZ >> PAGE_BITS;
        Local local = workers.get();
        Page page = local.last;
        if (page == null || !page.matches(px, py, pz)) {
            page = null;
            for (Page candidate : local.pages)
                if (candidate != null && candidate.matches(px, py, pz)) { page = candidate; break; }
        }
        boolean localPage = page != null;
        if (page == null) {
            var key = new PageKey(px, py, pz);
            Stripe stripe = stripe(key);
            synchronized (stripe) {
                page = stripe.pages.get(key);
                if (page == null) {
                    if (stripe.pages.size() == PAGES_PER_STRIPE) {
                        var oldest = stripe.pages.entrySet().iterator();
                        Page retired = oldest.next().getValue(); oldest.remove();
                        pageEvictions.increment(); evictions.add(retired.loaded.sum());
                        stripe.retainSparse(retired);
                    }
                    page = new Page(key); stripe.pages.put(key, page);
                }
            }
            local.pages[local.next++ & (local.pages.length - 1)] = page;
        }
        local.last = page;
        int slot = ((quartY & (PAGE_AXIS - 1)) * PAGE_AXIS + (quartZ & (PAGE_AXIS - 1)))
                * PAGE_AXIS + (quartX & (PAGE_AXIS - 1));
        Holder<Biome> biome = page.biomes.get(slot);
        if (biome != null) {
            if (localPage) localHits.increment(); else sharedHits.increment();
        } else {
            Stripe stripe = stripe(page.key);
            if (stripe.retired != null) {
                synchronized (stripe) { biome = stripe.retired.get(quartX, quartY, quartZ); }
                if (biome != null) {
                    sharedHits.increment();
                    if (page.biomes.compareAndSet(slot, null, biome)) page.loaded.increment();
                    return biome;
                }
            }
            // Modded climate evaluation stays outside cache locks. Concurrent
            // misses may duplicate one lookup, but never serialize noise work.
            loads.increment();
            biome = sample(source, climate, quartX, quartY, quartZ);
            if (biome != null && page.biomes.compareAndSet(slot, null, biome)) page.loaded.increment();
        }
        return biome;
    }

    private Stripe stripe(PageKey key) {
        return stripes[(key.hashCode() ^ key.hashCode() >>> 16) & (STRIPES - 1)];
    }

    static Holder<Biome> sample(BiomeSource source, Climate.Sampler climate, int x, int y, int z) {
        try {
            return source.getNoiseBiome(x, y, z, climate);
        } catch (PredictionWorkDeferred | java.util.concurrent.CancellationException deferred) {
            throw deferred;
        } catch (RuntimeException failure) {
            // A broken biome context invalidates placement as a whole. It must
            // not be swallowed as one unsupported feature and cached as empty.
            throw new LookupFailure(x, y, z, failure);
        }
    }

    static final class LookupFailure extends IllegalStateException {
        LookupFailure(int x, int y, int z, RuntimeException cause) {
            super("Prediction biome lookup failed at quart " + x + "," + y + "," + z, cause);
        }
    }

    String diagnostics() {
        return "localHits=" + localHits.sum() + ",sharedHits=" + sharedHits.sum()
                + ",loads=" + loads.sum() + ",evictions=" + evictions.sum() + ",pageEvictions=" + pageEvictions.sum();
    }
    private record PageKey(int x, int y, int z) {
        @Override public int hashCode() {
            return x * 0x9E3779B9 ^ Integer.rotateLeft(z * 0x85EBCA6B, 11) ^ y * 0xC2B2AE35;
        }
    }
    private static final class Page {
        final PageKey key;
        final java.util.concurrent.atomic.AtomicReferenceArray<Holder<Biome>> biomes =
                new java.util.concurrent.atomic.AtomicReferenceArray<>(PAGE_AXIS * PAGE_AXIS * PAGE_AXIS);
        final java.util.concurrent.atomic.LongAdder loaded = new java.util.concurrent.atomic.LongAdder();
        Page(PageKey key) { this.key = key; }
        boolean matches(int x, int y, int z) { return key.x == x && key.y == y && key.z == z; }
    }
    private static final class Local {
        final Page[] pages = new Page[8];
        Page last;
        int next;
    }
    private static final class Stripe {
        final java.util.LinkedHashMap<PageKey, Page> pages = new java.util.LinkedHashMap<>(16, .75F, true);
        volatile RetiredPoints retired;
        void retainSparse(Page page) {
            // Dense neighborhoods already use their page capacity efficiently.
            // Sparse reads must not consume an entire 512-slot page indefinitely.
            if (page.loaded.sum() > 64) return;
            if (retired == null) retired = new RetiredPoints();
            for (int i = 0; i < page.biomes.length(); i++) {
                Holder<Biome> biome = page.biomes.get(i);
                if (biome != null) retired.put((page.key.x << PAGE_BITS) + (i & 7),
                        (page.key.y << PAGE_BITS) + (i >> 6),
                        (page.key.z << PAGE_BITS) + ((i >> 3) & 7), biome);
            }
        }
    }
    /** Eight-way exact points, at most 16384 answers across all stripes. */
    private static final class RetiredPoints {
        private static final int BUCKETS = 128, WAYS = 8;
        final int[] x = new int[BUCKETS * WAYS], y = new int[BUCKETS * WAYS], z = new int[BUCKETS * WAYS];
        final byte[] next = new byte[BUCKETS];
        final Holder<Biome>[] biomes;
        @SuppressWarnings("unchecked") RetiredPoints() { biomes = (Holder<Biome>[]) new Holder<?>[BUCKETS * WAYS]; }
        private int bucket(int x, int y, int z) {
            int hash = x * 0x9E3779B9 ^ Integer.rotateLeft(z * 0x85EBCA6B, 11) ^ y * 0xC2B2AE35;
            // Independent mixing avoids correlating point buckets with page stripes.
            hash = (hash ^ hash >>> 16) * 0x7FEB352D;
            hash = (hash ^ hash >>> 15) * 0x846CA68B;
            return (hash ^ hash >>> 16) & (BUCKETS - 1);
        }
        Holder<Biome> get(int x, int y, int z) {
            int bucket = bucket(x, y, z);
            for (int at = bucket * WAYS; at < (bucket + 1) * WAYS; at++)
                if (biomes[at] != null && this.x[at] == x && this.y[at] == y && this.z[at] == z) return biomes[at];
            return null;
        }
        void put(int x, int y, int z, Holder<Biome> biome) {
            if (get(x, y, z) != null) return;
            int bucket = bucket(x, y, z), at = bucket * WAYS + next[bucket];
            next[bucket] = (byte) ((next[bucket] + 1) & (WAYS - 1));
            this.x[at] = x; this.y[at] = y; this.z[at] = z; biomes[at] = biome;
        }
    }
}

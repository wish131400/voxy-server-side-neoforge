package dev.xantha.vss.client.prediction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Registry and native-schedule metadata is immutable; transaction failures belong to one job. */
final class RustVegetationDescriptors {
    private final String[] names;
    private final boolean[] supported;
    private final RustTerrainSampler.JavaFeatureReason[] reasons;

    private RustVegetationDescriptors(int size) {
        names = new String[size];
        supported = new boolean[size];
        reasons = new RustTerrainSampler.JavaFeatureReason[size];
    }

    static RustVegetationDescriptors build(List<PlacedFeature> features, int step, Registry<PlacedFeature> registry,
                                           String[][] order, Predicate<String> supports) {
        var result = new RustVegetationDescriptors(features.size());
        for (int index = 0; index < features.size(); index++) {
            var key = registry.getKey(features.get(index));
            result.reasons[index] = RustTerrainSampler.JavaFeatureReason.UNREGISTERED;
            if (key == null) continue;
            String name = result.names[index] = key.toString();
            result.reasons[index] = RustTerrainSampler.JavaFeatureReason.ORDER_MISMATCH;
            if (step >= order.length || index >= order[step].length || !name.equals(order[step][index])) continue;
            result.reasons[index] = RustTerrainSampler.JavaFeatureReason.UNSUPPORTED;
            result.supported[index] = supports.test(name);
        }
        return result;
    }

    String name(int index) { return names[index]; }

    static final class Job {
        private final RustVegetationDescriptors descriptors;
        private java.util.BitSet rejected;
        private RustTerrainSampler.JavaFeatureReason[] overrides;

        Job(RustVegetationDescriptors descriptors) { this.descriptors = descriptors; }
        boolean supported(int index) { return descriptors.supported[index] && (rejected == null || !rejected.get(index)); }
        String name(int index) { return descriptors.name(index); }
        RustTerrainSampler.JavaFeatureReason reason(int index) {
            return overrides != null && overrides[index] != null ? overrides[index] : descriptors.reasons[index];
        }
        void reject(int index, RustTerrainSampler.JavaFeatureReason reason) {
            if (rejected == null) rejected = new java.util.BitSet();
            if (overrides == null) overrides = new RustTerrainSampler.JavaFeatureReason[descriptors.names.length];
            rejected.set(index);
            overrides[index] = reason;
        }
    }

    static final class Cache {
        private static final int MAX_ENTRIES = 64;
        private static final class Key {
            final List<PlacedFeature> features;
            final Registry<PlacedFeature> registry;
            final int step;
            Key(List<PlacedFeature> features, int step, Registry<PlacedFeature> registry) {
                this.features = features; this.step = step; this.registry = registry;
            }
            @Override public int hashCode() {
                return 31 * (31 * System.identityHashCode(features) + step) + System.identityHashCode(registry);
            }
            @Override public boolean equals(Object other) {
                return other instanceof Key key && key.features == features && key.registry == registry && key.step == step;
            }
        }
        private final Map<Key, RustVegetationDescriptors> entries = new LinkedHashMap<>(16, .75F, true);
        private final LongAdder hits = new LongAdder(), builds = new LongAdder();
        private long revision;

        RustVegetationDescriptors get(List<PlacedFeature> features, int step, Registry<PlacedFeature> registry,
                                      String[][] order, Predicate<String> supports) {
            // Production lists are already immutable; mutable callers must not create stale cache hits.
            var key = new Key(List.copyOf(features), step, registry);
            long startedRevision;
            synchronized (entries) {
                var cached = entries.get(key);
                if (cached != null) { hits.increment(); return cached; }
                startedRevision = revision;
            }
            var result = build(key.features, step, registry, order, supports);
            builds.increment();
            synchronized (entries) {
                if (revision != startedRevision) return result;
                entries.put(key, result);
                while (entries.size() > MAX_ENTRIES) entries.remove(entries.keySet().iterator().next());
            }
            return result;
        }
        void clear() { synchronized (entries) { revision++; entries.clear(); } }
        String diagnostics() {
            synchronized (entries) { return "featureSetup={hits=" + hits.sum() + ",builds=" + builds.sum()
                    + ",entries=" + entries.size() + "}"; }
        }
    }
}

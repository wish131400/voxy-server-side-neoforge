package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.feature.*;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RustVegetationDescriptorsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void sharedClassificationKeepsOriginalIndicesAndFailuresLocalToOneJob() {
        var fixture = fixture(4);
        var extra = feature();
        var features = List.of(fixture.features.get(0), fixture.features.get(1), extra, fixture.features.get(3));
        var order = new String[][] { { "test:f0", "test:f1", "test:f2", "test:different_index" } };
        var calls = new AtomicInteger();
        java.util.function.Predicate<String> support = name -> { calls.incrementAndGet(); return name.equals("test:f0"); };
        var cache = new RustVegetationDescriptors.Cache();
        var descriptors = cache.get(features, 0, fixture.registry, order, support);
        assertSame(descriptors, cache.get(features, 0, fixture.registry, order, support));
        assertEquals(2, calls.get(), "unchanged stages must not repeat native support classification");
        var first = new RustVegetationDescriptors.Job(descriptors);
        var second = new RustVegetationDescriptors.Job(descriptors);
        assertTrue(first.supported(0));
        assertEquals("test:f0", first.name(0));
        assertFalse(first.supported(1));
        assertEquals(RustTerrainSampler.JavaFeatureReason.UNSUPPORTED, first.reason(1));
        assertEquals(RustTerrainSampler.JavaFeatureReason.UNREGISTERED, first.reason(2));
        assertEquals(RustTerrainSampler.JavaFeatureReason.ORDER_MISMATCH, first.reason(3));
        first.reject(0, RustTerrainSampler.JavaFeatureReason.TRANSACTION);
        assertFalse(first.supported(0));
        assertEquals(RustTerrainSampler.JavaFeatureReason.TRANSACTION, first.reason(0));
        assertTrue(second.supported(0), "transaction failure must not poison another chunk's metadata");
        assertTrue(new RustVegetationDescriptors.Job(cache.get(features, 0, fixture.registry, order, support)).supported(0));
        assertFalse(new RustVegetationDescriptors.Job(cache.get(features, 1, fixture.registry, order, support)).supported(0),
                "a different global step must be classified separately");
    }

    @Test void changedListsRegistriesAndContextCachesCannotReuseStaleDescriptors() {
        var fixture = fixture(2);
        var cache = new RustVegetationDescriptors.Cache();
        var immutable = fixture.features;
        var first = cache.get(immutable, 0, fixture.registry, fixture.order, name -> true);
        var reordered = List.of(immutable.get(1), immutable.get(0));
        assertFalse(new RustVegetationDescriptors.Job(cache.get(reordered, 0, fixture.registry, fixture.order, name -> true)).supported(0));
        var mutable = new ArrayList<>(immutable);
        cache.get(mutable, 0, fixture.registry, fixture.order, name -> true);
        mutable.set(0, immutable.get(1));
        assertFalse(new RustVegetationDescriptors.Job(cache.get(mutable, 0, fixture.registry, fixture.order, name -> true)).supported(0));
        var otherRegistry = new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE, Lifecycle.stable());
        Registry.register(otherRegistry, ResourceLocation.parse("test:other0"), immutable.get(0));
        Registry.register(otherRegistry, ResourceLocation.parse("test:other1"), immutable.get(1));
        otherRegistry.freeze();
        assertEquals(RustTerrainSampler.JavaFeatureReason.ORDER_MISMATCH,
                new RustVegetationDescriptors.Job(cache.get(immutable, 0, otherRegistry, fixture.order, name -> true)).reason(0));
        var freshContext = new RustVegetationDescriptors.Cache();
        assertFalse(new RustVegetationDescriptors.Job(freshContext.get(immutable, 0, fixture.registry, fixture.order, name -> false)).supported(0));
        cache.clear();
        assertNotSame(first, cache.get(immutable, 0, fixture.registry, fixture.order, name -> true));
        for (int i = 1; i <= 70; i++) cache.get(immutable, i, fixture.registry, fixture.order, name -> true);
        assertTrue(cache.diagnostics().contains("entries=64}"));
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "vss.featureDescriptorBenchmark", matches = "true")
    void pairedProductionDescriptorSetupBenchmarkIncludesJobAllocation() {
        var fixture = fixture(518);
        var supported = Set.of("test:f0", "test:f17", "test:f63", "test:f255", "test:f517");
        var cache = new RustVegetationDescriptors.Cache();
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long[][] times = new long[2][7];
        long[][] bytes = new long[2][7];
        long[] checksums = new long[2];
        for (int round = -3; round < 7; round++) for (int turn = 0; turn < 2; turn++) {
            int mode = Math.floorMod(round + turn, 2);
            long beforeBytes = bean.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            long checksum = 0;
            for (int job = 0; job < 10_000; job++) {
                var descriptors = mode == 0 ? RustVegetationDescriptors.build(fixture.features, 0, fixture.registry,
                        fixture.order, supported::contains) : cache.get(fixture.features, 0, fixture.registry, fixture.order, supported::contains);
                var state = new RustVegetationDescriptors.Job(descriptors);
                int index = job % fixture.features.size();
                checksum += state.name(index).hashCode() + state.reason(index).ordinal() + (state.supported(index) ? 1 : 0);
            }
            long elapsed = System.nanoTime() - started;
            long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
            checksums[mode] = checksum;
            if (round >= 0) { times[mode][round] = elapsed; bytes[mode][round] = allocated; }
        }
        assertEquals(checksums[0], checksums[1]);
        for (var series : times) java.util.Arrays.sort(series);
        for (var series : bytes) java.util.Arrays.sort(series);
        System.out.printf(java.util.Locale.ROOT,
                "FEATURE_DESCRIPTOR_PAIRED features=518 jobs=10000 baselineMs=%.3f cachedMs=%.3f speedup=%.3f baselineAllocated=%d cachedAllocated=%d %s%n",
                times[0][3] / 1e6, times[1][3] / 1e6, (double) times[0][3] / times[1][3],
                bytes[0][3], bytes[1][3], cache.diagnostics());
    }

    @Test void clearingContextDuringBuildPreventsLateMetadataRetention() throws Exception {
        var fixture = fixture(1);
        var cache = new RustVegetationDescriptors.Cache();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> cache.get(fixture.features, 0, fixture.registry, fixture.order, name -> {
                entered.countDown();
                try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return true;
            }));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            cache.clear();
            release.countDown();
            var completed = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(cache.diagnostics().contains("entries=0}"), "an admitted builder must not retain metadata after context disposal");
            assertNotSame(completed, cache.get(fixture.features, 0, fixture.registry, fixture.order, name -> true));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    private record Fixture(List<PlacedFeature> features, Registry<PlacedFeature> registry, String[][] order) { }
    private static Fixture fixture(int size) {
        var registry = new MappedRegistry<PlacedFeature>(Registries.PLACED_FEATURE, Lifecycle.stable());
        var features = new ArrayList<PlacedFeature>();
        var names = new String[size];
        for (int i = 0; i < size; i++) {
            var feature = feature();
            features.add(feature);
            names[i] = "test:f" + i;
            Registry.register(registry, ResourceLocation.parse(names[i]), feature);
        }
        registry.freeze();
        return new Fixture(List.copyOf(features), registry, new String[][] { names });
    }
    private static PlacedFeature feature() {
        var feature = new Feature<NoneFeatureConfiguration>(NoneFeatureConfiguration.CODEC) {
            @Override public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) { return true; }
        };
        return new PlacedFeature(Holder.direct(new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE)), List.of());
    }
}

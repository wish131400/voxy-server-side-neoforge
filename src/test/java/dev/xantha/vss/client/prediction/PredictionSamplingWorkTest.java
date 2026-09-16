package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.MapCodec;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSamplingWorkTest {
    private static final long SEED = 2527382920944240122L;
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), SEED, -64, 384, "noise", "minecraft:overworld", 1L);
    private static HolderLookup.Provider lookup;

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = VanillaRegistries.createLookup();
    }

    @Test void surfaceAndDecorationAvoidUndergroundDensityScans() throws Exception {
        var source = new FixedBiomeSource(lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        var sampler = sampler(source, false);
        var calls = new AtomicInteger();
        // Instrument the expensive density evaluator while keeping actual
        // initial density, biome selection and the production call chain.
        var field = ClientTerrainSampler.class.getDeclaredField("finalDensity");
        field.setAccessible(true);
        field.set(sampler, new DensityFunctions.BeardifierOrMarker() {
            @Override public double compute(DensityFunction.FunctionContext context) {
                calls.incrementAndGet();
                return context.blockY() >= 150 ? 1 : -1;
            }
            @Override public double minValue() { return -1; }
            @Override public double maxValue() { return 1; }
        });
        var full = sampler.sample(-64, -64);
        int fullCalls = calls.getAndSet(0);
        assertTrue(fullCalls > 0, "fixture must exercise the old underground scan");
        for (int step : new int[]{1, 2, 4, 8, 16, 128}) {
            var surface = sampler.sampleForLod(-64, -64, step);
            assertEquals(full.surfaceY(), surface.surfaceY());
            assertEquals(full.topBlockIndex(), surface.topBlockIndex());
            assertEquals(full.fluidY(), surface.fluidY());
            assertEquals(full.fluid(), surface.fluid());
            assertEquals(full.snow(), surface.snow());
            assertEquals(full.ice(), surface.ice());
            assertTrue(surface.surfaceOnly());
            assertFalse(surface.floating());
        }
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -4, -4);
        assertEquals(160, level.column(-64, -64).surfaceY());
        assertEquals(0, calls.get(), "surface tiles and decoration must not scan underground at any LOD");
        System.out.println("Underground density evaluations per fixture column: old=" + fullCalls + ", surface=0");
    }

    @Test void grassFoliageWaterAndDecorationReuseExactBiomeCoordinates() {
        var source = new CountingSource();
        var sampler = sampler(source, false);
        source.calls.set(0);
        int grass = sampler.surfaceColor(-1, 64, -5);
        int leaves = sampler.foliageColor(-1, 64, -5);
        int water = sampler.waterTint(-1, 64, -5);
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -1, -1);
        assertSame(source.warm, level.getNoiseBiome(-1, 16, -2));
        assertEquals(1, source.calls.get(), "tints and decoration at the same quart use one climate lookup");
        assertEquals(source.warm.value().getGrassColor(-1, -5), grass);
        assertEquals(0xFF000000 | source.warm.value().getFoliageColor(), leaves);
        assertEquals(0xB2000000 | source.warm.value().getWaterColor(), water);
        assertSame(source.cold, level.getNoiseBiome(-1, 17, -2));
        assertEquals(2, source.calls.get(), "quart Y must be included in the key");
        // These collide if coordinates are truncated into a packed BlockPos.
        assertSame(source.cold, level.getNoiseBiome(-1, 16 + 4096, -2));
        var other = new PredictionBiomeCache(source, null);
        other.get(-1, 16, -2);
        assertEquals(4, source.calls.get(), "different world contexts cannot share cached biomes");
    }

    @Test void boundedBiomeCacheEvictionAndParallelWorkersKeepExactValues() throws Exception {
        var source = new CountingSource();
        var cache = new PredictionBiomeCache(source, null);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                final int offset = worker * 100_000;
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < 12_000; i++) {
                        int y = i % 2 == 0 ? 16 : 17;
                        assertSame(y == 16 ? source.warm : source.cold, cache.get(offset + i, y, -i));
                    }
                    assertSame(source.warm, cache.get(-1, 16, -2));
                }));
            }
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
    }

    @Test void exactBiomesSurviveWorkerHandoffsAndLocalSlotCollisions() throws Exception {
        var source = new CountingSource();
        var cache = new PredictionBiomeCache(source, null);
        var first = java.util.concurrent.Executors.newSingleThreadExecutor();
        var second = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            first.submit(() -> {
                for (int i = 0; i < 4096; i++) cache.get(i - 2048, 16 + (i & 1), -i);
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            int cold = source.calls.get();
            second.submit(() -> {
                for (int i = 0; i < 4096; i++) assertSame((i & 1) == 0 ? source.warm : source.cold,
                        cache.get(i - 2048, 16 + (i & 1), -i));
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            int repeated = source.calls.get() - cold;
            assertTrue(repeated < 32, "cross-worker reuse should avoid almost all " + cold + " repeated lookups, actual=" + repeated);
            assertSame(source.cold, cache.get(-1, 4112, -2));
            assertSame(source.warm, cache.get(-1, 16, -2));
            var other = new PredictionBiomeCache(source, null);
            int before = source.calls.get(); other.get(-1,16,-2);
            assertEquals(before + 1, source.calls.get(), "profile/seed contexts must remain separate");
            System.out.println("Biome handoff cold=" + cold + ", repeated=" + repeated + ", " + cache.diagnostics());
        } finally { first.shutdownNow(); second.shutdownNow(); }
    }

    @Test void decorationJobRetainsExactBiomesDespiteOtherRegionsEvictingSharedEntries() {
        var source = new CountingSource();
        var sampler = sampler(source, false);
        var level = new PredictionDecorationLevel(sampler, sampler, RegistryAccess.EMPTY, -1, -1);
        for (int i = 0; i < 4096; i++) level.getNoiseBiome(i % 16 - 8, i / 256 + 16, i / 16 % 16 - 8);
        for (int i = 0; i < 200_000; i++) sampler.noiseBiome(i + 100_000, 32, -i);
        int before = source.calls.get();
        for (int i = 0; i < 4096; i++) assertSame(i / 256 + 16 == 16 ? source.warm : source.cold,
                level.getNoiseBiome(i % 16 - 8, i / 256 + 16, i / 16 % 16 - 8));
        assertEquals(before, source.calls.get(), "an active decoration job must not repeat evicted noise queries");
        assertSame(source.cold, level.getNoiseBiome(-1, 4112, -2), "full quart Y remains distinct");
        assertSame(source.warm, level.getNoiseBiome(-1, 16, -2));
        System.out.println("Decoration working set: 4096 repeated queries, additional biome loads=" + (source.calls.get()-before-1));
    }

    @Test void surfaceRulesShareBiomeLookupWithTintsWithoutChangingMaterialOrWeather() {
        var source = new CountingSource();
        var sampler = sampler(source, true);
        var surface = sampler.sampleSurface(0, 0);
        int before = source.calls.get();
        sampler.surfaceColor(0, surface.surfaceY(), 0);
        sampler.foliageColor(0, surface.surfaceY(), 0);
        sampler.waterTint(0, surface.surfaceY(), 0);
        assertEquals(before, source.calls.get(), "surface weather already looked up the tint biome");
        var full = sampler.sample(0, 0);
        assertEquals(full.topBlockIndex(), surface.topBlockIndex());
        assertEquals(full.underBlockIndex(), surface.underBlockIndex());
        assertEquals(full.deepBlockIndex(), surface.deepBlockIndex());
        assertEquals(full.flags(), surface.flags() & ~ClientColumnSample.FLAG_SURFACE_ONLY);
    }

    @Test void surfaceEntryPointPreservesCustomBackendSamples() {
        var expected = new ClientTerrainSampler(SEED, PROFILE).sample(10, 20);
        var custom = new ClientTerrainSampler(SEED, PROFILE) {
            @Override public ClientColumnSample sample(int x, int z) { return expected; }
        };
        assertSame(expected, custom.sampleSurface(10, 20));
        assertSame(expected, custom.sampleForLod(10, 20, 1));
        assertSame(expected, custom.sampleForLod(10, 20, 128));
        var override = ClientTerrainSampler.withSurfaceOverride(sampler(new CountingSource(), false), (x, z) -> 70);
        assertEquals(70, override.sampleSurface(10, 20).surfaceY());
    }

    private static ClientTerrainSampler sampler(BiomeSource source, boolean surfaceRules) {
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var generator = new NoiseBasedChunkGenerator(source, settings);
        var state = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), SEED);
        RegistryAccess access = null;
        if (surfaceRules) {
            var biomes = new net.minecraft.core.MappedRegistry<Biome>(Registries.BIOME, com.mojang.serialization.Lifecycle.stable());
            lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(holder ->
                    biomes.register(holder.key(), holder.value(), net.minecraft.core.RegistrationInfo.BUILT_IN));
            biomes.freeze();
            access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        }
        return new ClientTerrainSampler(SEED, PROFILE, generator, state,
                LevelHeightAccessor.create(-64, 384), 63, List.of(), null, access) {
            @Override public int surfaceY(int x, int z) { return 160; }
        };
    }

    private static final class CountingSource extends BiomeSource {
        final AtomicInteger calls = new AtomicInteger();
        final Holder<Biome> warm = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        final Holder<Biome> cold = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.SNOWY_PLAINS);
        @Override protected MapCodec<? extends BiomeSource> codec() { return MapCodec.unit(this); }
        @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(warm, cold); }
        @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler climate) {
            calls.incrementAndGet();
            return y == 16 ? warm : cold;
        }
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionEndColumnsTest {
    private static HolderLookup.Provider lookup;
    private static RegistryAccess access;
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = VanillaRegistries.createLookup();
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h ->
                biomes.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze()));
    }
    private static ClientTerrainSampler terrain(boolean end) {
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(end ? NoiseGeneratorSettings.END : NoiseGeneratorSettings.OVERWORLD).value();
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace(end ? "the_end" : "overworld"),
                42L, settings.noiseSettings().minY(), settings.noiseSettings().height(),
                "noise", end ? "minecraft:end" : "minecraft:overworld", 1L);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(
                lookup.lookupOrThrow(Registries.BIOME).getOrThrow(end ? Biomes.THE_END : Biomes.PLAINS)), Holder.direct(settings));
        var random = RandomState.create(settings, lookup.lookupOrThrow(Registries.NOISE), 42L);
        return new ClientTerrainSampler(42, profile, generator, random,
                LevelHeightAccessor.create(profile.minY(), profile.height()), settings.seaLevel(), null, access);
    }
    private static ClientColumnSample heightfield(int top) {
        int endStone = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.END_STONE);
        return new ClientColumnSample(top, top, 0, endStone, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, endStone, endStone,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    private static ClientColumnSample[] grid(ClientColumnSample sample) {
        var result = new ClientColumnSample[9]; Arrays.fill(result, sample); return result;
    }
    private static void assertPublished(ClientColumnSample sample, PredictionColumnVolume expected) {
        var runs = new ArrayList<PredictionVoxySource.Run>();
        PredictionVoxySource.terrain(runs, sample, 1, 0);
        for (int y = 0; y < 128; y++) {
            final int yy = y;
            var actual = runs.stream().filter(r -> r.bottom() <= yy && yy < r.top()).reduce((a,b) -> b);
            assertEquals(expected.occupied(y, false), actual.isPresent(), "published occupancy at y=" + y);
            if (actual.isPresent()) assertTrue(actual.get().state().is(Blocks.END_STONE), "End material at " + y);
        }
    }
    @Test void flatIslandUndersidesAndStackedAirSurviveEveryPredictionSpacing() {
        var calls = new AtomicInteger();
        int stone = heightfield(72).topBlockIndex();
        var volume = PredictionColumnVolume.ofRuns(new int[]{24,32,stone,0,48,72,stone,0});
        var sampler = new ClientTerrainSampler(terrain(true), null) {
            @Override ClientColumnSample sampleInterior(int x, int z) { calls.incrementAndGet(); return volume.asSample(); }
        };
        for (int step : new int[]{1,2,4,8,16,32,64,1024}) {
            var samples = grid(heightfield(72));
            assertEquals(9, PredictionExteriorColumns.enrich(samples,3,step,-64,-64,sampler,()->true));
            for (var sample : samples) assertPublished(sample,volume);
            int before = calls.get();
            assertEquals(0, PredictionExteriorColumns.enrich(samples,3,step,-64,-64,sampler,()->true));
            assertEquals(before,calls.get(),"Complete columns must be reused");
        }
        assertEquals(72,calls.get(),"One column per anchor; no step squared footprint scan");
    }
    @Test void vanillaMainIslandOuterIslandsAndVoidMatchFullGeneratorColumns() {
        var sampler = terrain(true);
        boolean island = false, voidColumn = false;
        for (int[] p : List.of(new int[]{0,0},new int[]{90,-15},new int[]{-65,31},
                new int[]{512,512},new int[]{1605,-1456},new int[]{-4588,-1531})) {
            var expected = sampler.exteriorColumn(p[0],p[1]);
            island |= expected.size()>0; voidColumn |= expected.size()==0;
            for (int step : new int[]{1,4,8,16,32}) {
                var samples = new ClientColumnSample[9];
                samples[4] = sampler.sampleForLod(p[0],p[1],step);
                PredictionExteriorColumns.enrich(samples,3,step,p[0],p[1],sampler,()->true);
                assertNotNull(samples[4].volume(), Arrays.toString(p)+" spacing "+step);
                assertPublished(samples[4],expected);
            }
        }
        assertTrue(island); assertTrue(voidColumn);
    }
    @Test void nativeEndKeepsRustComputationAndMatchesJavaOccupancy() throws Exception {
        assertTrue(RustTerrainSampler.available(),"Run with the bundled native library");
        var context = terrain(true);
        var doc = JsonParser.parseString(Files.readString(Path.of(
                "tools/rust/vss-native-core/tests/fixtures/worldgen/end.json"))).getAsJsonObject();
        var shared = LithostitchedNativeTest.document();
        for (String name : List.of("block_definitions","biomes","grass_colormap","foliage_colormap"))
            doc.add(name,shared.get(name));
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        try (var rust = new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),context.profile(),context)) {
            for (int[] p : List.of(new int[]{0,0},new int[]{90,-15},new int[]{-65,31},new int[]{512,512},new int[]{1605,-1456})) {
                var samples = new ClientColumnSample[9]; samples[4] = rust.sampleForLod(p[0],p[1],32);
                PredictionExteriorColumns.enrich(samples,3,32,p[0],p[1],rust,()->true);
                assertNotNull(samples[4].volume());
                assertPublished(samples[4],context.exteriorColumn(p[0],p[1]));
            }
        }
    }
    @Test void oldPartialExteriorEvidenceIsReplacedAndRealCapturesArePreserved() {
        var sampler = terrain(true);
        var raw = sampler.sampleSurface(0,0);
        var legacy = PredictionExteriorColumns.withProfile(raw,null,0,1);
        var samples = grid(legacy);
        PredictionExteriorColumns.enrich(samples,3,1,0,0,sampler,()->true);
        assertNotNull(samples[4].volume()); assertPublished(samples[4],sampler.exteriorColumn(0,0));
        var captured = new ClientColumnSample(raw.surfaceY(),raw.fluidY(),0,raw.topBlockIndex(),0,0,0,0,0,
                ClientColumnSample.FLAG_CAPTURED,0,raw.underBlockIndex(),raw.deepBlockIndex(),
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        samples=grid(captured);
        assertEquals(0,PredictionExteriorColumns.enrich(samples,3,32,0,0,sampler,()->true));
        assertSame(captured,samples[4]);
    }
    @Test void opaqueHeightProvidersAndOverworldHeightfieldsKeepTheirOwnContract() {
        var end = ClientTerrainSampler.withSurfaceOverride(terrain(true),(x,z)->72);
        var samples = grid(heightfield(72));
        assertEquals(0,PredictionExteriorColumns.enrich(samples,3,32,0,0,end,()->true));
        assertNull(samples[4].volume());
        var overworld = terrain(false); samples=grid(heightfield(72));
        assertEquals(0,PredictionExteriorColumns.enrich(samples,3,32,0,0,overworld,()->true));
        var runs=new ArrayList<PredictionVoxySource.Run>();PredictionVoxySource.terrain(runs,samples[4],32,-64);
        assertTrue(runs.stream().anyMatch(r->r.bottom()==-64),"Main-world heightfields stay closed");
    }
    @Test void dimensionSwitchCancellationStopsBeforeTheNextColumn() {
        var sampler=terrain(true);var samples=grid(heightfield(72));var checks=new AtomicInteger();
        assertThrows(java.util.concurrent.CancellationException.class,()->
                PredictionExteriorColumns.enrich(samples,3,32,0,0,sampler,()->checks.incrementAndGet()<2));
        assertEquals(2,checks.get());
    }
    @AfterEach void awaitCacheClose() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }
    @Test void aNewGridSamplesFullColumnsDirectlyWithoutRepeatingTheHeightfieldPass() throws Exception {
        var fullCalls=new AtomicInteger();
        var sampler=new ClientTerrainSampler(terrain(true),null) {
            @Override public ClientColumnSample sampleForLod(int x,int z,int step) { fail("No duplicate surface pass");return null; }
            @Override ClientColumnSample sampleInterior(int x,int z) { fullCalls.incrementAndGet();return super.sampleInterior(x,z); }
        };
        try(var manager=new PredictionTileManager(Level.END,sampler,
                new PredictionMemoryBudget(256L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null)) {
            var method=PredictionTileManager.class.getDeclaredMethod("sampleGridFast",int.class,int.class,int.class,
                    int.class,ClientColumnSample[].class,boolean.class,boolean.class,java.util.function.BooleanSupplier.class);
            method.setAccessible(true);
            var samples=new ClientColumnSample[9];
            method.invoke(manager,0,0,32,3,samples,true,false,(java.util.function.BooleanSupplier)()->true);
            assertEquals(9,fullCalls.get());for(var sample:samples)assertNotNull(sample.volume());
            assertEquals(0,PredictionExteriorColumns.enrich(samples,3,32,0,0,sampler,()->true));
            assertEquals(9,fullCalls.get());
        }
    }
    @Test void rememberedHeightfieldsGainUndersidesAndSurviveASecondOpen(@TempDir Path directory) throws Exception {
        var sampler=terrain(true);var key=PredictionDiskCache.Key.terrain(0,0,0);
        try(var disk=new PredictionDiskCache(directory,77);var lease=disk.lease(key)) {
            assertTrue(disk.writeTerrain(lease,grid(sampler.sampleSurface(0,0))));disk.flush();
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
        try(var disk=new PredictionDiskCache(directory,77);var lease=disk.lease(key)) {
            var samples=disk.readTerrain(lease,9);assertNotNull(samples);assertNull(samples[4].volume());
            assertEquals(9,PredictionExteriorColumns.enrich(samples,3,32,0,0,sampler,()->true));
            assertTrue(disk.writeTerrain(lease,samples));disk.flush();
        }
        PredictionCacheTestFiles.awaitBackgroundClose();
        try(var disk=new PredictionDiskCache(directory,77);var lease=disk.lease(key)) {
            var samples=disk.readTerrain(lease,9);assertNotNull(samples[4].volume());
            assertEquals(0,PredictionExteriorColumns.enrich(samples,3,32,0,0,sampler,()->true));
            assertPublished(samples[4],sampler.exteriorColumn(0,0));
        }
    }
    @Test void reducedParentLodsKeepTheIslandUndersideAndInternalVoid() {
        var key=new PredictionTileManager.PredictionTileKey(Level.END,0,0,1);
        var children=PredictionParentLodTest.children(key);
        int stone=heightfield(72).topBlockIndex();
        var volume=PredictionColumnVolume.ofRuns(new int[]{24,32,stone,0,48,72,stone,0});
        for(var child:children.values()) { Arrays.fill(child.samples(),volume.asSample());Arrays.fill(child.heights(),72); }
        var input=PredictionParentLod.capture(key,8,PredictionParentLodTest.LAYOUT,children,Set.of(),Map.of());
        assertNotNull(input);var parent=PredictionParentLod.reduce(input);assertNotNull(parent);
        for(var sample:parent.samples())assertPublished(sample,volume);
    }
    private static byte[] legacyVoxelIdentity(ClientTerrainSampler sampler) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");var p=sampler.profile();
        hash.update((p.seed()+":"+p.dimension()+":"+p.minY()+":"+p.height()+":"+p.generatorType()+":"
                +p.generatorSettings()+":"+p.fingerprint()).getBytes(StandardCharsets.UTF_8));
        hash.update(p.generatorData());
        var method=PredictionVoxelCache.class.getDeclaredMethod("tagIdentity");method.setAccessible(true);
        hash.update((byte[])method.invoke(null));return hash.digest();
    }
    @Test void finishedEndVoxelCachesInvalidateWithoutChangingOtherDimensionsOrCachePaths(@TempDir Path directory) throws Exception {
        var end=terrain(true);var overworld=terrain(false);
        assertFalse(Arrays.equals(legacyVoxelIdentity(end),PredictionVoxelCache.identity(end)),"Reject old filled End geometry");
        assertArrayEquals(legacyVoxelIdentity(overworld),PredictionVoxelCache.identity(overworld));
        var storage=new PredictionCacheStorage(directory,null);
        assertTrue(storage.directory(end).endsWith(Path.of("minecraft/the_end/seed-2a")));
    }
}

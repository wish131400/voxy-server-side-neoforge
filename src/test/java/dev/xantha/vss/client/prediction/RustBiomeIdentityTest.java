package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RustBiomeIdentityTest {
    static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.tryParse("minecraft:overworld"), -64, 384, "noise", "minecraft:overworld", 1L);
    @TempDir Path directory;
    @BeforeAll static void bootstrap() {
        TerrainPredictionFixtures.bootstrap();
        String library=System.getProperty("vss.testNativeLibrary");
        if (library!=null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }
    static ByteBuffer direct(int bytes) { return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    static JsonObject document() throws Exception {
        var doc=LithostitchedNativeTest.document();
        doc.add("possible_biomes",new JsonArray());
        return doc;
    }
    static JsonObject fixedDocument() throws Exception {
        var doc=document();
        doc.add("biome_source",JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:swamp\"}"));
        return doc;
    }
    static final class Context extends ClientTerrainSampler {
        int queries;
        final boolean allowFallback;
        Context(boolean allowFallback) { super(1L,PROFILE); this.allowFallback=allowFallback; }
        @Override List<String> cacheBiomes() { return List.of("minecraft:swamp","minecraft:plains"); }
        @Override Holder<Biome> noiseBiome(int x,int y,int z) {
            queries++;
            assertTrue(allowFallback,"native identity must not repeat climate selection in Java");
            return TerrainPredictionFixtures.access().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.SWAMP);
        }
    }
    static ClientColumnSample[] samples(int surface,int water) {
        int none=ClientColumnSample.NO_SPAN;
        var sample=new ClientColumnSample(surface,water,-1,
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(net.minecraft.world.level.block.Blocks.GRASS_BLOCK),
                0,0,0,0,water>surface?1:0,ClientColumnSample.FLAG_SURFACE_ONLY,0,1,1,none,none,none,none);
        var samples=new ClientColumnSample[16]; Arrays.fill(samples,sample); return samples;
    }

    @Test void nativeIdentityIsRemappedPersistedAndHandedToVoxyWithoutJavaClimateQueries() throws Exception {
        long world=RustWorldgenBackend.create(1,13,fixedDocument().toString());
        var context=new Context(false);
        try (var sampler=new RustTerrainSampler(world,PROFILE,context)) {
            var samples=samples(3,17);
            assertEquals(16,sampler.resolveBiomeIndices(samples,4,-8,-8,1));
            assertTrue(Arrays.stream(samples).allMatch(s->s.biomeIndex()==0));
            assertTrue(sampler.diagnostics().contains("biomeIdentity={nativeBatches=1,nativePoints=4,javaFallbacks=0}"));
            String before=sampler.diagnostics();
            var key=PredictionDiskCache.Key.terrain(0,0,0);
            try (var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
                assertTrue(cache.writeTerrain(lease,samples)); cache.flush();
            }
            try (var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
                var restored=cache.readTerrain(lease,16);
                assertNotNull(restored);
                assertEquals(0,sampler.resolveBiomeIndices(restored,4,-8,-8,1));
                var tile=PredictionVoxySource.build(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0),
                        restored,4,1,sampler,PredictionVegetation.Tile.EMPTY,null);
                assertTrue(Arrays.stream(tile.columns()).allMatch(c->c.biome().equals("minecraft:swamp")));
            }
            assertEquals(before,sampler.diagnostics());
            assertEquals(0,context.queries);
        }
    }

    @Test void batchesLargerThanTheJniLimitKeepKnownEntriesAndResolveAllUnknowns() throws Exception {
        long world=RustWorldgenBackend.create(1,13,fixedDocument().toString());
        try (var sampler=new RustTerrainSampler(world,PROFILE,new Context(false))) {
            var samples=new ClientColumnSample[400]; Arrays.fill(samples,samples(8,8)[0]);
            samples[3]=samples[3].withBiomeIndex(1);
            assertEquals(399,sampler.resolveBiomeIndices(samples,20,-160,-80,4));
            assertEquals(1,samples[3].biomeIndex());
            for (int i=0;i<samples.length;i++) if (i!=3) assertEquals(0,samples[i].biomeIndex());
            assertTrue(sampler.diagnostics().contains("nativeBatches=2,nativePoints=399,javaFallbacks=0"));
        }
    }

    @Test void exactPreviewDisplayAndDecorationRecordsCarryTheirIdentityImmediately() throws Exception {
        var doc=fixedDocument();
        doc.getAsJsonObject("biome_source").addProperty("biome","minecraft:plains");
        var settings=doc.getAsJsonObject("settings");
        settings.addProperty("aquifers_enabled",false);
        settings.add("surface_rule",JsonParser.parseString("{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:stone\"}}"));
        var flat=JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":64,\"to_y\":80,\"from_value\":1,\"to_value\":-1}");
        settings.getAsJsonObject("noise_router").add("final_density",flat);
        settings.getAsJsonObject("noise_router").add("initial_density_without_jaggedness",flat.deepCopy());
        long world=RustWorldgenBackend.create(1,13,doc.toString());
        var context=new Context(false);
        try (var sampler=new RustTerrainSampler(world,PROFILE,context)) {
            var point=sampler.sample(-16,-16);
            assertEquals(1,point.biomeIndex());
            String before=sampler.diagnostics();
            assertEquals(point,sampler.sample(-16,-16));
            assertEquals(before,sampler.diagnostics(),"point cache must retain identity too");
            for (var sample:sampler.sampleGrid(32,32,4,4)) assertEquals(1,sample.biomeIndex());
            for (var sample:sampler.sampleGrid(64,64,4,4,4,new ClientColumnSample[16],true)) assertEquals(1,sample.biomeIndex());
            var display=sampler.sampleDisplayGrid(-16,-16,1,8,8,new ClientColumnSample[64]);
            assertEquals(point,display[0]);
            for (var sample:display) assertEquals(1,sample.biomeIndex());
            assertTrue(sampler.diagnostics().contains("\"adaptiveGrids\":1"));
            for (var row:sampler.decorationDisplayPage(0,0)) assertEquals(1,sampler.surfaceSample(row).biomeIndex());
            assertEquals(0,context.queries);
        }
    }

    @Test void rawNoiseIdentityMatchesMinecraftAtNegativeCoordinatesAndMultipleHeights() throws Exception {
        var doc=document();
        var access=TerrainPredictionFixtures.access();
        var source=BiomeSource.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE,access),doc.get("biome_source")).result().orElseThrow();
        for (long seed:new long[]{0,42,-918273645}) {
            var state=RandomState.create(access.registryOrThrow(Registries.NOISE_SETTINGS)
                    .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD).value(),access.lookupOrThrow(Registries.NOISE),seed);
            long world=RustWorldgenBackend.create(seed,BiomeManager.obfuscateSeed(seed),doc.toString());
            try {
                var names=JsonParser.parseString(RustWorldgenBackend.describe(world)).getAsJsonObject().getAsJsonArray("biome_names");
                assertEquals(names,JsonParser.parseString(RustWorldgenBackend.describeCompact(world)).getAsJsonObject().get("biome_names"));
                for (int pass=0;pass<2;pass++) {
                    var input=direct(256*12); var output=direct(256*4);
                    for (int i=0;i<256;i++) {
                        int n=pass==0?i:255-i;
                        input.putInt(n*137-20000).putInt((n%19)*13-80).putInt(16000-n*193);
                    }
                    assertEquals(256,RustWorldgenBackend.biomeIndices(world,input,output,256));
                    for (int i=0;i<256;i++) {
                        int n=pass==0?i:255-i;
                        String expected=source.getNoiseBiome(n*137-20000,(n%19)*13-80,16000-n*193,state.sampler())
                                .unwrapKey().orElseThrow().location().toString();
                        assertEquals(expected,names.get(output.getInt(i*4)).getAsString(),"seed="+seed+", point="+n);
                    }
                }
            } finally { RustWorldgenBackend.close(world); }
        }
    }

    @Test void dryAndWetPublicationUseRawExposedQuartHeightEvenWhenBiomeColorsMatch() throws Exception {
        var doc=document();
        var router=doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
        for (String node:List.of("temperature","vegetation","continents","erosion","ridges")) router.addProperty(node,0);
        router.add("depth",JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":0,\"to_y\":12,\"from_value\":-1,\"to_value\":1}"));
        var entries=new JsonArray();
        for (String name:List.of("minecraft:swamp","minecraft:plains")) {
            var row=new JsonObject(); var parameters=new JsonObject();
            for (String axis:List.of("temperature","humidity","continentalness","erosion","weirdness","offset")) parameters.addProperty(axis,0);
            parameters.addProperty("depth",entries.isEmpty()?-1:1);
            row.add("parameters",parameters);row.addProperty("biome",name);entries.add(row);
        }
        var source=new JsonObject();source.addProperty("type","minecraft:multi_noise");source.add("biomes",entries);doc.add("biome_source",source);
        var biomes=doc.getAsJsonObject("biomes");
        biomes.add("minecraft:plains",biomes.get("minecraft:swamp").deepCopy());
        long world=RustWorldgenBackend.create(1,918273645,doc.toString());
        try (var sampler=new RustTerrainSampler(world,PROFILE,new Context(false))) {
            for (int[] c:new int[][]{{8,8,0},{9,9,1},{3,13,1}}) {
                var samples=samples(c[0],c[1]);
                assertEquals(16,sampler.resolveBiomeIndices(samples,4,-7,-11,3));
                for (var sample:samples) assertEquals(c[2],sample.biomeIndex());
            }
        }
    }

    @Test void invalidAndCancelledBatchesPreserveOutputAndOldSurfaceLayout() throws Exception {
        long world=RustWorldgenBackend.create(1,13,fixedDocument().toString());
        try {
            var input=direct(24); var output=direct(8);
            output.putLong(0,0x1122334455667788L);
            input.putInt(12,Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndices(world,input,output,2));
            assertEquals(0x1122334455667788L,output.getLong(0));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndices(world,input,output,257));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndices(world,ByteBuffer.allocate(12),output,1));
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndices(world,input,output.asReadOnlyBuffer(),1));
            assertEquals(1,RustWorldgenBackend.biomeIndices(world,input,output,1));
            var terrain=direct(40); var xz=direct(8);
            assertEquals(1,RustWorldgenBackend.previewPoints(world,xz,terrain,1));
            RustWorldgenBackend.cancel(world);
            assertThrows(IllegalArgumentException.class,()->RustWorldgenBackend.biomeIndices(world,input,output,1));
        } finally { RustWorldgenBackend.close(world); }
    }
}

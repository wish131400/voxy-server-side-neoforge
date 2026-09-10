package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaPreviewSamplingTest {
    private static net.minecraft.core.HolderLookup.Provider lookup;
    private static RegistryAccess access;
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var key=net.minecraft.core.registries.Registries.BIOME;
        var biomes=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(key,com.mojang.serialization.Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(h->biomes.register(h.key(),h.value(),net.minecraft.core.RegistrationInfo.BUILT_IN));
        access=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes.freeze()));
    }

    private static ClientTerrainSampler sampler(JsonObject doc, long seed) {
        var ops=lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var settings=net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DIRECT_CODEC.parse(ops,doc.get("settings")).getOrThrow();
        var biome=net.minecraft.world.level.biome.BiomeSource.CODEC.parse(ops,doc.get("biome_source")).getOrThrow();
        var noise = doc.getAsJsonObject("settings").getAsJsonObject("noise");
        var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),seed,
                noise.get("min_y").getAsInt(),noise.get("height").getAsInt(),"noise","minecraft:overworld",0L);
        var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biome,net.minecraft.core.Holder.direct(settings));
        var random=net.minecraft.world.level.levelgen.RandomState.create(settings,lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),seed);
        return new ClientTerrainSampler(seed,profile,generator,random,
                LevelHeightAccessor.create(profile.minY(),profile.height()),settings.seaLevel(),List.of(),null,access);
    }

    @Test void previewUsesRealMaterialRulesButDoesNotPopulateExactColumnCache(@TempDir java.nio.file.Path directory) throws Exception {
        var doc = LithostitchedNativeTest.document();
        doc.getAsJsonObject("settings").add("surface_rule",JsonParser.parseString(
                "{\"type\":\"minecraft:block\",\"result_state\":{\"Name\":\"minecraft:orange_terracotta\"}}"));
        var source = sampler(doc,42);
        var column = new MinecraftColumnTerrainSampler(source);
        var cacheField = MinecraftColumnTerrainSampler.class.getDeclaredField("columns");
        cacheField.setAccessible(true);
        var preview = column.samplePreview(-17,31);
        assertTrue(preview.approximate()); assertTrue(preview.surfaceOnly());
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.ORANGE_TERRACOTTA),preview.topBlockIndex());
        column.surfaceColorForLod(-17,preview.surfaceY(),31,true);
        assertTrue(((Map<?,?>)cacheField.get(column)).isEmpty(),"preview materials/tints must not fetch exact columns");
        var key = PredictionDiskCache.Key.terrain(0,0,0);
        var grid = new ClientColumnSample[16]; Arrays.fill(grid,preview);
        try(var disk = new PredictionDiskCache(directory,77);var lease = disk.lease(key)) {
            assertTrue(disk.writeTerrain(lease,grid));
        }
        try(var disk = new PredictionDiskCache(directory,77);var lease = disk.lease(key)) {
            assertArrayEquals(grid,disk.readTerrain(lease,16));
        }
        var tileSamples = new ClientColumnSample[9]; Arrays.fill(tileSamples,preview);
        var tile = new PredictionTileManager.PredictionTile(null,new int[0],new int[0],tileSamples,null,
                new PredictionDepthBound(0,320),0,1,2,32);
        assertNull(PredictionTileManager.retainedSample(tile,1,1,16,false));
        assertSame(preview,PredictionTileManager.retainedSample(tile,1,1,16,true));
        var exact = column.sampleSurface(-17,31);
        assertFalse(exact.approximate());
        assertEquals(new MinecraftColumnTerrainSampler(sampler(doc,42)).sampleSurface(-17,31),exact);
        assertFalse(((Map<?,?>)cacheField.get(column)).isEmpty());
    }

    @Test void rawPreviewIsBoundedAndAccurateSearchHandlesNegativeHeights() throws Exception {
        var doc = LithostitchedNativeTest.document();
        var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
        var gradient = JsonParser.parseString("{\"type\":\"minecraft:y_clamped_gradient\",\"from_y\":-64,\"to_y\":0,\"from_value\":1,\"to_value\":-1}");
        router.add("final_density",gradient);router.add("initial_density_without_jaggedness",gradient);
        var sample = sampler(doc,0);
        assertEquals(-32,sample.surfaceY(0,0));
        assertTrue(Math.abs(sample.samplePreview(0,0).surfaceY()+32)<=4);
        router.addProperty("final_density",-1);
        var empty = sampler(doc,0).samplePreview(0,0);
        assertFalse(empty.hasSurface());assertFalse(empty.hasFluid());
        Thread.currentThread().interrupt();
        try { assertThrows(java.util.concurrent.CancellationException.class,()->sample.samplePreview(16,16)); }
        finally { Thread.interrupted(); }
    }

    @Test void exteriorIteratorMatchesFullVanillaColumnsAcrossDimensions() throws Exception {
        for(String dimension:List.of("overworld","nether","end")) {
            var doc = LithostitchedNativeTest.document();
            if(!dimension.equals("overworld")) {
                var other = JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                        "tools/rust/vss-native-core/tests/fixtures/worldgen/"+dimension+".json"))).getAsJsonObject();
                for(String k:List.of("settings","density_functions","noises","biome_source")) if(other.has(k)) doc.add(k,other.get(k));
            }
            var source = sampler(doc,-917);
            var column = new MinecraftColumnTerrainSampler(source);
            var heights = LevelHeightAccessor.create(source.profile().minY(),source.profile().height());
            for(int[] p:List.of(new int[]{0,0},new int[]{-17,31},new int[]{8191,-4097},new int[]{-32768,16384})) {
                var full = source.generatorContext().getBaseColumn(p[0],p[1],heights,source.randomStateContext());
                int floor=heights.getMinBuildHeight(),fluidY=floor,fluid=0;
                for(int y=heights.getMaxBuildHeight()-1;y>=heights.getMinBuildHeight();y--) {
                    var b=full.getBlock(y);if(b.isAir())continue;
                    if(b.getFluidState().isEmpty()){floor=y+1;break;}
                    if(fluid==0){fluid=b.getFluidState().is(Fluids.LAVA)?2:1;fluidY=y+1;}
                }
                var actual=column.sampleSurface(p[0],p[1]);
                assertEquals(floor,actual.surfaceY(),dimension+Arrays.toString(p));
                assertEquals(Math.max(floor,fluidY),actual.fluidY());assertEquals(fluid,actual.fluid());
                assertFalse(actual.approximate());
            }
        }
    }

    @Test void approximateBiomeAndSurfaceContextsCannotLeakIntoExactColors() throws Exception {
        var source = sampler(LithostitchedNativeTest.document(),0);
        var plains = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var snowy = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS);
        var biomes = new net.minecraft.world.level.biome.BiomeSource() {
            @Override protected com.mojang.serialization.MapCodec<? extends net.minecraft.world.level.biome.BiomeSource> codec() { throw new UnsupportedOperationException(); }
            @Override protected java.util.stream.Stream<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> collectPossibleBiomes() { return java.util.stream.Stream.of(plains,snowy); }
            @Override public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x,int y,int z,net.minecraft.world.level.biome.Climate.Sampler climate) {
                return FreeTerraForgedDensity.coarse() ? snowy : plains;
            }
        };
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomes,source.generatorContext().generatorSettings());
        var terrain = new ClientTerrainSampler(0,source.profile(),generator,source.randomStateContext(),
                LevelHeightAccessor.create(-64,384),63,List.of(),null,access);
        var preview = FreeTerraForgedDensity.coarse(true,()->terrain.samplePreview(0,0));
        assertTrue(preview.approximate());
        assertEquals(snowy.value().getGrassColor(0,0),FreeTerraForgedDensity.coarse(true,
                ()->terrain.surfaceColorForLod(0,preview.surfaceY(),0,true)));
        assertEquals(plains.value().getGrassColor(0,0),terrain.surfaceColorForLod(0,preview.surfaceY(),0,false));
        assertFalse(terrain.sampleSurface(0,0).snow(),"preview rule biome cache must remain separate too");
        assertFalse(FreeTerraForgedDensity.coarse());
    }

    @Test void compareColdJavaSamplingStages() throws Exception {
        var doc=LithostitchedNativeTest.document();
        var source=sampler(doc,42);
        var heights=LevelHeightAccessor.create(-64,384);
        // Three measured batches after one warmup. Each sampler has cold VSS
        // caches; JVM/world graph remain warm. No FPS or strict timing assertion.
        for(int round=0;round<4;round++) {
            var exact=new MinecraftColumnTerrainSampler(source);
            var preview=new MinecraftColumnTerrainSampler(source);
            long start=System.nanoTime();
            int checksum=0;
            for(int i=0;i<32;i++) {
                var column=source.generatorContext().getBaseColumn(-40+i%8*64,56+i/8*64,heights,source.randomStateContext());
                for(int y=319;y>=-64;y--)if(!column.getBlock(y).isAir() && column.getBlock(y).getFluidState().isEmpty()){checksum+=y+1;break;}
            }
            long full=System.nanoTime()-start;
            start=System.nanoTime();int actual=0;
            for(int i=0;i<32;i++)actual+=exact.surfaceY(-40+i%8*64,56+i/8*64);
            long exterior=System.nanoTime()-start;
            assertEquals(checksum,actual);
            start=System.nanoTime();
            for(int i=0;i<32;i++)assertTrue(preview.samplePreview(-40+i%8*64,56+i/8*64).approximate());
            long rough=System.nanoTime()-start;
            if(round>0)System.out.printf(java.util.Locale.ROOT,"JAVA_STAGE_BENCH points=32 fullColumnMs=%.3f exteriorHeightMs=%.3f previewWithMaterialsMs=%.3f%n",full/1e6,exterior/1e6,rough/1e6);
        }
    }
}

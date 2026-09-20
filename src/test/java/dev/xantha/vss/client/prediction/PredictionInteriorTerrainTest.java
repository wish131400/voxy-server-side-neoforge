package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionInteriorTerrainTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static int rock() { return BuiltInRegistries.BLOCK.getId(Blocks.NETHERRACK); }
    private static int lava() { return BuiltInRegistries.BLOCK.getId(Blocks.LAVA); }
    private static PredictionColumnVolume cavern() {
        return new PredictionColumnVolume(new int[]{0, 24, rock(), 0, 24, 32, lava(), 2,
                64, 72, rock(), 0, 110, 128, rock(), 0});
    }

    @Test void preservesMultipleCavesAndLavaInsteadOfOnlyTheBedrockRoof() {
        var volume = PredictionColumnVolume.sample(0,128,
                y -> y < 24 || y >= 64 && y < 72 || y >= 110 ? rock() : y < 32 ? lava() : -1,
                id -> id == lava() ? 2 : 0);
        assertEquals(cavern(), volume);
        var samples = new ClientColumnSample[9];
        Arrays.fill(samples, volume.asSample());
        var mesh = PredictionMeshBuilder.build(samples, null, 32, 0xffd9572b, 8, 3, false);
        for (int height : new int[]{24,72,128}) assertTrue(hasFace(mesh, height, 1), "floor " + height);
        for (int height : new int[]{0,64,110}) assertTrue(hasFace(mesh, height, -1), "ceiling " + height);
        for (int v = 0; v < mesh.vertexCount(); v++) assertEquals(0, mesh.normalX(v));
        assertTrue(mesh.waterVertexCount() > 0);
        for (int v = 0; v < mesh.waterVertexCount(); v++) {
            assertEquals(32 - PredictionMeshBuilder.FLUID_SURFACE_DROP, mesh.waterY(v), .0001);
            assertEquals(2 / 3f, mesh.waterNormalY(v), .0001, "lava kind survives packing");
        }
        var bounds = PredictionDepthBound.fromSamples(samples);
        assertTrue(bounds.contains(81), "player inside cave must not be culled as below terrain");
        assertEquals(0,bounds.minY()); assertEquals(128,bounds.maxY());
        assertTrue(mesh.packed().quadCount() >= 6, "all levels survive quad packing");
    }

    @Test void sideWallsOnlyCoverActualSolidRunsAndDoNotSealAir() {
        var samples = new ClientColumnSample[9];
        Arrays.fill(samples, new PredictionColumnVolume(new int[0]).asSample());
        samples[0] = cavern().asSample();
        var mesh = PredictionMeshBuilder.build(samples,null,32,0xffd9572b,4,3,false);
        boolean wall = false;
        for (int v = 0; v < mesh.vertexCount(); v += 6) {
            if (mesh.normalY(v) != 0) continue;
            wall = true;
            float lo=Float.MAX_VALUE,hi=-Float.MAX_VALUE;
            for (int k=0;k<6;k++) {lo=Math.min(lo,mesh.y(v+k));hi=Math.max(hi,mesh.y(v+k));}
            assertTrue(hi<=24 || lo>=64 && hi<=72 || lo>=110, "wall crossed cave: "+lo+".."+hi);
        }
        assertTrue(wall);
    }

    @Test void verticalRunsSurviveDiskReopenAndInterning(@TempDir Path directory) throws Exception {
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        var samples = new ClientColumnSample[]{cavern().asSample(),cavern().asSample()};
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease,samples));
        }
        try(var cache=new PredictionDiskCache(directory,77);var lease=cache.lease(key)) {
            var read=cache.readTerrain(lease,2);
            assertArrayEquals(samples,read);
            assertEquals(1,PredictionSampleCompaction.compact(read));
            assertEquals(cavern().bytes(),PredictionSampleCompaction.volumeBytes(read));
        }
    }

    @Test void nativeNetherColumnsContainCavesWith256BlockDimensionAnd128BlockNoise() throws Exception {
        assertTrue(RustTerrainSampler.available());
        Path root=Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc=JsonParser.parseString(Files.readString(root.resolve("nether.json"))).getAsJsonObject();
        for(var e:Map.of("block_definitions","blocks","biomes","biomes","grass_colormap","grass","foliage_colormap","foliage").entrySet())
            doc.add(e.getKey(),JsonParser.parseString(Files.readString(root.resolve(e.getValue()+".json"))));
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("the_nether"),0,256,"noise","minecraft:nether",1);
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var settings = lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS)
                .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER).value();
        var source = new net.minecraft.world.level.biome.FixedBiomeSource(lookup.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(net.minecraft.world.level.biome.Biomes.NETHER_WASTES));
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source, net.minecraft.core.Holder.direct(settings));
        var random = net.minecraft.world.level.levelgen.RandomState.create(settings,
                lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),42);
        var javaSampler = new ClientTerrainSampler(42,profile,generator,random,
                net.minecraft.world.level.LevelHeightAccessor.create(0,128),32,java.util.List.of());
        try(var sampler=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),profile,new ClientTerrainSampler(42,profile))) {
            long exteriorStart=System.nanoTime();
            for(int z=-4;z<4;z++)for(int x=-4;x<4;x++) sampler.sample(x*16,z*16);
            System.out.println("NETHER_EXTERIOR columns=64 elapsedMs="+(System.nanoTime()-exteriorStart)/1e6);
            int cavities=0, liquids=0; long bytes=0;
            long start=System.nanoTime();
            for(int z=-8;z<8;z++)for(int x=-8;x<8;x++) {
                var volume=sampler.sampleInterior(x*16,z*16).volume();
                assertNotNull(volume);assertTrue(volume.maxY()<=128);
                for(int i=1;i<volume.size();i++)if(volume.bottom(i)>volume.top(i-1))cavities++;
                for(int i=0;i<volume.size();i++)if(volume.fluid(i)==2)liquids++;
                bytes+=volume.bytes();
            }
            System.out.println("NETHER_INTERIOR columns=256 elapsedMs="+(System.nanoTime()-start)/1e6
                    +" cavities="+cavities+" lavaRuns="+liquids+" retainedVolumeBytes="+bytes);
            assertTrue(cavities>0); assertTrue(liquids>0);
            long javaStart=System.nanoTime();
            for(int z=-2;z<2;z++)for(int x=-2;x<2;x++) {
                var coated=sampler.sampleInterior(x*47,z*53).volume();
                var base=javaSampler.sampleInterior(x*47,z*53).volume();
                for(int y=0;y<128;y++) {
                    assertEquals(base.occupied(y,false),coated.occupied(y,false),"native material rules preserve cave geometry");
                    assertEquals(base.occupied(y,true),coated.occupied(y,true),"native material rules preserve lava geometry");
                }
            }
            System.out.println("NETHER_JAVA_PARITY columns=16 elapsedMs="+(System.nanoTime()-javaStart)/1e6);
            var oldStorage=PredictionCacheStorage.forWorld(Path.of("build/test"),Path.of("build/test/world"),null,null);
            assertNotEquals(oldStorage.directory(new ClientTerrainSampler(42,profile) {
                @Override boolean interiorTerrain() { return false; }
            }),oldStorage.directory(new ClientTerrainSampler(42,profile)));
        }
    }

    @Test void endFineRadiusExpandsWithinHorizonWithoutChangingOverworld() {
        int original=dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionFineDistanceBlocks;
        try {
            dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionFineDistanceBlocks=1536;
            assertEquals(2304,PredictionDetailBands.fineRadius(4096,Level.END));
            assertEquals(1536,PredictionDetailBands.fineRadius(4096,Level.OVERWORLD));
            assertEquals(1024,PredictionDetailBands.fineRadius(1024,Level.END));
            var layout=VssLodLayout.of(4096,6,false,true);
            var end=new PredictionTileManager.PredictionTileKey(Level.END,7,0,2);
            var overworld=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,7,0,2);
            assertEquals(64,PredictionDetailBands.cellAxis(end,layout,0,80,0,null,100,0,128));
            assertEquals(16,PredictionDetailBands.cellAxis(overworld,layout,0,80,0,null,100,0,128));
        } finally {dev.xantha.vss.config.VSSClientConfig.CONFIG.predictionFineDistanceBlocks=original;}
    }

    @Test void progressiveGridReusesInteriorColumnsAndRejectsRoofOnlyCaptures() throws Exception {
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.NETHER.location(),0,256,"noise","minecraft:nether",1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var sampler=new ClientTerrainSampler(42,profile) {
            @Override ClientColumnSample sampleInterior(int x,int z) {calls.incrementAndGet();return cavern().asSample();}
        };
        var manager=new PredictionTileManager(Level.NETHER,sampler,PredictionMemoryBudget.SHARED,null);
        try {
            var method=PredictionTileManager.class.getDeclaredMethod("sampleGridFast",int.class,int.class,int.class,int.class,
                    ClientColumnSample[].class,boolean.class,boolean.class,java.util.function.BooleanSupplier.class);
            method.setAccessible(true);
            var samples=new ClientColumnSample[9];samples[0]=cavern().asSample();
            samples[1]=new ClientColumnSample(128,128,0,rock(),0,0,0,0,0,ClientColumnSample.FLAG_CAPTURED,
                    0,rock(),rock(),ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
            var result=(ClientColumnSample[])method.invoke(manager,0,0,8,3,samples,true,false,(java.util.function.BooleanSupplier)()->true);
            assertEquals(8,calls.get());assertTrue(Arrays.stream(result).allMatch(s->s.volume()!=null));
            method.invoke(manager,0,0,8,3,result,false,true,(java.util.function.BooleanSupplier)()->true);
            assertEquals(8,calls.get(),"completed volume must survive preview-to-display refinement");
        } finally {manager.close();}
    }

    @Test void mixedResolutionSeamsDoNotDrawCurtainsAcrossCaves() {
        var fine = tile(-1,4,cavern());
        var coarse = tile(0,8,new PredictionColumnVolume(new int[]{0,20,rock(),0,64,70,rock(),0,110,128,rock(),0}));
        var patches = new PredictionLodSeams().update(java.util.List.of(surface(fine),surface(coarse)));
        assertFalse(patches.isEmpty());
        for(var patch:patches) {
            int[] words=patch.mesh().quads();
            for(int i=0;i<words.length;i+=12) {
                int top=((words[i+4]&65535)-32768)/4;
                int bottom=((words[i+5]&65535)-32768)/4;
                assertTrue(top<=24 || bottom>=64 && top<=72 || bottom>=110,
                        "seam crosses interior air: "+bottom+".."+top);
            }
        }
        assertNull(PredictionMorph.field(tile(0,4,cavern()),coarse),"roof morph cannot move internal floors or lava");
    }

    private static PredictionTileManager.PredictionTile tile(int x,int step,PredictionColumnVolume volume) {
        var samples=new ClientColumnSample[9]; Arrays.fill(samples,volume.asSample());
        int[] heights=new int[9];Arrays.fill(heights,128);
        var mesh=PredictionMeshBuilder.build(samples,null,32,0xffd9572b,step,3,false).compactForRendering();
        var tile=new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(Level.NETHER,x,0,0),
                heights,heights,samples,mesh,PredictionDepthBound.fromSamples(samples),0,1,2,step);
        mesh.prepareGpuPayload(tile);return tile;
    }
    private static PredictionLodSeams.Surface surface(PredictionTileManager.PredictionTile tile) {
        return new PredictionLodSeams.Surface(tile,new boolean[]{true,true,true,true});
    }

    private static boolean hasFace(PredictionMesh mesh,int y,int normal) {
        for(int v=0;v<mesh.vertexCount();v++)if(mesh.y(v)==y&&mesh.normalY(v)==normal)return true;
        return false;
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Lifecycle;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionNetherDecorationTest {
    static HolderLookup.Provider lookup;
    static Registry<Biome> biomes;
    static RegistryAccess access;
    static Map<TagKey<Block>, List<Holder<Block>>> previousTags;
    @TempDir java.nio.file.Path directory;

    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> registry.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
        biomes = registry.freeze();
        access = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        previousTags = BuiltInRegistries.BLOCK.getTags().collect(Collectors.toMap(p -> p.getFirst(), p -> p.getSecond().stream().toList()));
        var tags = new HashMap<>(previousTags);
        tags.put(BlockTags.NYLIUM, holders(Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM));
        tags.put(BlockTags.REPLACEABLE_BY_TREES, holders(Blocks.AIR, Blocks.CRIMSON_ROOTS, Blocks.WARPED_ROOTS, Blocks.NETHER_SPROUTS));
        tags.put(BlockTags.SOUL_FIRE_BASE_BLOCKS, holders(Blocks.SOUL_SAND, Blocks.SOUL_SOIL));
        BuiltInRegistries.BLOCK.bindTags(tags);
    }
    @AfterAll static void restore() { BuiltInRegistries.BLOCK.bindTags(previousTags); }
    static List<Holder<Block>> holders(Block... blocks) {
        return Arrays.stream(blocks).map(b -> (Holder<Block>)BuiltInRegistries.BLOCK.getHolder(id(b)).orElseThrow()).toList();
    }
    static int id(Block block) { return BuiltInRegistries.BLOCK.getId(block); }
    static com.google.gson.JsonObject nativeDocument(String biome) throws Exception {
        var root=java.nio.file.Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("nether.json"))).getAsJsonObject();
        for(var e:Map.of("block_definitions","blocks","biomes","biomes","grass_colormap","grass","foliage_colormap","foliage").entrySet())
            doc.add(e.getKey(),com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve(e.getValue()+".json"))));
        for(var entry:doc.getAsJsonObject("block_definitions").entrySet()) {
            var block=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(entry.getKey()));
            entry.getValue().getAsJsonObject().addProperty("replaceable",block.defaultBlockState().canBeReplaced());
        }
        doc.add("biome_source",com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\""+biome+"\"}"));
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        return doc;
    }
    static PredictionColumnVolume cavern() {
        return new PredictionColumnVolume(new int[]{0,56,id(Blocks.NETHERRACK),0,
                80,86,id(Blocks.NETHERRACK),0,110,128,id(Blocks.NETHERRACK),0});
    }
    static ClientTerrainSampler sampler(ResourceKey<Biome> biome, boolean synthetic) {
        return sampler(biome, synthetic, access);
    }
    static ClientTerrainSampler sampler(ResourceKey<Biome> biome, boolean synthetic, RegistryAccess registries) {
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var generator = new NoiseBasedChunkGenerator(new FixedBiomeSource(biomes.getHolderOrThrow(biome)), settings);
        var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE), 42);
        var profile = new DimensionProfile(Level.NETHER.location(),42,0,256,"noise","minecraft:nether",1);
        return new ClientTerrainSampler(42, profile, generator, random, LevelHeightAccessor.create(0,256),32,List.of(),null,registries) {
            @Override ClientColumnSample sampleInterior(int x, int z) {
                return synthetic ? resolveInterior(cavern().asSample(),x,z) : super.sampleInterior(x,z);
            }
            @Override public int surfaceY(int x,int z) { return synthetic ? 128 : super.surfaceY(x,z); }
        };
    }

    @Test void caveQueriesUseVolumeAndReuseColumns() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var terrain = new ClientTerrainSampler(42,new DimensionProfile(Level.NETHER.location(),42,0,256,"noise","minecraft:nether",1)) {
            @Override ClientColumnSample sampleInterior(int x,int z) { calls.incrementAndGet(); return cavern().asSample(); }
            @Override public ClientColumnSample sampleSurface(int x,int z) { fail("roof heightfield must not supply cave blocks"); return null; }
        };
        var cache = new VssLodSampleCache(512);
        for (int job=0;job<2;job++) {
            var level = new PredictionDecorationLevel(terrain,terrain,access,0,0,cache);
            for(int y=0;y<256;y++) assertEquals(cavern().occupied(y,true),!level.getBlockState(new BlockPos(1,y,1)).isAir());
            assertFalse(level.canSeeSky(new BlockPos(1,150,1)));
            level.useDisplayTerrain(true);
            assertTrue(level.getBlockState(new BlockPos(1,60,1)).isAir());
        }
        assertEquals(1,calls.get(),"multiple block queries and jobs reuse one materialized volume");
    }

    @Test void nativeAndJavaInteriorColumnsUseTheSameSurfaceRules() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var root=java.nio.file.Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen");
        var doc=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve("nether.json"))).getAsJsonObject();
        for(var e:Map.of("block_definitions","blocks","biomes","biomes","grass_colormap","grass","foliage_colormap","foliage").entrySet())
            doc.add(e.getKey(),com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(root.resolve(e.getValue()+".json"))));
        doc.add("possible_biomes",new com.google.gson.JsonArray());
        doc.add("biome_source",com.google.gson.JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:crimson_forest\"}"));
        var javaTerrain=sampler(Biomes.CRIMSON_FOREST,false);
        try(var nativeTerrain=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),javaTerrain.profile(),javaTerrain)) {
            long nativeNanos=0,javaNanos=0,bytes=0;
            for(int z=-2;z<2;z++) for(int x=-2;x<2;x++) {
                long start=System.nanoTime(); var nativeColumn=nativeTerrain.sampleInterior(x*47,z*53); nativeNanos+=System.nanoTime()-start;
                start=System.nanoTime(); var javaColumn=javaTerrain.sampleInterior(x*47,z*53); javaNanos+=System.nanoTime()-start;
                assertEquals(javaColumn.volume(),nativeColumn.volume(),"both backends need the same coated cave geometry");
                bytes+=nativeColumn.volume().bytes();
            }
            System.out.println("NETHER_COATED columns=16 nativeMs="+nativeNanos/1e6+" javaMs="+javaNanos/1e6+" retainedBytes="+bytes);
        }
    }

    @Test void actualNetherSurfaceRulesCoatEveryFloorAndKeepCaves() {
        for (var biome : List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var terrain = sampler(biome,true);
            Block nylium = biome == Biomes.CRIMSON_FOREST ? Blocks.CRIMSON_NYLIUM : Blocks.WARPED_NYLIUM;
            int floors=0,upperFloors=0;
            long start=System.nanoTime();
            for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                var volume=terrain.sampleInterior(x,z).volume();
                assertEquals(ClientColumnSample.NO_BLOCK,volume.blockAt(60));
                assertEquals(ClientColumnSample.NO_BLOCK,volume.blockAt(90));
                assertFalse(volume.occupied(60,false)); assertFalse(volume.occupied(90,false));
                if(volume.blockAt(55)==id(nylium)) floors++;
                if(volume.blockAt(85)==id(nylium)) upperFloors++;
                assertNotEquals(id(nylium),volume.blockAt(127),"roof is not a fungus floor");
            }
            System.out.println("NETHER_SURFACE biome="+biome.location()+" columns=256 ms="+(System.nanoTime()-start)/1e6+" floors="+floors+" upper="+upperFloors);
            assertTrue(floors>0); assertTrue(upperFloors>0);
        }
    }

    @Test void registeredFungiAndRootsGenerateBelowRoofAndPersist() throws Exception {
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var terrain=sampler(biome,true);
            var path=directory.resolve(biome.location().getPath());
            var expected=new HashMap<Integer,Map<BlockPos,BlockState>>();
            long start=System.nanoTime();
            int stems=0,plants=0;
            try(var cache=new PredictionDiskCache(path,42)) {
                var vegetation=new PredictionVegetation(terrain,cache);
                for(int x=0;x<4;x++) {
                    var blocks=vegetation.chunk(x,0); expected.put(x,blocks);
                    for(var e:blocks.entrySet()) {
                        assertTrue(e.getKey().getY()<128,"interior decorations must not migrate to the roof");
                        if(e.getValue().is(Blocks.CRIMSON_STEM)||e.getValue().is(Blocks.WARPED_STEM)) stems++;
                        if(e.getValue().is(Blocks.CRIMSON_ROOTS)||e.getValue().is(Blocks.WARPED_ROOTS)||e.getValue().is(Blocks.NETHER_SPROUTS)) plants++;
                    }
                }
                System.out.println("NETHER_DECORATION biome="+biome.location()+" chunks=4 ms="+(System.nanoTime()-start)/1e6+" stems="+stems+" plants="+plants+" "+vegetation.diagnostics());
                var types=new java.util.TreeSet<String>();
                expected.values().forEach(m->m.values().forEach(s->types.add(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString())));
                System.out.println("NETHER_FEATURE_BLOCKS "+types);
            }
            assertTrue(stems>0,"registered huge fungi must survive placement and extraction");
            assertTrue(plants>0,"registered ground vegetation must survive extraction");
            try(var cache=new PredictionDiskCache(path,42)) {
                var restored=new PredictionVegetation(terrain,cache);
                for(int x=0;x<4;x++) assertEquals(expected.get(x),restored.chunk(x,0));
                assertTrue(restored.diagnostics().contains(",chunks=0,blocks=0,"));
            }
        }
    }

    @Test void interiorMaterialsMatchVanillaSurfacePassOnTheSameGeometry() {
        var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        var noStructures=new DensityFunctions.BeardifierOrMarker() {
            public double compute(DensityFunction.FunctionContext c){return 0;}
            public double minValue(){return 0;} public double maxValue(){return 0;}
        };
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST,Biomes.SOUL_SAND_VALLEY,Biomes.BASALT_DELTAS)) {
            var terrain=sampler(biome,true);
            var generator=terrain.generatorContext(); var random=terrain.randomStateContext();
            var heights=LevelHeightAccessor.create(0,256);
            var chunk=new net.minecraft.world.level.chunk.ProtoChunk(new ChunkPos(0,0),net.minecraft.world.level.chunk.UpgradeData.EMPTY,heights,biomes,null) {
                @Override public int getHeight(Heightmap.Types type,int x,int z){return 127;}
            };
            for(int x=0;x<16;x++) for(int z=0;z<16;z++) for(int y=0;y<128;y++)
                if(cavern().occupied(y,true)) chunk.setBlockState(new BlockPos(x,y,z),Blocks.NETHERRACK.defaultBlockState(),false);
            var fluid=new Aquifer.FluidStatus(32,Blocks.LAVA.defaultBlockState());
            var noise=NoiseChunk.forChunk(chunk,random,noStructures,settings.value(),(x,y,z)->fluid,net.minecraft.world.level.levelgen.blending.Blender.empty());
            random.surfaceSystem().buildSurface(random,new BiomeManager((x,y,z)->biomes.getHolderOrThrow(biome),0),biomes,false,
                    new WorldGenerationContext(generator,heights),chunk,noise,settings.value().surfaceRule());
            for(int x=0;x<16;x+=3) for(int z=0;z<16;z+=3) {
                var volume=terrain.sampleInterior(x,z).volume();
                for(int y=0;y<128;y++) {
                    var expected=chunk.getBlockState(new BlockPos(x,y,z));
                    assertEquals(expected.isAir()?ClientColumnSample.NO_BLOCK:id(expected.getBlock()),volume.blockAt(y),
                            "surface mismatch biome="+biome.location()+" xyz="+x+","+y+","+z);
                }
            }
        }
    }

    @Test void hangingAndLowerLayerBlocksSurviveExtractionMeshingAndPacking() {
        var terrain=sampler(Biomes.CRIMSON_FOREST,true);
        var level=new PredictionDecorationLevel(terrain,terrain,access,0,0);
        level.setBlock(new BlockPos(1,60,1),Blocks.CRIMSON_ROOTS.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(1,90,1),Blocks.WARPED_ROOTS.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(2,108,2),Blocks.GLOWSTONE.defaultBlockState(),0,0);
        level.setBlock(new BlockPos(2,20,2),Blocks.GLOWSTONE.defaultBlockState(),0,0);
        var blocks=PredictionVegetation.surfaceBlocks(level);
        assertEquals(4,blocks.size(),"buried replacements also survive for volume editing");
        var tile=PredictionVegetation.Tile.of(blocks,0,0,4,1,1).withExteriorEnvelope();
        var samples=new ClientColumnSample[25]; Arrays.fill(samples,cavern().asSample());
        var mesh=PredictionMeshBuilder.build(samples,null,32,0,1,5,true,null,null,null,0,0,tile);
        boolean lower=false,upper=false,bottom=false;
        for(int v=0;v<mesh.vertexCount();v++) {
            if(mesh.y(v)==60) lower=true;
            if(mesh.y(v)==90) upper=true;
            if(mesh.y(v)==108&&mesh.normalY(v)==-1) bottom=true;
        }
        assertTrue(lower); assertTrue(upper); assertTrue(bottom,"glowstone underside must render inside a cave");
        assertTrue(mesh.packed().quadCount()>0);
    }

    @Test void simplifiedFungiAreBoundedAndUseClearCaveFloors() {
        var hint=PredictionSimpleVegetation.inspect(biomes.getHolderOrThrow(Biomes.CRIMSON_FOREST));
        assertTrue(hint.trees().stream().anyMatch(t->t.ground()!=null&&t.leaves().is(Blocks.NETHER_WART_BLOCK)));
        int grid=18; var samples=new ClientColumnSample[grid*grid]; var colors=new int[samples.length];
        var volume=new PredictionColumnVolume(new int[]{0,55,id(Blocks.NETHERRACK),0,55,56,id(Blocks.CRIMSON_NYLIUM),0,
                80,85,id(Blocks.NETHERRACK),0,85,86,id(Blocks.CRIMSON_NYLIUM),0,110,128,id(Blocks.BEDROCK),0});
        Arrays.fill(samples,volume.asSample());
        for(int step:new int[]{4,8,16,32}) {
            var result=PredictionSimpleVegetation.build(samples,grid,step,-256,-256,42,colors,colors,PredictionVegetation.Tile.EMPTY,(x,y,z)->hint);
            assertFalse(result.forms().isEmpty()); assertTrue(result.forms().size()<=PredictionSimpleVegetation.MAX_FORMS);
            assertTrue(result.forms().stream().allMatch(f->f.y()==56||f.y()==86));
            assertTrue(result.forms().stream().allMatch(f->!volume.occupied(f.y()+f.height()-1,false)));
            assertEquals(result,PredictionSimpleVegetation.build(samples,grid,step,-256,-256,42,colors,colors,PredictionVegetation.Tile.EMPTY,(x,y,z)->hint));
        }
        Arrays.fill(samples,new PredictionColumnVolume(new int[]{0,55,id(Blocks.NETHERRACK),0,55,56,id(Blocks.CRIMSON_NYLIUM),0,
                58,128,id(Blocks.NETHERRACK),0}).asSample());
        assertTrue(PredictionSimpleVegetation.build(samples,grid,8,0,0,42,colors,colors,PredictionVegetation.Tile.EMPTY,(x,y,z)->hint).forms().isEmpty(),"low ceilings cannot grow cap proxies");
    }

    @Test void fireAndSoulFireUseUprightDoubleSidedCutouts() {
        var blocks=Map.of(new BlockPos(1,60,1),Blocks.FIRE.defaultBlockState(),
                new BlockPos(2,90,2),Blocks.SOUL_FIRE.defaultBlockState());
        var tile=PredictionVegetation.Tile.of(blocks,0,0,4,1,1);
        var samples=new ClientColumnSample[25]; Arrays.fill(samples,new PredictionColumnVolume(new int[0]).asSample());
        var mesh=PredictionMeshBuilder.build(samples,null,32,0,1,5,true,null,null,null,0,0,tile);
        assertEquals(24,mesh.vertexCount(),"two crossed quads per fire, no horizontal tile or cube");
        for(int v=0;v<mesh.vertexCount();v+=6) {
            float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;
            for(int k=0;k<6;k++) { lo=Math.min(lo,mesh.y(v+k)); hi=Math.max(hi,mesh.y(v+k)); }
            assertEquals(1,hi-lo,0.001);
            assertEquals(1,mesh.normalY(v),0.001,"packed cross-plane marker keeps both sides visible");
        }
        assertEquals(4,mesh.packed().quadCount());
        assertFalse(PredictionVegetation.mergeable(Blocks.FIRE.defaultBlockState(),1));
        assertFalse(tile.occupied(1,60,1));
    }

    @Test void structureRoomsCutOnlyTheirFootprintAtEveryMeshSpacing() {
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int y=44;y<50;y++) blocks.put(new BlockPos(3,y,3),Blocks.AIR.defaultBlockState());
        blocks.put(new BlockPos(3,43,3),Blocks.NETHER_BRICKS.defaultBlockState());
        blocks.put(new BlockPos(3,49,4),Blocks.NETHER_BRICK_STAIRS.defaultBlockState());
        for(int spacing:new int[]{1,2,4,8}) {
            int grid=16/spacing+1;
            var samples=new ClientColumnSample[grid*grid]; Arrays.fill(samples,cavern().asSample());
            var tile=PredictionVegetation.boundedTile(blocks,0,0,16,spacing,Math.max(1,spacing/2));
            var edits=new PredictionInteriorEdits(samples,tile,0,0,spacing,grid);
            assertFalse(edits.column(3,3,false).occupied(46,true));
            assertTrue(edits.column(4,3,false).occupied(46,true),"adjacent unedited rock remains solid");
            assertFalse(edits.column(3,3,false).occupied(43,true),"foundation must replace the original rock");
            assertTrue(edits.column(3,3,true).occupied(43,true),"foundation still occludes neighboring terrain");
            var mesh=PredictionMeshBuilder.build(samples,null,32,0,spacing,grid,true,null,null,null,0,0,tile);
            boolean floor=false,ceiling=false;
            for(int v=0;v<mesh.vertexCount();v++) {
                if(mesh.y(v)==44 && mesh.normalY(v)==1) floor=true;
                if(mesh.y(v)==50 && mesh.normalY(v)==-1) ceiling=true;
            }
            assertTrue(floor,"foundation at exact height, spacing="+spacing);
            assertTrue(ceiling,"ceiling of the carved room, spacing="+spacing);
            assertTrue(mesh.packed().quadCount()>0);
        }
    }

    @Test void chunkFacingDecorationReadsAndWritesShareTransactions() {
        var terrain=sampler(Biomes.CRIMSON_FOREST,true);
        var level=new PredictionDecorationLevel(terrain,terrain,access,0,0);
        var chunk=level.getChunk(0,0,net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,true);
        var pos=new BlockPos(2,60,2);
        level.beginStructure();
        chunk.setBlockState(pos,Blocks.NETHER_BRICKS.defaultBlockState(),false);
        assertEquals(chunk.getBlockState(pos),level.getBlockState(pos));
        chunk.markPosForPostprocessing(pos);
        level.endFeature(false);
        assertTrue(chunk.getBlockState(pos).isAir());
        assertTrue(level.placed().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->level.getChunk(3,0,net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES,true));
    }

    @Test void nativeRegisteredNetherDecorationsUseTheInteriorStage() throws Exception {
        assertTrue(RustTerrainSampler.available());
        var registry=new MappedRegistry<net.minecraft.world.level.levelgen.placement.PlacedFeature>(Registries.PLACED_FEATURE,Lifecycle.stable());
        lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().forEach(h->registry.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        var registries=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes,registry.freeze()));
        var states=new com.google.gson.JsonArray();
        for(var block:BuiltInRegistries.BLOCK) for(var state:block.getStateDefinition().getPossibleStates())
            states.add(RustWorldgenDocument.encodeState(state));
        var features=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "tools/rust/vss-native-core/tests/fixtures/worldgen/features.json"))).getAsJsonObject();
        for(var biome:List.of(Biomes.CRIMSON_FOREST,Biomes.WARPED_FOREST)) {
            var context=sampler(biome,false,registries);
            var doc=nativeDocument(biome.location().toString());
            doc.add("input_states",states);
            doc.add("configured_features",features.get("configured_features"));
            doc.add("placed_features",features.get("placed_features"));
            var possible=new com.google.gson.JsonArray();possible.add(biome.location().toString());doc.add("possible_biomes",possible);
            try(var nativeTerrain=new RustTerrainSampler(RustWorldgenBackend.create(42,0,doc.toString()),context.profile(),context)) {
                var vegetation=new PredictionVegetation(nativeTerrain,null,false);
                long start=System.nanoTime();
                var placed=new HashMap<BlockPos,BlockState>();
                for(int x=0;x<4;x++) placed.putAll(vegetation.chunk(x,0));
                long nativeNanos=System.nanoTime()-start;
                var javaVegetation=new PredictionVegetation(context,null,false);
                var expected=new HashMap<BlockPos,BlockState>();
                start=System.nanoTime();
                for(int x=0;x<4;x++) expected.putAll(javaVegetation.chunk(x,0));
                long javaNanos=System.nanoTime()-start;
                var positions=new HashSet<>(expected.keySet());positions.addAll(placed.keySet());
                for(var at:positions) assertEquals(expected.get(at),placed.get(at),"mixed native/Java stream at "+at+" biome="+biome.location());
                assertTrue(placed.entrySet().stream().anyMatch(e->e.getKey().getY()<120&&PredictionVegetation.renderable(e.getValue())),vegetation.diagnostics());
                assertFalse(nativeTerrain.diagnostics().contains("nativeFeatures=0,"),nativeTerrain.diagnostics());
                System.out.println("NETHER_NATIVE_DECORATION biome="+biome.location()+" chunks=4 nativeMs="+nativeNanos/1e6+" javaMs="+javaNanos/1e6
                        +" "+vegetation.diagnostics()+" "+nativeTerrain.diagnostics());
            }
        }
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import dev.xantha.vss.testutil.TerrainPredictionFixtures;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

/** Real registry + Rust + remaining Java/native decoration + production Voxy encoding. No graphics context. */
class PredictionFarDecorationNativeTest {
    @BeforeAll static void bootstrap() throws Exception {
        TerrainPredictionFixtures.bootstrap();
        FarVanillaTags.bootstrap();
        assertTrue(RustTerrainSampler.available());
    }
    static RustTerrainSampler sampler()throws Exception { return sampler(Biomes.FOREST,42); }
    static RustTerrainSampler sampler(net.minecraft.resources.ResourceKey<Biome> biome,long seed)throws Exception {
        var access=TerrainPredictionFixtures.access();
        var original=TerrainPredictionFixtures.noise();
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(access.registryOrThrow(Registries.BIOME).getHolderOrThrow(biome)),original.generatorSettings());
        var settings=generator.generatorSettings().value();
        var random=RandomState.create(settings,access.lookupOrThrow(Registries.NOISE),seed);
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(),seed,-64,384,"noise","minecraft:overworld",1);
        var context=new ClientTerrainSampler(seed,profile,generator,random,LevelHeightAccessor.create(-64,384),settings.seaLevel(),null,access);
        var document=document(context,settings,biome.location().toString());
        long handle=RustWorldgenBackend.create(seed,BiomeManager.obfuscateSeed(seed),document.toString());
        context.bindHorizontalClimate(handle,document);
        return new RustTerrainSampler(handle,profile,context);
    }
    static PredictionVoxySource.Tile ground(RustTerrainSampler sampler,int bx,int bz,int span,int step) {
        int grid=span/step+2;var samples=new ClientColumnSample[grid*grid];
        for(int z=0;z<grid;z+=8)for(int x=0;x<grid;x+=8)
            sampler.sampleDisplayGridInPlace(bx+(x-1)*step,bz+(z-1)*step,step,Math.min(8,grid-x),Math.min(8,grid-z),samples,z*grid+x,grid);
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,span),Math.floorDiv(bz,span),2);
        return PredictionVoxySource.build(key,samples,grid,step,sampler,PredictionVegetation.Tile.EMPTY,null,false);
    }
    @Test void oneUnsupportedFeatureCannotAbortOtherBiomeTemplates() throws Exception {
        try(var terrain=sampler()) {
            var context=org.mockito.Mockito.spy(terrain.decorationContext());
            var generator=org.mockito.Mockito.spy(context.generatorContext());
            var settings=context.generatorContext().getBiomeGenerationSettings(context.cacheBiomeHolder(0));
            var broken=org.mockito.Mockito.mock(net.minecraft.world.level.levelgen.placement.PlacedFeature.class);
            org.mockito.Mockito.when(broken.placement()).thenThrow(new IllegalStateException("Mod rule needs a live world"));
            org.mockito.Mockito.when(broken.feature()).thenReturn(settings.features().get(9).get(0).value().feature());
            var stages=new ArrayList<>(settings.features());
            var entries=new ArrayList<net.minecraft.core.Holder<net.minecraft.world.level.levelgen.placement.PlacedFeature>>();
            entries.add(net.minecraft.core.Holder.direct(broken));
            settings.features().get(9).forEach(entries::add);
            stages.set(9,net.minecraft.core.HolderSet.direct(entries));
            var changed=org.mockito.Mockito.mock(net.minecraft.world.level.biome.BiomeGenerationSettings.class);
            org.mockito.Mockito.when(changed.features()).thenReturn(stages);
            org.mockito.Mockito.doReturn(changed).when(generator).getBiomeGenerationSettings(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doReturn(generator).when(context).generatorContext();
            var proxy=org.mockito.Mockito.spy(terrain);
            org.mockito.Mockito.doReturn(context).when(proxy).decorationContext();
            var models=new PredictionFarDecoration(proxy,true,8,()->true);
            assertFalse(models.replaced.contains(broken),"A failed rule must remain on original placement");
            assertFalse(models.replaced.isEmpty(),"One mod callback cannot disable all remaining templates");
        }
    }

    @Test void weightedCoverageKeepsCoresAndDoesNotDoubleCountOverlappingCrowns() {
        var leaf=net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState();
        var log=net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        assertTrue(leaf.is(net.minecraft.tags.BlockTags.LEAVES));
        for(int step:new int[]{2,4,8,16}) {
            var votes=new PredictionFarDecoration.Votes(step);
            votes.add(leaf,0,0,0,1);
            for(int repeat=0;repeat<20;repeat++)votes.add(leaf,0,0,0,1);
            if(step>2)assertFalse(votes.visible(),"Repeated overlap cannot promote one leaf fragment");
            votes.add(log,step-1,step-1,0,step);
            assertTrue(votes.visible(),"A slender trunk must survive");
            var crown=new PredictionFarDecoration.Votes(step);
            int columns=Math.max(1,(step*step+7)/8);
            for(int i=0;i<columns;i++)crown.add(leaf,i%step,i/step,0,step);
            assertTrue(crown.visible(),"Retain an airy but supported crown");
            assertSame(leaf,crown.winner());
        }
    }

    @Test void completeProductionPathIsStableAndActuallyReplacesFeatures()throws Exception {
        try(var sampler=sampler()) {
            var models=new PredictionFarDecoration(sampler,true,8,()->true);
            assertFalse(models.replaced.isEmpty(),models.initialization().toString());
            assertTrue(models.templates.values().stream().flatMap(List::stream).anyMatch(PredictionFarDecoration.Template::woody),models.initialization().toString());
            assertThrows(CancellationException.class,()->new PredictionFarDecoration(sampler,true,8,()->false));
            var base=ground(sampler,8192,-8192,128,4);
            var service=new PredictionFarDecorations(sampler);
            long previewStarted=System.nanoTime();
            var preview=service.preview(base,null,true,p->false,()->true);
            assertTrue(preview.approximateDecorations());assertFalse(preview.surfaceComplete());
            assertFalse(preview.features().isEmpty());
            var fallbackField=PredictionFarDecorations.class.getDeclaredField("vegetation");
            fallbackField.setAccessible(true);
            assertNull(fallbackField.get(service),"Template coverage must not initialize or run the fallback graph");
            var cachedBox=new PredictionVoxySource.Box(0,300,0,1,301,1,net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            var knownPartial=new PredictionVoxySource.Tile(base.key(),base.baseX(),base.baseZ(),base.span(),base.axis(),base.step(),
                    base.columns(),List.of(cachedBox),base.terrainBytes(),false,0);
            var previewWithCache=service.preview(knownPartial,null,true,p->false,()->true);
            assertTrue(previewWithCache.features().contains(cachedBox),"First templates retain known disk-cache fallback");
            var finerGround=ground(sampler,8192,-8192,128,2).retainFeatures(previewWithCache);
            var refinedPartial=service.preview(finerGround,null,true,p->false,()->true);
            assertTrue(refinedPartial.features().contains(cachedBox),"Partial geometric refinement retains fallback too");
            assertFalse(refinedPartial.surfaceComplete());assertEquals(2,refinedPartial.decorationStep());
            assertNull(fallbackField.get(service),"Geometry and cache handoff never run the fallback placement graph");
            // Read actual Voxy voxels while fallback has not even been initialized.
            var trunk=preview.features().stream().filter(b->b.state().is(net.minecraft.tags.BlockTags.LOGS)).findFirst().orElseThrow();
            int wx=preview.baseX()+trunk.minX(),wz=preview.baseZ()+trunk.minZ(),wy=trunk.maxY()-1;
            dev.xantha.vss.compat.VoxyPredictionBridge.Mapper mapper=(state,biome)->state.isAir()?0:((long)net.minecraft.world.level.block.Block.getId(state)+1)<<27;
            try(var source=new PredictionVoxySource()) {
                source.put(preview);
                var voxels=source.section(dev.xantha.vss.compat.VoxyPredictionBridge.position(0,
                        Math.floorDiv(wx,32),Math.floorDiv(wy,32),Math.floorDiv(wz,32)),mapper);
                assertNotNull(voxels);
                assertEquals(mapper.map(trunk.state(),"minecraft:forest"),voxels[(wy&31)<<10|(wz&31)<<5|(wx&31)] & ~31L,
                        "Partial template trees must reach the Voxy section before fallback completes");
            }
            long firstVisible=System.nanoTime();
            var first=service.build(preview,null,true,p->false,()->true);
            long completed=System.nanoTime();
            System.out.printf(Locale.ROOT,"FAR_FIRST_VISIBLE span=128 previewMs=%.3f completionAfterPreviewMs=%.3f totalMs=%.3f%n",
                    (firstVisible-previewStarted)/1e6,(completed-firstVisible)/1e6,(completed-previewStarted)/1e6);
            assertTrue(first.features().containsAll(preview.features()),"Fallback must retain every preview template box");
            var repeat=service.build(base,null,true,p->false,()->true);
            assertTrue(first.approximateDecorations());assertTrue(first.surfaceComplete());
            assertSame(base.columns(),first.columns());assertFalse(first.features().isEmpty());
            assertEquals(first.features(),repeat.features(),"Standing still cannot change distant decoration");
            var pool=Executors.newFixedThreadPool(2);
            try {
                var futures=pool.invokeAll(List.<Callable<PredictionVoxySource.Tile>>of(
                        ()->service.build(base,null,true,p->false,()->true),
                        ()->new PredictionFarDecorations(sampler).build(base,null,true,p->false,()->true)));
                for(var f:futures)assertEquals(first.features(),f.get(60,TimeUnit.SECONDS).features());
            } finally { pool.shutdownNow(); }
            assertNull(PredictionFarDecoration.current());
            // Woody anchors use exact world coordinates, regardless of output LOD or neighbour tile.
            var fine=models.session().generate(8192,-8192,128,4,true);
            var coarse=models.session().generate(8192,-8192,128,8,true);
            assertFalse(fine.anchors().stream().filter(a->a.model().woody()).toList().isEmpty(),
                    "A forest must retain actual scattered trees after soil checks");
            assertEquals(fine.anchors().stream().filter(a->a.model().woody()).map(PredictionFarDecoration.Anchor::id).toList(),
                    coarse.anchors().stream().filter(a->a.model().woody()).map(PredictionFarDecoration.Anchor::id).toList());
            try(var capture=models.session().enter(()->true,p->true,base)) {
                assertTrue(PredictionFarDecoration.current().generate(8192,-8192,128,4,true).boxes().isEmpty(),
                        "Real captured columns must suppress the approximate layer");
            }
            var outer=models.session();var inner=models.session();
            try(var a=outer.enter(()->true,p->false,base)) {
                assertSame(outer,PredictionFarDecoration.current());
                assertThrows(CancellationException.class,()->{
                    try(var b=inner.enter(()->false,p->false,base)){inner.generate(8192,-8192,128,4,true);}
                });
                assertSame(outer,PredictionFarDecoration.current());
            }
            assertNull(PredictionFarDecoration.current());
            System.out.println("FAR_MODEL "+new Gson().toJson(models.initialization()));
            System.out.println("FAR_DIAGNOSTICS "+service.diagnostics());
        }
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="VSS_FAR_BENCH",matches="true")
    void pairedCompleteGenerationBenchmark()throws Exception {
        try(var exact=sampler();var approximate=sampler()) {
            var normal=new PredictionVegetation(exact,null,true);
            var far=new PredictionFarDecorations(approximate);
            for(int round=-1;round<3;round++) {
                int bx=8192+round*512,bz=-8192-round*512;
                var outputs=new HashMap<Boolean,PredictionVoxySource.Tile>();
                for(boolean fast:round%2==0?new boolean[]{false,true}:new boolean[]{true,false}) {
                    var s=fast?approximate:exact;
                    long start=System.nanoTime();var base=ground(s,bx,bz,256,4);long terrainEnd=System.nanoTime();
                    var result=fast?far.build(base,null,true,p->false,()->true):base.withVegetation(
                            normal.tileForRendering(bx,bz,256,1,true,p->false,()->true),null,true);
                    long end=System.nanoTime();assertFalse(result.features().isEmpty());outputs.put(fast,result);
                    System.out.printf(Locale.ROOT,"FAR_INTEGRATED round=%d approximate=%s chunks=256 wallMs=%.3f terrainMs=%.3f decorationAndEncodingMs=%.3f boxes=%d bytes=%d%n",
                            round,fast,(end-start)/1e6,(terrainEnd-start)/1e6,(end-terrainEnd)/1e6,result.features().size(),result.bytes());
                }
                var expected=woodyVoxels(outputs.get(false),4);var actual=woodyVoxels(outputs.get(true),4);
                assertFalse(actual.isEmpty(),"Fast results must include trees, not only fallback decoration");
                int intersection=0;for(long cell:actual)if(expected.contains(cell))intersection++;
                System.out.printf(Locale.ROOT,"FAR_OCCUPANCY round=%d exact=%d approximate=%d intersection=%d ratio=%.4f%n",
                        round,expected.size(),actual.size(),intersection,expected.isEmpty()?Double.NaN:(double)actual.size()/expected.size());
            }
        }
    }


    @Test void bambooBiomeRetainsItsDenseStemsInFirstVisibleTemplates()throws Exception {
        try(var terrain=sampler(Biomes.BAMBOO_JUNGLE,42)) {
            var base=ground(terrain,8192,-8192,128,4);
            var models=new PredictionFarDecoration(terrain,true,8,()->true);
            var bambooRule=models.biomeRules.values().stream().flatMap(List::stream).filter(r->r.choices().stream()
                    .anyMatch(c->c.models().stream().anyMatch(t->t.spans().stream().anyMatch(v->v.state().is(net.minecraft.world.level.block.Blocks.BAMBOO)))))
                    .findFirst().orElseThrow();
            assertTrue(bambooRule.count()>10,"Fixture must exercise density above the old grid cap");
            List<Long> ids=null;
            for(int step:new int[]{4,8,16}) {
                var session=models.session();PredictionFarDecoration.Result result;
                try(var scope=session.enter(()->true,p->false,base)) {result=session.generate(8192,-8192,128,step,true);}
                var bambooAnchors=result.anchors().stream().filter(a->a.model().spans().stream().anyMatch(v->v.state().is(net.minecraft.world.level.block.Blocks.BAMBOO))).toList();
                assertFalse(bambooAnchors.isEmpty());
                var stable=bambooAnchors.stream().map(PredictionFarDecoration.Anchor::id).toList();
                if(ids==null)ids=stable;else assertEquals(ids,stable,"Bamboo density and anchor positions cannot change with LOD");
                assertTrue(result.boxes().stream().anyMatch(v->v.state().is(net.minecraft.world.level.block.Blocks.BAMBOO)),"Bamboo survives coarse material reduction");
                System.out.println("BAMBOO_DENSITY step="+step+" expectedPerChunk="+bambooRule.count()+" anchors="+bambooAnchors.size()+" boxes="+result.boxes().size());
            }
            var service=new PredictionFarDecorations(terrain);
            var preview=service.preview(base,null,true,p->false,()->true);
            var stem=preview.features().stream().filter(v->v.state().is(net.minecraft.world.level.block.Blocks.BAMBOO)).findFirst().orElseThrow();
            int x=base.baseX()+stem.minX(),z=base.baseZ()+stem.minZ(),y=stem.maxY()-1;
            dev.xantha.vss.compat.VoxyPredictionBridge.Mapper mapper=(state,biome)->state.isAir()?0:((long)net.minecraft.world.level.block.Block.getId(state)+1)<<27;
            try(var source=new PredictionVoxySource()) {
                source.put(preview);
                var data=source.section(dev.xantha.vss.compat.VoxyPredictionBridge.position(0,Math.floorDiv(x,32),Math.floorDiv(y,32),Math.floorDiv(z,32)),mapper);
                assertNotNull(data);assertEquals(mapper.map(stem.state(),"minecraft:bamboo_jungle"),data[(y&31)<<10|(z&31)<<5|(x&31)] & ~31L);
            }
            var completed=service.build(preview,null,true,p->false,()->true);
            assertTrue(completed.features().containsAll(preview.features()),"Completing the retained decoration cannot drop template bamboo");
        }
    }

    private static JsonObject document(ClientTerrainSampler context,NoiseGeneratorSettings settings,String biome)throws Exception {
        var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
        var ops=lookup.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        var registries=new JsonObject();
        export(registries,"noises",lookup.lookupOrThrow(Registries.NOISE),net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC,ops);
        export(registries,"density_functions",lookup.lookupOrThrow(Registries.DENSITY_FUNCTION),DensityFunction.DIRECT_CODEC,ops);
        export(registries,"biomes",lookup.lookupOrThrow(Registries.BIOME),Biome.DIRECT_CODEC,ops);
        export(registries,"configured_features",lookup.lookupOrThrow(Registries.CONFIGURED_FEATURE),net.minecraft.world.level.levelgen.feature.ConfiguredFeature.DIRECT_CODEC,ops);
        export(registries,"placed_features",lookup.lookupOrThrow(Registries.PLACED_FEATURE),net.minecraft.world.level.levelgen.placement.PlacedFeature.DIRECT_CODEC,ops);
        var generator=new JsonObject();
        generator.add("settings",NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops,lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()).getOrThrow());
        generator.add("biome_source",fixedBiome(biome));
        var doc=RustWorldgenDocument.snapshot(generator,registries,context);
        for(String color:List.of("grass","foliage"))doc.add(color+"_colormap",JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen/"+color+".json"))));
        return doc;
    }
    private static <T> void export(JsonObject out,String name,net.minecraft.core.HolderLookup.RegistryLookup<T> registry,
            com.mojang.serialization.Codec<T> codec,com.mojang.serialization.DynamicOps<JsonElement> ops) {
        var entries=new JsonObject();registry.listElements().forEach(h->entries.add(h.key().location().toString(),codec.encodeStart(ops,h.value()).getOrThrow()));out.add(name,entries);
    }

    @Test @SuppressWarnings("unchecked") void managerDispatchesFarWorkAndPromotesAfterTeleport()throws Exception {
        var config=dev.xantha.vss.config.VSSClientConfig.CONFIG;
        boolean enabled=config.predictionFarDecorations;int radius=config.predictionExactDistanceBlocks;
        config.predictionFarDecorations=true;config.predictionExactDistanceBlocks=4096;
        var terrain=sampler();
        try(var manager=new PredictionTileManager(Level.OVERWORLD,terrain,new PredictionMemoryBudget(
                1024L*PredictionMemoryBudget.MIB,0,()->Long.MAX_VALUE,System::nanoTime,1),null)) {
            field("layout").set(manager,VssLodLayout.of(8192,2,true,false));
            field("unifiedVoxy").setBoolean(manager,true);
            var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,80,0,0);
            var raw=ground(terrain,5120,0,64,4);
            var base=new PredictionVoxySource.Tile(key,5120,0,64,raw.axis(),4,raw.columns(),raw.features(),raw.terrainBytes());
            var resident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    PredictionVoxelData.forVoxy(base),new PredictionDepthBound(-64,320),0,1,base.axis(),4);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,resident);
            for(String name:List.of("desiredKeys","terrainLeaves"))((Set<Object>)field(name).get(manager)).add(key);
            field("terrainTargets").set(manager,Map.of(key,base.axis()));
            field("ordinaryTargets").set(manager,Map.of(key,base.axis()));
            field("viewBands").set(manager,Map.of(key,PredictionWorkView.VISIBLE));
            ((PredictionVoxySource)field("voxySource").get(manager)).put(base);
            var service=org.mockito.Mockito.spy(new PredictionFarDecorations(terrain));
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            org.mockito.Mockito.doAnswer(invocation->{
                entered.countDown();assertTrue(release.await(60,TimeUnit.SECONDS));return invocation.callRealMethod();
            }).when(service).build(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            field("farDecorations").set(manager,service);
            call(manager,"refreshFarSurfaces",new Class<?>[0]);
            assertEquals(true,call(manager,"surfaceBuildReady",new Class<?>[]{PredictionTileManager.PredictionTileKey.class},key));
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
                while(entered.getCount()>0 && System.nanoTime()<deadline) {
                    synchronized(manager) {call(manager,"serviceVisibleSurfaces",new Class<?>[]{int.class,int.class,long.class},0,0,System.nanoTime()+1_000_000);}
                    Thread.sleep(5);
                }
                assertEquals(0,entered.getCount(),manager.surfaceDiagnostics());
                var partial=manager.renderSnapshot().tiles().get(key).data().voxyTile;
                assertTrue(partial.approximateDecorations());assertFalse(partial.surfaceComplete());
                assertFalse(partial.features().isEmpty(),"Templates must be published before blocked fallback starts");
                assertFalse(((Set<?>)field("surfaceReady").get(manager)).contains(key));
                assertEquals(1,((java.util.concurrent.atomic.AtomicInteger)field("activeFarCompletions").get(manager)).get());
            } finally {release.countDown();}
            finish(manager,key,true);
            var far=manager.renderSnapshot().tiles().get(key).data().voxyTile;
            assertTrue(far.approximateDecorations(),manager.surfaceDiagnostics());
            var completionCount=service.performanceSnapshot().get("completionBuilds");
            var rawFine=ground(terrain,5120,0,64,1);
            var fine=new PredictionVoxySource.Tile(key,5120,0,64,rawFine.axis(),1,
                    rawFine.columns(),List.of(),rawFine.terrainBytes(),false,0).retainFeatures(far);
            assertEquals(4,fine.decorationStep());assertTrue(fine.needsDecorationRefinement());
            var fineResident=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                    PredictionVoxelData.forVoxy(fine),new PredictionDepthBound(-64,320),0,2,fine.axis(),1);
            ((Map<Object,Object>)field("ready").get(manager)).put(key,fineResident);
            field("terrainTargets").set(manager,Map.of(key,fine.axis()));
            field("ordinaryTargets").set(manager,Map.of(key,fine.axis()));
            call(manager,"refreshFarSurfaces",new Class<?>[0]);
            assertEquals(true,call(manager,"surfaceBuildReady",new Class<?>[]{PredictionTileManager.PredictionTileKey.class},key));
            long refineDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(manager.renderSnapshot().tiles().get(key).data().voxyTile.decorationStep()!=1 && System.nanoTime()<refineDeadline) {
                synchronized(manager){call(manager,"serviceVisibleSurfaces",new Class<?>[]{int.class,int.class,long.class},0,0,System.nanoTime()+1_000_000);}
                Thread.sleep(5);
            }
            var refined=manager.renderSnapshot().tiles().get(key).data().voxyTile;
            assertEquals(1,refined.decorationStep());assertTrue(refined.surfaceComplete());
            assertFalse(refined.needsDecorationRefinement());
            assertEquals(completionCount,service.performanceSnapshot().get("completionBuilds"),"Geometric refinement reuses completed fallback");
            assertEquals(far.features().subList(far.cityFeatures()+far.templateFeatures(),far.features().size()),
                    refined.features().subList(refined.cityFeatures()+refined.templateFeatures(),refined.features().size()));
            assertFalse(far.features().subList(far.cityFeatures(),far.cityFeatures()+far.templateFeatures()).equals(
                    refined.features().subList(refined.cityFeatures(),refined.cityFeatures()+refined.templateFeatures())),"Coarse boxes must actually change");
            long previews=((Number)service.performanceSnapshot().get("previewBuilds")).longValue();
            for(int i=0;i<20;i++) {
                call(manager,"refreshFarSurfaces",new Class<?>[0]);
                synchronized(manager){call(manager,"serviceVisibleSurfaces",new Class<?>[]{int.class,int.class,long.class},0,0,System.nanoTime()+1_000_000);}
            }
            assertEquals(previews,((Number)service.performanceSnapshot().get("previewBuilds")).longValue(),"Completed fine templates do not loop");
            field("cameraBlockX").setDouble(manager,5120);
            call(manager,"refreshFarSurfaces",new Class<?>[0]);
            assertSame(refined,manager.renderSnapshot().tiles().get(key).data().voxyTile);
            finish(manager,key,false);
            var exact=manager.renderSnapshot().tiles().get(key).data().voxyTile;
            assertTrue(exact.surfaceComplete());assertFalse(exact.approximateDecorations());
            assertSame(fine.columns(),exact.columns());
        } finally {config.predictionFarDecorations=enabled;config.predictionExactDistanceBlocks=radius;}
    }
    private static void finish(PredictionTileManager manager,PredictionTileManager.PredictionTileKey key,boolean approximate)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        do {
            synchronized(manager) {call(manager,"serviceVisibleSurfaces",new Class<?>[]{int.class,int.class,long.class},0,0,System.nanoTime()+1_000_000);}
            Thread.sleep(5);
            var tile=manager.renderSnapshot().tiles().get(key).data().voxyTile;
            if(tile.surfaceComplete() && tile.approximateDecorations()==approximate)return;
        } while(System.nanoTime()<deadline);
        fail(manager.surfaceDiagnostics());
    }
    private static java.lang.reflect.Field field(String name)throws Exception {
        var f=PredictionTileManager.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Object call(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        var m=PredictionTileManager.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }

    private static JsonObject fixedBiome(String name) {
        var biome=new JsonObject();biome.addProperty("type","minecraft:fixed");biome.addProperty("biome",name);return biome;
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="VSS_FAR_QUALITY",matches="true")
    void differentBiomesAndSeedsRetainTreesAcrossOutputLods()throws Exception {
        for(var biome:List.of(Biomes.FOREST,Biomes.TAIGA,Biomes.DARK_FOREST,Biomes.BIRCH_FOREST,Biomes.JUNGLE,Biomes.PLAINS)) {
            try(var sampler=sampler(biome,918273)) {
                var base=ground(sampler,9216,-11264,128,4);
                var exact=base.withVegetation(new PredictionVegetation(sampler,null,true)
                        .tileForRendering(9216,-11264,128,1,true,p->false,()->true),null,true);
                var far=new PredictionFarDecorations(sampler).build(base,null,true,p->false,()->true);
                var expected=woodyVoxels(exact,4);var actual=woodyVoxels(far,4);
                var models=new PredictionFarDecoration(sampler,true,8,()->true);
                List<Long> anchors=null;
                for(int step:new int[]{4,8,16}) {
                    var session=models.session();
                    PredictionFarDecoration.Result result;
                    try(var scope=session.enter(()->true,p->false,base)) { result=session.generate(9216,-11264,128,step,true); }
                    PredictionFarDecoration.Result raw;
                    var fineSession=models.session();
                    try(var scope=fineSession.enter(()->true,p->false,base)) { raw=fineSession.generate(9216,-11264,128,step,false); }
                    int rawCells=templateCells(raw.boxes(),step),weightedCells=templateCells(result.boxes(),step);
                    assertTrue(weightedCells<=rawCells,"Coverage weighting cannot create new occupied cells");
                    var current=result.anchors().stream().filter(a->a.model().woody()).map(PredictionFarDecoration.Anchor::id).toList();
                    if(anchors==null)anchors=current;else assertEquals(anchors,current,"LOD cannot change tree selection");
                    long leaves=result.boxes().stream().filter(b->b.state().is(net.minecraft.tags.BlockTags.LEAVES)).count();
                    if(!current.isEmpty())assertTrue(leaves>0,"LOD must retain forest canopy, "+biome+" step "+step);
                    System.out.printf(Locale.ROOT,"FAR_QUALITY biome=%s seed=918273 step=%d fullExactAt4=%d fullApproximateAt4=%d ratioAt4=%.4f rawTemplateCells=%d weightedTemplateCells=%d anchors=%d leafBoxes=%d%n",
                            biome,step,expected.size(),actual.size(),expected.isEmpty()?Double.NaN:(double)actual.size()/expected.size(),
                            rawCells,weightedCells,current.size(),leaves);
                }
            }
        }
    }

    private static int templateCells(List<PredictionVoxySource.Box> boxes,int step) {
        return woodyVoxels(boxes.stream().filter(b->b.state().is(net.minecraft.tags.BlockTags.LOGS)
                || b.state().is(net.minecraft.tags.BlockTags.LEAVES)).toList(),step).size();
    }
    private static Set<Long> woodyVoxels(PredictionVoxySource.Tile tile,int step) { return woodyVoxels(tile.features(),step); }
    private static Set<Long> woodyVoxels(List<PredictionVoxySource.Box> boxes,int step) {
        var cells=new HashMap<Long,net.minecraft.world.level.block.state.BlockState>();
        for(var b:boxes)for(int z=Math.floorDiv(b.minZ(),step);z<=Math.floorDiv(b.maxZ()-1,step);z++)
            for(int x=Math.floorDiv(b.minX(),step);x<=Math.floorDiv(b.maxX()-1,step);x++)
                for(int y=Math.floorDiv(b.minY(),step);y<=Math.floorDiv(b.maxY()-1,step);y++)
                    cells.put(net.minecraft.core.BlockPos.asLong(x,y,z),b.state());
        cells.entrySet().removeIf(e->!e.getValue().is(net.minecraft.tags.BlockTags.LOGS) && !e.getValue().is(net.minecraft.tags.BlockTags.LEAVES));
        return cells.keySet();
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="VSS_FAR_LARGE",matches="true")
    void largestEligibleFarTileCompletesWithBoundedCancellation()throws Exception {
        try(var sampler=sampler()) {
            var ground=ground(sampler,8192,-8192,1024,16);
            var service=new PredictionFarDecorations(sampler);long start=System.nanoTime();
            var result=service.build(ground,null,true,p->false,()->true);
            assertTrue(result.approximateDecorations());assertFalse(result.features().isEmpty());
            System.out.printf(Locale.ROOT,"FAR_LARGE chunks=4096 span=1024 step=16 wallMs=%.3f bytes=%d boxes=%d%n",
                    (System.nanoTime()-start)/1e6,result.bytes(),result.features().size());
            var checks=new java.util.concurrent.atomic.AtomicInteger();start=System.nanoTime();
            assertThrows(CancellationException.class,()->service.build(ground,null,true,p->false,()->checks.incrementAndGet()<200));
            assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));assertNull(PredictionFarDecoration.current());
        }
    }
}

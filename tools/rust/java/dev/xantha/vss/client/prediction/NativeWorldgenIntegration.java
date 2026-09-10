package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import com.mojang.serialization.Lifecycle;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import static dev.xantha.vss.client.prediction.NativeWorldgenReference.*;

/** Runs the actual production sampler and mixed vegetation bridge without starting a game. */
public final class NativeWorldgenIntegration {
    private static <T> Registry<T> registry(HolderLookup.Provider lookup,ResourceKey<? extends Registry<T>> key) {
        var result=new MappedRegistry<T>(key,Lifecycle.stable());
        lookup.lookupOrThrow(key).listElements().forEach(h->result.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        return result.freeze();
    }
    public static void main(String[] args)throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        RustReferenceMain.loadTags();RustReferenceMain.loadColors();
        require(RustTerrainSampler.available(),"Packaged Rust backend did not load");
        var lookup=VanillaRegistries.createLookup();
        var access=new RegistryAccess.ImmutableRegistryAccess(List.of(registry(lookup,Registries.BIOME),registry(lookup,Registries.PLACED_FEATURE)));
        var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        int samples=0,vegetation=0;
        for(String name:List.of("plains","bamboo_jungle","eroded_badlands","frozen_ocean","savanna","taiga","dark_forest")) {
            var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,ResourceLocation.withDefaultNamespace(name)));
            var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),settings);
            var profile=new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),0L,-64,384,"noise","minecraft:overworld",0L);
            var random=RandomState.create(settings.value(),lookup.lookupOrThrow(Registries.NOISE),0);
            var context=new ClientTerrainSampler(0,profile,generator,random,LevelHeightAccessor.create(-64,384),63,List.of(),null,access);
            var original=document("overworld");
            original.add("biome_source",JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:"+name+"\"}"));
            // Exercise the production registry/state export with oracle colormaps;
            // no Minecraft client/resource manager exists in this headless process.
            var doc=RustWorldgenDocument.snapshot(original,original,context);
            require(doc.getAsJsonArray("input_states").size()>10000,"Runtime block-state export is incomplete");
            doc.add("grass_colormap",read("grass.json"));doc.add("foliage_colormap",read("foliage.json"));
            if (args.length > 0 && args[0].equals("--live")) {
                verifyLiveTicks(doc, profile, context);
                return;
            }
            if(name.equals("plains"))Files.writeString(Path.of("build/reports/rust-worldgen-production-document.json"),JSON.toJson(doc));
            long world=RustWorldgenBackend.create(0,BiomeManager.obfuscateSeed(0),JSON.toJson(doc));
            try(var sampler=new RustTerrainSampler(world,profile,context)) {
                for(int spacing:new int[]{1,4,8,16,64}) {
                    var grid=sampler.sampleGrid(-17,-19,spacing,4);
                    for(int i=0;i<grid.length;i++) {
                        int x=-17+i%4*spacing,z=-19+i/4*spacing;
                        require(grid[i].equals(sampler.sample(x,z)),"Production grid layout/material mismatch "+name+" "+spacing);
                        int y=grid[i].surfaceY()+12;
                        require(sampler.surfaceColor(x,y,z)==biome.value().getGrassColor(x,z),"Production grass tint");
                        require(sampler.foliageColor(x,y,z)==(0xff000000|biome.value().getFoliageColor()),"Production foliage tint");
                        require(sampler.waterTint(x,y,z)==(0xb2000000|biome.value().getWaterColor()),"Production water tint");samples++;
                    }
                }
                var compact=direct(256*40);RustWorldgenBackend.surfaceColumns(world,0,0,compact);
                for (int spacing : new int[]{1, 16}) for (int[] size : new int[][]{{8,2},{2,8},{2,2}}) {
                    var rectangle = sampler.sampleGrid(-17, -19, spacing, size[0], size[1]);
                    require(rectangle.length == size[0] * size[1], "Rectangle sampled unused edge points");
                    for (int i = 0; i < rectangle.length; i++) {
                        require(rectangle[i].equals(sampler.sample(-17 + i % size[0] * spacing,
                                -19 + i / size[0] * spacing)), "Rectangular native grid layout/material mismatch");
                        samples++;
                    }
                }
                long volume=RustWorldgenBackend.surfaceProxy(world,0,0);
                try {
                    var full=direct(80*384*80*4);RustWorldgenBackend.readVolume(volume,full);
                    for(int x:new int[]{-16,0,15,31})for(int z:new int[]{-16,0,15,31})for(int y:new int[]{-64,20,62,63,64,80,150,319}) {
                        int index=((x+32)*80+z+32)*384+y+64;
                        require(sampler.states()[full.getInt(index*4)]==sampler.proxyBlock(x,y,z),"Native/Java proxy block mismatch");
                    }
                    var edit=direct(32);int stone=sampler.stateId(Blocks.STONE.defaultBlockState());
                    edit.putInt(0,0).putInt(4,128).putInt(8,0).putInt(12,stone);
                    edit.putInt(16,32).putInt(20,128).putInt(24,0).putInt(28,stone);
                    rejects(()->RustWorldgenBackend.applyEdits(volume,edit,2));
                    RustWorldgenBackend.readVolume(volume,full);
                    require(full.getInt(((32*80+32)*384+192)*4)!=stone,"Rejected edit partially committed");
                    require(RustWorldgenBackend.applyEdits(volume,edit,1)==1,"Valid edit transfer");
                    require(RustWorldgenBackend.readEdits(volume,direct(4))==0,"Imported Java edits echoed back");
                    RustWorldgenBackend.readVolume(volume,full);
                    require(full.getInt(((32*80+32)*384+192)*4)==stone,"Valid edit not committed");
                } finally {RustWorldgenBackend.close(volume);}
                if(List.of("plains","bamboo_jungle","savanna","taiga","dark_forest").contains(name)) {
                    int step=GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
                    var features=FeatureSorter.buildFeaturesPerStep(List.copyOf(generator.getBiomeSource().possibleBiomes()),h->h.value().getGenerationSettings().features(),true).get(step).features();
                    var actual=new PredictionDecorationLevel(sampler,context,access,0,0);
                    var expected=new PredictionDecorationLevel(sampler,context,access,0,0);
                    // Represents already-placed structure ground and irrigation edits.
                    for(var level:List.of(actual,expected)) {
                        level.beginFeature();
                        for(int x=-8;x<25;x++)for(int z=-8;z<25;z++)level.setBlock(new BlockPos(x,127,z),Blocks.GRASS_BLOCK.defaultBlockState(),19,0);
                        level.setBlock(new BlockPos(5,127,5),Blocks.WATER.defaultBlockState(),19,0);
                        level.endFeature(true);
                    }
                    try(var nativeStage=new RustVegetationStage(sampler,actual,0,0)) {
                        nativeStage.selectStep(features,step);
                        for(String target:switch(name) {
                            case "plains" -> List.of("patch_tall_grass_2","flower_plains","patch_grass_plain");
                            case "savanna" -> List.of("trees_savanna");
                            case "taiga" -> List.of("trees_taiga");
                            case "dark_forest" -> List.of("dark_forest_vegetation");
                            default -> List.of("bamboo");
                        }) {
                            var feature=access.registryOrThrow(Registries.PLACED_FEATURE).get(ResourceLocation.withDefaultNamespace(target));
                            int index=features.indexOf(feature);require(index>=0,"Missing test placed feature "+name+":"+target);
                            // Interleave a Java edit between Rust features, then verify order.
                            nativeStage.beforeJava();actual.beginFeature();expected.beginFeature();
                            var obstacle=new BlockPos(7+vegetation,128,9);
                            actual.setBlock(obstacle,Blocks.STONE.defaultBlockState(),19,0);
                            expected.setBlock(obstacle,Blocks.STONE.defaultBlockState(),19,0);
                            actual.endFeature(true);expected.endFeature(true);nativeStage.afterJava();
                            require(nativeStage.place(index),"Supported vegetation fell back to Java: "+target);
                            var r=new WorldgenRandom(new XoroshiroRandomSource(0));long seed=r.setDecorationSeed(0,0,0);r.setFeatureSeed(seed,index,step);
                            expected.beginFeature();feature.placeWithBiomeCheck(expected,generator,r,new BlockPos(0,-64,0));expected.endFeature(true);
                            require(actual.placed().equals(expected.placed()),"Mixed bridge changed vegetation/structure writes: "+target);
                            if(target.startsWith("trees_")||target.equals("dark_forest_vegetation"))require(actual.placed().values().stream().anyMatch(s->s.is(net.minecraft.tags.BlockTags.LOGS)),"Tree bridge produced no logs");
                            vegetation++;
                        }
                        nativeStage.finish();
                    }
                }
                if(name.equals("plains")) {
                    var maps=new JsonObject();int[] grass=new int[65536],leaves=new int[65536];Arrays.fill(grass,0x123456);Arrays.fill(leaves,0x654321);
                    maps.add("grass_colormap",JSON.toJsonTree(grass));maps.add("foliage_colormap",JSON.toJsonTree(leaves));
                    sampler.replaceColormaps(maps);
                    require(sampler.surfaceColor(0,80,0)==0x123456&&sampler.foliageColor(0,80,0)==0xff654321,"Resource reload kept old native colors");
                    var after=direct(256*40);RustWorldgenBackend.surfaceColumns(world,0,0,after);
                    for(int i=0;i<256;i++) {
                        for(int j=0;j<7;j++)require(compact.getInt((i*10+j)*4)==after.getInt((i*10+j)*4),"Colormap reload changed terrain");
                        require(after.getInt((i*10+7)*4)==0x123456,"Colormap reload kept stale native surface cache");
                    }
                    maps.add("foliage_colormap",new JsonArray());rejects(()->sampler.replaceColormaps(maps));
                    require(sampler.surfaceColor(0,80,0)==0x123456,"Rejected colormap changed valid colors");
                }
                sampler.cancelWork();
                try {sampler.sampleGrid(10000,10000,16,2);throw new AssertionError("Cancelled sampler generated terrain");}
                catch(java.util.concurrent.CancellationException expected) { }
            }
            rejects(()->RustWorldgenBackend.describe(world));
            if (name.equals("plains")) verifyColdCoverage(doc, profile, context);
        }
        System.out.println("Production Rust adapter: "+samples+" grid/material/tint samples, "+vegetation+" mixed vegetation jobs; state transfer, bounds, cancellation and packaged DLL passed");
    }

    /** Measures the real production manager, including colour, mesh and GPU payload preparation. */
    @SuppressWarnings("unchecked")
    private static void verifyColdCoverage(JsonObject doc, DimensionProfile profile, ClientTerrainSampler context) throws Exception {
        long handle = RustWorldgenBackend.create(0, BiomeManager.obfuscateSeed(0), JSON.toJson(doc));
        var nativeSampler = new RustTerrainSampler(handle, profile, context);
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int oldDistance = config.predictionDistanceBlocks;
        config.predictionDistanceBlocks = 65536;
        try (var manager = new PredictionTileManager(profile.levelKey(), nativeSampler,
                new PredictionMemoryBudget(512L * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime, 1), null)) {
            var key = new PredictionTileManager.PredictionTileKey(profile.levelKey(), -1, -1, manager.layout().levelCount() - 1);
            for (String fieldName : List.of("desiredKeys", "terrainLeaves")) {
                var field = PredictionTileManager.class.getDeclaredField(fieldName); field.setAccessible(true);
                ((Set<PredictionTileManager.PredictionTileKey>) field.get(manager)).add(key);
            }
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class, int.class, int.class);
            enqueue.setAccessible(true);
            long start = System.nanoTime();
            enqueue.invoke(manager, key, 0, 0);
            awaitBuild(manager);
            long coverageNanos = System.nanoTime() - start;
            require(manager.readyCount() == 1, "Cold coverage tile missing");
            var preview = manager.readyTiles().iterator().next();
            require(preview.cellAxis() == 8 && preview.spanBlocks() == 65536 && preview.mesh().gpuPayload() != null,
                    "Cold Rust tile did not publish the bounded coverage mesh");
            start = System.nanoTime();
            for (int axis : new int[]{16,32,64}) {
                long stageStart = System.nanoTime();
                enqueue.invoke(manager, key, 0, 0);
                awaitBuild(manager);
                var stage = manager.readyTiles().iterator().next();
                require(stage.cellAxis() == axis && stage.spanBlocks() == preview.spanBlocks(), "Refinement stage lost tile coverage");
                System.out.printf(java.util.Locale.ROOT, "Production refinement %dx%d: %.2f ms%n", axis, axis, (System.nanoTime()-stageStart)/1e6);
            }
            var full = manager.readyTiles().iterator().next();
            require(full.cellAxis() == 64 && full.revision() > preview.revision()
                    && full.spanBlocks() == preview.spanBlocks() && full.baseBlockX() == preview.baseBlockX(),
                    "Preview did not converge to full terrain over the same region");
            System.out.printf(java.util.Locale.ROOT,
                    "Production first coverage: 100 points, %.2f ms; complete staged refinement: %.2f ms; same 65536-block tile, single worker, excludes profile decode%n",
                    coverageNanos / 1e6, (System.nanoTime() - start) / 1e6);
            System.out.println("Production refinement phases: " + manager.surfaceDiagnostics());
        } finally { config.predictionDistanceBlocks = oldDistance; }
    }
    private static void awaitBuild(PredictionTileManager manager) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (manager.pendingCount() > 0 && System.nanoTime() < deadline) Thread.sleep(2);
        require(manager.pendingCount() == 0 && manager.failedTileCount() == 0, "Production coverage/refinement failed or timed out");
    }

    private static void verifyLiveTicks(JsonObject doc, DimensionProfile profile, ClientTerrainSampler context) throws Exception {
        long handle = RustWorldgenBackend.create(0, BiomeManager.obfuscateSeed(0), JSON.toJson(doc));
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        config.predictionDistanceBlocks = 65536;
        var budget = PredictionMemoryBudget.adaptive(Runtime.getRuntime().maxMemory(),
                () -> Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory(),
                System::nanoTime, 4, () -> java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                        .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum());
        try (var manager = new PredictionTileManager(profile.levelKey(), new RustTerrainSampler(handle, profile, context), budget, null)) {
            long start = System.nanoTime(), last = start;
            while (System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) {
                manager.tick(282,151,-85,700,null,128);
                Thread.sleep(50);
                if (System.nanoTime() - last > java.util.concurrent.TimeUnit.SECONDS.toNanos(5)) {
                    last = System.nanoTime();
                    System.out.println("Live native " + (last-start)/1_000_000_000 + "s: lods=" + Arrays.toString(manager.readyLodCounts())
                            + ", pending=" + manager.pendingCount() + ", failures=" + manager.failedTileCount() + ", "
                            + budget.diagnostics() + ", " + manager.surfaceDiagnostics());
                }
            }
            require(manager.readyTiles().stream().anyMatch(t -> t.key().lod() == 0 && t.cellAxis() == 64), "Native live scheduler did not reach nearby block detail");
        }
    }
}

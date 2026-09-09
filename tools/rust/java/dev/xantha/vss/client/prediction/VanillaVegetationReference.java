package dev.xantha.vss.client.prediction;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.biome.*;
import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;

/** Actual vanilla feature placement; the input level is explicit flat ground and obstacles. */
public final class VanillaVegetationReference {
    static List<int[]> blocks(Map<BlockPos,BlockState> map){return map.entrySet().stream().sorted(Comparator.<Map.Entry<BlockPos,BlockState>>comparingInt(e->e.getKey().getX()).thenComparingInt(e->e.getKey().getZ()).thenComparingInt(e->e.getKey().getY())).map(e->new int[]{e.getKey().getX(),e.getKey().getY(),e.getKey().getZ(),VanillaSurfaceReference.STATE_IDS.computeIfAbsent(e.getValue(),k->VanillaSurfaceReference.STATE_IDS.size())}).toList();}
    public static void main(String[] args)throws Exception{
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();RustReferenceMain.loadTags();RustReferenceMain.loadColors();
        var root=Path.of(args[0]);VanillaSurfaceReference.exportBlocks(root);var lookup=VanillaRegistries.createLookup();var json=new Gson();var doc=new JsonObject();var configured=new JsonObject();var placed=new JsonObject();
        for(var h:lookup.lookupOrThrow(Registries.CONFIGURED_FEATURE).listElements().toList())configured.add(h.key().location().toString(),dev.xantha.vss.common.worldgen.VanillaFeatureSnapshot.correct(h.value(),VanillaWorldgenReference.resource("worldgen/configured_feature/"+h.key().location().getPath())));
        for(var h:lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList())placed.add(h.key().location().toString(),VanillaWorldgenReference.resource("worldgen/placed_feature/"+h.key().location().getPath()));
        doc.add("configured_features",configured);doc.add("placed_features",placed);Files.writeString(root.resolve("features.json"),json.toJson(doc));
        var allBiomes=lookup.lookupOrThrow(Registries.BIOME).listElements().map(h->(Holder<Biome>)h).toList();
        var featureNames=new IdentityHashMap<net.minecraft.world.level.levelgen.placement.PlacedFeature,String>();
        lookup.lookupOrThrow(Registries.PLACED_FEATURE).listElements().forEach(h->featureNames.put(h.value(),h.key().location().toString()));
        try(var orderOut=Files.newBufferedWriter(root.resolve("feature-order.jsonl"))){
            for(var biomeOrder:List.of(allBiomes,allBiomes.reversed(),allBiomes.subList(0,17),allBiomes.subList(17,allBiomes.size()))) {
                var order=FeatureSorter.buildFeaturesPerStep(biomeOrder,h->h.value().getGenerationSettings().features(),true);
                orderOut.write(json.toJson(Map.of("possible_biomes",biomeOrder.stream().map(h->h.unwrapKey().orElseThrow().location().toString()).toList(),"steps",order.stream().map(s->s.features().stream().map(featureNames::get).toList()).toList())));orderOut.newLine();
            }
        }
        var order=FeatureSorter.buildFeaturesPerStep(allBiomes,h->h.value().getGenerationSettings().features(),true);
        var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);var biome=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var generator=new NoiseBasedChunkGenerator(new FixedBiomeSource(biome),settings);int rows=0;
        try(var out=Files.newBufferedWriter(root.resolve("vegetation.jsonl"))){
            for(String name:List.of("oak","fancy_oak","dark_oak","birch","spruce","pine","acacia","azalea_tree","jungle_bush","jungle_tree_no_vine","jungle_tree","swamp_oak","mega_jungle_tree","mega_pine","mega_spruce","cherry","mangrove","tall_mangrove","patch_grass","patch_tall_grass","patch_large_fern","flower_default","flower_plain","flower_meadow","flower_flower_forest","bamboo_no_podzol","bamboo_some_podzol","huge_brown_mushroom","huge_red_mushroom")){
                var key=ResourceKey.create(Registries.CONFIGURED_FEATURE,ResourceLocation.withDefaultNamespace(name));var feature=lookup.lookupOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(key).value();
                for(long seed:new long[]{0,-1,693280690516334765L})for(int pattern=0;pattern<3;pattern++){
                    var level=new FeatureStampLevel(seed,RegistryAccess.EMPTY,Blocks.GRASS_BLOCK.defaultBlockState());
                    if(pattern==1){for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.placed().put(new BlockPos(x,69,z),Blocks.STONE.defaultBlockState());}
                    if(pattern==2){for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)if((x+z)%3==0)level.placed().put(new BlockPos(x,63,z),Blocks.WATER.defaultBlockState());}
                    var input=blocks(level.placed());var r=VanillaKernelReference.random(seed,3);boolean result=feature.place(level,generator,r,new BlockPos(0,64,0));
                    out.write(json.toJson(Map.of("feature","minecraft:"+name,"seed",Long.toString(seed),"pattern",pattern,"input",input,"output",blocks(level.placed()),"placed",result,"after",Long.toString(r.nextLong()))));out.newLine();rows++;
                }
            }
        }
        VanillaSurfaceReference.exportStates(root,"vegetation-states.json");System.out.println("Direct vanilla vegetation rows="+rows);
        int step=GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();int placedRows=0;
        var shuffleField=Collections.class.getDeclaredField("r");shuffleField.setAccessible(true);
        try(var out=Files.newBufferedWriter(root.resolve("placed-vegetation.jsonl"))){
            for(var pair:List.of(new String[]{"bamboo","bamboo_jungle"},new String[]{"patch_grass_plain","plains"},new String[]{"patch_grass_forest","forest"},new String[]{"patch_tall_grass","plains"},new String[]{"flower_default","forest"},new String[]{"flower_meadow","meadow"},new String[]{"trees_savanna","savanna"},new String[]{"trees_taiga","taiga"},new String[]{"trees_birch","birch_forest"},new String[]{"trees_mangrove","mangrove_swamp"},new String[]{"trees_cherry","cherry_grove"},new String[]{"trees_jungle","jungle"},new String[]{"trees_old_growth_pine_taiga","old_growth_pine_taiga"},new String[]{"trees_old_growth_spruce_taiga","old_growth_spruce_taiga"},new String[]{"trees_swamp","swamp"},new String[]{"dark_forest_vegetation","dark_forest"},new String[]{"mushroom_island_vegetation","mushroom_fields"},new String[]{"trees_birch_and_oak","forest"},new String[]{"trees_flower_forest","flower_forest"})){
                var h=lookup.lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,ResourceLocation.withDefaultNamespace(pair[1])));
                var gen=new NoiseBasedChunkGenerator(new FixedBiomeSource(h),settings);
                var feature=lookup.lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,ResourceLocation.withDefaultNamespace(pair[0]))).value();
                int index=order.get(step).indexMapping().applyAsInt(feature);
                for(long seed:new long[]{0,-1,693280690516334765L})for(int pattern=0;pattern<3;pattern++){
                    var level=new FeatureStampLevel(seed,RegistryAccess.EMPTY,Blocks.GRASS_BLOCK.defaultBlockState()){
                        @Override public Holder<Biome> getBiome(BlockPos p){return h;}
                        @Override public Holder<Biome> getNoiseBiome(int x,int y,int z){return h;}
                        @Override public Holder<Biome> getUncachedNoiseBiome(int x,int y,int z){return h;}
                        @Override public int getHeight(Heightmap.Types type,int x,int z){var p=new BlockPos.MutableBlockPos(x,0,z);for(int y=319;y>=-64;y--)if(type.isOpaque().test(getBlockState(p.setY(y))))return y+1;return -64;}
                    };
                    if(pattern==1)for(int x=-5;x<21;x++)for(int z=-5;z<21;z++)if((x+z)%3==0)level.placed().put(new BlockPos(x,63,z),Blocks.WATER.defaultBlockState());
                    if(pattern==2)for(int x=2;x<10;x++)for(int z=2;z<10;z++)level.placed().put(new BlockPos(x,66,z),Blocks.STONE.defaultBlockState());
                    var input=blocks(level.placed());var r=new WorldgenRandom(new XoroshiroRandomSource(0));long decoration=r.setDecorationSeed(seed,0,0);r.setFeatureSeed(decoration,index,step);
                    long entropy=seed^pattern^89172345L;shuffleField.set(null,new Random(entropy));
                    boolean result=feature.placeWithBiomeCheck(level,gen,r,new BlockPos(0,-64,0));
                    var row=json.toJsonTree(Map.of("feature","minecraft:"+pair[0],"biome","minecraft:"+pair[1],"seed",Long.toString(seed),"pattern",pattern,"index",index,"step",step,"input",input,"output",blocks(level.placed()),"placed",result,"after",Long.toString(r.nextLong()))).getAsJsonObject();
                    row.addProperty("entropy",Long.toString(entropy));out.write(json.toJson(row));out.newLine();placedRows++;
                }
            }
        }
        VanillaSurfaceReference.exportStates(root,"vegetation-states.json");System.out.println("Direct vanilla placed vegetation rows="+placedRows);
    }
}

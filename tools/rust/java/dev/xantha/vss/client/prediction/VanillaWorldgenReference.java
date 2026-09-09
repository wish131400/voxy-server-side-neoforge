package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.*;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.*;

/** The oracle executes Minecraft's classes; no save or third-party native library. */
public final class VanillaWorldgenReference {
    static JsonElement resource(String path) throws IOException {
        try(var in=VanillaWorldgenReference.class.getResourceAsStream("/data/minecraft/"+path+".json")) {
            if(in==null) throw new IOException("Missing vanilla resource "+path);
            return JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    static String bits(double value){return Long.toUnsignedString(Double.doubleToRawLongBits(value),16);}
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var lookup=VanillaRegistries.createLookup();var json=new Gson();var root=Path.of(args[0]);Files.createDirectories(root);
        var densities=new JsonObject();var noises=new JsonObject();
        for(var h:lookup.lookupOrThrow(Registries.DENSITY_FUNCTION).listElements().toList()) densities.add(h.key().location().toString(),resource("worldgen/density_function/"+h.key().location().getPath()));
        for(var h:lookup.lookupOrThrow(Registries.NOISE).listElements().toList()) noises.add(h.key().location().toString(),resource("worldgen/noise/"+h.key().location().getPath()));
        int rows=0;
        try(var out=Files.newBufferedWriter(root.resolve("reference.jsonl"))) {
            for(var name:List.of("overworld","large_biomes","amplified","nether","end")) {
                var settings=lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS,ResourceLocation.withDefaultNamespace(name)));
                var doc=new JsonObject();doc.add("settings",resource("worldgen/noise_settings/"+name));doc.add("density_functions",densities);doc.add("noises",noises);
                BiomeSource source=name.equals("end")?TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME)):
                        MultiNoiseBiomeSource.createFromList(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(name.equals("nether")?MultiNoiseBiomeSourceParameterLists.NETHER:MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters());
                var sourceDoc=new JsonObject();sourceDoc.addProperty("type",name.equals("end")?"minecraft:the_end":"minecraft:multi_noise");
                if(!name.equals("end")) {
                    var entries=new JsonArray();
                    for(var entry:lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(name.equals("nether")?MultiNoiseBiomeSourceParameterLists.NETHER:MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters().values()) {
                        var e=new JsonObject();e.add("parameters",Climate.ParameterPoint.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,entry.getFirst()).getOrThrow());e.addProperty("biome",entry.getSecond().unwrapKey().orElseThrow().location().toString());entries.add(e);
                    }sourceDoc.add("biomes",entries);
                }doc.add("biome_source",sourceDoc);
                Files.writeString(root.resolve(name+".json"),json.toJson(doc));
                var generator=new NoiseBasedChunkGenerator(source,settings);var height=settings.value().noiseSettings();
                for(long seed:new long[]{0,-1,693280690516334765L}) {
                    var state=RandomState.create(settings.value(),lookup.lookupOrThrow(Registries.NOISE),seed);
                    List<int[]> points=new ArrayList<>(List.of(new int[]{0,0,0},new int[]{-1,-64,-1},new int[]{16,63,-16},new int[]{-30000000,319,30000000},new int[]{-16777216,-63,16777216}));
                    var r=new Random(20260909);
                    for(int i=0;i<96;i++)points.add(new int[]{r.nextInt(60000)-30000,height.minY()+r.nextInt(height.height()),r.nextInt(60000)-30000});
                    Map<String,DensityFunction> functions=new LinkedHashMap<>();var router=state.router();
                    functions.put("barrier",router.barrierNoise());functions.put("fluid_level_floodedness",router.fluidLevelFloodednessNoise());
                    functions.put("fluid_level_spread",router.fluidLevelSpreadNoise());functions.put("lava",router.lavaNoise());
                    functions.put("temperature",router.temperature());functions.put("vegetation",router.vegetation());functions.put("continents",router.continents());
                    functions.put("erosion",router.erosion());functions.put("depth",router.depth());functions.put("ridges",router.ridges());
                    functions.put("initial_density_without_jaggedness",router.initialDensityWithoutJaggedness());functions.put("final_density",router.finalDensity());
                    functions.put("vein_toggle",router.veinToggle());functions.put("vein_ridged",router.veinRidged());functions.put("vein_gap",router.veinGap());
                    Map<String,Object> values=new LinkedHashMap<>();
                    functions.forEach((key,f)->values.put(key,points.stream().map(p->bits(f.compute(new DensityFunction.SinglePointContext(p[0],p[1],p[2])))).toList()));
                    List<Object> columns=new ArrayList<>();
                    for(var pos:List.of(new int[]{0,0},new int[]{-1,-1},new int[]{17,-31},new int[]{1234,-5678},new int[]{-29999999,29999999})) {
                        var column=generator.getBaseColumn(pos[0],pos[1],LevelHeightAccessor.create(height.minY(),height.height()),state);
                        List<String> blocks=new ArrayList<>();for(int y=height.minY();y<height.minY()+height.height();y++)blocks.add(BuiltInRegistries.BLOCK.getKey(column.getBlock(y).getBlock()).toString());
                        columns.add(Map.of("x",pos[0],"z",pos[1],"blocks",blocks));
                    }
                    var biomeValues=points.stream().map(p->source.getNoiseBiome(p[0]>>2,p[1]>>2,p[2]>>2,state.sampler()).unwrapKey().orElseThrow().location().toString()).toList();
                    List<int[]> zoom=new ArrayList<>();long zoomSeed=BiomeManager.obfuscateSeed(seed);
                    var manager=new BiomeManager((x,y,z)->{zoom.add(new int[]{x,y,z});return lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);},zoomSeed);
                    for(var p:points)manager.getBiome(new net.minecraft.core.BlockPos(p[0],p[1],p[2]));
                    out.write(json.toJson(Map.of("settings",name,"seed",Long.toString(seed),"points",points,"density",values,"columns",columns,"biomes",biomeValues,"zoom_seed",Long.toString(zoomSeed),"zoom",zoom)));out.newLine();rows++;
                }
            }
        }
        System.out.println("Direct vanilla worldgen rows="+rows+" output="+root.toAbsolutePath());
    }
}

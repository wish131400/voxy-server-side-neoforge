package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="vss.testNativeLibrary", matches=".+")
class PredictionVegetationLoadingBenchmark {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        RustWorldgenBackend.load(Path.of(System.getProperty("vss.testNativeLibrary")));
    }

    @Test void compareIdenticalNativeTilesWithAndWithoutLightVegetation() throws Exception {
        var doc = LithostitchedNativeTest.document();
        doc.add("possible_biomes", new com.google.gson.JsonArray());
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        var source = BiomeSource.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, lookup), doc.get("biome_source")).getOrThrow();
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        for (int step : new int[]{4, 8, 16}) for (int round = 0; round < 3; round++) {
            long seed = round % 2 == 0 ? 0 : 917;
            var profile = new DimensionProfile(ResourceLocation.withDefaultNamespace("overworld"),seed,-64,384,"noise","minecraft:overworld",seed);
            var random = RandomState.create(settings.value(), lookup.lookupOrThrow(Registries.NOISE),seed);
            var generator = new NoiseBasedChunkGenerator(source,settings);
            var context = new ClientTerrainSampler(seed,profile,generator,random,LevelHeightAccessor.create(-64,384),63,List.of());
            long world=RustWorldgenBackend.create(seed,net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(seed),doc.toString());
            try(var rust=new RustTerrainSampler(world,profile,context)) {
                int baseX=-512+round*1024,baseZ=-512-round*1024;
                rust.sampleDisplayGrid(32768,32768,step,8,8,new ClientColumnSample[64]);
                long start=System.nanoTime();
                var samples=new ClientColumnSample[66*66];
                for (int z=0;z<66;z+=8) for (int x=0;x<66;x+=8) {
                    int width=Math.min(8,66-x),height=Math.min(8,66-z);
                    var batch=rust.sampleDisplayGrid(baseX+(x-1)*step,baseZ+(z-1)*step,step,width,height,
                            new ClientColumnSample[width*height]);
                    for(int row=0;row<height;row++) System.arraycopy(batch,row*width,samples,(z+row)*66+x,width);
                }
                long sampleNs=System.nanoTime()-start;
                int[] grass=new int[samples.length],leaf=new int[samples.length],colors=new int[samples.length];
                start=System.nanoTime();
                for(int i=0;i<samples.length;i++) {
                    int x=baseX+(i%66-1)*step,z=baseZ+(i/66-1)*step,y=samples[i].surfaceY();
                    grass[i]=rust.surfaceColor(x,y,z); leaf[i]=rust.foliageColor(x,y,z);
                    colors[i]=PredictionLighting.shade(PredictionMaterialPalette.colorFor(samples[i],grass[i]),
                            y,63,false,samples[i].fluid()!=0);
                }
                long colorsNs=System.nanoTime()-start;
                var vegetation=new PredictionSimpleVegetation(rust);
                start=System.nanoTime();
                var simple=vegetation.build(samples,66,step,baseX,baseZ,grass,leaf,PredictionVegetation.Tile.EMPTY);
                var enabledColors=forestColors(samples,grass,colors,simple);
                long simpleNs=System.nanoTime()-start;
                start=System.nanoTime();
                forestColors(samples,grass,colors,vegetation.build(samples,66,step,baseX,baseZ,grass,leaf,PredictionVegetation.Tile.EMPTY));
                long warmNs=System.nanoTime()-start;
                long[] meshNs=new long[2]; int[] quads=new int[2];
                for(int iteration=0;iteration<10;iteration++) for(int order=0;order<2;order++) {
                    int mode=(iteration+order)%2;
                    start=System.nanoTime();
                    var mesh=PredictionMeshBuilder.build(samples,mode==0?colors:enabledColors,63,0xff3f76e4,step,66,true,null,leaf,null,
                            baseX,baseZ,PredictionVegetation.Tile.EMPTY,mode==0?PredictionSimpleVegetation.Result.EMPTY:simple).compactForRendering();
                    long elapsed=System.nanoTime()-start;
                    if(iteration>=4) meshNs[mode]+=elapsed;
                    quads[mode]=mesh.packed().quadCount();
                }
                start=System.nanoTime();
                int probes=PredictionWallEvidence.enrich(samples,66,step,baseX,baseZ,rust);
                long wallNs=System.nanoTime()-start;
                System.out.printf(Locale.ROOT,"VEGETATION_LOADING step=%d round=%d seed=%d samplingMs=%.3f colorsMs=%.3f hintsColdMs=%.3f hintsWarmMs=%.3f meshOffMs=%.3f meshOnMs=%.3f forms=%d quadsOff=%d quadsOn=%d wallProbes=%d wallMs=%.3f %s%n",
                        step,round,seed,sampleNs/1e6,colorsNs/1e6,simpleNs/1e6,warmNs/1e6,meshNs[0]/6e6,meshNs[1]/6e6,
                        simple.forms().size(),quads[0],quads[1],probes,wallNs/1e6,vegetation.diagnostics());
                assertTrue(probes<=PredictionWallEvidence.MAX_PROBES);
                assertTrue(simple.forms().size()<=PredictionSimpleVegetation.MAX_FORMS);
            }
        }
    }

    private static int[] forestColors(ClientColumnSample[] samples,int[] grass,int[] colors,PredictionSimpleVegetation.Result simple) {
        if(simple.forestTints()==null) return colors;
        var result=colors.clone();
        for(int i=0;i<samples.length;i++) if(simple.forestTints()[i]!=grass[i])
            result[i]=PredictionLighting.shade(PredictionMaterialPalette.colorFor(samples[i],simple.forestTints()[i]),
                    samples[i].surfaceY(),63,false,false);
        return result;
    }
}

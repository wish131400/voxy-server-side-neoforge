package dev.xantha.vss.client.prediction;

import com.google.gson.JsonObject;
import java.util.Locale;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, cold sampler comparison; timing is reported, never asserted. */
@EnabledIfSystemProperty(named="vss.testNativeLibrary", matches=".+")
class FallbackSamplingBenchmarkTest {
    @Test void compareFallbackWithNativeOnIdenticalCoordinates() throws Exception {
        JavaPreviewSamplingTest.bootstrap();
        RustWorldgenBackend.load(java.nio.file.Path.of(System.getProperty("vss.testNativeLibrary")));
        var factory=JavaPreviewSamplingTest.class.getDeclaredMethod("sampler",JsonObject.class,long.class);
        factory.setAccessible(true);
        for(long seed:new long[]{0,917}) for(int step:new int[]{4,64}) for(int round=0;round<5;round++) {
            var doc=LithostitchedNativeTest.document();
            doc.add("possible_biomes",new com.google.gson.JsonArray());
            var source=(ClientTerrainSampler)factory.invoke(null,doc,seed);
            ClientColumnSample[][] results=new ClientColumnSample[4][];
            long[] nanos=new long[4];
            // Alternate order; every stage gets a fresh Java sampler/native world.
            for(int order=0;order<4;order++) {
                int mode=(round%2==0)?order:3-order;
                if(mode<2) {
                    var javaSampler=new MinecraftColumnTerrainSampler(source);
                    var out=new ClientColumnSample[64];long start=System.nanoTime();
                    for(int i=0;i<64;i++)out[i]=mode==0?javaSampler.samplePreview(-40+i%8*step,56+i/8*step)
                            :javaSampler.sampleSurface(-40+i%8*step,56+i/8*step);
                    nanos[mode]=System.nanoTime()-start;results[mode]=out;
                } else try(var rust=new RustTerrainSampler(RustWorldgenBackend.create(seed,BiomeManager.obfuscateSeed(seed),doc.toString()),source.profile(),source)) {
                    long start=System.nanoTime();
                    results[mode]=mode==2?rust.sampleDisplayGrid(-40,56,step,8,8,new ClientColumnSample[64])
                            :rust.sampleGrid(-40,56,step,8,8,new ClientColumnSample[64],false);
                    nanos[mode]=System.nanoTime()-start;
                }
            }
            for(var out:results){assertEquals(64,out.length);for(var s:out)assertNotNull(s);}
            int heightDiff=0,waterDiff=0,materialDiff=0,maxHeight=0;
            for(int i=0;i<64;i++) {var j=results[1][i];var r=results[3][i];
                int diff=Math.abs(j.surfaceY()-r.surfaceY());if(diff!=0)heightDiff++;maxHeight=Math.max(maxHeight,diff);
                if(j.fluid()!=r.fluid()||j.fluidY()!=r.fluidY())waterDiff++;
                if(j.topBlockIndex()!=r.topBlockIndex()||j.underBlockIndex()!=r.underBlockIndex()||j.deepBlockIndex()!=r.deepBlockIndex())materialDiff++;
            }
            int[] previewHeightDiff=new int[2],previewMaxHeight=new int[2],previewWaterDiff=new int[2];
            for(int k=0;k<2;k++)for(int i=0;i<64;i++) {
                var preview=results[k==0?0:2][i];var exact=results[1][i];
                int diff=Math.abs(preview.surfaceY()-exact.surfaceY());
                if(diff!=0)previewHeightDiff[k]++;previewMaxHeight[k]=Math.max(previewMaxHeight[k],diff);
                if(preview.fluid()!=exact.fluid()||preview.fluidY()!=exact.fluidY())previewWaterDiff[k]++;
            }
            System.out.printf(Locale.ROOT,"FALLBACK_BENCH seed=%d step=%d round=%d javaPreviewUs=%.3f javaSurfaceUs=%.3f rustDisplayUs=%.3f rustSurfaceUs=%.3f exactHeightDiff=%d exactMaxHeightDiff=%d exactWaterDiff=%d exactMaterialDiff=%d%n",
                    seed,step,round,nanos[0]/64000.0,nanos[1]/64000.0,nanos[2]/64000.0,nanos[3]/64000.0,heightDiff,maxHeight,waterDiff,materialDiff);
            System.out.printf(Locale.ROOT,"FALLBACK_QUALITY seed=%d step=%d round=%d javaPreviewHeightDiff=%d javaPreviewMaxHeight=%d javaPreviewWaterDiff=%d rustDisplayHeightDiff=%d rustDisplayMaxHeight=%d rustDisplayWaterDiff=%d%n",
                    seed,step,round,previewHeightDiff[0],previewMaxHeight[0],previewWaterDiff[0],previewHeightDiff[1],previewMaxHeight[1],previewWaterDiff[1]);
        }
    }
}

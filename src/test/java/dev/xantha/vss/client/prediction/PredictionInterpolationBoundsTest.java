package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

/** Reproduce a mod-shortened interpolation domain with genuinely shortened slice arrays. */
class PredictionInterpolationBoundsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void ftfRequestHonorsActualHeightAcrossNoiseCellsAndRejectedRoofs() throws Exception {
        var c=PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(
                DensityFunctions.interpolated(DensityFunctions.constant(1)),false));
        try(var fast=new FreeTerraForgedExterior(c.generator(),c.random(),c.heights());var scope=fast.request()) {
            assertNotNull(sample(fast,0,72));
            Object request=((ThreadLocal<?>)field(fast,"request")).get();
            Object cursor=((Map<?,?>)field(request,"chunks")).values().iterator().next();
            shorten((NoiseChunk)field(cursor,"work"),18);
            assertNull(sample(fast,4,100),"roof above the actual 80-block ceiling is air");
            assertArrayEquals(c.originalRange(8,0,1,-64,72),sample(fast,8,72));
            assertNull(sample(fast,12,81),"the first excluded block must not select cellCountY");
        }
    }

    @Test void genericBatchAndOrderedReplayHonorShortenedArraysAndAbsoluteProbeY() throws Exception {
        for(var density:List.of(DensityFunctions.interpolated(DensityFunctions.constant(1)),
                PredictionJavaExteriorTest.orderedLayers())) {
            var c=PredictionJavaExteriorTest.context(PredictionJavaExteriorTest.synthetic(density,false));
            var fast=c.fast();
            assertNotNull(fast.sample(0,0,1,-64,72,()->true));
            Object cache=((ThreadLocal<?>)field(fast,"contexts")).get();
            for(Object work:(Object[])field(cache,"work"))if(work!=null) {
                shorten((NoiseChunk)work,18);
                // Production captures these after construction-time Mixins resize the arrays.
                for(Object reset:(List<?>)field(work,"resets")) {
                    var capture=reset.getClass().getDeclaredMethod("capture");capture.setAccessible(true);capture.invoke(reset);
                }
            }
            assertNull(fast.sample(0,1,1,-64,100,()->true));
            assertArrayEquals(c.originalRange(0,2,1,-64,72),fast.sample(0,2,1,-64,72,()->true));
        }
    }

    private static boolean[] sample(FreeTerraForgedExterior fast,int x,int top) {
        return fast.sample(new int[]{x},new int[]{0},1,new int[]{-64},new int[]{top},1,()->true)[0];
    }
    private static Object field(Object owner,String name) throws Exception {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try {Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
            catch(NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void shorten(NoiseChunk work,int cells) throws Exception {
        Field count=NoiseChunk.class.getDeclaredField("cellCountY");count.setAccessible(true);count.setInt(work,cells);
        for(Object interpolator:(List<?>)field(work,"interpolators")) {
            for(String name:List.of("slice0","slice1")) {
                double[][] slices=(double[][])field(interpolator,name);
                for(int z=0;z<slices.length;z++)slices[z]=Arrays.copyOf(slices[z],cells+1);
            }
        }
    }
}

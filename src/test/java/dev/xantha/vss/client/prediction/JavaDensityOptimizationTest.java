package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.world.level.levelgen.*;
import org.junit.jupiter.api.*;

class JavaDensityOptimizationTest {
    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }
    @AfterEach void clear() {
        for (String key : List.of("vss.javaColumnMemo", "vss.javaHeightCache", "vss.javaDensityContext"))
            System.clearProperty(key);
    }

    @Test void rawCacheMarkersNeverEraseVerticalDependencies() {
        var y = DensityFunctions.yClampedGradient(-64, 320, -1, 1);
        for (var root : List.of(y, DensityFunctions.flatCache(y), DensityFunctions.cache2d(y),
                DensityFunctions.cacheOnce(y), DensityFunctions.interpolated(y))) {
            var wrapped = DensityMemo.wrapRoots(root)[0];
            assertFalse(new PredictionRawDensity().inspect(wrapped).horizontal());
            for (int height = -64; height < 320; height += 3) {
                var p = new DensityFunction.SinglePointContext(-19, height, 31);
                assertEquals(Double.doubleToLongBits(root.compute(p)), Double.doubleToLongBits(wrapped.compute(p)));
            }
        }
    }

    @Test void statefulDescendantsCannotBeMemoizedThroughVanillaParents() {
        var mutable = new Stateful();
        var root = DensityFunctions.add(DensityFunctions.constant(2), DensityFunctions.cacheOnce(mutable));
        var wrapped = DensityMemo.wrapRoots(root)[0];
        var p = new DensityFunction.SinglePointContext(-19, 70, 31);
        assertEquals(3, wrapped.compute(p));
        mutable.value = 7;
        assertEquals(9, wrapped.compute(p));
        assertFalse(new PredictionRawDensity().inspect(wrapped).pure());
        var custom = ClientTerrainSampler.custom(1,
                new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),1,-64,384,"noise","minecraft:overworld",0),
                (x,z) -> (int)mutable.value);
        assertEquals(7, custom.surfaceY(1, 2));
        mutable.value = 11;
        assertEquals(11, custom.surfaceY(1, 2));
    }

    @Test void heightCacheHasExactKeysAndBoundedStorage() {
        var cache = new PredictionHeightCache();
        var positions = new ArrayList<int[]>();
        for (int i=0;i<20000;i++) {
            int x = i * 16381, z = -i * 8191, h = i % 384 - 64;
            positions.add(new int[]{x,z,h}); cache.put(x,z,h);
            assertEquals(h,cache.get(x,z));
        }
        for (int[] p : positions) {
            int got=cache.get(p[0],p[1]);
            assertTrue(got==Integer.MIN_VALUE || got==p[2],"collisions must miss, never return another column");
        }
    }

    @Test void noiseScaleLookupSurvivesRenamedRecordAccessors() throws Exception {
        assertEquals(.375, PredictionRawDensity.yScale(new Noise(null, 2, .375)));
        assertEquals(0, PredictionRawDensity.yScale(new ShiftedNoise(null,null,null,2,0,null)));
    }

    // Production Forge records use SRG names, not the Mojmap method yScale().
    private record Noise(Object f_1_, double f_2_, double f_3_) { }
    private record ShiftedNoise(Object f_1_, Object f_2_, Object f_3_, double f_4_, double f_5_, Object f_6_) { }

    @Test void coldBatchLocalityAndOreElisionAreMeasured() throws Exception {
        var doc = document("overworld");
        double[] rowMs=new double[6],batchMs=new double[6];
        for(int round=0;round<7;round++) {
            var row=DensityMemoBenchTest.sampler(doc,42);
            var batch=DensityMemoBenchTest.sampler(doc,42);
            var a=new ClientColumnSample[34*34];var b=a.clone();
            int base=round*2048;
            long rt=0,bt=0;
            for(int arm=0;arm<2;arm++) {
                boolean linear=(arm+round)%2==0;
                long start=System.nanoTime();
                if(linear)for(int z=0;z<34;z++)for(int x=0;x<34;x++)a[z*34+x]=row.sampleSurface(base+x*2,z*2);
                else for(int bz=0;bz<34;bz+=8)for(int bx=0;bx<34;bx+=8)
                    for(int z=bz;z<Math.min(bz+8,34);z++)for(int x=bx;x<Math.min(bx+8,34);x++)b[z*34+x]=batch.sampleSurface(base+x*2,z*2);
                if(linear)rt=System.nanoTime()-start;else bt=System.nanoTime()-start;
            }
            assertArrayEquals(a,b);
            if(round>0){rowMs[round-1]=rt/1e6;batchMs[round-1]=bt/1e6;}
        }
        System.out.printf(Locale.ROOT,"JAVA_BATCH_BENCH points=1156 rowMs=%.3f spatialMs=%.3f%n",median(rowMs),median(batchMs));
        var lookup=net.minecraft.data.registries.VanillaRegistries.createLookup();
        var settings=lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var c=PredictionJavaExteriorTest.context(settings);
        var old=c.fast();
        var noOre=new NoiseGeneratorSettings(settings.noiseSettings(),settings.defaultBlock(),settings.defaultFluid(),
                settings.noiseRouter(),settings.surfaceRule(),settings.spawnTarget(),settings.seaLevel(),
                settings.disableMobGeneration(),settings.isAquifersEnabled(),false,settings.useLegacyRandomSource());
        var fast=new PredictionJavaExterior(new NoiseBasedChunkGenerator(c.generator().getBiomeSource(),
                net.minecraft.core.Holder.direct(noOre)),c.random(),c.heights());
        double[] oldMs=new double[6],newMs=new double[6];
        for(int round=0;round<7;round++) {
            var expected=new boolean[32][];var actual=new boolean[32][];
            long ot=0,nt=0;
            for(int arm=0;arm<2;arm++) {
                boolean original=(arm+round)%2==0;
                long start=System.nanoTime();
                for(int i=0;i<32;i++) (original?expected:actual)[i]=(original?old:fast).sample(
                        1605+i%8*4, -1456+i/8*4, 4, -48, 48, ()->true);
                if(original)ot=System.nanoTime()-start;else nt=System.nanoTime()-start;
            }
            for(int i=0;i<32;i++)assertArrayEquals(expected[i],actual[i]);
            if(round>0){oldMs[round-1]=ot/1e6;newMs[round-1]=nt/1e6;}
        }
        System.out.printf(Locale.ROOT,"JAVA_OCCUPANCY_BENCH footprints=32 oldMs=%.3f newMs=%.3f%n",median(oldMs),median(newMs));
    }

    @Test void horizontalMemoAndColdSurfaceResultsMatchOriginalAcrossDimensions() throws Exception {
        for (String dimension : List.of("overworld", "nether", "end")) {
            var doc = document(dimension);
            compare(dimension, doc);
        }
        String mountain = System.getProperty("vss.mountainDocument");
        if (mountain != null) {
            var doc = JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(mountain))).getAsJsonObject();
            // The standalone fixture has no mod block registry. Preserve its
            // terrain graph while using vanilla material rules for this replay.
            var vanilla = document("overworld");
            doc.getAsJsonObject("settings").add("surface_rule",vanilla.getAsJsonObject("settings").get("surface_rule"));
            doc.add("biome_source", JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            compare("mountain", doc);
        }
    }

    private static JsonObject document(String dimension) throws Exception {
        var doc = LithostitchedNativeTest.document();
        if (!dimension.equals("overworld")) {
            var other = JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                    "tools/rust/vss-native-core/tests/fixtures/worldgen/"+dimension+".json"))).getAsJsonObject();
            for(String k:List.of("settings","density_functions","noises","biome_source")) if(other.has(k)) doc.add(k,other.get(k));
        }
        return doc;
    }

    private static void compare(String label, JsonObject doc) {
        // Cold, disjoint point sets each round. Timed runs alternate order;
        // sampler/graph construction is deliberately outside the measurement.
        System.setProperty("vss.javaDensityContext","off");
        System.setProperty("vss.javaColumnMemo","off");
        System.setProperty("vss.javaHeightCache","off");
        var original = DensityMemoBenchTest.sampler(doc,5052304137288917019L);
        System.setProperty("vss.javaColumnMemo","on");
        var horizontal = DensityMemoBenchTest.sampler(doc,5052304137288917019L);
        System.setProperty("vss.javaDensityContext","on");
        var context = DensityMemoBenchTest.sampler(doc,5052304137288917019L);
        System.setProperty("vss.javaHeightCache","on");
        var optimized = DensityMemoBenchTest.sampler(doc,5052304137288917019L);
        assertTrue(new PredictionRawDensity().inspect(optimized.randomStateContext().router().finalDensity()).pure(),label);
        double[] oldMs=new double[6], columnMs=new double[6], contextMs=new double[6], newMs=new double[6], warmMs=new double[6];
        for (int round=0;round<7;round++) {
            int baseX = 1605 + round * 4096, baseZ=-1456-round*1024;
            ClientColumnSample[][] out = new ClientColumnSample[4][];
            var samplers=List.of(original,horizontal,context,optimized);
            long[] times=new long[4];
            for(int k=0;k<4;k++) {
                int arm=round%2==0?k:3-k;
                long start=System.nanoTime();
                out[arm]=surfaceGrid(samplers.get(arm),baseX,baseZ);
                times[arm]=System.nanoTime()-start;
            }
            assertArrayEquals(out[0],out[1],label+" horizontal memo");
            assertArrayEquals(out[0],out[2],label+" context reuse");
            assertArrayEquals(out[0],out[3],label+" height reuse");
            long start=System.nanoTime();
            assertArrayEquals(out[0],surfaceGrid(optimized,baseX,baseZ));
            long warm=System.nanoTime()-start;
            if(round>0){oldMs[round-1]=times[0]/1e6;columnMs[round-1]=times[1]/1e6;contextMs[round-1]=times[2]/1e6;newMs[round-1]=times[3]/1e6;warmMs[round-1]=warm/1e6;}
        }
        System.out.printf(Locale.ROOT,"JAVA_OPT_BENCH %s points=256 baselineMs=%.3f columnMs=%.3f contextMs=%.3f optimizedMs=%.3f warmMs=%.3f%n",
                label,median(oldMs),median(columnMs),median(contextMs),median(newMs),median(warmMs));
    }

    static ClientColumnSample[] surfaceGrid(ClientTerrainSampler sampler, int x, int z) {
        var result=new ClientColumnSample[256];
        for(int i=0;i<result.length;i++) result[i]=sampler.sampleSurface(x+i%16*2,z+i/16*2);
        return result;
    }
    private static double median(double[] values){Arrays.sort(values);return (values[values.length/2-1]+values[values.length/2])/2;}

    private static final class Stateful implements DensityFunction.SimpleFunction, DensityMemo.NonMemoizable {
        double value=1;
        @Override public double compute(DensityFunction.FunctionContext p){return value;}
        @Override public double minValue(){return -100;}
        @Override public double maxValue(){return 100;}
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec(){throw new UnsupportedOperationException();}
    }
}

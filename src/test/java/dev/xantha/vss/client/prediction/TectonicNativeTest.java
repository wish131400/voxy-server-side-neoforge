package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "vss.tectonicJar", matches = ".+")
class TectonicNativeTest {
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        String library = System.getProperty("vss.testNativeLibrary");
        if (library != null) RustWorldgenBackend.load(Path.of(library));
        else assertTrue(RustTerrainSampler.available());
    }

    @Test void noiseSeedsMatchTheReleasedTectonicMixinAndKeepOriginalParameters() throws Exception {
        var url=Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try(var loader=new URLClassLoader(new java.net.URL[]{url},getClass().getClassLoader())) {
            var remap=loader.loadClass("dev.worldgen.tectonic.mixin.NoisesMixin")
                    .getDeclaredMethod("tectonic$fixTectonicNoiseSeeds",net.minecraft.resources.ResourceLocation.class);
            remap.setAccessible(true);
            var export=Class.forName("dev.xantha.vss.networking.server.session.WorldgenCodecSnapshot")
                    .getDeclaredMethod("noiseSeedAliases",java.util.Set.class,boolean.class);
            export.setAccessible(true);
            var names=java.util.stream.Stream.of("tectonic:parameter/continentalness","tectonic:parameter/erosion",
                    "tectonic:parameter/offset","tectonic:island/continents_a","minecraft:continentalness")
                    .map(net.minecraft.resources.ResourceLocation::parse).toList();
            var aliases=(JsonObject)export.invoke(null,new java.util.HashSet<>(names),true);
            var doc=LithostitchedNativeTest.document();
            doc.add("noise_seed_aliases",aliases);
            for(int i=0;i<names.size();i++) {
                var parameters=new net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters(-6-i,
                        new it.unimi.dsi.fastutil.doubles.DoubleArrayList(new double[]{1,0,0.25+i*0.125}));
                doc.getAsJsonObject("noises").add(names.get(i).toString(),
                        net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE,parameters).getOrThrow());
                var node=new JsonObject();node.addProperty("type","minecraft:noise");node.addProperty("noise",names.get(i).toString());
                node.addProperty("xz_scale",0.13);node.addProperty("y_scale",0.25);
                doc.getAsJsonObject("settings").getAsJsonObject("noise_router").add("seed_probe_"+i,node);
            }
            for(long seed:new long[]{0,731,4200473513645810264L}) {
                long world=RustWorldgenBackend.create(seed,0,doc.toString());
                try {
                    var factory=new net.minecraft.world.level.levelgen.XoroshiroRandomSource(seed).forkPositional();
                    var input=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
                    var output=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<names.size();i++) {
                        var key=(net.minecraft.resources.ResourceLocation)remap.invoke(null,names.get(i));
                        var parameters=net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters.DIRECT_CODEC
                                .parse(JsonOps.INSTANCE,doc.getAsJsonObject("noises").get(names.get(i).toString())).getOrThrow();
                        var expected=net.minecraft.world.level.levelgen.synth.NormalNoise.create(factory.fromHashOf(key),parameters);
                        for(int[] pos:new int[][]{{-186,128,-50},{0,0,0},{777,-17,-919},{10000,256,-10000}}) {
                            input.putInt(0,pos[0]).putInt(4,pos[1]).putInt(8,pos[2]);
                            RustWorldgenBackend.density(world,"seed_probe_"+i,input,output,1);
                            assertEquals(expected.getValue(pos[0]*0.13,pos[1]*0.25,pos[2]*0.13),output.getDouble(0),1e-12,
                                    names.get(i)+" seed="+seed+" pos="+java.util.Arrays.toString(pos));
                        }
                    }
                } finally {RustWorldgenBackend.close(world);}
            }
        }
    }

    @Test @SuppressWarnings("unchecked")
    void releasedInvertCodecRunsInRustWithMatchingIeeeResults() throws Exception {
        var url = Path.of(System.getProperty("vss.tectonicJar")).toUri().toURL();
        try (var loader = new URLClassLoader(new java.net.URL[]{url}, getClass().getClassLoader())) {
            var type = loader.loadClass("dev.worldgen.tectonic.worldgen.densityfunction.Invert");
            Codec<DensityFunction> codec = ((MapCodec<DensityFunction>) type.getField("DATA_CODEC").get(null)).codec();
            var doc = LithostitchedNativeTest.document();
            var router = doc.getAsJsonObject("settings").getAsJsonObject("noise_router");
            var expected = new java.util.LinkedHashMap<String, DensityFunction>();
            var inputs = List.of(DensityFunctions.yClampedGradient(-128, 128, -2, 2),
                    DensityFunctions.constant(0.0), DensityFunctions.constant(-0.0),
                    DensityFunctions.constant(Double.MIN_VALUE), DensityFunctions.constant(-Double.MIN_VALUE),
                    DensityFunctions.constant(0.25), DensityFunctions.constant(-7.0));
            for (int i = 0; i < inputs.size(); i++) {
                var function = (DensityFunction) type.getMethod("create", DensityFunction.class).invoke(null, inputs.get(i));
                var json = codec.encodeStart(JsonOps.INSTANCE, function).getOrThrow().getAsJsonObject();
                json.addProperty("type", "tectonic:invert");
                router.add("invert_" + i, json);
                expected.put("invert_" + i, function);
            }
            var generator = new JsonObject();
            generator.add("settings", doc.get("settings"));
            generator.add("biome_source", doc.get("biome_source"));
            assertNull(PredictionWorldgenCapabilities.nativeTerrainRejection(generator, doc),
                    "supported reciprocal must not force the entire terrain graph to Java");
            long world = RustWorldgenBackend.create(731, 0, doc.toString());
            try {
                var points = ByteBuffer.allocateDirect(385 * 12).order(ByteOrder.LITTLE_ENDIAN);
                var results = ByteBuffer.allocateDirect(385 * 8).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = -192; y <= 192; y++) points.putInt(-17).putInt(y).putInt(256);
                for (var entry : expected.entrySet()) {
                    assertEquals(385, RustWorldgenBackend.density(world, entry.getKey(), points, results, 385));
                    for (int y = -192; y <= 192; y++) {
                        double value = entry.getValue().compute(new DensityFunction.SinglePointContext(-17, y, 256));
                        assertEquals(Double.doubleToRawLongBits(value),
                                Double.doubleToRawLongBits(results.getDouble((y + 192) * 8)), entry.getKey() + " y=" + y);
                    }
                }
            } finally { RustWorldgenBackend.close(world); }
        }
    }
}

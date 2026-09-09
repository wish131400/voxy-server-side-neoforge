package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.*;

/** Direct Minecraft oracle; does not load a save, captured data or any native DLL. */
public final class VanillaNoiseDependencyReference {
    private static final long[] SEEDS={0,1,-1,Long.MIN_VALUE,Long.MAX_VALUE,-6939851821800531130L,693280690516334765L};
    public static void main(String[] args) throws Exception {
        net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/tmp/rust-reference"));
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Path path=Path.of(args[0]); Files.createDirectories(path.getParent());
        var json=new Gson(); int rows=0;
        List<double[]> points=new ArrayList<>(List.of(new double[]{0,0,0},new double[]{-.000001,-64,.999999},
                new double[]{16,63,-16},new double[]{-30000000,319,30000000},new double[]{-16777216,-63.5,16777216}));
        var coords=new Random(91107);
        for(int i=0;i<128;i++) points.add(new double[]{coords.nextDouble()*100000-50000,coords.nextDouble()*384-64,coords.nextDouble()*100000-50000});
        try(var out=Files.newBufferedWriter(path)) {
            for(long seed:SEEDS) for(int kind=0;kind<4;kind++) {
                var random=VanillaKernelReference.random(seed,kind);
                List<Object> values=new ArrayList<>();
                for(int i=0;i<256;i++) {
                    // Test cached pairs, interleaved calls, and reseeding with a pending pair.
                    if(i%13==1) random.setSeed(seed+i);
                    values.add(List.of(VanillaKernelReference.bits(random.nextGaussian()),Long.toString(random.nextLong())));
                }
                out.write(json.toJson(Map.of("type","gaussian","seed",Long.toString(seed),"kind",kind,"values",values)));out.newLine();rows++;
                if(kind>=2) continue;
                var noise=new ImprovedNoise(VanillaKernelReference.random(seed,kind));
                values=new ArrayList<>();
                for(var p:points) {
                    double[] derivative={1.25,-2.5,3.75};
                    double value=noise.noiseWithDerivative(p[0],p[1],p[2],derivative);
                    values.add(List.of(p,VanillaKernelReference.bits(value),Arrays.stream(derivative).mapToObj(VanillaKernelReference::bits).toList()));
                }
                out.write(json.toJson(Map.of("type","derivative","seed",Long.toString(seed),"kind",kind,"values",values)));out.newLine();rows++;
                // The first three are NoiseRouterData's overworld, nether and end presets.
                for(double[] parameters:List.of(new double[]{.25,.125,80,160,8},new double[]{.25,.375,80,60,8},
                        new double[]{.25,.25,80,160,4},new double[]{.001,1000,.001,1000,1})) {
                    for(boolean wired:List.of(false,true)) {
                        RandomSource stream=VanillaKernelReference.random(seed,kind);
                        if(wired && kind==1) stream=stream.forkPositional().fromHashOf("minecraft:terrain");
                        var blended=new BlendedNoise(stream,parameters[0],parameters[1],parameters[2],parameters[3],parameters[4]);
                        values=new ArrayList<>();
                        for(var p:points) {
                            int[] pos={(int)p[0],(int)p[1],(int)p[2]};
                            double value=blended.compute(new DensityFunction.SinglePointContext(pos[0],pos[1],pos[2]));
                            values.add(List.of(pos,VanillaKernelReference.bits(value)));
                        }
                        out.write(json.toJson(Map.of("type","blended","seed",Long.toString(seed),"kind",kind,"wired",wired,
                                "parameters",parameters,"values",values,"min",VanillaKernelReference.bits(blended.minValue()),
                                "max",VanillaKernelReference.bits(blended.maxValue()),"after",Long.toString(stream.nextLong()))));out.newLine();rows++;
                    }
                }
                for(List<Integer> octaves:List.of(List.of(0),List.of(-2,-1,0),List.of(-4,-2),List.of(1),
                        List.of(2,4),List.of(-2,0,3),List.of(0,0,-2))) {
                    var stream=VanillaKernelReference.random(seed,kind);
                    var perlin=new PerlinSimplexNoise(stream,octaves);
                    values=new ArrayList<>();
                    for(var p:points) values.add(List.of(p,VanillaKernelReference.bits(perlin.getValue(p[0],p[2],false)),
                            VanillaKernelReference.bits(perlin.getValue(p[0],p[2],true))));
                    out.write(json.toJson(Map.of("type","perlin_simplex","seed",Long.toString(seed),"kind",kind,
                            "octaves",octaves,"values",values,"after",Long.toString(stream.nextLong()))));out.newLine();rows++;
                }
            }
        }
        System.out.println("Minecraft 1.21.1 noise dependency rows="+rows+" path="+path.toAbsolutePath());
    }
}

package dev.xantha.vss.client.prediction;

import com.google.gson.Gson;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.synth.*;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

/** Calls the mapped Minecraft 1.21.1 implementation, independent of VSS terrain. */
public final class VanillaKernelReference {
    static RandomSource random(long seed, int kind) {
        RandomSource source = kind % 2 == 0 ? new LegacyRandomSource(seed) : new XoroshiroRandomSource(seed);
        return kind < 2 ? source : new WorldgenRandom(source);
    }
    static String bits(double value) { return Long.toUnsignedString(Double.doubleToRawLongBits(value), 16); }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]); Files.createDirectories(path.getParent());
        var json = new Gson(); int rows = 0;
        try (var out = Files.newBufferedWriter(path)) {
            long[] seeds = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, -6939851821800531130L, 693280690516334765L};
            for (long seed : seeds) for (int kind = 0; kind < 4; kind++) {
                var r = random(seed, kind);
                List<Object> sequence = new ArrayList<>();
                int[] bounds = {1, 2, 3, 17, 256, 1073741825, Integer.MAX_VALUE};
                for (int i = 0; i < 256; i++) sequence.add(List.of(r.nextInt(), Long.toString(r.nextLong()),
                        r.nextInt(bounds[i % bounds.length]), bits(r.nextDouble()), Float.floatToRawIntBits(r.nextFloat()), r.nextBoolean()));
                out.write(json.toJson(Map.of("type","random","seed",Long.toString(seed),"kind",kind,"values",sequence))); out.newLine(); rows++;
                var factory = random(seed, kind).forkPositional();
                for (String name : List.of("octave_-7", "minecraft:surface", "草地🌲")) {
                    var child = factory.fromHashOf(name);
                    List<String> values = new ArrayList<>(); for (int i = 0; i < 16; i++) values.add(Long.toString(child.nextLong()));
                    out.write(json.toJson(Map.of("type","hash","seed",Long.toString(seed),"kind",kind,"name",name,"values",values))); out.newLine(); rows++;
                }
                for (int[] pos : List.of(new int[]{-17,-64,23},new int[]{30000000,319,-30000000})) {
                    var child = factory.at(pos[0],pos[1],pos[2]);
                    List<String> values = new ArrayList<>(); for (int i = 0; i < 16; i++) values.add(Long.toString(child.nextLong()));
                    out.write(json.toJson(Map.of("type","position","seed",Long.toString(seed),"kind",kind,"position",pos,"values",values))); out.newLine(); rows++;
                }
                if (kind >= 2) {
                    var wg = (WorldgenRandom) random(seed,kind);
                    long decoration = wg.setDecorationSeed(seed,-272,368);
                    wg.setFeatureSeed(decoration,92,9);
                    long feature = wg.nextLong(); wg.setLargeFeatureSeed(seed,-17,23);
                    long large = wg.nextLong(); wg.setLargeFeatureWithSalt(seed,-17,23,10387312);
                    out.write(json.toJson(Map.of("type","worldgen","seed",Long.toString(seed),"kind",kind,
                            "values",List.of(Long.toString(decoration),Long.toString(feature),Long.toString(large),Long.toString(wg.nextLong()))))); out.newLine(); rows++;
                }
                if (kind >= 2) continue;
                List<double[]> points = new ArrayList<>(List.of(new double[]{0,0,0},new double[]{-0.000001,-64,0.999999},
                        new double[]{16,63,-16},new double[]{-30000000,319,30000000},new double[]{-16777216,-63.5,16777216}));
                var coords = new java.util.Random(89123);
                for (int i=0;i<128;i++) points.add(new double[]{coords.nextDouble()*100000-50000,coords.nextDouble()*384-64,coords.nextDouble()*100000-50000});
                var improved = new ImprovedNoise(random(seed,kind)); var simplex = new SimplexNoise(random(seed,kind));
                List<Object> values = new ArrayList<>();
                for (var p : points) values.add(List.of(p,bits(improved.noise(p[0],p[1],p[2])),
                        bits(improved.noise(p[0],p[1],p[2],.25,.1)),bits(simplex.getValue(p[0],p[2])),bits(simplex.getValue(p[0],p[1],p[2]))));
                out.write(json.toJson(Map.of("type","base_noise","seed",Long.toString(seed),"kind",kind,"values",values))); out.newLine(); rows++;
                for (double[] amps : List.of(new double[]{1},new double[]{1,0,1,.5,0,1},new double[]{0,1,0},new double[]{0,0})) {
                    for (boolean legacy : List.of(false,true)) {
                        int octave=-7;
                        var params=new NormalNoise.NoiseParameters(octave,new DoubleArrayList(amps));
                        var normal=legacy ? NormalNoise.createLegacyNetherBiome(random(seed,kind),params) : NormalNoise.create(random(seed,kind),params);
                        var perlin=legacy ? PerlinNoise.createLegacyForLegacyNetherBiome(random(seed,kind),octave,new DoubleArrayList(amps))
                                : PerlinNoise.create(random(seed,kind),octave,new DoubleArrayList(amps));
                        values=new ArrayList<>();
                        for (var p:points) values.add(List.of(p,bits(perlin.getValue(p[0],p[1],p[2])),bits(normal.getValue(p[0],p[1],p[2])),
                                bits(perlin.getValue(p[0],p[1],p[2],.25,.1,true))));
                        out.write(json.toJson(Map.of("type","octaves","seed",Long.toString(seed),"kind",kind,"first",octave,
                                "amplitudes",amps,"legacy",legacy,"values",values))); out.newLine(); rows++;
                    }
                }
            }
        }
        System.out.println("Minecraft 1.21.1 reference rows="+rows+" path="+path.toAbsolutePath());
    }
}

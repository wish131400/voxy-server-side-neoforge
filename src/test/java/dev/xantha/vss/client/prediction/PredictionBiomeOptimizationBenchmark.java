package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Same-coordinate, alternating A/B of climate, actual biome lookup and region routing. */
@EnabledIfEnvironmentVariable(named="VSS_BIOME_BENCH_OUTPUT", matches=".+")
class PredictionBiomeOptimizationBenchmark {
    private static final int ROUNDS = 40, WARMUP = 20, POINTS = 8192;
    private static final long SEED = 9124665638826140590L;
    private static volatile long consumed;
    private final List<String> csv = new ArrayList<>();

    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }

    @Test void measureClimateAndRoutingOnIdenticalQueries() throws Exception {
        System.out.printf("BIOME_ENV java=%s vm=%s points=%d rounds=%d discard=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"), POINTS, ROUNDS, WARMUP);
        csv.add("stage,round,points,oldMs,newMs,oldCpuMs,newCpuMs");
        try (var context = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(), SEED);
             var optimized = new PredictionClimateSampler(context.randomStateContext().sampler())) {
            Climate.Sampler source = context.randomStateContext().sampler();
            optimized.sampler().sample(1, 2, 3);
            PredictionClimateSamplerTest.awaitCompiled(optimized);
            for (String stage : List.of("climate-grid", "climate-columns", "biome-columns")) {
                var biome = context.biomeSourceContext();
                benchmark(stage, (round, count) -> {
                    Object[] values = new Object[count];
                    for (int i = 0; i < count; i++) {
                        int[] position = position(stage, round, i);
                        values[i] = stage.equals("biome-columns")
                                ? biome.getNoiseBiome(position[0],position[1],position[2],source)
                                : source.sample(position[0],position[1],position[2]);
                    }
                    return values;
                }, (round, count) -> {
                    Object[] values = new Object[count];
                    for (int i = 0; i < count; i++) {
                        int[] position = position(stage, round, i);
                        values[i] = stage.equals("biome-columns")
                                ? biome.getNoiseBiome(position[0],position[1],position[2],optimized.sampler())
                                : optimized.sampler().sample(position[0],position[1],position[2]);
                    }
                    return values;
                });
            }
            System.out.println("BIOME_COMPILER " + optimized.diagnostics());
        }
        String snapshot = System.getProperty("vss.liveSnapshot");
        if (snapshot != null) {
            try (var context = capturedClimate(Path.of(snapshot));
                 var optimized = new PredictionClimateSampler(context.randomStateContext().sampler())) {
                var source = context.randomStateContext().sampler();
                optimized.sampler().sample(1,2,3);
                PredictionClimateSamplerTest.awaitCompiled(optimized);
                for (String stage : List.of("captured-climate-grid","captured-climate-columns"))
                    benchmark(stage,(round,count) -> climate(source,stage,round,count),
                            (round,count) -> climate(optimized.sampler(),stage,round,count));
                System.out.println("CAPTURED_BIOME_COMPILER " + optimized.diagnostics());
            }
        }
        var regions = List.of(new TerrablenderUniqueness.Region(0,10),new TerrablenderUniqueness.Region(1,6),
                new TerrablenderUniqueness.Region(2,3),new TerrablenderUniqueness.Region(3,1));
        var original = TerrablenderUniquenessTest.uncached(SEED,3,regions);
        var cached = TerrablenderUniqueness.build(SEED,3,regions);
        benchmark("region-routing", (round,count) -> route(original,round,count),
                (round,count) -> route(cached,round,count));
        Path output = Path.of(System.getenv("VSS_BIOME_BENCH_OUTPUT"));
        Files.createDirectories(output);
        Files.write(output.resolve("rounds.csv"),csv);
    }

    private static int[] position(String stage, int round, int i) {
        int baseX = round * 1031 - 16000, baseZ = 12000 - round * 547;
        if (stage.endsWith("climate-grid")) return new int[]{baseX+i%128,16,baseZ+i/128};
        int column = i / 32;
        return new int[]{baseX+column%16,i%32-8,baseZ+column/16};
    }

    private static Object[] climate(Climate.Sampler sampler, String stage, int round, int count) {
        Object[] values = new Object[count];
        for (int i=0;i<count;i++) {
            var position = position(stage,round,i);
            values[i] = sampler.sample(position[0],position[1],position[2]);
        }
        return values;
    }

    private static ClientTerrainSampler capturedClimate(Path directory) throws Exception {
        JavaDensityCompilationBenchmark.registerCapturedInvert();
        var document = com.google.gson.JsonParser.parseString(Files.readString(
                directory.resolve("minecraft_overworld-generator.json"))).getAsJsonObject();
        var registry = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("registries.json")))
                .getAsJsonObject();
        var settingsJson = document.getAsJsonObject("settings");
        var router = settingsJson.getAsJsonObject("noise_router");
        // Replay only captured climate roots; unrelated terrain/material mods are not part of this benchmark.
        for (String key : new ArrayList<>(router.keySet()))
            if (!List.of("temperature","vegetation","continents","erosion","depth","ridges").contains(key))
                router.addProperty(key,0.0);
        settingsJson.add("surface_rule",LithostitchedNativeTest.document().getAsJsonObject("settings").get("surface_rule"));
        var definitions = registry.getAsJsonObject("density_functions");
        var reachable = new com.google.gson.JsonObject();
        var pending = new ArrayList<com.google.gson.JsonElement>(); pending.add(router);
        for (int i=0;i<pending.size();i++) {
            var value=pending.get(i);
            if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> pending.add(entry.getValue()));
            else if (value.isJsonArray()) value.getAsJsonArray().forEach(pending::add);
            else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String key = value.getAsString();
                if (definitions.has(key) && !reachable.has(key)) {
                    reachable.add(key,definitions.get(key)); pending.add(definitions.get(key));
                }
            }
        }
        var input = new com.google.gson.JsonObject();
        input.add("density_functions",reachable); input.add("noises",registry.get("noises"));
        var registries = ClientWorldgenRegistries.decode(input,net.minecraft.core.RegistryAccess.EMPTY);
        var settings = net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DIRECT_CODEC.parse(
                registries.ops(),settingsJson).getOrThrow();
        try (var vanilla = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(),SEED)) {
            var access = vanilla.decorationAccess();
            var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                    vanilla.biomeSourceContext(),net.minecraft.core.Holder.direct(settings));
            var random = net.minecraft.world.level.levelgen.RandomState.create(settings,registries.noiseLookup(),SEED);
            var noise = settings.noiseSettings();
            var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),SEED,
                    noise.minY(),noise.height(),"noise","minecraft:overworld",0);
            System.out.printf("CAPTURED_CLIMATE definitions=%d retained=%d fullModpackRouting=false%n",definitions.size(),reachable.size());
            return new ClientTerrainSampler(SEED,profile,generator,random,
                    net.minecraft.world.level.LevelHeightAccessor.create(noise.minY(),noise.height()),
                    settings.seaLevel(),null,access);
        }
    }

    private static Object[] route(TerrablenderUniqueness.Tree tree, int round, int count) {
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            int[] position = position("columns",round,i);
            values[i] = tree.get(position[0],position[2]);
        }
        return values;
    }

    @FunctionalInterface private interface Query { Object[] run(int round, int count); }

    private void benchmark(String stage, Query original, Query candidate) {
        double[] oldMs = new double[ROUNDS-WARMUP], newMs = oldMs.clone();
        var bean = ManagementFactory.getThreadMXBean();
        for (int round = 0; round < ROUNDS; round++) {
            Object[] oldValues, newValues;
            double beforeMs, afterMs, beforeCpu, afterCpu;
            if (round % 2 == 0) {
                long cpu = bean.getCurrentThreadCpuTime(), start = System.nanoTime();
                oldValues = original.run(round, POINTS);
                beforeMs = (System.nanoTime()-start)/1e6; beforeCpu = (bean.getCurrentThreadCpuTime()-cpu)/1e6;
                cpu = bean.getCurrentThreadCpuTime(); start = System.nanoTime();
                newValues = candidate.run(round, POINTS);
                afterMs = (System.nanoTime()-start)/1e6; afterCpu = (bean.getCurrentThreadCpuTime()-cpu)/1e6;
            } else {
                long cpu = bean.getCurrentThreadCpuTime(), start = System.nanoTime();
                newValues = candidate.run(round, POINTS);
                afterMs = (System.nanoTime()-start)/1e6; afterCpu = (bean.getCurrentThreadCpuTime()-cpu)/1e6;
                cpu = bean.getCurrentThreadCpuTime(); start = System.nanoTime();
                oldValues = original.run(round, POINTS);
                beforeMs = (System.nanoTime()-start)/1e6; beforeCpu = (bean.getCurrentThreadCpuTime()-cpu)/1e6;
            }
            assertArrayEquals(oldValues,newValues,stage + " round=" + round);
            consumed = Arrays.hashCode(newValues);
            csv.add(String.format(Locale.ROOT,"%s,%d,%d,%.6f,%.6f,%.6f,%.6f",
                    stage,round,POINTS,beforeMs,afterMs,beforeCpu,afterCpu));
            if (round >= WARMUP) { oldMs[round-WARMUP]=beforeMs; newMs[round-WARMUP]=afterMs; }
        }
        double baseline = median(oldMs), optimized = median(newMs);
        System.out.printf(Locale.ROOT,"BIOME_BENCH %s oldMs=%.3f newMs=%.3f reduction=%.2f%% speedup=%.3f exact=true%n",
                stage,baseline,optimized,100*(baseline-optimized)/baseline,baseline/optimized);
    }

    private static double median(double[] values) {
        var sorted=values.clone(); Arrays.sort(sorted);
        return (sorted[sorted.length/2-1]+sorted[sorted.length/2])/2;
    }
}

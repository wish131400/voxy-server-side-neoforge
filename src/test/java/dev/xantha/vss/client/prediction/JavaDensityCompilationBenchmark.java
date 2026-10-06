package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in same-JVM A/B of the actual production sampler with compilation disabled/enabled. */
@EnabledIfEnvironmentVariable(named="VSS_JIT_BENCH_OUTPUT",matches=".+")
class JavaDensityCompilationBenchmark {
    private static volatile long consumed;
    private static final Field FINAL = field("finalDensity"), INITIAL = field("initialDensity");
    private static final long SEED = 5052304137288917019L;
    private static final int WARMUP = 24, ROUNDS = 44;
    private static final int BATCHES = 4;
    private final List<String> csv = new ArrayList<>();

    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }

    @AfterEach void saveRounds() throws Exception {
        System.clearProperty("vss.javaDensityCompiler");
        if (csv.isEmpty()) return;
        Path destination = Path.of(System.getenv("VSS_JIT_BENCH_OUTPUT"));
        Files.createDirectories(destination);
        csv.add(0,"scene,stage,round,points,oldMs,newMs,oldCpuMs,newCpuMs,oldAllocBytes,newAllocBytes");
        Files.write(destination.resolve("rounds.csv"),csv);
    }

    @Test void measureCurrentMemoAgainstSpecializedBytecode() throws Exception {
        System.out.printf("JIT_ENV java=%s vm=%s arch=%s heapMiB=%d%n",System.getProperty("java.version"),
                System.getProperty("java.vm.name"),System.getProperty("os.arch"),Runtime.getRuntime().maxMemory()/1048576);
        boolean serverOnly="true".equals(System.getenv("VSS_JIT_SERVER_ONLY"));
        if (!serverOnly) for (String dimension : List.of("overworld","nether","end")) compare(dimension,document(dimension));
        String mountain = System.getProperty("vss.mountainDocument");
        if (mountain != null && !serverOnly) {
            JsonObject doc = JsonParser.parseString(Files.readString(Path.of(mountain))).getAsJsonObject();
            doc.getAsJsonObject("settings").add("surface_rule",document("overworld").getAsJsonObject("settings").get("surface_rule"));
            doc.add("biome_source",JsonParser.parseString("{\"type\":\"minecraft:fixed\",\"biome\":\"minecraft:plains\"}"));
            compare("mountain",doc);
        }
        String snapshot = System.getProperty("vss.liveSnapshot");
        if (snapshot != null) {
            registerCapturedInvert();
            Path input=Path.of(snapshot);
            JsonObject doc=JsonParser.parseString(Files.readString(input.resolve("minecraft_overworld-generator.json"))).getAsJsonObject();
            JsonObject registries=JsonParser.parseString(Files.readString(input.resolve("registries.json"))).getAsJsonObject();
            for (String key : List.of("noises","density_functions")) doc.add(key,registries.get(key));
            doc.getAsJsonObject("settings").add("surface_rule",document("overworld").getAsJsonObject("settings").get("surface_rule"));
            var all=doc.getAsJsonObject("density_functions");
            var reachable=reachableDefinitions(doc.get("settings"),all);
            doc.add("density_functions",reachable);
            System.out.printf("JIT_FIXTURE server allDefinitions=%d reachableDefinitions=%d%n",all.size(),reachable.size());
            compare("server",doc,true,9124665638826140590L);
        }
    }

    private void compare(String label, JsonObject doc) throws Exception {
        compare(label,doc,false,SEED);
    }

    private void compare(String label, JsonObject doc, boolean captured, long seed) throws Exception {
        LongFunction<ClientTerrainSampler> factory = captured ? value -> capturedSampler(doc,value)
                : value -> DensityMemoBenchTest.sampler(doc,value);
        System.setProperty("vss.javaDensityCompiler", "off");
        ClientTerrainSampler original = factory.apply(seed);
        System.clearProperty("vss.javaDensityCompiler");
        ClientTerrainSampler candidate = factory.apply(seed);
        candidate.surfaceY(19,35);
        var compilation = DensityGraphCompilerTest.compilation(candidate);
        DensityGraphCompilerTest.awaitReady(compilation);
        var metrics = compilation.metrics();
        System.out.printf(Locale.ROOT,
                "JIT_BUILD %s totalMs=%.3f analyzeMs=%.3f emitMs=%.3f defineMs=%.3f bytecodeBytes=%d nodes=%d specialized=%d memoSlots=%d%n",
                label,metrics.buildMs(),metrics.analyzeMs(),metrics.emitMs(),metrics.defineMs(),metrics.bytecodeBytes(),
                metrics.nodes(),metrics.specialized(),metrics.memoSlots());
        DensityFunction[] baseline = {(DensityFunction)FINAL.get(original),(DensityFunction)INITIAL.get(original)};
        DensityFunction[] generated = {(DensityFunction)FINAL.get(candidate),(DensityFunction)INITIAL.get(candidate)};
        System.setProperty("vss.javaDensityCompiler", "off");
        measureTemplateBinding(label,factory,seed,DensityGraphCompiler.compile(baseline));
        // Raw density, height-only march and complete surface records have different bottlenecks.
        benchmark(label,"density",original,candidate,baseline,generated,4096);
        benchmark(label,"height",original,candidate,baseline,generated,1024);
        if (!label.equals("nether")) benchmark(label,"surface",original,candidate,baseline,generated,256);
        candidate.close(); original.close();
    }

    private void measureTemplateBinding(String label, LongFunction<ClientTerrainSampler> factory, long baseSeed,
                                        DensityGraphCompiler.Compiled template) throws Exception {
        double[] reuseMs = new double[12], freshMs = new double[12];
        for (int i=0;i<12;i++) {
            long seed = baseSeed+i*104729L;
            var sampler = factory.apply(seed);
            var oracle = factory.apply(seed);
            var roots = new DensityFunction[]{(DensityFunction)FINAL.get(sampler),(DensityFunction)INITIAL.get(sampler)};
            DensityGraphCompiler.Compiled reused, fresh;
            if (i%2 == 0) { reused=DensityGraphCompiler.compile(roots); fresh=DensityGraphCompiler.compileUncached(roots); }
            else { fresh=DensityGraphCompiler.compileUncached(roots); reused=DensityGraphCompiler.compile(roots); }
            assertTrue(template.sharesCodeWith(reused));
            assertFalse(template.sharesCodeWith(fresh));
            reuseMs[i] = reused.metrics().buildMs(); freshMs[i] = fresh.metrics().buildMs();
            for (int p=0;p<32;p++) {
                var context = new DensityFunction.SinglePointContext(71+p*3,64+p%8*16,217-p*7);
                // Compare against a separate sampler so no candidate reads a value produced by its oracle.
                assertEquals(Double.doubleToRawLongBits(((DensityFunction)FINAL.get(oracle)).compute(context)),
                        Double.doubleToRawLongBits(reused.compute(0,context)),label+" rebind seed="+seed);
                assertEquals(Double.doubleToRawLongBits(((DensityFunction)INITIAL.get(oracle)).compute(context)),
                        Double.doubleToRawLongBits(reused.compute(1,context)),label+" rebind initial seed="+seed);
            }
            sampler.close(); oracle.close();
        }
        System.out.printf(Locale.ROOT,"JIT_REUSE %s rounds=12 freshBuildMedianMs=%.3f reuseBuildMedianMs=%.3f sameGeneratedClass=true seedBindingIdentical=true%n",
                label,median(freshMs),median(reuseMs));
    }
    private record CapturedInvert(DensityFunction input, double minValue, double maxValue) implements DensityFunction {
        static final com.mojang.serialization.MapCodec<CapturedInvert> CODEC = DensityFunction.HOLDER_HELPER_CODEC
                .fieldOf("argument").xmap(CapturedInvert::create,CapturedInvert::input);
        static CapturedInvert create(DensityFunction input) {
            double min=input.minValue(), max=input.maxValue();
            return new CapturedInvert(input,min<0 && max>0 ? Double.NEGATIVE_INFINITY:min,
                    min<0 && max>0 ? Double.POSITIVE_INFINITY:max);
        }
        @Override public double compute(FunctionContext context) { return 1.0/input.compute(context); }
        @Override public void fillArray(double[] values, ContextProvider context) {
            input.fillArray(values,context);
            for (int i=0;i<values.length;i++) values[i]=1.0/values[i];
        }
        @Override public DensityFunction mapAll(Visitor visitor) { return create(input.mapAll(visitor)); }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return net.minecraft.util.KeyDispatchDataCodec.of(CODEC);
        }
    }
    static void registerCapturedInvert() throws Exception {
        // Exact raw evaluator/layout verified from Tectonic 3.0.17; remains opaque to compilation.
        var registry=net.minecraft.core.registries.BuiltInRegistries.DENSITY_FUNCTION_TYPE;
        var key=net.minecraft.resources.ResourceLocation.parse("tectonic:invert");
        if (registry.containsKey(key)) return;
        Field frozen=net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen"); frozen.setAccessible(true);
        boolean previous=frozen.getBoolean(registry); frozen.setBoolean(registry,false);
        try { net.minecraft.core.Registry.register(registry,key,CapturedInvert.CODEC); }
        finally { frozen.setBoolean(registry,previous); }
    }

    private void benchmark(String label, String stage, ClientTerrainSampler original, ClientTerrainSampler candidate,
                           DensityFunction[] baseline, DensityFunction[] generated, int points) {
        double[] oldMs = new double[ROUNDS-WARMUP], newMs = oldMs.clone();
        double[] oldCpu = oldMs.clone(), newCpu = oldMs.clone(), oldAlloc = oldMs.clone(), newAlloc = oldMs.clone();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
        for (int round=0;round<ROUNDS;round++) {
            int x = 1605+round*4096, z = -1456-round*2048;
            Object[] results = new Object[2];
            long[] wall = new long[2], cpu = new long[2], allocation = new long[2];
            for (int arm=0;arm<2;arm++) {
                int k = (arm+round)%2;
                long id = Thread.currentThread().getId();
                long allocated = threads.getThreadAllocatedBytes(id), cpuStart = threads.getCurrentThreadCpuTime();
                long start = System.nanoTime();
                Object[] batches = new Object[BATCHES];
                for (int batch=0;batch<BATCHES;batch++) batches[batch] = run(stage,k==0?original:candidate,
                        k==0?baseline:generated,x+batch*1024,z-batch*1024,points);
                results[k] = batches;
                wall[k] = System.nanoTime()-start;
                cpu[k] = threads.getCurrentThreadCpuTime()-cpuStart;
                allocation[k] = threads.getThreadAllocatedBytes(id)-allocated;
            }
            for (int batch=0;batch<BATCHES;batch++) {
                Object a=((Object[])results[0])[batch], b=((Object[])results[1])[batch];
                if (a instanceof long[] values) assertArrayEquals(values,(long[])b,label+" "+stage+" density bits");
                else if (a instanceof int[] values) assertArrayEquals(values,(int[])b,label+" "+stage+" heights");
                else assertArrayEquals((Object[])a,(Object[])b,label+" "+stage+" complete samples");
            }
            if (round>=WARMUP) {
                int i=round-WARMUP;
                oldMs[i]=wall[0]/1e6; newMs[i]=wall[1]/1e6;
                oldCpu[i]=cpu[0]/1e6; newCpu[i]=cpu[1]/1e6;
                oldAlloc[i]=allocation[0]; newAlloc[i]=allocation[1];
            }
            csv.add(String.format(Locale.ROOT,"%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d",
                    label,stage,round,points*BATCHES,wall[0]/1e6,wall[1]/1e6,cpu[0]/1e6,cpu[1]/1e6,allocation[0],allocation[1]));
        }
        System.out.printf(Locale.ROOT,
                "JIT_AB %s stage=%s points=%d rounds=%d warmup=%d oldMs=%.3f newMs=%.3f reductionPct=%.2f oldCpuMs=%.3f newCpuMs=%.3f oldAllocBytes=%.0f newAllocBytes=%.0f identical=true%n",
                label,stage,points*BATCHES,ROUNDS,WARMUP,median(oldMs),median(newMs),100*(1-median(newMs)/median(oldMs)),
                median(oldCpu),median(newCpu),median(oldAlloc),median(newAlloc));
    }

    private Object run(String stage, ClientTerrainSampler sampler, DensityFunction[] roots, int x, int z, int points) {
        if (stage.equals("density")) {
            long[] values = new long[points*2]; long checksum=0;
            for (int i=0;i<points;i++) {
                // Repeated Y queries per X/Z exercise the existing horizontal reuse.
                var context = new DensityFunction.SinglePointContext(x+i/64%8*2,-64+i%64*6,z+i/512*2);
                values[i*2]=Double.doubleToRawLongBits(roots[0].compute(context));
                values[i*2+1]=Double.doubleToRawLongBits(roots[1].compute(context)); checksum^=values[i*2];
            }
            consumed=checksum; return values;
        }
        if (stage.equals("height")) {
            int[] values = new int[points]; long checksum=0;
            for (int i=0;i<points;i++) { values[i]=sampler.densitySurfaceY(x+i%32*2,z+i/32*2); checksum+=values[i]; }
            consumed=checksum; return values;
        }
        ClientColumnSample[] values = new ClientColumnSample[points]; long checksum=0;
        for (int i=0;i<points;i++) { values[i]=sampler.sampleSurface(x+i%16*2,z+i/16*2); checksum+=values[i].surfaceY(); }
        consumed=checksum; return values;
    }

    private static JsonObject document(String dimension) throws Exception {
        JsonObject doc = LithostitchedNativeTest.document();
        if (!dimension.equals("overworld")) {
            var other=JsonParser.parseString(Files.readString(Path.of("tools/rust/vss-native-core/tests/fixtures/worldgen/"+dimension+".json"))).getAsJsonObject();
            for (String key : List.of("settings","density_functions","noises","biome_source")) if(other.has(key)) doc.add(key,other.get(key));
        }
        return doc;
    }
    private static ClientTerrainSampler capturedSampler(JsonObject doc, long seed) {
        try {
            var input = new JsonObject();
            for (String key : List.of("noises","density_functions")) input.add(key,doc.get(key));
            var registries = ClientWorldgenRegistries.decode(input,net.minecraft.core.RegistryAccess.EMPTY);
            var settings=net.minecraft.world.level.levelgen.NoiseGeneratorSettings.DIRECT_CODEC.parse(registries.ops(),doc.get("settings")).getOrThrow();
            Field accessField=DensityMemoBenchTest.class.getDeclaredField("access"); accessField.setAccessible(true);
            var access=(net.minecraft.core.RegistryAccess)accessField.get(null);
            var plains=access.registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            var generator=new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                    new net.minecraft.world.level.biome.FixedBiomeSource(plains),net.minecraft.core.Holder.direct(settings));
            var random=net.minecraft.world.level.levelgen.RandomState.create(settings,registries.noiseLookup(),seed);
            var noise=settings.noiseSettings();
            var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),seed,noise.minY(),noise.height(),"noise","minecraft:overworld",0);
            return new ClientTerrainSampler(seed,profile,generator,random,
                    net.minecraft.world.level.LevelHeightAccessor.create(noise.minY(),noise.height()),settings.seaLevel(),null,access);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static JsonObject reachableDefinitions(com.google.gson.JsonElement root, JsonObject definitions) {
        var pending=new ArrayList<com.google.gson.JsonElement>(); pending.add(root);
        var result=new JsonObject();
        for (int i=0;i<pending.size();i++) {
            var value=pending.get(i);
            if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> pending.add(entry.getValue()));
            else if (value.isJsonArray()) value.getAsJsonArray().forEach(pending::add);
            else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String id=value.getAsString();
                if (definitions.has(id) && !result.has(id)) { result.add(id,definitions.get(id)); pending.add(definitions.get(id)); }
            }
        }
        return result;
    }
    private static double median(double[] values) { double[] sorted=values.clone(); Arrays.sort(sorted); return (sorted[sorted.length/2-1]+sorted[sorted.length/2])/2; }
    private static Field field(String name) {
        try { Field f=ClientTerrainSampler.class.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}

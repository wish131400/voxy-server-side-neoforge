package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import io.netty.buffer.Unpooled;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;

/** Full-runtime experiment; patching, hashes and cold contexts are managed outside release builds. */
public final class PredictionGenerationComparison {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<Map<String,Object>> ROWS=new ArrayList<>();
    private static final Map<Integer,String> ORACLES=new HashMap<>();
    public static void run(Path output) throws Exception {
        ComparisonBootstrap.loadColors();
        if(!RustTerrainSampler.available())throw new AssertionError("Native backend absent");
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(output.resolve("worldgen-profile.bin"))));
        WorldgenProfileS2CPayload payload;
        try {payload=WorldgenProfileS2CPayload.decode(buffer);}finally{buffer.release();}
        JsonObject registriesJson=JsonParser.parseString(new String(LodByteCompression.decompress(payload.registries(),
                payload.registriesCompression(),payload.registriesRawSize(),WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        registriesJson.add("structure_templates",JsonParser.parseString(Files.readString(output.resolve("structure-templates.json"))));
        var profile=payload.dimensions().stream().filter(p->p.levelKey().equals(Level.OVERWORLD)).findFirst().orElseThrow();
        JsonObject generator=JsonParser.parseString(new String(LodByteCompression.decompress(profile.generatorData(),
                profile.generatorCompression(),profile.generatorRawSize(),WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        int rounds=Integer.getInteger("vss.experiment.rounds",2),warmups=Integer.getInteger("vss.experiment.warmups",1);
        int side=Integer.getInteger("vss.experiment.side",8),step=4,span=side*16,grid=span/step+2;
        if(side!=8 && side!=16)throw new IllegalArgumentException("Side must be 8 or 16 chunks");
        String[] modes=System.getProperty("vss.experiment.modes").split(",");
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("java",System.getProperty("java.version"));metadata.put("processors",Runtime.getRuntime().availableProcessors());
        metadata.put("seed",payload.seed());metadata.put("sideChunks",side);metadata.put("rounds",rounds);metadata.put("warmups",warmups);
        metadata.put("modes",modes);metadata.put("verifyBiomes",DecorationExperimentSettings.verifyBiomes);
        metadata.put("scope","NeoForge runtime, production snapshot plus opt-in patches, no rendering; timers exclude hashing and context setup");
        Files.writeString(output.resolve("experiment-environment.json"),JSON.toJson(metadata));
        for(String mode:modes) {
            DecorationExperimentSettings.select(mode);
            var registries=ClientWorldgenRegistries.decode(registriesJson,RegistryAccess.EMPTY);registries.bindWorldSeed(payload.seed());
            var context=ClientWorldgenProfileDecoder.decodeJavaSampler(profile,generator,registries,registries.access());
            if(context==null)throw new AssertionError("Java context absent");
            JsonObject document=RustWorldgenDocument.snapshot(generator,registriesJson,context);
            document.addProperty("vss_experiment_scratch_pool",mode.contains("scratch"));
            document.addProperty("vss_experiment_single_fast",mode.endsWith("single"));
            document.addProperty("vss_experiment_raw_workspace",mode.contains("raw"));
            for(String name:List.of("grass","foliage")) {
                var image=ComparisonBootstrap.image(name);var colors=new JsonArray(65536);
                for(int value:image.getRGB(0,0,256,256,null,0,256))colors.add(value&0xffffff);
                document.add(name+"_colormap",colors);
            }
            long handle=RustWorldgenBackend.create(profile.seed(),BiomeManager.obfuscateSeed(profile.seed()),document.toString());
            try(var sampler=new RustTerrainSampler(handle,profile,context)) {
                sampler.experimentPrepare();
                var vegetation=new PredictionVegetation(sampler,null,true);
                if(!vegetation.available())throw new AssertionError("Vegetation unavailable");
                for(int round=-warmups;round<rounds;round++) {
                    int cx=8192+round*512,cz=-8192-round*512;
                    int bx=cx*16,bz=cz*16;
                    DecorationExperimentSettings.reset();
                    long gc=gcMillis(),allocation=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long cpu=os.getProcessCpuTime(),threadCpu=threads.getCurrentThreadCpuTime(),start=System.nanoTime();
                    var values=new ClientColumnSample[grid*grid];
                    for(int gz=0;gz<grid;gz+=8)for(int gx=0;gx<grid;gx+=8)
                        sampler.sampleDisplayGridInPlace(bx+(gx-1)*step,bz+(gz-1)*step,step,
                                Math.min(8,grid-gx),Math.min(8,grid-gz),values,gz*grid+gx,grid);
                    long terrainEnd=System.nanoTime();
                    var plants=vegetation.tileForRendering(bx,bz,span,step,true,p->false,()->true);
                    long decorationEnd=System.nanoTime();
                    var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,span),Math.floorDiv(bz,span),2);
                    var tile=PredictionVoxySource.build(key,values,grid,step,sampler,plants,null,true);
                    long end=System.nanoTime(),cpuEnd=os.getProcessCpuTime(),threadEnd=threads.getCurrentThreadCpuTime();
                    long allocationEnd=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    String hash=fingerprint(plants,tile);
                    boolean match=ORACLES.containsKey(round)&&ORACLES.get(round).equals(hash);
                    if(mode.equals("baseline")) {ORACLES.put(round,hash);match=true;}
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("mode",mode);row.put("round",round);row.put("warmup",round<0);row.put("chunks",side*side);
                    row.put("wallMs",(end-start)/1e6);row.put("terrainMs",(terrainEnd-start)/1e6);
                    row.put("decorationMs",(decorationEnd-terrainEnd)/1e6);row.put("encodingMs",(end-decorationEnd)/1e6);
                    row.put("processCpuMs",(cpuEnd-cpu)/1e6);row.put("threadCpuMs",(threadEnd-threadCpu)/1e6);
                    row.put("javaThreadAllocatedMiB",(allocationEnd-allocation)/1048576.0);row.put("heapUsedMiB",heap/1048576.0);
                    row.put("gcMs",gcMillis()-gc);row.put("plantBlocks",plants.blocks().size());row.put("featureBoxes",tile.features().size());
                    row.put("surfaceCalls",DecorationExperimentSettings.surfaceCalls);row.put("surfacePoints",DecorationExperimentSettings.surfacePoints);
                    row.put("biomeQueries",DecorationExperimentSettings.biomeQueries);row.put("biomeCalls",DecorationExperimentSettings.biomeCalls);
                    row.put("biomeCacheHits",DecorationExperimentSettings.biomeCacheHits);row.put("biomeVerified",DecorationExperimentSettings.biomeVerified);
                    row.put("scans",DecorationExperimentSettings.scans);row.put("hash",hash);row.put("matchesBaseline",match);
                    ROWS.add(row);Files.writeString(output.resolve("results.json"),JSON.toJson(ROWS));
                    Files.writeString(output.resolve("diagnostics-"+mode+"-"+round+".txt"),sampler.diagnostics()+"\n"+vegetation.diagnostics());
                    System.out.println("VSS_DECORATION_EXPERIMENT "+new Gson().toJson(row));
                }
            }
            // Release discarded contexts between variants. Outside all measurement windows.
            System.gc();
        }
        Files.writeString(output.resolve("prediction-complete.txt"),"success\n");
    }
    private static long gcMillis() {return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    private static String fingerprint(PredictionVegetation.Tile plants,PredictionVoxySource.Tile tile)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var data=new DataOutputStream(new BufferedOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest)))) {
            var entries=new ArrayList<>(plants.blocks().entrySet());entries.sort(Comparator.comparingLong(e->e.getKey().asLong()));
            data.writeInt(entries.size());
            for(var e:entries){data.writeLong(e.getKey().asLong());data.writeInt(Block.BLOCK_STATE_REGISTRY.getId(e.getValue()));}
            for(var c:tile.columns()) {
                data.writeUTF(c.biome());data.writeInt(c.runs().size());
                for(var r:c.runs()){data.writeInt(r.bottom());data.writeInt(r.top());data.writeInt(Block.BLOCK_STATE_REGISTRY.getId(r.state()));}
            }
            var boxes=new ArrayList<>(tile.features());
            boxes.sort(Comparator.comparingInt(PredictionVoxySource.Box::minX).thenComparingInt(PredictionVoxySource.Box::minY)
                    .thenComparingInt(PredictionVoxySource.Box::minZ).thenComparingInt(PredictionVoxySource.Box::maxX)
                    .thenComparingInt(PredictionVoxySource.Box::maxY).thenComparingInt(PredictionVoxySource.Box::maxZ)
                    .thenComparingInt(b->Block.BLOCK_STATE_REGISTRY.getId(b.state())));
            data.writeInt(boxes.size());for(var b:boxes){data.writeInt(b.minX());data.writeInt(b.minY());data.writeInt(b.minZ());
                data.writeInt(b.maxX());data.writeInt(b.maxY());data.writeInt(b.maxZ());data.writeInt(Block.BLOCK_STATE_REGISTRY.getId(b.state()));}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

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

/** Complete generation-to-Voxy input comparison; no production switches or renderer. */
public final class PredictionGenerationComparison {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<Map<String,Object>> ROWS=new ArrayList<>();
    private record Run(String mode,RustTerrainSampler sampler,PredictionVegetation vegetation,ClientTerrainSampler context) {}
    public static void run(Path output)throws Exception {
        ComparisonBootstrap.loadColors();
        ExperimentChecks.run(output);
        if(!RustTerrainSampler.available())throw new AssertionError("Native backend unavailable");
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(output.resolve("worldgen-profile.bin"))));
        WorldgenProfileS2CPayload payload;
        try{payload=WorldgenProfileS2CPayload.decode(buffer);}finally{buffer.release();}
        var registriesJson=JsonParser.parseString(new String(LodByteCompression.decompress(payload.registries(),payload.registriesCompression(),payload.registriesRawSize(),WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        registriesJson.add("structure_templates",JsonParser.parseString(Files.readString(output.resolve("structure-templates.json"))));
        var profile=payload.dimensions().stream().filter(p->p.levelKey().equals(Level.OVERWORLD)).findFirst().orElseThrow();
        var generator=JsonParser.parseString(new String(LodByteCompression.decompress(profile.generatorData(),profile.generatorCompression(),profile.generatorRawSize(),WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        int rounds=Integer.getInteger("vss.experiment.rounds",5),warmups=Integer.getInteger("vss.experiment.warmups",2);
        int side=Integer.getInteger("vss.experiment.side",16);
        int[] steps=Arrays.stream(System.getProperty("vss.lod.steps","4").split(",")).mapToInt(Integer::parseInt).toArray();
        String[] modes=System.getProperty("vss.experiment.modes","baseline,span,demand,span_demand").split(",");
        if(Arrays.stream(modes).filter("baseline"::equals).count()!=1)throw new IllegalArgumentException("Exactly one baseline is required");
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("java",System.getProperty("java.version"));metadata.put("processors",Runtime.getRuntime().availableProcessors());metadata.put("seed",profile.seed());metadata.put("sideChunks",side);metadata.put("steps",steps);metadata.put("rounds",rounds);metadata.put("warmups",warmups);metadata.put("modes",modes);
        metadata.put("horizontalClimateEnabledAndBound",true);
        metadata.put("scope","Production snapshot; display terrain -> ordered decoration -> exact spans/blocks -> Voxy input; no rendering, disk or scheduler; hashing and initialization excluded");
        Files.writeString(output.resolve("experiment-environment.json"),JSON.toJson(metadata));
        var contexts=new ArrayList<Run>();
        try{
            for(String mode:modes){
                LodExperiment.select(mode);
                DecorationExperimentSettings.select(mode);
                System.setProperty("vss.horizontalClimate","on");
                var registries=ClientWorldgenRegistries.decode(registriesJson,RegistryAccess.EMPTY);registries.bindWorldSeed(payload.seed());
                var context=ClientWorldgenProfileDecoder.decodeJavaSampler(profile,generator,registries,registries.access());
                if(context==null)throw new AssertionError("Java worldgen context absent");
                var document=RustWorldgenDocument.snapshot(generator,registriesJson,context);
                document.addProperty("vss_horizontal_climate",true);
                document.addProperty("vss_dry_air",true);
                document.addProperty("vss_aquifer_nearby",true);
                for(String name:List.of("grass","foliage")){
                    var image=ComparisonBootstrap.image(name);var colors=new JsonArray(65536);
                    for(int value:image.getRGB(0,0,256,256,null,0,256))colors.add(value&0xffffff);
                    document.add(name+"_colormap",colors);
                }
                long handle=RustWorldgenBackend.create(profile.seed(),BiomeManager.obfuscateSeed(profile.seed()),document.toString());
                var sampler=new RustTerrainSampler(handle,profile,context);
                context.bindHorizontalClimate(handle,document);
                Files.writeString(output.resolve("climate-initialization-"+mode+".txt"),context.climateCompilerDiagnostics()+"\n"+RustWorldgenBackend.horizontalStats(handle));
                if(contexts.isEmpty())ExperimentChecks.lifecycle(sampler,context);
                LodExperiment.select(mode);
                var vegetation=new PredictionVegetation(sampler,null,true);
                if(!vegetation.available())throw new AssertionError("Decoration unavailable");
                contexts.add(new Run(mode,sampler,vegetation,context));
            }
            for(int round=-warmups;round<rounds;round++)for(int step:steps){
                var order=new ArrayList<>(contexts);
                if((round&1)!=0)Collections.reverse(order.subList(1,order.size()));
                var group=new ArrayList<Map<String,Object>>();
                for(var run:order){
                    LodExperiment.select(run.mode);LodExperiment.reset();
                    DecorationExperimentSettings.select(run.mode);DecorationExperimentSettings.reset();
                    // Oracle recording from older experiments is baseline-only work. Disable it
                    // uniformly; the complete output comparison below supplies our oracle.
                    FeatureHotspotProbe.beginRound("measure",round);
                    int cx=8192+round*512+(step==4?0:step*1024),cz=-8192-round*512;
                    int span=side*16,grid=span/step+2,bx=cx*16,bz=cz*16;
                    String climateBefore=run.context.climateCompilerDiagnostics();
                    long gc=gcMillis(),allocation=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    long cpu=threads.getCurrentThreadCpuTime(),start=System.nanoTime();
                    var values=new ClientColumnSample[grid*grid];
                    for(int gz=0;gz<grid;gz+=8)for(int gx=0;gx<grid;gx+=8)
                        run.sampler.sampleDisplayGridInPlace(bx+(gx-1)*step,bz+(gz-1)*step,step,Math.min(8,grid-gx),Math.min(8,grid-gz),values,gz*grid+gx,grid);
                    long terrainEnd=System.nanoTime();
                    // Match the manager: complete world-coordinate plants, independent of terrain spacing.
                    var plants=run.vegetation.tileForRendering(bx,bz,span,1,true,p->false,()->true);
                    long decorationEnd=System.nanoTime();
                    var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,span),Math.floorDiv(bz,span),2);
                    var tile=PredictionVoxySource.build(key,values,grid,step,run.sampler,plants,null,true);
                    long end=System.nanoTime(),cpuEnd=threads.getCurrentThreadCpuTime();
                    long allocationEnd=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    String climateAfter=run.context.climateCompilerDiagnostics();
                    long shared=counter(climateAfter,"horizontalCopied")-counter(climateBefore,"horizontalCopied");
                    if(shared<=0)throw new AssertionError("Horizontal climate sharing did not execute: "+climateAfter);
                    FeatureHotspotProbe.save(output,run.mode,round);
                    long gcEnd=gcMillis();
                    var blockHash=fingerprint(plants,tile);
                    long mappingStarted=System.nanoTime();
                    var mappedHash=MappedChecks.fingerprint(tile);
                    double mappingMs=(System.nanoTime()-mappingStarted)/1e6;
                    var hash=blockHash+":"+mappedHash;
                    var row=new LinkedHashMap<String,Object>();row.put("mode",run.mode);row.put("round",round);row.put("warmup",round<0);row.put("step",step);row.put("chunks",side*side);
                    row.put("wallMs",(end-start)/1e6);row.put("terrainMs",(terrainEnd-start)/1e6);row.put("decorationMs",(decorationEnd-terrainEnd)/1e6);row.put("encodingMs",(end-decorationEnd)/1e6);
                    row.put("threadCpuMs",(cpuEnd-cpu)/1e6);row.put("javaThreadAllocatedMiB",(allocationEnd-allocation)/1048576.0);row.put("gcMs",gcEnd-gc);
                    row.put("plantBlocks",plants.blocks().size());row.put("featureBoxes",tile.features().size());row.put("plantBytes",plants.bytes());row.put("counts",LodExperiment.stats());row.put("hash",hash);
                    row.put("surfacePoints",DecorationExperimentSettings.surfacePoints);row.put("surfaceCalls",DecorationExperimentSettings.surfaceCalls);row.put("biomeQueries",DecorationExperimentSettings.biomeQueries);
                    row.put("mappingValidationMs",mappingMs);
                    row.put("horizontalCopiedDelta",shared);
                    row.put("horizontalBatchesDelta",counter(climateAfter,"horizontalBatches")-counter(climateBefore,"horizontalBatches"));
                    ROWS.add(row);group.add(row);Files.writeString(output.resolve("results.json"),JSON.toJson(ROWS));
                    Files.writeString(output.resolve("diagnostics-"+run.mode+"-"+round+"-"+step+".txt"),run.sampler.diagnostics()+"\n"+run.vegetation.diagnostics()+"\n"+climateAfter+"\n"+RustWorldgenBackend.horizontalStats(run.sampler.handle()));
                    System.out.println("VSS_LOD_DECORATION "+new Gson().toJson(row));
                }
                var expected=group.stream().filter(r->r.get("mode").equals("baseline")).findFirst().orElseThrow().get("hash");
                boolean valid=true;
                for(var row:group){boolean same=expected.equals(row.get("hash"));row.put("matchesBaseline",same);valid&=same;}
                Files.writeString(output.resolve("results.json"),JSON.toJson(ROWS));
                if(!valid)throw new AssertionError("Output mismatch round="+round+" step="+step);
                System.gc();
            }
        }finally{for(var run:contexts)run.sampler.close();}
        Files.writeString(output.resolve("prediction-complete.txt"),"success\n");
    }
    private static long counter(String diagnostics,String name){
        var matcher=java.util.regex.Pattern.compile("(?:^|,)"+name+"=(\\d+)").matcher(diagnostics);
        if(!matcher.find())throw new AssertionError("Missing climate counter "+name+": "+diagnostics);
        return Long.parseLong(matcher.group(1));
    }
    private static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    private static String fingerprint(PredictionVegetation.Tile plants,PredictionVoxySource.Tile tile)throws Exception{
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new BufferedOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest)))){
            var entries=new ArrayList<>(plants.blocks().entrySet());entries.sort(Comparator.comparingLong(e->e.getKey().asLong()));
            out.writeInt(entries.size());for(var e:entries){out.writeLong(e.getKey().asLong());out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(e.getValue()));}
            for(var c:tile.columns()){out.writeUTF(c.biome());out.writeInt(c.runs().size());for(var r:c.runs()){out.writeInt(r.bottom());out.writeInt(r.top());out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(r.state()));}}
            // Box packing differs by design. Compare the exact expanded BlockState footprint.
            var blocks=new TreeMap<Long,Integer>();
            for(var b:tile.features())for(int x=b.minX();x<b.maxX();x++)for(int z=b.minZ();z<b.maxZ();z++)for(int y=b.minY();y<b.maxY();y++)
                blocks.put(net.minecraft.core.BlockPos.asLong(x,y,z),Block.BLOCK_STATE_REGISTRY.getId(b.state()));
            out.writeInt(blocks.size());for(var e:blocks.entrySet()){out.writeLong(e.getKey());out.writeInt(e.getValue());}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

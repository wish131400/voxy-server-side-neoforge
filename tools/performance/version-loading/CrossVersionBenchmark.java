package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.server.session.WorldgenProfileHolder;
import dev.xantha.vss.networking.server.session.WorldgenTemplateCatalog;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;

/** Opt-in full prediction generation benchmark; never included in a release. */
public final class CrossVersionBenchmark {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static void run(MinecraftServer server) {
        Path out=Path.of(System.getProperty("vss.bench.output"));
        try {
            Files.writeString(out.resolve("runtime-environment.json"),JSON.toJson(Map.of(
                    "javaVersion",System.getProperty("java.version"),"javaVm",System.getProperty("java.vm.name"),
                    "processors",Runtime.getRuntime().availableProcessors(),"os",System.getProperty("os.name"))));
            var payload=WorldgenProfileHolder.payloadFor(server,1,Level.OVERWORLD);
            var registryRoot=JsonParser.parseString(new String(LodByteCompression.decompress(payload.registries(),payload.registriesCompression(),payload.registriesRawSize(),WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
            var templates=new JsonObject();
            for(var entry:WorldgenTemplateCatalog.snapshot(server.getResourceManager()).entries().entrySet())
                templates.addProperty(entry.getKey().toString(),Base64.getEncoder().encodeToString(entry.getValue().read()));
            registryRoot.add("structure_templates",templates);
            var profile=payload.dimensions().stream().filter(p->p.levelKey().equals(Level.OVERWORLD)).findFirst().orElseThrow();
            var generator=JsonParser.parseString(new String(LodByteCompression.decompress(profile.generatorData(),profile.generatorCompression(),profile.generatorRawSize(),WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
            Files.writeString(out.resolve("registries.json"),registryRoot.toString());
            Files.writeString(out.resolve("generator.json"),generator.toString());
            if(!RustTerrainSampler.available())throw new AssertionError("Native unavailable");
            System.setProperty("vss.horizontalClimate","on");
            // A live client supplies synced clocks/timelines too. EMPTY is not
            // a valid stand-in for 26.x's dimension decoding environment.
            var registries=ClientWorldgenRegistries.decode(registryRoot,server.registryAccess());
            registries.bindWorldSeed(payload.seed());
            var context=ClientWorldgenProfileDecoder.decodeJavaSampler(profile,generator,registries,registries.access());
            if(context==null)throw new AssertionError("No Java context");
            var document=RustWorldgenDocument.snapshot(generator,registryRoot,context);
            document.addProperty("vss_horizontal_climate",true);
            document.addProperty("vss_dry_air",true);
            document.addProperty("vss_aquifer_nearby",true);
            for(String name:List.of("grass","foliage")) {
                String path="/assets/minecraft/textures/colormap/"+name+".png";
                java.awt.image.BufferedImage image;
                try(var input=CrossVersionBenchmark.class.getResourceAsStream(path)) {
                    if(input!=null)image=javax.imageio.ImageIO.read(input);
                    else try(var zip=new java.util.zip.ZipFile(System.getProperty("vss.bench.resources"));var stream=zip.getInputStream(zip.getEntry(path.substring(1)))){image=javax.imageio.ImageIO.read(stream);}
                }
                int[] colors=image.getRGB(0,0,256,256,null,0,256);
                if(name.equals("grass"))net.minecraft.world.level.GrassColor.init(colors);else net.minecraft.world.level.FoliageColor.init(colors);
                var array=new JsonArray(65536);for(int color:colors)array.add(color&0xffffff);document.add(name+"_colormap",array);
            }
            Files.writeString(out.resolve("native-document.json"),document.toString());
            long handle=RustWorldgenBackend.create(profile.seed(),BiomeManager.obfuscateSeed(profile.seed()),document.toString());
            try(var sampler=new RustTerrainSampler(handle,profile,context)) {
                context.bindHorizontalClimate(handle,document);
                var vegetation=new PredictionVegetation(sampler,null,true);
                if(!vegetation.available())throw new AssertionError("Decoration unavailable");
                prepareGpu(sampler,out);
                var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);
                int side=Integer.getInteger("vss.bench.side",16),step=Integer.getInteger("vss.bench.step",4);
                int warmups=Integer.getInteger("vss.bench.warmups",2),rounds=Integer.getInteger("vss.bench.rounds",5);
                var rows=new ArrayList<Map<String,Object>>();
                for(int round=-warmups;round<rounds;round++) {
                    int cx=8192+round*512,cz=-8192-round*512,span=side*16,grid=span/step+2,bx=cx*16,bz=cz*16;
                    String before=context.climateCompilerDiagnostics();
                    long allocated=threads.getThreadAllocatedBytes(Thread.currentThread().getId()),cpu=threads.getCurrentThreadCpuTime(),gc=gcMillis(),start=System.nanoTime();
                    var samples=new ClientColumnSample[grid*grid];
                    display(sampler,bx-step,bz-step,step,grid,samples);
                    long terrainEnd=System.nanoTime();
                    var plants=vegetation.tileForRendering(bx,bz,span,1,true,p->false,()->true);
                    long decorationEnd=System.nanoTime();
                    var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,span),Math.floorDiv(bz,span),2);
                    var tile=PredictionVoxySource.build(key,samples,grid,step,sampler,plants,null,true);
                    long end=System.nanoTime(),cpuEnd=threads.getCurrentThreadCpuTime(),allocatedEnd=threads.getThreadAllocatedBytes(Thread.currentThread().getId());
                    long copied=counter(context.climateCompilerDiagnostics(),"horizontalCopied")-counter(before,"horizontalCopied");
                    var row=new LinkedHashMap<String,Object>();
                    row.put("round",round);row.put("warmup",round<0);row.put("chunks",side*side);row.put("step",step);
                    row.put("wallMs",(end-start)/1e6);row.put("terrainMs",(terrainEnd-start)/1e6);row.put("decorationMs",(decorationEnd-terrainEnd)/1e6);row.put("encodingMs",(end-decorationEnd)/1e6);
                    row.put("threadCpuMs",(cpuEnd-cpu)/1e6);row.put("allocatedMiB",(allocatedEnd-allocated)/1048576.0);row.put("gcMs",gcMillis()-gc);
                    row.put("plantBlocks",plants.blocks().size());row.put("featureBoxes",tile.features().size());row.put("horizontalCopied",copied);
                    row.put("rustHorizontal",JsonParser.parseString(RustWorldgenBackend.horizontalStats(handle)));
                    row.put("hash",fingerprint(plants,tile));row.put("vegetation",vegetation.performanceSnapshot());row.put("display",displayStats(sampler));
                    rows.add(row);Files.writeString(out.resolve("results.json"),JSON.toJson(rows));
                    Files.writeString(out.resolve("diagnostics-"+round+".txt"),sampler.diagnostics()+"\n"+vegetation.diagnostics()+"\n"+context.climateCompilerDiagnostics()+"\n"+RustWorldgenBackend.horizontalStats(handle));
                    if(plants.blocks().isEmpty())throw new AssertionError("No decoration output");
                    System.out.println("VSS_VERSION_LOADING "+new Gson().toJson(row));System.gc();
                }
            }
            Files.writeString(out.resolve("complete.txt"),"success\n");
        }catch(Throwable failure){failure.printStackTrace();try{Files.writeString(out.resolve("failure.txt"),failure.toString());}catch(Exception ignored){}}
        finally{server.halt(false);}
    }
    // Replaced only for 26.3, whose production manager has a region-level GPU entry.
    private static void display(RustTerrainSampler sampler,int x,int z,int step,int grid,ClientColumnSample[] samples) {
        for(int dz=0;dz<grid;dz+=8)for(int dx=0;dx<grid;dx+=8)
            sampler.sampleDisplayGridInPlace(x+dx*step,z+dz*step,step,Math.min(8,grid-dx),Math.min(8,grid-dz),samples,dz*grid+dx,grid);
    }
    private static Object displayStats(RustTerrainSampler sampler){return Map.of();}
    private static void prepareGpu(RustTerrainSampler sampler,Path output)throws Exception{}
    private static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    private static long counter(String text,String name){var m=java.util.regex.Pattern.compile("(?:^|,)"+name+"=(\\d+)").matcher(text);if(!m.find())throw new AssertionError(text);return Long.parseLong(m.group(1));}
    private static String fingerprint(PredictionVegetation.Tile plants,PredictionVoxySource.Tile tile)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),hash))){
            var blocks=new ArrayList<>(plants.blocks().entrySet());blocks.sort(Comparator.comparingLong(e->e.getKey().asLong()));
            out.writeInt(blocks.size());for(var e:blocks){out.writeLong(e.getKey().asLong());out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(e.getValue()));}
            for(var column:tile.columns()){out.writeUTF(column.biome());for(var run:column.runs()){out.writeInt(run.bottom());out.writeInt(run.top());out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(run.state()));}}
            for(var b:tile.features()){out.writeInt(b.minX());out.writeInt(b.minY());out.writeInt(b.minZ());out.writeInt(b.maxX());out.writeInt(b.maxY());out.writeInt(b.maxZ());out.writeInt(Block.BLOCK_STATE_REGISTRY.getId(b.state()));}
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}

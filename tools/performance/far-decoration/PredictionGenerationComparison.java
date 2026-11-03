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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;

/** Actual production snapshot -> native terrain -> decoration -> Voxy CPU input experiment. */
public final class PredictionGenerationComparison {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static final List<Map<String,Object>> ROWS=new ArrayList<>();
    record Run(String mode,RustTerrainSampler sampler,PredictionVegetation vegetation,ClientTerrainSampler context,FarDecorationPrototype far) {}
    record Output(PredictionVoxySource.Tile tile,FarDecorationPrototype.Result approximation,Map<String,Object> row) {}
    public static void run(Path out)throws Exception {
        ComparisonBootstrap.loadColors();
        if(!RustTerrainSampler.available())throw new AssertionError("Native backend unavailable");
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(Files.readAllBytes(out.resolve("worldgen-profile.bin"))));
        WorldgenProfileS2CPayload payload;
        try{payload=WorldgenProfileS2CPayload.decode(buffer);}finally{buffer.release();}
        var registriesJson=JsonParser.parseString(new String(LodByteCompression.decompress(payload.registries(),payload.registriesCompression(),payload.registriesRawSize(),WorldgenProfileS2CPayload.MAX_REGISTRIES_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        registriesJson.add("structure_templates",JsonParser.parseString(Files.readString(out.resolve("structure-templates.json"))));
        var profile=payload.dimensions().stream().filter(p->p.levelKey().equals(Level.OVERWORLD)).findFirst().orElseThrow();
        var generator=JsonParser.parseString(new String(LodByteCompression.decompress(profile.generatorData(),profile.generatorCompression(),profile.generatorRawSize(),WorldgenProfileS2CPayload.MAX_GENERATOR_RAW_BYTES),StandardCharsets.UTF_8)).getAsJsonObject();
        int rounds=Integer.getInteger("vss.experiment.rounds",5),warmups=Integer.getInteger("vss.experiment.warmups",2),side=Integer.getInteger("vss.experiment.side",16);
        int[] steps=Arrays.stream(System.getProperty("vss.lod.steps","4").split(",")).mapToInt(Integer::parseInt).toArray();
        var modes=System.getProperty("vss.experiment.modes","baseline,scatter_full,scatter_lod").split(",");
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();threads.setThreadAllocatedMemoryEnabled(true);threads.setThreadCpuTimeEnabled(true);
        Files.writeString(out.resolve("environment.json"),JSON.toJson(Map.of("seed",profile.seed(),"java",System.getProperty("java.version"),"processors",Runtime.getRuntime().availableProcessors(),"sideChunks",side,"steps",steps,"rounds",rounds,"warmups",warmups,"scope","Native display terrain + existing structure/unsupported feature fallback + approximate scatter + Voxy CPU encoding. No renderer/disk/scheduler. Appearance is intentionally different.")));
        var runs=new ArrayList<Run>();
        try {
            for(String mode:modes) {
                FarDecorationPrototype.active=null;
                long initStart=System.nanoTime();
                var registries=ClientWorldgenRegistries.decode(registriesJson,RegistryAccess.EMPTY);registries.bindWorldSeed(payload.seed());
                var context=ClientWorldgenProfileDecoder.decodeJavaSampler(profile,generator,registries,registries.access());
                if(context==null)throw new AssertionError("Missing Java context");
                var document=RustWorldgenDocument.snapshot(generator,registriesJson,context);
                for(String name:List.of("grass","foliage")) {
                    var image=ComparisonBootstrap.image(name);var colors=new JsonArray(65536);
                    for(int value:image.getRGB(0,0,256,256,null,0,256))colors.add(value&0xffffff);
                    document.add(name+"_colormap",colors);
                }
                long handle=RustWorldgenBackend.create(profile.seed(),BiomeManager.obfuscateSeed(profile.seed()),document.toString());
                var sampler=new RustTerrainSampler(handle,profile,context);
                context.bindHorizontalClimate(handle,document);
                var vegetation=new PredictionVegetation(sampler,null,true);
                if(!vegetation.available())throw new AssertionError("Decoration unavailable");
                long scatterStart=System.nanoTime(),allocation=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
                var far=mode.equals("baseline")?null:new FarDecorationPrototype(sampler,mode.startsWith("proxy_"),mode.equals("proxy_coarse")?8:1);
                var init=new LinkedHashMap<String,Object>();
                init.put("contextAndCompilerMs",(System.nanoTime()-initStart)/1e6);
                init.put("scatterCompilerMs",(System.nanoTime()-scatterStart)/1e6);
                init.put("scatterCompilerJavaMiB",(threads.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocation)/1048576.0);
                if(far!=null)init.put("coverage",far.initialization());
                Files.writeString(out.resolve("initialization-"+mode+".json"),JSON.toJson(init));
                runs.add(new Run(mode,sampler,vegetation,context,far));
            }
            for(int round=-warmups;round<rounds;round++)for(int step:steps) {
                var order=new ArrayList<>(runs);
                if((round&1)!=0)Collections.reverse(order);
                int cx=8192+round*512+(step==4?0:step*1024),cz=-8192-round*512;
                var outputs=new LinkedHashMap<String,Output>();
                for(var run:order) {
                    var output=measure(run,cx*16,cz*16,side*16,step,threads,round);
                    outputs.put(run.mode,output);
                    ROWS.add(output.row);
                    Files.writeString(out.resolve("results.json"),JSON.toJson(ROWS));
                    Files.writeString(out.resolve("diagnostics-"+run.mode+"-"+round+"-"+step+".txt"),run.sampler.diagnostics()+"\n"+run.vegetation.diagnostics()+"\n"+run.context.climateCompilerDiagnostics());
                    System.out.println("VSS_FAR_DECORATION "+new Gson().toJson(output.row));
                }
                var baseline=outputs.get("baseline");
                if(baseline==null)throw new AssertionError("baseline required");
                for(var output:outputs.values()) {
                    if(!terrainHash(baseline.tile).equals(terrainHash(output.tile)))throw new AssertionError("Terrain changed");
                    output.row.put("terrainMatchesBaseline",true);
                    output.row.put("qualityAtStep",quality(baseline.tile,output.tile,step));
                    // Actual Voxy buildSection conversion, with all non-empty result hashes retained.
                    output.row.put("mappedHash",MappedChecks.fingerprint(output.tile));
                }
                Files.writeString(out.resolve("results.json"),JSON.toJson(ROWS));
                System.gc();
            }
            stabilityChecks(runs,out,side*16);
        } finally { FarDecorationPrototype.active=null;for(var run:runs)run.sampler.close(); }
        Files.writeString(out.resolve("prediction-complete.txt"),"success\n");
    }

    static Output measure(Run run,int bx,int bz,int span,int step,com.sun.management.ThreadMXBean threads,int round)throws Exception {
        FarDecorationPrototype.active=run.far;
        if(run.far!=null)run.far.proxyColumns.clear();
        FarProbe.reset();
        int grid=span/step+2;
        long bytes=threads.getThreadAllocatedBytes(Thread.currentThread().threadId()),cpu=threads.getCurrentThreadCpuTime(),gc=gcMillis(),start=System.nanoTime();
        var values=new ClientColumnSample[grid*grid];
        for(int gz=0;gz<grid;gz+=8)for(int gx=0;gx<grid;gx+=8)
            run.sampler.sampleDisplayGridInPlace(bx+(gx-1)*step,bz+(gz-1)*step,step,Math.min(8,grid-gx),Math.min(8,grid-gz),values,gz*grid+gx,grid);
        long terrainEnd=System.nanoTime();
        var plants=run.vegetation.tileForRendering(bx,bz,span,1,true,p->false,()->true);
        long fallbackEnd=System.nanoTime();
        var approximation=run.far==null?null:run.far.generate(bx,bz,span,step,!run.mode.equals("scatter_full"));
        long decorationEnd=System.nanoTime();
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,Math.floorDiv(bx,span),Math.floorDiv(bz,span),2);
        var tile=PredictionVoxySource.build(key,values,grid,step,run.sampler,plants,null,true);
        if(approximation!=null)tile=merge(tile,approximation);
        long end=System.nanoTime(),cpuEnd=threads.getCurrentThreadCpuTime(),bytesEnd=threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var row=new LinkedHashMap<String,Object>();
        row.put("mode",run.mode);row.put("round",round);row.put("warmup",round<0);row.put("step",step);row.put("chunks",span*span/256);row.put("baseX",bx);row.put("baseZ",bz);
        row.put("wallMs",(end-start)/1e6);row.put("terrainMs",(terrainEnd-start)/1e6);row.put("fallbackDecorationMs",(fallbackEnd-terrainEnd)/1e6);row.put("scatterMs",(decorationEnd-fallbackEnd)/1e6);row.put("decorationMs",(decorationEnd-terrainEnd)/1e6);row.put("encodingMs",(end-decorationEnd)/1e6);
        row.put("threadCpuMs",(cpuEnd-cpu)/1e6);row.put("javaThreadAllocatedMiB",(bytesEnd-bytes)/1048576.0);row.put("gcMs",gcMillis()-gc);
        row.put("exactFallbackBlocks",plants.blocks().size());row.put("featureBoxes",tile.features().size());row.put("tileBytesEstimate",tile.bytes());row.put("counts",FarProbe.stats());
        if(run.far!=null)row.put("scatterCounts",run.far.stats());
        if(run.far!=null)row.put("coarseEnvironmentColumns",run.far.proxyColumns.size());
        if(approximation!=null) {
            var deltas=new ArrayList<Integer>();
            for(var site:approximation.anchors()) {
                int x=site.x()-bx,z=site.z()-bz;
                if(x>=0&&z>=0&&x<span&&z<span) deltas.add(Math.abs(site.y()-values[(Math.floorDiv(z,step)+1)*grid+Math.floorDiv(x,step)+1].surfaceY()));
            }
            Collections.sort(deltas);
            if(!deltas.isEmpty())row.put("anchorVsRenderedCellHeight",Map.of("count",deltas.size(),"median",deltas.get(deltas.size()/2),"p95",deltas.get((deltas.size()-1)*95/100),"max",deltas.getLast()));
        }
        row.put("geometryHash",geometryHash(tile));row.put("terrainHash",terrainHash(tile));
        return new Output(tile,approximation,row);
    }

    static PredictionVoxySource.Tile merge(PredictionVoxySource.Tile tile,FarDecorationPrototype.Result approximate) {
        var boxes=new PredictionVoxyBoxes.Builder();
        // Exact structure/fallback edits win over the appearance layer.
        for(var b:approximate.boxes())boxes.add(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ(),b.state());
        boxes.addRange(tile.features(),0,tile.features().size());
        return new PredictionVoxySource.Tile(tile.key(),tile.baseX(),tile.baseZ(),tile.span(),tile.axis(),tile.step(),tile.columns(),boxes.build(),tile.terrainBytes(),true,0);
    }
    static String terrainHash(PredictionVoxySource.Tile tile)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            for(var c:tile.columns()){out.writeUTF(c.biome());for(var r:c.runs()){out.writeInt(r.bottom());out.writeInt(r.top());out.writeInt(Block.getId(r.state()));}}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String geometryHash(PredictionVoxySource.Tile tile)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest))) {
            for(var b:tile.features()){out.writeInt(b.minX());out.writeInt(b.minY());out.writeInt(b.minZ());out.writeInt(b.maxX());out.writeInt(b.maxY());out.writeInt(b.maxZ());out.writeInt(Block.getId(b.state()));}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Map<Long,Integer> voxels(PredictionVoxySource.Tile tile,int step,boolean woody) {
        var map=new HashMap<Long,Integer>();
        for(var b:tile.features()) {
            if(woody && !b.state().is(BlockTags.LOGS) && !b.state().is(BlockTags.LEAVES))continue;
            for(int z=Math.floorDiv(b.minZ(),step);z<=Math.floorDiv(b.maxZ()-1,step);z++)
                for(int x=Math.floorDiv(b.minX(),step);x<=Math.floorDiv(b.maxX()-1,step);x++)
                    for(int y=Math.floorDiv(b.minY(),step);y<=Math.floorDiv(b.maxY()-1,step);y++)
                        map.put(net.minecraft.core.BlockPos.asLong(x,y,z),Block.getId(b.state()));
        }
        return map;
    }
    static Map<String,Object> quality(PredictionVoxySource.Tile baseline,PredictionVoxySource.Tile candidate,int step) {
        var a=voxels(baseline,step,false);var b=voxels(candidate,step,false);
        // Apply all feature materials before measuring visible woody occupancy.
        // Filtering first incorrectly counts leaves later overwritten by bamboo/other features.
        for(var map:List.of(a,b))map.entrySet().removeIf(e->{var state=Block.stateById(e.getValue());return !state.is(BlockTags.LOGS)&&!state.is(BlockTags.LEAVES);});
        long intersection=b.keySet().stream().filter(a::containsKey).count();
        long materials=b.entrySet().stream().filter(e->e.getValue().equals(a.get(e.getKey()))).count();
        return Map.of("baselineWoodyVoxels",a.size(),"candidateWoodyVoxels",b.size(),"intersection",intersection,
                "sameState",materials,"intersectionOverUnion",a.isEmpty()&&b.isEmpty()?1.0:intersection/(double)Math.max(1,a.size()+b.size()-intersection),
                "candidateToBaselineVolume",a.isEmpty()&&b.isEmpty()?1.0:b.size()/(double)Math.max(1,a.size()));
    }
    static void stabilityChecks(List<Run> runs,Path out,int span)throws Exception {
        var checks=new ArrayList<Map<String,Object>>();
        for(var run:runs)if(run.far!=null) {
            FarDecorationPrototype.active=run.far;
            int bx=-256,bz=-512;
            var a=run.far.generate(bx,bz,span,4,true);
            var b=run.far.generate(bx,bz,span,4,true);
            if(!a.equals(b))throw new AssertionError("Stationary regeneration changed");
            var c=run.far.generate(bx,bz,span,8,true);
            var ids=new HashMap<Long,String>();
            for(var v:a.anchors())ids.put(v.id(),v.x()+","+v.y()+","+v.z());
            for(var v:c.anchors())if(!Objects.equals(ids.get(v.id()),v.x()+","+v.y()+","+v.z()))throw new AssertionError("LOD changed anchor");
            var shifted=run.far.generate(bx+span/2,bz,span,4,true);
            var common=new HashMap<Long,String>();
            for(var v:a.anchors())if(v.x()>=bx+span/2 && v.x()<bx+span)common.put(v.id(),v.x()+","+v.y()+","+v.z());
            var shiftedIds=new HashMap<Long,String>();
            for(var v:shifted.anchors())shiftedIds.put(v.id(),v.x()+","+v.y()+","+v.z());
            for(var e:common.entrySet())if(!Objects.equals(e.getValue(),shiftedIds.get(e.getKey())))throw new AssertionError("Moving window changed/missed anchor");
            checks.add(Map.of("mode",run.mode,"stationaryEqual",true,"lodAnchorStable",true,"negativeCoordinateOverlapStable",true,"checkedAnchors",ids.size(),"overlapAnchors",common.size()));
        }
        Files.writeString(out.resolve("stability-checks.json"),JSON.toJson(checks));
    }
    static long gcMillis(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
}

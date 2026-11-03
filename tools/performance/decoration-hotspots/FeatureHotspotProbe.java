package dev.xantha.vss.client.prediction;

import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Isolated, single-generation-thread attribution; never included in release source sets. */
final class FeatureHotspotProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Cost> COSTS = new LinkedHashMap<>();
    private static final Set<Long> EXACT = new HashSet<>();
    private static Scope current;
    private static long repeatedPoints;
    private static long heightCalls,heightPoints,heightProved,heightNanos;
    private static final Map<Integer,Set<String>> NO_WRITE_ORACLE = new HashMap<>(), BIOME_ORACLE = new HashMap<>();
    private static String mode;
    private static int round;
    private static long oracleSkipped;

    private static final class Cost {
        String id, type, modifiers;
        long calls, nanos, writes, noWriteCalls, noWriteNanos, exactCalls, exactPoints, exactNanos;
        long noWriteExactNanos, biomeCalls, biomeNanos, biomeLoads, biomeLoadNanos, displayCalls, displayNanos;
        long javaCalls, nativeCalls, treeCalls;
        long biomeChecks, biomePassed;
    }
    static final class Scope implements AutoCloseable {
        final Cost cost;
        final long started = System.nanoTime();
        long writes, exactNanos;
        String route = "java";
        String attempt;
        long biomeChecks, biomePassed;
        Scope(Cost cost) { this.cost = cost; }
        @Override public void close() {
            long elapsed = System.nanoTime() - started;
            cost.calls++; cost.nanos += elapsed; cost.writes += writes;
            if (writes == 0) { cost.noWriteCalls++; cost.noWriteNanos += elapsed; cost.noWriteExactNanos += exactNanos; }
            if (route.equals("native")) cost.nativeCalls++;
            else if (route.equals("tree")) cost.treeCalls++;
            else cost.javaCalls++;
            cost.biomeChecks+=biomeChecks; cost.biomePassed+=biomePassed;
            if(mode.equals("baseline") && attempt!=null && writes==0) {
                NO_WRITE_ORACLE.computeIfAbsent(round,ignored->new HashSet<>()).add(attempt);
                if(biomeChecks>0 && biomePassed==0)
                    BIOME_ORACLE.computeIfAbsent(round,ignored->new HashSet<>()).add(attempt);
            }
            current = null;
        }
    }
    static Scope begin(String id, String type, String modifiers) {
        if (current != null) throw new IllegalStateException("Overlapping top-level feature probes");
        Cost cost = COSTS.computeIfAbsent(id, ignored -> {
            Cost c = new Cost(); c.id=id; c.type=type; c.modifiers=modifiers; return c;
        });
        return current = new Scope(cost);
    }
    static Scope feature(ClientTerrainSampler context, PlacedFeature feature) {
        var id = context.decorationAccess().registryOrThrow(Registries.PLACED_FEATURE).getKey(feature);
        return begin(String.valueOf(id), String.valueOf(net.minecraft.core.registries.BuiltInRegistries.FEATURE
                .getKey(feature.feature().value().feature())), feature.placement().stream()
                .map(p -> p.getClass().getSimpleName()).toList().toString());
    }
    static boolean skip(ClientTerrainSampler context,PlacedFeature feature,int cx,int cz) {
        if(!mode.startsWith("oracle"))return false;
        String key=attempt(context,feature,cx,cz);
        var keys=(mode.equals("oracle_biome")?BIOME_ORACLE:NO_WRITE_ORACLE).get(round);
        if(keys!=null && keys.contains(key)){oracleSkipped++;return true;}
        return false;
    }
    private static String attempt(ClientTerrainSampler context,PlacedFeature feature,int cx,int cz) {
        return cx+","+cz+":"+context.decorationAccess().registryOrThrow(Registries.PLACED_FEATURE).getKey(feature);
    }
    static void source(ClientTerrainSampler context,PlacedFeature feature,int cx,int cz) {
        current.attempt=attempt(context,feature,cx,cz);
    }
    static void biomeFilter(boolean accepted) {
        if(current!=null){current.biomeChecks++;if(accepted)current.biomePassed++;}
    }
    static void route(String route) { if (current != null) current.route=route; }
    static void write() { if (current != null) current.writes++; }
    static int surface(long handle, ByteBuffer input, ByteBuffer output, int count) {
        Scope scope=current;
        if(scope!=null) for(int i=0;i<count;i++) {
            long p=(long)input.getInt(i*8)<<32|input.getInt(i*8+4)&0xffffffffL;
            if(!EXACT.add(p))repeatedPoints++;
        }
        long started=System.nanoTime();
        try { return RustWorldgenBackend.surfacePoints(handle,input,output,count); }
        finally { if(scope!=null) {long dt=System.nanoTime()-started;
            scope.cost.exactCalls++; scope.cost.exactPoints+=count; scope.cost.exactNanos+=dt; scope.exactNanos+=dt;
        }}
    }
    static int display(long handle,ByteBuffer input,ByteBuffer output,int count) {
        Scope scope=current;long started=System.nanoTime();
        try {return RustWorldgenBackend.decorationPoints(handle,input,output,count);}
        finally {if(scope!=null){scope.cost.displayCalls++;scope.cost.displayNanos+=System.nanoTime()-started;}}
    }
    static int heights(long handle,ByteBuffer input,ByteBuffer output,int count) {
        long start=System.nanoTime();
        int written=RustWorldgenBackend.surfaceHeights(handle,input,output,count);
        heightCalls++;heightPoints+=count;heightNanos+=System.nanoTime()-start;
        for(int i=0;i<written;i++)if(output.getInt(i*8)!=Integer.MIN_VALUE)heightProved++;
        return written;
    }
    static Holder<Biome> biome(ClientTerrainSampler context,int x,int y,int z) {
        Scope scope=current;long started=System.nanoTime();
        try {return context.noiseBiome(x,y,z);}
        finally {if(scope!=null){scope.cost.biomeCalls++;scope.cost.biomeNanos+=System.nanoTime()-started;}}
    }
    static Holder<Biome> load(BiomeSource source,Climate.Sampler climate,int x,int y,int z) {
        Scope scope=current;long started=System.nanoTime();
        try {return source.getNoiseBiome(x,y,z,climate);}
        finally {if(scope!=null){scope.cost.biomeLoads++;scope.cost.biomeLoadNanos+=System.nanoTime()-started;}}
    }
    static void reset() {COSTS.clear();EXACT.clear();repeatedPoints=0;current=null;heightCalls=heightPoints=heightProved=heightNanos=0;}
    static void beginRound(String selectedMode,int selectedRound) {reset();mode=selectedMode;round=selectedRound;oracleSkipped=0;}
    static void save(Path output,String mode,int round) throws java.io.IOException {
        var report=new LinkedHashMap<String,Object>();
        report.put("mode",mode);report.put("round",round);report.put("uniqueJavaExactPoints",EXACT.size());
        report.put("repeatedJavaExactPoints",repeatedPoints);
        report.put("oracleSkipped",oracleSkipped);
        report.put("heightCalls",heightCalls);report.put("heightPoints",heightPoints);
        report.put("heightProved",heightProved);report.put("heightMs",heightNanos/1e6);
        report.put("scope","Inclusive feature times; exact/biome query times are nested. No-write means no journal writes, not safely removable.");
        report.put("features",COSTS.values().stream().sorted(Comparator.comparingLong((Cost c)->c.nanos).reversed()).toList());
        Files.writeString(output.resolve("feature-costs-"+mode+"-"+round+".json"),JSON.toJson(report));
    }
}

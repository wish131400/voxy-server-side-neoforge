package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.Map;

/** Single generation-thread counters, identically enabled for baseline and candidate. */
final class FarProbe {
    static long sourceChunks, featureAttempts, replaced, surfacePoints, surfaceNs, biomeQueries;
    static long displayPoints,displayNs,proxies,proxyNs,javaFeatures;
    static boolean structure;
    static boolean proxy() {return FarDecorationPrototype.active!=null && FarDecorationPrototype.active.proxyEnvironment;}
    static void structures(PredictionSurfaceStructures structures,PredictionDecorationLevel level,int x,int z,long seed,int step) {
        structure=true;
        try {level.useDisplayTerrain(false);structures.place(level,x,z,seed,step);}
        finally {structure=false;}
    }
    static int surface(long handle, ByteBuffer in, ByteBuffer out, int count) {
        surfacePoints += count;
        long start = System.nanoTime();
        try { return RustWorldgenBackend.surfacePoints(handle, in, out, count); }
        finally { surfaceNs += System.nanoTime() - start; }
    }
    static int display(long handle,ByteBuffer in,ByteBuffer out,int count) {
        displayPoints+=count;long start=System.nanoTime();
        try{return RustWorldgenBackend.decorationPoints(handle,in,out,count);}
        finally{displayNs+=System.nanoTime()-start;}
    }
    static long proxy(long handle,int x,int z,int mode) {
        proxies++;long start=System.nanoTime();
        try{return RustWorldgenBackend.decorationProxy(handle,x,z,mode);}
        finally{proxyNs+=System.nanoTime()-start;}
    }
    static void reset() { sourceChunks=featureAttempts=replaced=surfacePoints=surfaceNs=biomeQueries=displayPoints=displayNs=proxies=proxyNs=javaFeatures=0; }
    static Map<String,Object> stats() {
        return Map.ofEntries(Map.entry("sourceChunks",sourceChunks),Map.entry("consideredFeatures",featureAttempts),Map.entry("replaced",replaced),
                Map.entry("javaFeatures",javaFeatures),Map.entry("surfacePoints",surfacePoints),Map.entry("surfaceMs",surfaceNs/1e6),Map.entry("biomeQueries",biomeQueries),
                Map.entry("displayPoints",displayPoints),Map.entry("displayMs",displayNs/1e6),Map.entry("proxies",proxies),Map.entry("proxyMs",proxyNs/1e6));
    }
}

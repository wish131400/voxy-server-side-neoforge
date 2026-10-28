package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.compat.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.jupiter.api.*;

/** Same deterministic replay is run against the frozen baseline and the candidate. */
class PredictionMaterialReplayTest {
    @BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    static Method buildMethod() throws Exception {
        var method=PredictionVoxySource.class.getDeclaredMethod("buildSection",long.class,VoxyPredictionBridge.Mapper.class,List.class,long[].class,boolean.class);
        method.setAccessible(true);return method;
    }
    static volatile long sink;
    @Test void alternatingBaselineAndCandidateReplayPreservesEveryVoxel() throws Exception {
        var current=buildMethod();
        var baseline=PredictionMaterialBaselineSource.class.getDeclaredMethod("buildSection",long.class,VoxyPredictionBridge.Mapper.class,List.class,long[].class,boolean.class);
        baseline.setAccessible(true);
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().getId();
        for(int lod:new int[]{0,1,3}) {
            var tile=PredictionVoxyPreparationTest.fixture(0,0,64,73);var tiles=List.of(tile);
            var mapper=PredictionVoxyPreparationTest.MAPPER;var data=new long[32768];var oldData=new long[32768];
            try(var source=new PredictionVoxySource();var old=new PredictionMaterialBaselineSource()) {
                source.put(tile);old.put(tile);
                long pos=VoxyPredictionBridge.position(lod,0,0,0);
                source.section(pos,mapper);old.section(pos,mapper);
                for(int i=0;i<1600;i++) {
                    pos=VoxyPredictionBridge.position(lod,0,i%4,0);
                    current.invoke(source,pos,mapper,tiles,data,false);baseline.invoke(old,pos,mapper,tiles,oldData,false);
                }
                var newTimes=new double[15];var oldTimes=new double[15];var newBytes=new long[15];var oldBytes=new long[15];
                for(int trial=0;trial<15;trial++) {
                    for(int path=0;path<2;path++) {
                        boolean candidate=((trial+path)&1)==0;
                        var method=candidate ? current:baseline;Object instance=candidate ? source:old;
                        long before=threads.getThreadAllocatedBytes(tid),start=System.nanoTime();
                        long checksum=0;
                        for(int i=0;i<512;i++) {
                            method.invoke(instance,VoxyPredictionBridge.position(lod,0,i%4,0),mapper,tiles,data,false);
                            checksum+=data[(i*1337)&32767];
                        }
                        sink=checksum;
                        (candidate ? newTimes:oldTimes)[trial]=(System.nanoTime()-start)/1e6;
                        (candidate ? newBytes:oldBytes)[trial]=threads.getThreadAllocatedBytes(tid)-before;
                    }
                }
                long hash=1;
                for(int y=-1;y<5;y++) {
                    pos=VoxyPredictionBridge.position(lod,0,y,0);
                    current.invoke(source,pos,mapper,tiles,data,false);baseline.invoke(old,pos,mapper,tiles,oldData,false);
                    assertArrayEquals(oldData,data);
                    for(long value:data)hash=31*hash+value;
                    for(int face=0;face<6;face++)assertArrayEquals(old.face(pos,mapper,face),source.face(pos,mapper,face));
                }
                Arrays.sort(newTimes);Arrays.sort(oldTimes);Arrays.sort(newBytes);Arrays.sort(oldBytes);
                System.out.printf(Locale.ROOT,"MATERIAL_AB lod=%d builds=512 baselineMs=%.6f candidateMs=%.6f baselineBytes=%d candidateBytes=%d outputHash=%d%n",lod,oldTimes[7],newTimes[7],oldBytes[7],newBytes[7],hash);
            }
        }
    }
    @Test void replayConversionWithFineColumnsAndReducedPages() throws Exception {
        var build=buildMethod();
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().getId();
        for(int lod:new int[]{0,1,3}) {
            var tiles=List.of(PredictionVoxyPreparationTest.fixture(0,0,64,73));
            var mapper=PredictionVoxyPreparationTest.MAPPER;var data=new long[32768];
            try(var source=new PredictionVoxySource()) {
                for(int i=0;i<600;i++)build.invoke(source,VoxyPredictionBridge.position(lod,0,i%4,0),mapper,tiles,data,false);
                long checksum=0;var times=new double[9];var allocations=new long[9];
                for(int trial=0;trial<9;trial++) {
                    long before=threads.getThreadAllocatedBytes(tid),start=System.nanoTime();
                    for(int i=0;i<256;i++) {
                        build.invoke(source,VoxyPredictionBridge.position(lod,0,i%4,0),mapper,tiles,data,false);
                        checksum+=data[(i*1337)&32767];
                    }
                    times[trial]=(System.nanoTime()-start)/1e6;
                    allocations[trial]=threads.getThreadAllocatedBytes(tid)-before;
                }
                sink=checksum;
                // Complete outputs, including fluid, biome and feature bits, not only sampled benchmark voxels.
                long outputHash=1;
                for(int y=-1;y<5;y++) {
                    build.invoke(source,VoxyPredictionBridge.position(lod,0,y,0),mapper,tiles,data,false);
                    for(long value:data)outputHash=31*outputHash+value;
                }
                Arrays.sort(times);Arrays.sort(allocations);
                System.out.printf(Locale.ROOT,"MATERIAL_REPLAY lod=%d builds=256 medianMs=%.6f allocatedBytes=%d outputHash=%d checksum=%d%n",lod,times[4],allocations[4],outputHash,checksum);
            }
        }
    }
}

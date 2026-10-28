package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class VoxySectionReplayTest {
    static volatile VoxySectionData sink;
    static volatile VoxySectionBaselineData oldSink;
    static long[] fixture(int length,int runs) {
        long[] data=new long[length];
        for(int i=0;i<length;i++)data[i]=(long)((long)i*runs/length)*379L+0xFA555555FFFFFFFFL;
        return data;
    }
    @Test void exactBoundariesDenseCrossoverAndConcurrentWorkersStayDetached() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<Void>>();
            for(int length:new int[]{1024,32768})for(int runs:new int[]{1,2,127,2047,2048,2049,21845,21846,32768}) {
                tasks.add(()->{
                    var data=fixture(length,Math.min(runs,length));var expected=data.clone();
                    var compact=length==1024 ? VoxySectionData.compactFaceCopy(data):VoxySectionData.compactCopy(data);
                    Arrays.fill(data,0);compact.copyTo(data);assertArrayEquals(expected,data);
                    assertArrayEquals(expected,compact.array());
                    assertTrue(compact.bytes()<=length*8L+128);return null;
                });
            }
            for(var result:pool.invokeAll(tasks))result.get();
        } finally {pool.shutdownNow();}
    }
    @Test void replayCompressionAndCompareBoundedHotExpansionCost() {
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().getId();
        for(int runs:new int[]{1,64,800,2048,3000,32768}) {
            var data=fixture(32768,runs);
            for(int warmup=0;warmup<2000;warmup++)sink=VoxySectionData.compactCopy(data);
            var times=new double[9];var allocations=new long[9];
            for(int trial=0;trial<9;trial++) {
                long before=threads.getThreadAllocatedBytes(tid),start=System.nanoTime();
                for(int i=0;i<512;i++)sink=VoxySectionData.compactCopy(data);
                times[trial]=(System.nanoTime()-start)/1e6;allocations[trial]=threads.getThreadAllocatedBytes(tid)-before;
            }
            Arrays.sort(times);Arrays.sort(allocations);
            var compact=VoxySectionData.compactCopy(data);long[] out=new long[32768];
            for(int i=0;i<1000;i++){compact.copyTo(out);System.arraycopy(data,0,out,0,data.length);}
            var compactTimes=new double[9];var denseTimes=new double[9];
            for(int trial=0;trial<9;trial++) {
                // Alternate order to avoid always favoring the second path.
                if((trial&1)==0) {
                    compactTimes[trial]=copy(compact,data,out,false);denseTimes[trial]=copy(compact,data,out,true);
                } else {
                    denseTimes[trial]=copy(compact,data,out,true);compactTimes[trial]=copy(compact,data,out,false);
                }
            }
            Arrays.sort(compactTimes);Arrays.sort(denseTimes);assertArrayEquals(data,out);
            System.out.printf(Locale.ROOT,"SECTION_REPLAY runs=%d builds=512 medianMs=%.6f allocatedBytes=%d compactBytes=%d copyRunsMs=%.6f copyDenseMs=%.6f%n",runs,times[4],allocations[4],compact.bytes(),compactTimes[4],denseTimes[4]);
        }
    }
    @Test void alternatingBaselineAndCandidateCompression() {
        var threads=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().getId();
        for(int runs:new int[]{1,64,800,2048,3000,32768}) {
            var data=fixture(32768,runs);
            for(int i=0;i<1600;i++) {sink=VoxySectionData.compactCopy(data);oldSink=VoxySectionBaselineData.compactCopy(data);}
            double[] times=new double[15],oldTimes=new double[15];long[] bytes=new long[15],oldBytes=new long[15];
            for(int trial=0;trial<15;trial++)for(int path=0;path<2;path++) {
                boolean candidate=((trial+path)&1)==0;
                long before=threads.getThreadAllocatedBytes(tid),start=System.nanoTime();
                if(candidate)for(int i=0;i<256;i++)sink=VoxySectionData.compactCopy(data);
                else for(int i=0;i<256;i++)oldSink=VoxySectionBaselineData.compactCopy(data);
                (candidate ? times:oldTimes)[trial]=(System.nanoTime()-start)/1e6;
                (candidate ? bytes:oldBytes)[trial]=threads.getThreadAllocatedBytes(tid)-before;
            }
            Arrays.sort(times);Arrays.sort(oldTimes);Arrays.sort(bytes);Arrays.sort(oldBytes);
            assertArrayEquals(oldSink.array(),sink.array());assertEquals(oldSink.bytes(),sink.bytes());
            System.out.printf(Locale.ROOT,"SECTION_AB runs=%d builds=256 baselineMs=%.6f candidateMs=%.6f baselineBytes=%d candidateBytes=%d%n",runs,oldTimes[7],times[7],oldBytes[7],bytes[7]);
        }
    }
    static double copy(VoxySectionData compact,long[] dense,long[] out,boolean expanded) {
        long start=System.nanoTime();
        for(int i=0;i<1024;i++)if(expanded)System.arraycopy(dense,0,out,0,dense.length);else compact.copyTo(out);
        return (System.nanoTime()-start)/1e6;
    }
}

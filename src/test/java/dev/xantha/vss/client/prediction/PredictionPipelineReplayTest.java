package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.junit.jupiter.api.*;

/** Same scene and workload for before/after runs; timings are diagnostic, never FPS claims. */
class PredictionPipelineReplayTest {
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static final VoxyPredictionBridge.Mapper MAPPER=(s,b)->15L<<56 | (long)(s.is(Blocks.STONE)?1:s.is(Blocks.DIRT)?2:3)<<27;
    private static volatile long consumed;
    @Test void replayIdenticalColdVolumesAndFaces() {
        var random=new Random(93);var columns=new PredictionVoxySource.Column[256*256];
        for(int i=0;i<columns.length;i++) {
            int top=64+random.nextInt(30);
            columns[i]=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,top-3,Blocks.STONE.defaultBlockState()),
                    new PredictionVoxySource.Run(top-3,top,Blocks.DIRT.defaultBlockState())),"minecraft:plains");
        }
        var forest=new ArrayList<PredictionVoxySource.Box>();
        for(int i=0;i<32768;i++) {
            int x=random.nextInt(256),z=random.nextInt(256),y=96+random.nextInt(32);
            forest.add(new PredictionVoxySource.Box(x,y,z,x+1,y+5,z+1,Blocks.OAK_LEAVES.defaultBlockState()));
        }
        var tile=new PredictionVoxySource.Tile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0),
                0,0,256,256,1,columns,List.copyOf(forest),4_000_000,true,0);
        for(int lod:new int[]{0,3}) {
            var times=new ArrayList<Long>();long cpuTotal=0,expected=0;
            var thread=java.lang.management.ManagementFactory.getThreadMXBean();
            try(var source=new PredictionVoxySource()) {
                source.put(tile);
                for(int round=0;round<30;round++) {
                    long start=System.nanoTime(),cpuStart=thread.getCurrentThreadCpuTime(),checksum=1;
                    for(int n=0;n<12;n++) {
                        source.releaseMapper(MAPPER);
                        long pos=VoxyPredictionBridge.position(lod,lod==0?n%8:0,lod==0?3:0,lod==0?n/8:0);
                        long[] data=n%2==0?source.section(pos,MAPPER):source.face(pos,MAPPER,n%6);
                        for(long value:data)checksum=31*checksum+value;
                    }
                    long elapsed=System.nanoTime()-start,cpuElapsed=thread.getCurrentThreadCpuTime()-cpuStart;consumed=checksum;
                    if(round==0)expected=checksum;else assertEquals(expected,checksum);
                    if(round>=10){times.add(elapsed);cpuTotal+=cpuElapsed;}
                }
                System.out.println("PIPELINE_STAGES lod="+lod+" "+source.diagnostics());
            }
            Collections.sort(times);
            System.out.printf(Locale.ROOT,"PIPELINE_REPLAY lod=%d forest=%d operations=12 medianMs=%.3f cpuTotalMs=%.3f checksum=%d%n",lod,forest.size(),times.get(times.size()/2)/1e6,cpuTotal/1e6,expected);
        }
    }
}

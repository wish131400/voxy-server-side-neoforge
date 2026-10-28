package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionDataOrientedPerformanceTest {
    private static volatile Object sink;
    @BeforeAll static void bootstrap(){ClientTerrainSamplerTest.bootstrapMinecraft();}
    private static Object legacy(Map<BlockPos,BlockState> source) {
        var merged=new HashMap<BlockPos,BlockState>();merged.putAll(source);
        var cells=PredictionPackedVegetationTest.reference(merged,0,0,256,4,1);
        var blocks=Collections.unmodifiableMap(new HashMap<>(merged));return List.of(cells,blocks);
    }
    private record Measurement(double millis,long bytes) { }
    private static Measurement measure(Runnable work,int iterations) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);long id=Thread.currentThread().getId();
        long bytes=bean.getThreadAllocatedBytes(id),start=System.nanoTime();
        for(int i=0;i<iterations;i++)work.run();
        return new Measurement((System.nanoTime()-start)/1e6/iterations,(bean.getThreadAllocatedBytes(id)-bytes)/iterations);
    }
    @Test void completePlantPayloadUsesLessAllocationWithoutDroppingBlocks() {
        var source=new HashMap<BlockPos,BlockState>();
        for(int i=0;i<65536;i++)source.put(new BlockPos(i&255,70+(i>>>12),i>>>8),
                i%5==0?Blocks.OAK_LOG.defaultBlockState():Blocks.OAK_LEAVES.defaultBlockState());
        var sources=List.of(new PredictionVegetationDisplayCache.Source(1,source));
        Runnable before=()->sink=legacy(source);
        Runnable after=()->sink=PredictionVegetationDisplayCache.merge(0,0,256,4,true,sources,p->false);
        for(int i=0;i<6;i++){before.run();after.run();}
        var old=measure(before,8);var packed=measure(after,8);
        var tile=(PredictionVegetation.Tile)sink;assertEquals(source,tile.blocks());assertEquals(source.size(),tile.voxelCount());
        assertFalse(tile.hasCellObjects());assertTrue(packed.bytes()<old.bytes()*.70,"primitive publication must reduce actual thread allocation");
        System.out.println("VSS_DATA_ORIENTED_VEGETATION={\"blocks\":"+source.size()+",\"legacyMs\":"+old.millis()+",\"packedMs\":"+packed.millis()
                +",\"legacyAllocatedBytes\":"+old.bytes()+",\"packedAllocatedBytes\":"+packed.bytes()+",\"packedRetainedEstimate\":"+tile.bytes()+"}");
    }
    @Test void lifecycleComponentsShareOneIdentityIndexAndReuseSlots() {
        var states=new PredictionTileStates<Integer>(11,5);int n=10000;
        for(int k=0;k<n;k++){for(int c=0;c<11;c++)states.flag(c).add(k);for(int c=0;c<5;c++)states.values(c).put(k,(long)k);}
        assertEquals(n,states.identities());int capacity=states.slotCapacity();
        for(int c=0;c<11;c++)states.flag(c).clear();for(int c=0;c<5;c++)states.values(c).clear();
        for(int k=n;k<n*2;k++)states.flag(0).add(k);assertEquals(capacity,states.slotCapacity());
        System.out.println("VSS_DATA_ORIENTED_STATES={\"tiles\":"+n+",\"separateMemberships\":"+(n*16)+",\"sharedIdentities\":"+states.identities()+",\"capacity\":"+capacity+"}");
    }
}

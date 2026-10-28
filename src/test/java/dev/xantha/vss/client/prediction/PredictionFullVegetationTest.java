package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionFullVegetationTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restoreTags() { PredictionVegetationTest.restoreTags(); }

    @Test void fullResolutionPublicationKeepsEverySampledLeafLogAndSmallPlant() {
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            blocks.put(new BlockPos(x,64,z),Blocks.OAK_LOG.defaultBlockState());
            blocks.put(new BlockPos(x,65,z),Blocks.OAK_LEAVES.defaultBlockState());
            blocks.put(new BlockPos(x,66,z),Blocks.FERN.defaultBlockState());
        }
        var sources=List.of(new PredictionVegetationDisplayCache.Source(0,Map.copyOf(blocks)));
        for(int step:new int[]{1,2,4,8}) {
            var tile=PredictionVegetationDisplayCache.merge(0,0,16,step,true,sources,p->false);
            assertEquals(blocks,tile.blocks(),"Voxy owns simplification; publication cannot discard sampled plants");
            assertEquals(blocks.size(),tile.cells().values().stream().mapToInt(List::size).sum());
            assertTrue(tile.cells().values().stream().flatMap(List::stream).allMatch(v->v.size()==1));
        }
    }

    @Test void adjacentTilesKeepTheirRespectivePartsOfABoundaryCanopy() {
        var blocks=Map.of(new BlockPos(15,70,7),Blocks.OAK_LEAVES.defaultBlockState(),
                new BlockPos(16,70,7),Blocks.OAK_LEAVES.defaultBlockState());
        var sources=List.of(new PredictionVegetationDisplayCache.Source(0,blocks));
        var left=PredictionVegetationDisplayCache.merge(0,0,16,1,true,sources,p->false);
        var right=PredictionVegetationDisplayCache.merge(16,0,16,1,true,sources,p->false);
        assertTrue(left.cells().values().stream().flatMap(List::stream).anyMatch(v->v.x()==15&&v.y()==70));
        assertTrue(right.cells().values().stream().flatMap(List::stream).anyMatch(v->v.x()==0&&v.y()==70));
    }
}

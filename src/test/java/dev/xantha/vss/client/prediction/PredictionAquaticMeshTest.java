package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionAquaticMeshTest {
    @BeforeAll static void bootstrap(){PredictionVegetationTest.bootstrap();}
    @AfterAll static void restore(){PredictionVegetationTest.restoreTags();}

    @Test void aquaticPlantsAreCrossedCutoutsAndDoNotOccludeNeighbors() {
        for(var block:List.of(Blocks.KELP,Blocks.KELP_PLANT,Blocks.SEAGRASS,Blocks.TALL_SEAGRASS)) {
            var state=block.defaultBlockState();assertFalse(PredictionVegetation.solid(state));
            var tile=PredictionVegetation.Tile.of(Map.of(new BlockPos(0,60,0),state),0,0,1,1,1);
            assertFalse(tile.occupied(0,60,0));
            var sample=new ClientColumnSample(58,63,0,ClientColumnSample.NO_BLOCK,0,0,0,0,1,
                    ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
            var mesh=PredictionMeshBuilder.build(new ClientColumnSample[]{sample,sample,sample,sample},null,
                    63,0xb22d78c5,1,2,true,null,null,null,0,0,tile);
            assertEquals(18,mesh.vertexCount(),"one ground quad plus two plant planes");
            assertEquals(6,mesh.waterVertexCount(),"plant must not punch a hole in the water");
        }
    }

    @Test void pressureReductionKeepsWholeStalksAndAllGroundStructures() {
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=-64;z<64;z++)for(int x=-64;x<64;x++)for(int y=70;y<74;y++)
            blocks.put(new BlockPos(x,y,z),y==73?Blocks.KELP.defaultBlockState():Blocks.KELP_PLANT.defaultBlockState());
        var special=Map.of(new BlockPos(-1,64,-1),Blocks.FARMLAND.defaultBlockState(),
                new BlockPos(1,64,1),Blocks.DIRT_PATH.defaultBlockState(),
                new BlockPos(3,64,3),Blocks.WATER.defaultBlockState(),
                new BlockPos(1,65,1),Blocks.DANDELION.defaultBlockState());
        blocks.putAll(special);
        var tile=PredictionVegetation.boundedTile(blocks,-64,-64,128,2,1);
        assertTrue(tile.blocks().size()<blocks.size());
        special.forEach((p,s)->assertEquals(s,tile.blocks().get(p)));
        tile.blocks().forEach((p,s)->{
            if(p.getY()==73)for(int y=70;y<73;y++)assertEquals(Blocks.KELP_PLANT.defaultBlockState(),tile.blocks().get(new BlockPos(p.getX(),y,p.getZ())));
        });
        assertEquals(tile.blocks(),PredictionVegetation.boundedTile(new LinkedHashMap<>(blocks),-64,-64,128,2,1).blocks());
    }
}

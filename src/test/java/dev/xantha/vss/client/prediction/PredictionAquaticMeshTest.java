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



    @Test void fullVoxelPublicationKeepsWholeStalksAndAllGroundStructures() {
        var blocks=new HashMap<BlockPos,BlockState>();
        for(int z=-64;z<64;z++)for(int x=-64;x<64;x++)for(int y=70;y<74;y++)
            blocks.put(new BlockPos(x,y,z),y==73?Blocks.KELP.defaultBlockState():Blocks.KELP_PLANT.defaultBlockState());
        var special=Map.of(new BlockPos(-1,64,-1),Blocks.FARMLAND.defaultBlockState(),
                new BlockPos(1,64,1),Blocks.DIRT_PATH.defaultBlockState(),
                new BlockPos(3,64,3),Blocks.WATER.defaultBlockState(),
                new BlockPos(1,65,1),Blocks.DANDELION.defaultBlockState());
        blocks.putAll(special);
        var tile=PredictionVegetation.Tile.of(blocks,-64,-64,128,2,1);
        assertEquals(blocks,tile.blocks());
        special.forEach((p,s)->assertEquals(s,tile.blocks().get(p)));
        tile.blocks().forEach((p,s)->{
            if(p.getY()==73)for(int y=70;y<73;y++)assertEquals(Blocks.KELP_PLANT.defaultBlockState(),tile.blocks().get(new BlockPos(p.getX(),y,p.getZ())));
        });
        assertEquals(tile.blocks(),PredictionVegetation.Tile.of(new LinkedHashMap<>(blocks),-64,-64,128,2,1).blocks());
    }
}

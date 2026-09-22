package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionSnowSurfacesTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void contiguousSnowHasOneRoofAndOnlyExposedRimWithoutLosingArea() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z=0; z<8; z++) for (int x=0; x<8; x++) blocks.put(new BlockPos(x-8,80,z-8), Blocks.SNOW.defaultBlockState());
        var tile = PredictionVegetation.Tile.of(blocks,-8,-8,8,8,1);
        var roofs = PredictionSnowSurfaces.roofs(tile,0,(x,z)->64);
        assertEquals(1, roofs.size());
        assertEquals(64, roofs.getFirst().width()*roofs.getFirst().depth());
        assertEquals(80.125F, roofs.getFirst().y());
        assertEquals(80.125F, PredictionSnowSurfaces.sideBottom(tile,1,80,0,80));
        assertEquals(80F, PredictionSnowSurfaces.sideBottom(tile,-1,80,0,80));
    }

    @Test void roofUnionPreservesHolesHeightChangesAndCoverageCells() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int z=0;z<8;z++) for(int x=0;x<8;x++) {
            if (x==3&&z==3) continue;
            blocks.put(new BlockPos(x,80,z), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, x<5?1:3));
        }
        var tile=PredictionVegetation.Tile.of(blocks,0,0,8,4,1);
        Map<BlockPos,Float> restored=new HashMap<>();
        for(int cell=0;cell<4;cell++) for(var roof:PredictionSnowSurfaces.roofs(tile,cell,(x,z)->64)) {
            assertEquals(cell,roof.z()/4*2+roof.x()/4);
            assertEquals(cell,(roof.z()+roof.depth()-1)/4*2+(roof.x()+roof.width()-1)/4);
            for(int z=0;z<roof.depth();z++)for(int x=0;x<roof.width();x++)
                assertNull(restored.put(new BlockPos(roof.x()+x,80,roof.z()+z),roof.y()),"no overlapping roofs");
        }
        assertEquals(blocks.keySet(),restored.keySet());
        blocks.forEach((p,s)->assertEquals(80+PredictionSnowSurfaces.height(s),restored.get(p)));
        assertEquals(80.375F,PredictionSnowSurfaces.sideBottom(tile,5,80,0,80));
    }

    @Test void fullyBuriedOrFullBlockSnowIsLeftToExistingSolidMesher() {
        var tile=PredictionVegetation.Tile.of(Map.of(BlockPos.ZERO,Blocks.SNOW.defaultBlockState()
                .setValue(SnowLayerBlock.LAYERS,8)),0,0,4,4,1);
        assertTrue(PredictionSnowSurfaces.roofs(tile,0,(x,z)->-1).isEmpty());
        var thin=PredictionVegetation.Tile.of(Map.of(BlockPos.ZERO,Blocks.SNOW.defaultBlockState()),0,0,4,4,1);
        assertTrue(PredictionSnowSurfaces.roofs(thin,0,(x,z)->1).isEmpty());
    }

    @Test void productionMeshRetainsSnowShapeWithFewerQuads() {
        Map<BlockPos,BlockState> blocks=new HashMap<>();
        for(int z=0;z<8;z++)for(int x=0;x<8;x++)blocks.put(new BlockPos(x,80,z),Blocks.SNOW.defaultBlockState());
        var tile=PredictionVegetation.Tile.of(blocks,0,0,8,8,1);
        var sample=new ClientColumnSample(64,64,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,ClientColumnSample.FLAG_SURFACE_ONLY,0,
                ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN);
        var samples=new ClientColumnSample[4]; java.util.Arrays.fill(samples,sample);
        var mesh=PredictionMeshBuilder.build(samples,new int[]{-1,-1,-1,-1},63,0,8,2,true,null,null,null,0,0,tile).packed();
        int snowQuads=0;double roofArea=0;
        for(int q=0;q<mesh.quadCount();q++) {
            if(mesh.y(q,0)<80)continue;
            snowQuads++;
            float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minZ=Float.POSITIVE_INFINITY,maxZ=Float.NEGATIVE_INFINITY;
            boolean roof=true;
            for(int c=0;c<4;c++) {
                assertTrue(mesh.y(q,c)>=80 && mesh.y(q,c)<=80.125F);
                roof &= mesh.y(q,c)==80.125F;
                minX=Math.min(minX,mesh.x(q,c));maxX=Math.max(maxX,mesh.x(q,c));
                minZ=Math.min(minZ,mesh.z(q,c));maxZ=Math.max(maxZ,mesh.z(q,c));
            }
            if(roof)roofArea+=(maxX-minX)*(maxZ-minZ);
        }
        assertEquals(64,roofArea);
        assertEquals(5,snowQuads,"snow consolidation plus the existing quad merger keeps only one roof and four rim faces");
    }
}

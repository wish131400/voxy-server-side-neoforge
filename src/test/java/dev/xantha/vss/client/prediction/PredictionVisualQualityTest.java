package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionVisualQualityTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static PredictionTile tile(int tx,int tz,int lod,int axis,int height) {
        int step=(64<<lod)/axis, n=axis+1;
        int[] heights=new int[n*n]; Arrays.fill(heights,height);
        var samples=new ClientColumnSample[n*n];
        Arrays.fill(samples,new ClientColumnSample(height,height,0,ClientColumnSample.NO_BLOCK,0,0,0,0,0,
                ClientColumnSample.FLAG_SURFACE_ONLY,0,ClientColumnSample.NO_BLOCK,ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN));
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD,tx,tz,lod),heights,heights,samples,null,
                new PredictionDepthBound(height-16,height+16),0,1,axis,step);
    }

    @Test void actualCellBudgetUsesSmallestSufficientPowerOfTwoGrid() {
        for(double budget:new double[]{1.5,3,6,12}) for(double projected=1;projected<=64*budget;projected+=.25) {
            int axis=PredictionDetailBands.projectedCellAxis(projected,budget);
            assertTrue(projected/axis<=budget);
            if(axis>16) assertTrue(projected/(axis/2)>budget);
        }
    }

    @Test void reliefSummarizesExistingColumnsAndOnlyPromotesKnownRoughTiles() {
        var flat=tile(0,0,4,32,64);
        assertFalse(PredictionRelief.of(flat).needsRefinement());
        var ridge=tile(0,0,4,32,64);
        var elevated=tile(0,0,4,32,160).samples()[0];
        ridge.samples()[16*33+16]=elevated;
        assertTrue(PredictionRelief.of(ridge).needsRefinement());
        assertEquals(96,PredictionRelief.of(ridge).maxStep());
        var layout=VssLodLayout.of(65536,6,true,false);
        var ordinary=PredictionLodPlanner.plan(Level.OVERWORLD,114.5,4998,125.5,layout,null,1300);
        var selected=ordinary.stream().filter(k -> {
            double d=Math.sqrt(PredictionWorkOrder.distanceSquared(k,layout,114.5,125.5)+4678D*4678);
            double error=layout.tileBlocks(k.lod())*1300/d;
            return k.lod()>0 && error>layout.pixelThreshold()/1.5 && error<=layout.pixelThreshold();
        }).findFirst().orElseThrow();
        var refined=PredictionLodPlanner.plan(Level.OVERWORLD,114.5,4998,125.5,layout,null,1300,
                -64,320,768,k->true,selected::equals);
        assertFalse(refined.contains(selected));
        assertTrue(refined.containsAll(PredictionTransitionPlan.children(selected)));
        assertTrue(refined.size()<=ordinary.size()+3+PredictionTransitionPlan.MAX_EXTRA_LEAVES);
    }

    @Test void morphPinsBordersLimitsDisplacementAndExpiresWithoutResampling() {
        for(int sign:new int[]{-1,1}) {
            var child=tile(sign,sign,3,32,64);
            var parent=tile(Math.floorDiv(sign,2),Math.floorDiv(sign,2),4,32,80);
            float[] field=PredictionMorph.field(child,parent);
            assertNotNull(field);
            int axis=33;
            for(int i=0;i<axis;i++) {
                assertEquals(0,field[i]); assertEquals(0,field[(axis-1)*axis+i]);
                assertEquals(0,field[i*axis]); assertEquals(0,field[i*axis+axis-1]);
            }
            assertEquals(8,field[16*axis+16]);
            for(float d:field) assertTrue(Math.abs(d)<=8);
        }
        assertEquals(1,PredictionMorph.amount(0));
        assertEquals(.5F,PredictionMorph.amount(PredictionMorph.DURATION_NANOS/2),.0001);
        assertEquals(0,PredictionMorph.amount(PredictionMorph.DURATION_NANOS));
        assertNull(PredictionMorph.field(tile(0,0,0,64,64),tile(0,0,1,64,80)));
        assertNull(PredictionMorph.field(tile(0,0,3,32,64),null));
    }

    @Test void cachedTreesKeepOriginalCoordinatesSpeciesAndBoundedSize() {
        Map<BlockPos,net.minecraft.world.level.block.state.BlockState> source=new HashMap<>();
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) for(int y=64;y<90;y++)
            source.put(new BlockPos(x,y,z),((x&1)==0?Blocks.OAK_LEAVES:Blocks.BIRCH_LEAVES).defaultBlockState());
        var medium=PredictionVegetation.cachedRepresentative(source,0,0,64,4);
        assertTrue(medium.blocks().size()<=4096);
        assertEquals(1,medium.voxelSize());
        medium.blocks().forEach((p,s)->assertEquals(source.get(p),s));
        var again=PredictionVegetation.cachedRepresentative(new HashMap<>(source),0,0,64,4);
        assertEquals(medium.blocks(),again.blocks());
        assertEquals(0xff55aa22,PredictionVegetation.forestTint(0xff55aa22,0xff004400,0));
        assertEquals(PredictionVegetation.forestTint(0xff55aa22,0xff004400,1),
                PredictionVegetation.forestTint(0xff55aa22,0xff004400,100));
    }
}

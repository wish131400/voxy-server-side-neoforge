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








}

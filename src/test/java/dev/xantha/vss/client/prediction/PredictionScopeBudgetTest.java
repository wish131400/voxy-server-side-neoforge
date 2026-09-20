package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionScopeBudgetTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void largeUploadBatchRetainsUnrelatedSeamPatchAndMask() {
        var inputs = new ArrayList<>(List.of(PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(-1,-1,2,64)),
                PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(0,-1,4,96))));
        var seams = new PredictionLodSeams();
        var before = seams.update(inputs).get(0);
        var mask = seams.boundaryMask(inputs.get(0).tile().key());
        // A former >64 change fallback rebuilt every resident seam and bounds.
        for (int i=0;i<80;i++) inputs.add(PredictionLodSeamsTest.surface(PredictionLodSeamsTest.tile(100+i*2,100,1,80)));
        var result = seams.update(inputs);
        assertSame(before,result.get(0));
        assertSame(mask,seams.boundaryMask(inputs.get(0).tile().key()));
        assertTrue(seams.localReuses()>=2);
        var full = new PredictionLodSeams().update(inputs);
        assertEquals(full.size(),result.size());
        for (int i=0;i<full.size();i++) assertArrayEquals(full.get(i).mesh().quads(),result.get(i).mesh().quads());
    }

    @Test void telescopePreservesTerrainPriorityButBoundsFullDecoration() {
        var layout=VssLodLayout.of(16384,6,true,true);
        var focus=new VssLodFocus(8192,0,1024,10000);
        var center=new PredictionTileKey(Level.OVERWORLD,128,0,0);
        var outer=new PredictionTileKey(Level.OVERWORLD,137,0,0);
        assertTrue(PredictionWorkOrder.surfaceEligible(center,layout,0,0,512,focus));
        assertFalse(PredictionWorkOrder.surfaceEligible(outer,layout,0,0,512,focus));
        assertTrue(PredictionWorkOrder.scoped(outer,layout,focus));
        assertTrue(PredictionWorkOrder.surfaceEligible(new PredictionTileKey(Level.OVERWORLD,2,0,0),layout,0,0,512,focus));
        assertTrue(PredictionWorkOrder.priority(center,layout,0,32,false,focus)
                < PredictionWorkOrder.priority(outer,layout,0,32,false,focus));
        assertEquals(3,PredictionWorkOrder.detailBuildLimit(8,true,true));
        assertEquals(8,PredictionWorkOrder.detailBuildLimit(8,false,false));
        assertEquals(1,PredictionWorkOrder.detailBuildLimit(1,true,true));
    }

    @Test void smallAimJitterReusesFocusButMovementAndClosingDoNot() {
        var previous=new VssLodFocus(8192,0,1024,10000);
        assertSame(previous,VssLodFocus.stabilize(previous,new VssLodFocus(8195,4,1032,10064)));
        var moved=new VssLodFocus(8208,0,1024,10000);
        assertSame(moved,VssLodFocus.stabilize(previous,moved));
        assertNull(VssLodFocus.stabilize(previous,null));
        assertSame(moved,VssLodFocus.stabilize(null,moved));
    }

}

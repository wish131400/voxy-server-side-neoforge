package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionWorkViewTest {
    @Test void coneIncludesFrontBufferNearbyAndScopeButNotDistantRear() {
        var layout=VssLodLayout.of(8192,2,true,true);
        var view=PredictionWorkView.of(0,80,0,1,0,0,70,16D/9);
        var front=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,40,0,1);
        var rear=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-40,0,1);
        var nearby=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,0,1);
        assertTrue(view.foreground(front,layout,null,-64,320));
        assertFalse(view.foreground(rear,layout,null,-64,320));
        assertTrue(view.foreground(nearby,layout,null,-64,320));
        assertTrue(view.foreground(rear,layout,new VssLodFocus(-5056,64,1024,9000),-64,320));
        var reversed=PredictionWorkView.of(0,80,0,-1,0,0,70,16D/9);
        assertTrue(reversed.foreground(rear,layout,null,-64,320));
        assertFalse(reversed.foreground(front,layout,null,-64,320));
    }

    @Test void lookingDownFromHighAltitudeUsesTheRealThreeDimensionalView() {
        var layout=VssLodLayout.of(8192,2,true,true);
        var below=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
        var down=PredictionWorkView.of(0,5020,0,0,-1,0,70,16D/9);
        var up=PredictionWorkView.of(0,5020,0,0,1,0,70,16D/9);
        assertTrue(down.foreground(below,layout,null,-64,320));
        assertFalse(up.foreground(below,layout,null,-64,320));
        assertNull(PredictionWorkView.of(0,0,0,0,0,0,70,1));
    }
}

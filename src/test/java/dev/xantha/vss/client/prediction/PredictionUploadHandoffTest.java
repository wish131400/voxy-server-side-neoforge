package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import dev.xantha.vss.client.prediction.PredictionTileManager.RenderSnapshot;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionUploadHandoffTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    private static final VssLodLayout LAYOUT = VssLodLayout.of(65536, 6, true, true);





















    @Test void loadedDetailSurvivesDistanceSelectionAndCoarserChildPreviews() {
        var fullParent = tile(64,-65,1,1);
        var preview = new PredictionTile(new PredictionTileKey(Level.OVERWORLD,128,-130,0),
                new int[0],new int[0],new ClientColumnSample[0],null,
                new PredictionDepthBound(64,64),0,2,8,8);
        var initial = source(Map.of(fullParent.key(),fullParent,preview.key(),preview));
        for (int desired=0; desired<11; desired++) assertSame(fullParent,initial.coveringTile(512,-520,desired),
                "Nominal LOD 0 preview must not replace actual two-block detail");
        var fullChild = tile(128,-130,0,3);
        var refined = source(Map.of(fullParent.key(),fullParent,fullChild.key(),fullChild));
        for (int desired=0; desired<11; desired++) assertSame(fullChild,refined.coveringTile(512,-520,desired),
                "Already loaded distant detail must survive closing the telescope");
    }



    private static RenderSnapshot source(Map<PredictionTileKey, PredictionTile> tiles) {
        return new RenderSnapshot(Level.OVERWORLD, LAYOUT, Map.copyOf(tiles), Map.of());
    }
    private static PredictionTile tile(int x, int z, int lod, long revision) {
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD, x, z, lod),
                new int[0], new int[0], new ClientColumnSample[0], null,
                new PredictionDepthBound(64, 64), 0, revision, 64, 1 << lod);
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCoverageIndexTest {
    @BeforeAll static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test void primitiveIndexPreservesSelectionAcrossMissingLevelsAndNegativeCoordinates() {
        var layout = VssLodLayout.of(4096, 6, true, false);
        var tiles = new HashMap<PredictionTileKey, PredictionTile>();
        var random = new Random(83617);
        for (int lod : new int[]{0, 2, 3, 6}) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            if (random.nextBoolean()) {
                var tile = PredictionCoverageWorkTest.tile(x, z, lod, layout);
                tiles.put(tile.key(), tile);
            }
        }
        var snapshot = new RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
        for (int i = 0; i < 10_000; i++) {
            int x = random.nextInt(2048) - 1024, z = random.nextInt(2048) - 1024;
            int desired = random.nextInt(12) - 1;
            var expected = PredictionTileManager.findCoveringKey(tiles.keySet(), Level.OVERWORLD, layout, x, z, desired);
            assertSame(tiles.get(expected), snapshot.coveringTile(x, z, desired));
        }
    }



    @Test void tileHashesDistributeRegularTerrainGrids() {
        for (int step : new int[]{1, 16, 256}) {
            var hashes = new HashSet<Integer>();
            for (int z = -128; z < 128; z++) for (int x = -128; x < 128; x++)
                hashes.add(new PredictionTileKey(Level.OVERWORLD, x * step, z * step, 0).hashCode());
            assertTrue(hashes.size() > 64_000, "Terrain coordinates should not form long hash collision chains");
        }
    }
}

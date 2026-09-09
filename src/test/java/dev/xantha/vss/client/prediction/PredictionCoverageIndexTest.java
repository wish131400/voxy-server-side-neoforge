package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionCoverageIndexTest {
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

    @Test void chunkGroupsProduceTheSameMaskAsIndividualCells() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var layout = VssLodLayout.of(4096, 6, true, false);
        var method = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTile.class,
                int.class, int.class, double.class, RenderSnapshot.class, VssLodFocus.class);
        method.setAccessible(true);
        var tiles = new HashMap<PredictionTileKey, PredictionTile>();
        for (int lod = 0; lod < layout.levelCount(); lod++) for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) {
            if ((x + z + lod) % 3 != 0) {
                var tile = PredictionCoverageWorkTest.tile(x, z, lod, layout);
                tiles.put(tile.key(), tile);
            }
        }
        var snapshot = new RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
        for (var focus : new VssLodFocus[]{null, new VssLodFocus(-128, 80, 1024, 8)}) {
            double scale = focus == null ? 900 : 9000;
            for (var tile : tiles.values()) {
                var actual = (PredictionRenderer.TileCoverage) method.invoke(null, tile, -1, 2, scale, snapshot, focus);
                boolean[] expected = new boolean[4096];
                for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) {
                    int cx = Math.floorDiv(tile.baseBlockX() + x * tile.spacingBlocks() + tile.spacingBlocks() / 2, 16);
                    int cz = Math.floorDiv(tile.baseBlockZ() + z * tile.spacingBlocks() + tile.spacingBlocks() / 2, 16);
                    double distance = Math.max(64, Math.hypot(cx + 1, cz - 2) * 16);
                    int desired = PredictionRenderer.lodForBlocks(distance, scale, PredictionRenderer.pixelsPerQuad());
                    if (focus != null) {
                        if (focus.contains(cx * 16 + 8, cz * 16 + 8)) desired = Math.min(desired,
                                PredictionRenderer.lodForBlocks(distance, focus.selectionScale(scale), PredictionRenderer.pixelsPerQuad()));
                        if (PredictionWorkOrder.surfaceFocus(focus).intersects(cx * 16D, cz * 16D, (cx + 1D) * 16, (cz + 1D) * 16)) desired = 0;
                    }
                    var key = PredictionTileManager.findCoveringKey(tiles.keySet(), Level.OVERWORLD, layout, cx, cz, desired);
                    expected[z * 64 + x] = tile.key().equals(key) || key == null && desired >= tile.key().lod();
                }
                assertArrayEquals(expected, actual.allowed(), tile.key().toString());
            }
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

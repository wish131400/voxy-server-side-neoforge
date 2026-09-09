package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

class PredictionCoverageWorkTest {
    @Test void movingViewCoverageWorkload() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var layout = VssLodLayout.of(4096, 6, true, false);
        var tiles = new HashMap<PredictionTileKey, PredictionTile>();
        var visible = new ArrayList<PredictionTile>();
        for (int lod = 0; lod < layout.levelCount(); lod++) for (int z = -8; z < 8; z++) for (int x = -8; x < 8; x++) {
            var tile = tile(x, z, lod, layout);
            tiles.put(tile.key(), tile);
            if (z >= 0 && z < 4 && x >= -4 && x < 4) visible.add(tile);
        }
        var snapshot = new RenderSnapshot(Level.OVERWORLD, layout, Map.copyOf(tiles), Map.of());
        var method = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTile.class,
                int.class, int.class, double.class, RenderSnapshot.class, VssLodFocus.class);
        method.setAccessible(true);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long allocated = 0; var times = new ArrayList<Double>();
        for (int frame = 0; frame < 20; frame++) {
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            long considered = 0;
            for (var tile : visible) {
                var coverage = (PredictionRenderer.TileCoverage) method.invoke(null, tile, frame % 3, 0,
                        1024D + frame * 16, snapshot, null);
                considered += coverage.considered();
                assertEquals(4096, coverage.allowed().length);
                assertEquals(coverage.considered(), coverage.rendered() + coverage.coverageSkipped());
            }
            double elapsed = (System.nanoTime() - start) / 1e6;
            if (frame >= 8) { times.add(elapsed); allocated += bean.getThreadAllocatedBytes(thread) - before; }
            assertEquals(visible.size() * 4096L, considered);
        }
        Collections.sort(times);
        System.out.printf(Locale.ROOT, "COVERAGE_WORK tiles=%d resident=%d medianMs=%.3f maxMs=%.3f allocatedMiBPerFrame=%.3f%n",
                visible.size(), tiles.size(), times.get(times.size() / 2), times.getLast(), allocated / 1048576D / times.size());
    }

    static PredictionTile tile(int x, int z, int lod, VssLodLayout layout) {
        var mesh = new PredictionMesh(new float[0], new float[0], new int[0], new float[0], new float[0],
                new int[0], new boolean[0], 0, 0, 4096, null, null, null, null, 64);
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD, x, z, lod), new int[0], new int[0],
                new ClientColumnSample[0], mesh, new PredictionDepthBound(64, 64), 0, 1, 64, layout.sampleSpacing(lod));
    }
}

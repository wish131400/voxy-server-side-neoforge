package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionLodSeamsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void mixedLevelsConnectExactlyTheDisplayedHeightsOnBothAxesAndHeightOrders() {
        for (int step : new int[]{1, 2, 4, 16, 64}) for (boolean alongX : new boolean[]{true, false})
            for (boolean fineHigher : new boolean[]{true, false}) {
                var fine = tile(-1, -1, step, fineHigher ? 96 : 64);
                var coarse = tile(alongX ? 0 : -1, alongX ? -1 : 0, step * 2, fineHigher ? 64 : 96);
                var patches = new PredictionLodSeams().update(List.of(surface(fine), surface(coarse)));
                assertEquals(1, patches.size());
                var packed = patches.getFirst().mesh();
                assertEquals(fine, patches.getFirst().surface().tile());
                assertEquals(64 * step * 32D, area(packed), "every fine segment must connect once");
                for (int q = 0; q < packed.quadCount(); q++) {
                    int[] words = packed.quads();
                    int offset = q * 12;
                    int top = ((words[offset + 4] & 65535) - 32768) / 4;
                    int bottom = ((words[offset + 5] & 65535) - 32768) / 4;
                    assertTrue(top <= 96 && bottom >= 64 && top > bottom, "no underground curtain or bottom plane");
                    assertNotEquals(0, words[offset + 9] & 1 << 24, "ownership remains on the fine source cell");
                }
            }
    }

    @Test void partialParentCoverageStitchesAllFourSidesAndRefreshesWhenNeighborChanges() {
        var fine = tile(1, 1, 1, 64);
        var coarse = tile(0, 0, 4, 96);
        var parent = surface(coarse);
        for (int z = 16; z < 32; z++) for (int x = 16; x < 32; x++) parent.allowed()[z * 64 + x] = false;
        var cache = new PredictionLodSeams();
        var patches = cache.update(List.of(surface(fine), parent));
        assertEquals(4 * 64 * 32D, patches.stream().mapToDouble(p -> area(p.mesh())).sum());
        assertSame(patches, cache.update(List.of(new PredictionLodSeams.Surface(fine, surface(fine).allowed()),
                new PredictionLodSeams.Surface(coarse, parent.allowed().clone()))), "stationary frames reuse seam data");
        var replacement = new PredictionLodSeams.Surface(tile(0, 0, 4, 80), parent.allowed());
        var next = cache.update(List.of(surface(fine), replacement));
        assertNotSame(patches, next);
        assertEquals(4 * 64 * 16D, next.stream().mapToDouble(p -> area(p.mesh())).sum());
        assertTrue(cache.update(List.of(surface(fine))).isEmpty(), "removed neighbors leave no stale seams");
    }

    @Test void equalHeightBoundariesNeedNoExtraGeometry() {
        assertTrue(new PredictionLodSeams().update(List.of(surface(tile(-1, -1, 2, 64)),
                surface(tile(0, -1, 8, 64)))).isEmpty());
    }

    @Test void refinedTileStitchesToAStillCoarseNeighborWithoutWaiting() {
        var fine=tile(-1,0,4,64);
        var coarse=tile(0,0,64,96);
        var patches=new PredictionLodSeams().update(List.of(surface(fine),surface(coarse)));
        assertEquals(256*32D,patches.stream().mapToDouble(p->area(p.mesh())).sum(),
                "The full shared edge must be closed even across a temporary 16:1 spacing difference");
    }

    @Test void unrelatedUploadsReuseExistingSeamPayloads() {
        var inputs = new ArrayList<>(List.of(surface(tile(-1, -1, 2, 64)), surface(tile(0, -1, 4, 96))));
        var cache = new PredictionLodSeams();
        var initial = cache.update(inputs).getFirst().mesh();
        inputs.add(surface(tile(100, 100, 16, 80)));
        assertSame(initial, cache.update(inputs).getFirst().mesh());
        cache.clear();
        assertNotSame(initial, cache.update(inputs).getFirst().mesh());
    }

    @Test void copiedCoverageCanonicalizesNeighborsButChangedCoverageInvalidatesThem() {
        var fine = surface(tile(-1, -1, 2, 64));
        var coarse = surface(tile(0, -1, 4, 96));
        var seams = new PredictionLodSeams();
        var initial = seams.update(List.of(fine, coarse)).getFirst().mesh();
        var copy = new PredictionLodSeams.Surface(coarse.tile(), coarse.allowed().clone());
        var distant = surface(tile(100, 100, 16, 80));
        assertSame(initial, seams.update(List.of(fine, copy, distant)).getFirst().mesh());
        boolean[] changed = coarse.allowed().clone();
        Arrays.fill(changed, false);
        assertTrue(seams.update(List.of(fine, new PredictionLodSeams.Surface(coarse.tile(), changed), distant)).isEmpty());
        assertEquals(area(initial), area(seams.update(List.of(fine, coarse, distant)).getFirst().mesh()));
    }

    @Test void seamSummaryFollowsMeshLifetimeAndNeedsNoRenderCache() {
        var original = tile(0, 0, 2, 64);
        var replacement = tile(0, 0, 2, 80);
        var first = original.mesh().seamMesh();
        assertSame(first, original.mesh().seamMesh());
        assertNotSame(first, replacement.mesh().seamMesh());
        var seams = new PredictionLodSeams();
        seams.update(List.of(surface(original), surface(tile(1, 0, 2, 80))));
        long builds = PredictionSeamMesh.builds();
        seams.clear();
        assertSame(first, original.mesh().seamMesh(), "visibility caches never own or rebuild wall data");
        assertEquals(builds, PredictionSeamMesh.builds());
        assertThrows(IllegalStateException.class, original.mesh()::packed,
                "published tiles must release the complete CPU quad view");
    }

    @Test void turningAcrossLargeVisibleSetsReusesIndicesAndPreservesExactSeams() {
        var all = new ArrayList<PredictionLodSeams.Surface>();
        for (int z = 0; z < 12; z++) for (int x = 0; x < 14; x++) {
            int height = ((x + z) & 1) == 0 ? 96 : 64;
            all.add(surface(wetTile(x, z, 2, height, 0, height - 16)));
        }
        var seams = new PredictionLodSeams();
        seams.update(all);
        long builds = seams.wallIndexBuilds();
        assertTrue(builds > 64, "exercise the old cache's working-set overflow");
        long warmNanos = 0, coldNanos = 0;
        for (int turn = 0; turn < 12; turn++) {
            var visible = new ArrayList<PredictionLodSeams.Surface>();
            for (var surface : all) {
                int x = surface.tile().key().tileX();
                if ((turn & 1) == 0 ? x < 11 : x >= 3) visible.add(surface);
            }
            long start = System.nanoTime();
            var actual = seams.update(visible);
            warmNanos += System.nanoTime() - start;
            start = System.nanoTime();
            var expected = new PredictionLodSeams().update(visible);
            coldNanos += System.nanoTime() - start;
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < actual.size(); i++) {
                assertSame(expected.get(i).surface().tile(), actual.get(i).surface().tile());
                assertArrayEquals(expected.get(i).mesh().quads(), actual.get(i).mesh().quads(),
                        "visibility changes must preserve every seam vertex, material and coverage owner");
            }
        }
        assertEquals(builds, seams.wallIndexBuilds(), "turning must not rescan unchanged geometry");
        System.out.println("SEAM_TURN_REPLAY tiles=" + all.size() + ",turns=12,initialBuilds=" + builds
                + ",repeatBuilds=" + (seams.wallIndexBuilds() - builds)
                + ",warmMs=" + warmNanos / 1e6 + ",coldMs=" + coldNanos / 1e6);
    }

    @Test void seamSummaryBytesAreChargedToTheOwningTile() {
        var tile = tile(0, 0, 2, 64);
        long payload = tile.mesh().gpuPayload().retainedHeapBytes();
        long summary = tile.mesh().seamMesh().retainedHeapBytes();
        assertTrue(summary > 0);
        assertTrue(tile.mesh().retainedHeapBytes() >= payload + summary);
        assertTrue(tile.retainedHeapBytes() >= tile.mesh().retainedHeapBytes());
        long builds = PredictionSeamMesh.builds();
        for (int i = 0; i < 100; i++) new PredictionLodSeams.Index(List.of(surface(tile)));
        assertEquals(builds, PredictionSeamMesh.builds(), "camera frames cannot rebuild worker-owned indices");
    }

    @Test void underwaterSeamsUseTheSameWaterDepthLightingAsOrdinaryWalls() {
        var fine = wetTile(-1, -1, 2, 50, 55, 50);
        var coarse = wetTile(0, -1, 4, 32, 55, 32);
        var mesh = new PredictionLodSeams().update(List.of(surface(fine), surface(coarse))).getFirst().mesh();
        for (int q = 0; q < mesh.quadCount(); q++) {
            int i = q * 12; int[] w = mesh.quads();
            for (int corner = 0; corner < 4; corner++) {
                int yw = w[i + (corner < 2 ? 4 : 5)];
                int y = (((yw >>> ((corner & 1) * 16)) & 65535) - 32768) / 4;
                assertEquals(Math.clamp(55 - y, 0, 15), w[i + (corner == 0 ? 7 : 8 + corner)] >>> 28,
                        "a stitch must not turn a submerged cliff into a full-sky bright window");
            }
        }
    }

    @Test void existingVisibleCliffFacesAreNotDrawnAgainByStitches() {
        for (int margin : new int[]{64, 80}) {
            var fine = wetTile(-1, -1, 2, 96, 0, margin);
            var coarse = wetTile(0, -1, 4, 64, 0, 64);
            var patches = new PredictionLodSeams().update(List.of(surface(fine), surface(coarse)));
            assertEquals(64 * 2 * (margin - 64D), patches.stream().mapToDouble(p -> area(p.mesh())).sum(),
                    "stitches should fill only the missing vertical interval, never coplanar existing walls");
        }
    }

    @Test void negativeFacingMarginWallsAlsoKeepTheirExistingGeometry() {
        var fine = wetTile(-1, -1, 2, 32, 63, 50);
        var coarse = wetTile(0, -1, 4, 50, 63, 50);
        assertTrue(new PredictionLodSeams().update(List.of(surface(fine), surface(coarse))).isEmpty(),
                "the fine tile already owns the inward wall emitted by its higher margin sample");
    }

    private static PredictionTile wetTile(int tx, int tz, int step, int height, int water, int eastMargin) {
        int[] heights = new int[65 * 65]; Arrays.fill(heights, height);
        var samples = new ClientColumnSample[heights.length];
        for (int z = 0; z < 65; z++) for (int x = 0; x < 65; x++) {
            int y = x == 64 ? eastMargin : height;
            heights[z * 65 + x] = y;
            samples[z * 65 + x] = new ClientColumnSample(y, water, 0, ClientColumnSample.NO_BLOCK,
                    0, 0, 0, 0, water > y ? 1 : 0, PredictionWallEvidence.CHECKED, 0,
                    ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK, -64,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
        int[] colors = new int[heights.length]; Arrays.fill(colors, 0xFF808080);
        var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0xFF3F76E4, step, 65, false).compactForRendering();
        var tile = new PredictionTile(new PredictionTileKey(Level.OVERWORLD, tx, tz, Integer.numberOfTrailingZeros(step)),
                heights, heights, samples, mesh, new PredictionDepthBound(0, Math.max(height, water)), 0, height, 64, step);
        mesh.prepareGpuPayload(tile); return tile;
    }

    static PredictionLodSeams.Surface surface(PredictionTile tile) {
        boolean[] allowed = new boolean[64 * 64]; Arrays.fill(allowed, true);
        return new PredictionLodSeams.Surface(tile, allowed);
    }

    static PredictionTile tile(int tx, int tz, int step, int height) {
        int[] heights = new int[65 * 65]; Arrays.fill(heights, height);
        var samples = new ClientColumnSample[heights.length];
        Arrays.fill(samples, new ClientColumnSample(height, height, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0, 0,
                PredictionWallEvidence.CHECKED, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                -64, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        int[] colors = new int[heights.length]; Arrays.fill(colors, 0xFF00FF00);
        var mesh = PredictionMeshBuilder.build(samples, colors, 63, 0, step, 65, false).compactForRendering();
        var tile = new PredictionTile(new PredictionTileKey(Level.OVERWORLD, tx, tz, Integer.numberOfTrailingZeros(step)),
                heights, heights, samples, mesh, new PredictionDepthBound(height, height), 0, height, 64, step);
        mesh.prepareGpuPayload(tile);
        return tile;
    }

    private static double area(PredictionPackedMesh mesh) {
        double sum = 0;
        for (int q = 0; q < mesh.quadCount(); q++) {
            int i = q * 12; int[] w = mesh.quads();
            double length = Math.abs((w[i] & 65535) - (w[i] >>> 16))
                    + Math.abs((w[i + 2] & 65535) - (w[i + 2] >>> 16));
            length *= 1 << ((w[i + 6] >>> 16) & 15);
            sum += length * ((w[i + 4] & 65535) - (w[i + 5] & 65535)) / 4D;
        }
        return sum;
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class PredictionSubmissionCoverageTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static PredictionExactCoverageMask.Snapshot mask(int min, int max) {
        var index = new PredictionExactCoverageIndex();
        for (int z = min; z <= max; z++) for (int x = min; x <= max; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        return index.snapshot(Level.OVERWORLD, 0, 0, 64, ExactCoverageGate.SETTLE_NANOS);
    }
    private static int[] quad(int x, int z, int flags) {
        int y = 32768 + 64 * 4;
        return new int[]{x | (x + 1) << 16, x | (x + 1) << 16, z | z << 16, z + 1 | (z + 1) << 16,
                y | y << 16, y | y << 16, flags, -1, 0, -1, -1, -1};
    }
    private static PredictionDrawRanges filter(int[] words, PredictionExactCoverageMask.Snapshot index,
                                               double base, double height, double radius) {
        var ranges = new PredictionDrawRanges(new int[]{0}, new int[]{words.length / 12});
        return new PredictionOwnershipRuns(words).filter(ranges, index, base, base, 4, height, 4, radius,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
    }

    @Test void stableInteriorRemovesOpaqueAndWaterWithoutAnyDepthTest() {
        for (int fluid : new int[]{0, 1}) {
            var words = quad(33, 33, fluid << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
            assertEquals(0, filter(words, mask(0, 6), 0, 68, 4096).quads);
            assertEquals(1, filter(words, null, 0, 68, 4096).quads);
            assertEquals(1, filter(words, mask(0, 6), 0, 68, 0).quads);
        }
    }

    @Test void wholeMeshOwnershipProofIsRevokedWithCoverageAltitudeAndMorph() {
        var packed = PredictionPackedMesh.terrainRecords(quad(33,33,0),1);
        var coverage = new PredictionSubmissionCoverage();
        coverage.update(mask(0,6),new Vec3(4,68,4),4096);
        assertTrue(coverage.fullyOwned(packed,0,0));
        coverage.update(null,new Vec3(4,68,4),4096);
        assertFalse(coverage.fullyOwned(packed,0,0));
        coverage.update(mask(0,6),new Vec3(4,1000,4),480);
        assertFalse(coverage.fullyOwned(packed,0,0));
        packed.morph(new float[]{0},-64,1000);
        coverage.update(mask(0,6),new Vec3(4,68,4),480);
        assertFalse(coverage.fullyOwned(packed,0,0));
    }

    @Test void boundaryCrossingsMissingAndUnsettledColumnsPreserveWholeGeometry() {
        assertEquals(1, filter(quad(1, 33, 0), mask(0, 6), 0, 68, 4096).quads, "one chunk boundary fallback");
        assertEquals(1, filter(quad(16, 33, 0), mask(0, 6), 0, 68, 4096).quads, "closed edge also touches boundary chunk");
        var index = new PredictionExactCoverageIndex();
        long now = ExactCoverageGate.SETTLE_NANOS;
        for (int z = 0; z < 7; z++) for (int x = 0; x < 7; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        index.remove(Level.OVERWORLD, 2, 2);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now), 0, 68, 4096).quads);
        index.confirm(Level.OVERWORLD, 2, 2, now);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now), 0, 68, 4096).quads);
        assertEquals(0, filter(quad(33, 33, 0), index.snapshot(Level.OVERWORLD, 0, 0, 64, now * 2), 0, 68, 4096).quads);
        assertEquals(1, filter(quad(33, 33, 0), index.snapshot(Level.NETHER, 0, 0, 64, now * 2), 0, 68, 4096).quads);
    }

    @Test void negativeCoordinatesAltitudeMorphAndCameraCellMarginAreConservative() {
        var words = quad(33, 33, 0);
        assertEquals(0, filter(words, mask(-7, 0), -96, 68, 4096).quads);
        assertEquals(1, filter(words, mask(0, 6), 0, 1000, 480).quads);
        assertEquals(1, filter(words, mask(0, 6), 0, 68, 64).quads);
        var runs = new PredictionOwnershipRuns(words);
        assertEquals(1, runs.filter(new PredictionDrawRanges(new int[]{0}, new int[]{1}), mask(0, 6),
                0, 0, 4, 68, 4, 480, -64, 1000).quads, "morph can move the mesh outside Voxy's sphere");
    }

    @Test void interleavedOwnershipPreservesOrderAndDoesNotApplyFaceBitmaskToRuns() {
        int[] words = new int[160 * 12];
        for (int q = 0; q < 160; q++) System.arraycopy(quad(q % 2 == 0 ? 33 : 1, 33, 0), 0, words, q * 12, 12);
        var filtered = filter(words, mask(0, 6), 0, 68, 4096);
        assertEquals(80, filtered.quads); assertEquals(80, filtered.first.length);
        for (int i = 0; i < 80; i++) { assertEquals(i * 2 + 1, filtered.first[i]); assertEquals(1, filtered.count[i]); }
    }

    @Test void passCacheRestoresGeometryWhenIndexRangeOrPositionChanges() {
        var mesh = PredictionPackedMesh.terrainRecords(quad(33, 33, 0), 64);
        var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        var state = new PredictionSubmissionCoverage(); var index = mask(0, 6);
        state.update(index, new Vec3(0, 64, 0), 4096);
        var removed = state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL);
        assertEquals(0, removed.quads);
        assertSame(removed, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        long revision = state.revision();
        state.update(index, new Vec3(7.99, 71.99, 7.99), 4096); assertEquals(revision, state.revision());
        state.update(index, new Vec3(0, 10000, 0), 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(index, new Vec3(0, 64, 0), 0);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
        state.update(null, new Vec3(0, 64, 0), 4096);
        assertSame(ranges, state.filter(mesh, ranges, 0, 0, false, VssLodFaceGroup.ALL));
    }

    @Test void voxyRetirementDoesNotWaitForAnUnuploadedPredictionAncestor() {
        var layout = VssLodLayout.of(8192, 6, true, true);
        var tile = PredictionCoverageWorkTest.tile(0, 0, 0, layout);
        var parent = PredictionCoverageWorkTest.tile(0, 0, 1, layout);
        var initial = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(tile.key(), tile), Map.of());
        var state = new PredictionRenderResidency(); state.retain(initial); state.uploaded(tile);
        var waiting = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout, Map.of(parent.key(), parent), Map.of());
        state.retain(waiting); assertTrue(state.contains(tile), "normal prediction refinement must wait");
        var handedOff = new PredictionTileManager.RenderSnapshot(Level.OVERWORLD, layout,
                Map.of(parent.key(), parent), Map.of(), null, Set.of(tile.key()));
        state.retain(handedOff); assertFalse(state.contains(tile), "Voxy ownership is already the replacement");
    }
}

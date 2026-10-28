package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionParentLodTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    static ClientColumnSample sample(int y) {
        return new ClientColumnSample(y, y, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN);
    }
    static PredictionTile tile(PredictionTileKey key, long revision, int height) {
        int axis = 8, step = 64 * (1 << key.lod()) / axis;
        var samples = new ClientColumnSample[81]; Arrays.fill(samples, sample(height));
        int[] heights = new int[81]; Arrays.fill(heights, height);
        return new PredictionTile(key, heights, heights, samples,
                PredictionVoxelFixtures.flat(key,axis,step,height), new PredictionDepthBound(height, height),
                1, revision, axis, step);
    }
    static Map<PredictionTileKey, PredictionTile> children(PredictionTileKey parent) {
        var result = new HashMap<PredictionTileKey, PredictionTile>();
        for (int i = 0; i < 4; i++) {
            var key = new PredictionTileKey(parent.dimension(), parent.tileX()*2+(i&1),
                    parent.tileZ()*2+(i>>1), parent.lod()-1);
            result.put(key, tile(key, i+1, 64));
        }
        return result;
    }
    static final VssLodLayout LAYOUT = VssLodLayout.of(4096, 2, false, false);

    @Test void wallEvidenceDoesNotChangeSampleProvenance() {
        var captured = PredictionWallEvidence.inspectCaptured(withFlags(sample(96), ClientColumnSample.FLAG_CAPTURED),
                -64, y -> y >= 80 || y < 64);
        assertTrue(captured.reusableFor(true), "checked real columns must remain reusable for previews");
        assertTrue(captured.reusableFor(false), "checked real columns must remain reusable for exact terrain");
        assertTrue(captured.reusableForDisplay());
        assertTrue(PredictionWallEvidence.hasInterior(captured));
        assertEquals(0, ClientColumnSample.FLAG_PARENT_LOD & (PredictionWallEvidence.CHECKED
                | PredictionWallEvidence.CAPTURED_OCCUPANCY | PredictionExteriorColumns.CHECKED));
    }

    @Test void approximateDisplayAndCaptureMipsCanReduceForDisplayButNeverBecomeExactInputs() {
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var children = children(key);
        var first = children.values().iterator().next();
        first.samples()[0] = withFlags(sample(-44), ClientColumnSample.FLAG_APPROXIMATE
                | ClientColumnSample.FLAG_SURFACE_ONLY | PredictionWallEvidence.CHECKED);
        var input = PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of());
        assertNotNull(input);
        for(int flags:new int[]{ClientColumnSample.FLAG_APPROXIMATE,ClientColumnSample.FLAG_DISPLAY,
                ClientColumnSample.FLAG_CAPTURE_MIP,ClientColumnSample.FLAG_PARENT_LOD}) {
            for(var child:children.values())Arrays.fill(child.samples(),withFlags(sample(-44),flags
                    | ClientColumnSample.FLAG_SURFACE_ONLY | PredictionWallEvidence.CHECKED));
            var result=PredictionParentLod.reduce(input);
            assertNotNull(result,"Drawable child provenance must not fail after structural admission");
            assertTrue(result.proof().current(children,Set.of(),Map.of()));
            for(var sample:result.samples()) {
                assertFalse(sample.reusableFor(false),"Display reduction must never contaminate exact sampling");
                assertTrue((sample.flags()&ClientColumnSample.FLAG_PARENT_LOD)!=0);
            }
        }
    }

    private static ClientColumnSample withFlags(ClientColumnSample s, int flags) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(), flags,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(),
                s.lowerTop(), s.lowerBottom(), s.spanFloor(), s.volume());
    }

    @Test void reducesAllQuadrantsAndKeepsSourceVersionsAtNegativeCoordinates() {
        var key = new PredictionTileKey(Level.OVERWORLD, -2, -3, 1);
        var children = children(key);
        var input = PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of());
        assertNotNull(input);
        var result = PredictionParentLod.reduce(input);
        assertEquals(100, result.samples().length);
        for (var s : result.samples()) {
            assertEquals(64, s.surfaceY());
            assertFalse(s.reusableFor(false), "a coarse mip must not poison exact generation samples");
            assertFalse(s.captured());
        }
        assertTrue(result.proof().current(children, Set.of(), Map.of()));
        assertTrue(result.proof().describes(input.children()));
        assertEquals(16, result.proof().error());
        assertSame(children.get(input.sources()[0].key()).samples()[0], input.children()[0].samples()[0]);
    }

    @Test void aFineRidgeSurvivesAggregationInsteadOfDisappearingBetweenMipPoints() {
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var children = children(key);
        var fine = children.get(new PredictionTileKey(Level.OVERWORLD, 0, 0, 0));
        fine.samples()[30] = sample(120);
        var input = PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of());
        var result = PredictionParentLod.reduce(input);
        assertEquals(120, result.samples()[22].surfaceY());
        assertTrue(result.proof().error() >= 56);
        assertEquals(120, fine.samples()[30].surfaceY(), "source arrays must remain immutable during reduction");
    }

    @Test void decorationPublicationKeepsTerrainProofWhileNewTerrainAndEditsStillInvalidateIt() {
        var key=new PredictionTileKey(Level.OVERWORLD,0,0,1);
        var children=children(key);
        var input=PredictionParentLod.capture(key,8,LAYOUT,children,Set.of(),Map.of());
        var proof=PredictionParentLod.reduce(input).proof();
        var old=input.children()[0];
        var mesh=PredictionVoxelData.forVoxy(old.data().voxyTile);
        mesh.terrainRevision=old.terrainRevision();
        var decorated=new PredictionTile(old.key(),old.heights(),old.groundHeights(),old.samples(),mesh,
                old.depthBound(),2,999,old.cellAxis(),old.spacingBlocks());
        children.put(old.key(),decorated);
        assertTrue(proof.current(children,Set.of(),Map.of()),"Plants do not change the parent terrain mip");
        var updated=input.children().clone();updated[0]=decorated;
        assertTrue(proof.describes(updated));
        assertFalse(proof.current(children,Set.of(),Map.of(old.key(),1L)));
        children.put(old.key(),tile(old.key(),1000,70));
        assertFalse(proof.current(children,Set.of(),Map.of()));
    }

    @Test void missingDirtyLowResolutionAndChangedSourcesCannotPublishACoherentParent() {
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var children = children(key); var first = children.keySet().iterator().next();
        assertNull(PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(first), Map.of()));
        assertNull(PredictionParentLod.capture(key, 64, LAYOUT, children, Set.of(), Map.of()));
        var proof = PredictionParentLod.reduce(PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of())).proof();
        assertFalse(proof.current(children, Set.of(first), Map.of()));
        assertFalse(proof.current(children, Set.of(), Map.of(first, 1L)));
        children.put(first, tile(first, 999, 64));
        assertFalse(proof.current(children, Set.of(), Map.of()));
        children.remove(first);
        assertNull(PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of()));
    }

    @Test void cavityChangesForceAnUnboundedErrorRatherThanHidingThemWithAHeightOnlyProxy() {
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var children = children(key); var fine = children.values().iterator().next();
        var s = fine.samples()[1];
        fine.samples()[1] = new ClientColumnSample(s.surfaceY(), s.fluidY(), 0, 1, 0, 0, 0, 0, 0, 0,
                0, 1, 1, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, PredictionColumnVolume.ofRuns(new int[]{0,16,1,0,32,64,1,0}));
        var result = PredictionParentLod.reduce(PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of()));
        assertEquals(Double.POSITIVE_INFINITY, result.proof().error());
    }

    @Test void coarseInterpolationCannotUnderestimateAPlateauBetweenMipCorners() {
        var key = new PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var children = children(key); var fine = children.get(new PredictionTileKey(Level.OVERWORLD, 0, 0, 0));
        for (int z = 2; z < 4; z++) for (int x = 2; x < 4; x++) fine.samples()[z * 9 + x] = sample(120);
        var result = PredictionParentLod.reduce(PredictionParentLod.capture(key, 8, LAYOUT, children, Set.of(), Map.of()));
        assertTrue(result.proof().error() >= 56, "zero error at mip anchors is not a bound on the interpolated surface");
    }
}

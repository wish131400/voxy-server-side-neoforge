package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class PredictionCaveInteriorTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    static ClientColumnSample cave() {
        return PredictionWallEvidence.inspectCaptured(captured(120), -64,
                y -> y >= 105 || y < 65);
    }

    static ClientColumnSample captured(int height) {
        var s = PredictionSimpleVegetationTest.sample(height);
        return withFlags(s, s.flags() | ClientColumnSample.FLAG_CAPTURED);
    }

    static ClientColumnSample withFlags(ClientColumnSample s, int flags) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(), flags,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(),
                s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }

    private static ClientColumnSample[] grid(ClientColumnSample sample, int axis) {
        var result = new ClientColumnSample[axis * axis]; Arrays.fill(result, sample); return result;
    }

    private static PredictionTileManager.PredictionTile tile(ClientColumnSample sample, int step) {
        var samples = grid(sample, 3);
        var heights = new int[9]; Arrays.fill(heights, sample.surfaceY());
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, step, 3, false).compactForRendering();
        return new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,4),
                heights, heights, samples, mesh, PredictionDepthBound.fromSamples(samples), 0, 1, 2, step);
    }

    @Test void capsHaveCorrectHeightsNormalsAndNeverAddWaterOrSealTheOpening() {
        var mesh = PredictionMeshBuilder.build(grid(cave(),3),null,63,0,1,3,false);
        int floor=0, ceiling=0;
        for(int v=0;v<mesh.vertexCount();v++) {
            if(mesh.y(v)==65 && mesh.normalY(v)==1) floor++;
            if(mesh.y(v)==105 && mesh.normalY(v)==-1) ceiling++;
            assertFalse(mesh.y(v)>65 && mesh.y(v)<105);
        }
        assertEquals(4*6,floor); assertEquals(4*6,ceiling);
        assertEquals(0,mesh.waterVertexCount());
        var packed=mesh.packed();
        for(int cell=0;cell<4;cell++) assertEquals(120,packed.y(packed.quadForCell(cell),0));
        double ceilingArea=0;
        for(int q=0;q<packed.quadCount();q++) if(packed.normalY(q,0)==-1) {
            double area=0;
            for(int c=0;c<4;c++) area+=packed.x(q,c)*packed.z(q,(c+1)%4)-packed.z(q,c)*packed.x(q,(c+1)%4);
            ceilingArea+=Math.abs(area)*.5;
        }
        assertEquals(2*2,ceilingArea,"packing must retain the complete ceiling rectangle");
    }

    @Test void unknownSolidMissingFloorAndMismatchedRoofsDoNotGetInventedCaps() {
        for(var s:List.of(PredictionSimpleVegetationTest.sample(120),
                PredictionWallEvidence.inspectCaptured(captured(120),-64,y->true),
                PredictionWallEvidence.inspectCaptured(captured(120),-64,y->y>=105),
                PredictionWallEvidence.inspectCaptured(captured(120),-64,y->y<110))) {
            assertFalse(PredictionWallEvidence.hasInterior(s));
            var t=tile(s,1);
            assertFalse(PredictionPackedMesh.pack(t).downFaces());
            assertEquals(120,t.depthBound().minY());
        }
    }

    @Test void boundsAndFaceSelectionKeepCeilingsAvailableBelowTheTile() {
        var t=tile(cave(),1);
        assertEquals(new PredictionDepthBound(65,120),t.depthBound());
        t.mesh().prepareGpuPayload(t);
        assertTrue(t.mesh().gpuPayload().downFaces());
        var geometry=new PredictionRenderGeometry();
        geometry.update(new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,VssLodLayout.of(8192,6,true,false),
                Map.of(t.key(),t),Map.of()));
        var visible=geometry.visible(new Vec3(-10,40,-10),null,8192);
        assertEquals(1,visible.size());
        assertNotEquals(0,visible.getFirst().faces() & 1 << VssLodFaceGroup.HORIZONTAL);
        assertEquals(120,t.mesh().seamMesh().topY(0));
    }

    @Test void surfaceMorphCannotShiftConfirmedCaveBoundaries() {
        assertNotNull(PredictionMorph.field(tile(PredictionSimpleVegetationTest.sample(120),8),
                tile(PredictionSimpleVegetationTest.sample(126),16)));
        assertNull(PredictionMorph.field(tile(cave(),8),tile(PredictionSimpleVegetationTest.sample(126),16)));
    }

    @Test void predictedLegacyAndCoarseCapturesCannotCreateInteriorGeometry() {
        var trusted = cave();
        var predicted = withFlags(trusted, trusted.flags() & ~ClientColumnSample.FLAG_CAPTURED);
        var legacy = withFlags(trusted, trusted.flags() & ~PredictionWallEvidence.CAPTURED_OCCUPANCY);
        for (int step : new int[]{1, 2, 4, 8, 16, 128}) {
            for (var s : List.of(predicted, legacy, trusted)) {
                if (s == trusted && step == 1) continue;
                assertFalse(PredictionWallEvidence.hasInterior(s, step));
                assertFalse(PredictionPackedMesh.pack(tile(s, step)).downFaces(),
                        "untrusted or coarse columns must not add cave ceilings");
            }
        }
    }

    @Test void marginCroppingKeepsExactlyTwoCapsPerConfirmedCell() {
        var samples=grid(PredictionSimpleVegetationTest.sample(120),10);
        samples[11]=cave();
        var mesh=PredictionMeshBuilder.build(samples,null,63,0,1,10,false);
        int floor=0,ceiling=0;
        for(int v=0;v<mesh.vertexCount();v++) {
            if(mesh.y(v)==65 && mesh.normalY(v)==1) floor++;
            if(mesh.y(v)==105 && mesh.normalY(v)==-1) ceiling++;
        }
        assertEquals(6,floor); assertEquals(6,ceiling);
    }

    @Test void enclosedCavityHasFourRockSidesEvenWhenAllSurfaceHeightsMatch() {
        var samples = grid(PredictionWallEvidence.inspectCaptured(captured(120), -64, y -> true), 4);
        samples[5] = cave();
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 4, false);
        assertEquals(160, verticalArea(mesh), 0.001,
                "a 40-block cavity needs four one-block-wide sides, not a transparent interior");
    }

    @Test void changingCaveFloorAndCeilingExposeOnlyTheSolidDifference() {
        var samples = grid(cave(), 4);
        samples[5] = PredictionWallEvidence.inspectCaptured(captured(120), -64,
                y -> y >= 95 || y < 85);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 4, false);
        assertEquals(120, verticalArea(mesh), 0.001,
                "each side exposes 20 blocks of raised floor and 10 blocks of lowered ceiling");
        for (int v = 0; v < mesh.vertexCount(); v++) if (mesh.normalY(v) == 0)
            assertFalse(mesh.y(v) > 85 && mesh.y(v) < 95, "shared air must remain open");
    }

    private static double verticalArea(PredictionMesh mesh) {
        double area = 0;
        for (int v = 0; v < mesh.vertexCount(); v += 3) {
            if (mesh.normalY(v) != 0) continue;
            double ax = mesh.x(v + 1) - mesh.x(v), ay = mesh.y(v + 1) - mesh.y(v), az = mesh.z(v + 1) - mesh.z(v);
            double bx = mesh.x(v + 2) - mesh.x(v), by = mesh.y(v + 2) - mesh.y(v), bz = mesh.z(v + 2) - mesh.z(v);
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            area += Math.sqrt(nx * nx + ny * ny + nz * nz) * .5;
        }
        return area;
    }
}

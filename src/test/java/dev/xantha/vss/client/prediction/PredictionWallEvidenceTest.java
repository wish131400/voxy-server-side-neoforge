package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PredictionWallEvidenceTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void wallEvidenceSurvivesDiskRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var roof=PredictionCaveInteriorTest.cave();
        var key=PredictionDiskCache.Key.terrain(0,0,0);
        try(var cache=new PredictionDiskCache(directory,917);var lease=cache.lease(key)) {
            assertTrue(cache.writeTerrain(lease,new ClientColumnSample[]{roof}));
        }
        try(var cache=new PredictionDiskCache(directory,917);var lease=cache.lease(key)) {
            var restored=cache.readTerrain(lease,1)[0];
            assertEquals(roof,restored);
            assertEquals(PredictionWallEvidence.intervals(roof,50,120,1),PredictionWallEvidence.intervals(restored,50,120,1));
        }
    }

    @Test void verifiedArchKeepsRoofAndFloorAndNeverSealsAir() {
        var s = PredictionCaveInteriorTest.cave();
        assertEquals(105, s.surfaceBottom()); assertEquals(65, s.lowerTop());
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(105, 120), new PredictionLodSeams.HeightSpan(50, 65)),
                PredictionWallEvidence.intervals(s, 50, 120, 1));
        var solid = PredictionWallEvidence.inspect(PredictionSimpleVegetationTest.sample(120), -64, y -> true);
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(50, 120)), PredictionWallEvidence.intervals(solid, 50, 120, 1));
    }

    @Test void unknownSurfaceRetainsClosedHeightfieldUntilAirIsConfirmed() {
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(50, 120)),
                PredictionWallEvidence.intervals(PredictionSimpleVegetationTest.sample(120), 50, 120, 1));
    }

    @Test void unverifiedCliffsMatchSolidGeometryAtEveryDisplaySpacing() {
        for(int step:new int[]{1,4,8,16,32,64,128,1024}) {
            var unknown=new ClientColumnSample[9];
            var confirmed=new ClientColumnSample[9];
            for(int i=0;i<9;i++) {
                unknown[i]=PredictionSimpleVegetationTest.sample(i%3==0?64:120);
                confirmed[i]=PredictionWallEvidence.inspect(unknown[i],-64,y->true);
            }
            var actual=PredictionMeshBuilder.build(unknown,null,63,0,step,3,false);
            var expected=PredictionMeshBuilder.build(confirmed,null,63,0,step,3,false);
            assertEquals(expected.vertexCount(),actual.vertexCount(),"unverified cliff at spacing "+step);
            for(int v=0;v<actual.vertexCount();v++) {
                assertEquals(expected.x(v),actual.x(v));
                assertEquals(expected.y(v),actual.y(v),"cliff cannot be reduced to a four-block plate");
                assertEquals(expected.z(v),actual.z(v));
            }
        }
    }

    @Test void evidenceThatCannotAttachToTheDisplayRoofDoesNotPunchAHole() {
        var display=PredictionSimpleVegetationTest.sample(120);
        var inconsistent=PredictionWallEvidence.inspect(display,-64,y->y<110);
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(50,120)),
                PredictionWallEvidence.intervals(inconsistent,50,120,1));
    }

    @Test void limitedChecksReachTheTallestDropEvenAtTheEndOfTheGrid() {
        var samples=new ClientColumnSample[20*20];
        for(int i=0;i<samples.length;i++) samples[i]=PredictionSimpleVegetationTest.sample(i%2==0?100:110);
        samples[samples.length-1]=PredictionSimpleVegetationTest.sample(220);
        var checked=new ArrayList<Integer>();
        var profile=new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"),0,-64,384,"noise","minecraft:overworld",0);
        var sampler=new ClientTerrainSampler(0,profile) {
            @Override ClientColumnSample wallEvidence(int x,int z,ClientColumnSample sample) {
                checked.add(sample.surfaceY());
                return PredictionWallEvidence.inspect(sample,-64,y->true);
            }
        };
        assertEquals(64,PredictionWallEvidence.enrich(samples,20,4,0,0,sampler));
        assertEquals(220,checked.getFirst());
        assertNotEquals(0,samples[samples.length-1].flags()&PredictionWallEvidence.CHECKED);
    }

    @Test void meshAndLodStitchPreserveConfirmedCaveOpening() {
        var roof = PredictionCaveInteriorTest.cave();
        var low = PredictionWallEvidence.inspect(PredictionSimpleVegetationTest.sample(60), -64, y -> y < 60);
        var samples = new ClientColumnSample[65*65]; Arrays.fill(samples, roof);
        for (int z=0;z<65;z++) samples[z*65+64]=low;
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, 1, 65, false);
        for(int v=0;v<mesh.vertexCount();v++) if(mesh.normalY(v)==0)
            assertFalse(mesh.y(v)>65 && mesh.y(v)<105,"wall vertex inside confirmed cave");
        var higher = PredictionLodSeamsTest.tile(-1,-1,1,120);
        Arrays.fill(higher.samples(),roof);
        var lower = PredictionLodSeamsTest.tile(0,-1,2,60);
        var patches = new PredictionLodSeams().update(List.of(PredictionLodSeamsTest.surface(higher),PredictionLodSeamsTest.surface(lower)));
        assertFalse(patches.isEmpty());
        for(var patch:patches) {
            var words=patch.mesh().quads();
            for(int i=0;i<words.length;i+=12) {
                int top=((words[i+4]&65535)-32768)/4,bottom=((words[i+5]&65535)-32768)/4;
                assertTrue(top<=65 || bottom>=105,"seam fills confirmed air");
            }
        }
    }

    @Test void unrecordedStrataBelowTheSecondRunRemainSolid() {
        var s = PredictionWallEvidence.inspectCaptured(PredictionCaveInteriorTest.captured(120), -64,
                y -> y >= 105 || y >= 60 && y < 65 || y < 40);
        assertEquals(60, s.lowerBottom());
        assertEquals(List.of(new PredictionLodSeams.HeightSpan(105,120), new PredictionLodSeams.HeightSpan(-64,65)),
                PredictionWallEvidence.intervals(s,-64,120,1));
    }

    @Test void legacyAndPredictiveGapsCannotPerforateCliffsAtAnySpacing() {
        var cave = PredictionCaveInteriorTest.cave();
        var legacy = PredictionCaveInteriorTest.withFlags(cave, cave.flags() & ~PredictionWallEvidence.CAPTURED_OCCUPANCY);
        var predicted = PredictionWallEvidence.inspect(PredictionSimpleVegetationTest.sample(120), -64,
                y -> y >= 105 || y < 65);
        for (int step : new int[]{1,2,4,8,16,32,128}) for (var s : List.of(legacy, predicted, cave)) {
            if (s == cave && step == 1) continue;
            assertEquals(List.of(new PredictionLodSeams.HeightSpan(50,120)),
                    PredictionWallEvidence.intervals(s,50,120,step));
            var samples = new ClientColumnSample[9];
            var baseline = new ClientColumnSample[9];
            for (int i=0;i<9;i++) {
                baseline[i] = PredictionSimpleVegetationTest.sample(i%3==0?50:120);
                samples[i] = i%3==0 ? baseline[i] : s;
            }
            var actual = PredictionMeshBuilder.build(samples,null,63,0,step,3,false);
            var expected = PredictionMeshBuilder.build(baseline,null,63,0,step,3,false);
            assertEquals(expected.vertexCount(),actual.vertexCount());
            for (int v=0;v<actual.vertexCount();v++) {
                assertEquals(expected.x(v),actual.x(v));
                assertEquals(expected.y(v),actual.y(v));
                assertEquals(expected.z(v),actual.z(v));
            }
        }
    }

    @Test void fineSeamCannotUseTheCoarseNeighborsSingleColumnAsAnOpening() {
        var fine = PredictionLodSeamsTest.tile(-1,-1,1,60);
        var coarse = PredictionLodSeamsTest.tile(0,-1,2,120);
        var surfaces = List.of(PredictionLodSeamsTest.surface(fine),PredictionLodSeamsTest.surface(coarse));
        var baseline = new PredictionLodSeams().update(surfaces);
        var original = coarse.samples()[0];
        var captured = PredictionCaveInteriorTest.withFlags(original, original.flags() | ClientColumnSample.FLAG_CAPTURED);
        Arrays.fill(coarse.samples(),PredictionWallEvidence.inspectCaptured(captured,-64,y->y>=105||y<65));
        var actual = new PredictionLodSeams().update(surfaces);
        assertFalse(actual.isEmpty());
        assertEquals(baseline.size(),actual.size());
        for (int i=0;i<actual.size();i++) assertArrayEquals(baseline.get(i).mesh().quads(),actual.get(i).mesh().quads());
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PredictionWallEvidenceTest {
    static ClientColumnSample cave() {
        return PredictionWallEvidence.inspectCaptured(captured(120), -64,
                y -> y >= 105 || y < 65);
    }

    static ClientColumnSample captured(int height) {
        var s = PredictionVoxelFixtures.sample(height);
        return withFlags(s, s.flags() | ClientColumnSample.FLAG_CAPTURED);
    }

    static ClientColumnSample withFlags(ClientColumnSample s, int flags) {
        return new ClientColumnSample(s.surfaceY(), s.fluidY(), s.biomeIndex(), s.topBlockIndex(),
                s.structureIndex(), s.treeKind(), s.treeDensity(), s.treeHeight(), s.fluid(), flags,
                s.groundFeatureKind(), s.underBlockIndex(), s.deepBlockIndex(), s.surfaceBottom(),
                s.lowerTop(), s.lowerBottom(), s.spanFloor());
    }


    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void wallEvidenceSurvivesDiskRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var roof=cave();
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









    @Test void limitedChecksReachTheTallestDropEvenAtTheEndOfTheGrid() {
        var samples=new ClientColumnSample[20*20];
        for(int i=0;i<samples.length;i++) samples[i]=PredictionVoxelFixtures.sample(i%2==0?100:110);
        samples[samples.length-1]=PredictionVoxelFixtures.sample(220);
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








}

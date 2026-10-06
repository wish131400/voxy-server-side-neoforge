package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PredictionAdaptiveDisplayGridTest {
    private static ClientColumnSample sample(int x, int z) {
        int y = 100 + x + 2 * z;
        return new ClientColumnSample(y, y, 7, 11, 13, 17, 19, 23, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 29, 31, 37,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    @Test
    void distantLinearPatchUsesFourQuadrantProbesAndMarksDisplaySamples() {
        AtomicInteger calls = new AtomicInteger();
        ClientColumnSample[] result = PredictionAdaptiveDisplayGrid.sample(4,
                new ClientColumnSample[64], index -> {
                    calls.incrementAndGet();
                    return sample(index % 8, index / 8);
                });

        assertEquals(36, calls.get(), "boundary, centre and quadrant probes only");
        for (int index = 0; index < result.length; index++) {
            int x = index % 8;
            int z = index / 8;
            assertNotNull(result[index]);
            assertEquals(100 + x + 2 * z, result[index].surfaceY());
            assertEquals(result[index].surfaceY(), result[index].fluidY());
            assertTrue((result[index].flags() & ClientColumnSample.FLAG_DISPLAY) != 0);
            assertEquals(7, result[index].biomeIndex());
            assertEquals(13, result[index].structureIndex());
        }
    }

    @Test
    void protectedMetadataMismatchFallsBackToAllMissingPoints() {
        AtomicInteger calls = new AtomicInteger();
        ClientColumnSample[] result = PredictionAdaptiveDisplayGrid.sample(8,
                new ClientColumnSample[64], index -> {
                    calls.incrementAndGet();
                    ClientColumnSample value = sample(index % 8, index / 8);
                    return index == 18
                            ? new ClientColumnSample(value.surfaceY(), value.fluidY(), value.biomeIndex(),
                                    value.topBlockIndex() + 1, value.structureIndex(), value.treeKind(),
                                    value.treeDensity(), value.treeHeight(), value.fluid(), value.flags(),
                                    value.groundFeatureKind(), value.underBlockIndex(), value.deepBlockIndex(),
                                    value.surfaceBottom(), value.lowerTop(), value.lowerBottom(), value.spanFloor())
                            : value;
                });

        assertEquals(64, calls.get(), "a rejected patch must resolve every missing point");
        assertEquals(12, result[18].topBlockIndex());
        assertEquals(0, result[18].flags() & ClientColumnSample.FLAG_DISPLAY,
                "fallback output remains an exact sample");
    }

    @Test
    void spacingBelowExperimentThresholdUsesEveryPoint() {
        AtomicInteger calls = new AtomicInteger();
        ClientColumnSample[] result = PredictionAdaptiveDisplayGrid.sample(2,
                new ClientColumnSample[64], index -> {
                    calls.incrementAndGet();
                    return sample(index % 8, index / 8);
                });

        assertEquals(64, calls.get());
        assertTrue(java.util.Arrays.stream(result)
                .allMatch(value -> (value.flags() & ClientColumnSample.FLAG_DISPLAY) == 0));
    }

    @Test
    void priorDisplaySamplesAreProbedAgainBeforeExactReuse() {
        ClientColumnSample[] initial = new ClientColumnSample[64];
        for (int i = 0; i < initial.length; i++) {
            ClientColumnSample value = sample(i % 8, i / 8);
            initial[i] = new ClientColumnSample(value.surfaceY(), value.fluidY(), value.biomeIndex(),
                    value.topBlockIndex(), value.structureIndex(), value.treeKind(), value.treeDensity(),
                    value.treeHeight(), value.fluid(), value.flags() | ClientColumnSample.FLAG_DISPLAY,
                    value.groundFeatureKind(), value.underBlockIndex(), value.deepBlockIndex(),
                    value.surfaceBottom(), value.lowerTop(), value.lowerBottom(), value.spanFloor());
        }
        AtomicInteger calls = new AtomicInteger();
        ClientColumnSample[] result = PredictionAdaptiveDisplayGrid.sample(4, initial, index -> {
            calls.incrementAndGet();
            return sample(index % 8, index / 8);
        });
        assertEquals(36, calls.get());
        assertTrue(java.util.Arrays.stream(result)
                .allMatch(value -> (value.flags() & ClientColumnSample.FLAG_DISPLAY) != 0));
    }
}

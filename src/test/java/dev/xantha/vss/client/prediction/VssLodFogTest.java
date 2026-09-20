package dev.xantha.vss.client.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VssLodFogTest {
    @Test void distantVoxyRemainsVisibleBeyondPredictionHorizon() {
        var fog = VssLodFog.shared(4096, 8608, 512, true);
        assertEquals(8608, fog.end());
        assertEquals(4734.4F, fog.start(), .01F);
        assertTrue(fog.start() > 4609, "old complete-fog boundary must still retain terrain color");
        assertEquals(Math.log(2) / 8608, fog.aerialDensity(), 1e-9);
    }

    @Test void viewDistanceChangesRecomputeFogWithoutChangingPredictionRange() {
        assertEquals(4096, VssLodFog.shared(4096, 2048, 512, true).end());
        assertEquals(16384, VssLodFog.shared(4096, 16384, 512, true).end());
        assertEquals(4096, VssLodFog.shared(4096, 512, 512, true).end());
        var wideVanilla = VssLodFog.shared(4096, 8192, 8192, true);
        assertTrue(wideVanilla.end() - wideVanilla.start() >= 819,
                "clear radius must not collapse fog to a one-block transition");
    }

    @Test void disablingFogRetainsDisabledShaderDistances() {
        var fog = VssLodFog.shared(4096, 8608, 512, false);
        assertFalse(fog.haze());
        assertEquals(10_000_000, fog.shaderStart());
        assertEquals(10_000_001, fog.shaderEnd());
    }
}

package dev.xantha.vss.client.prediction;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.Level;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
class PredictionCaptureRefreshTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @Test void burstsWaitForQuietButContinuousTrafficCannotStarveRefresh() {
        var refresh = new PredictionCaptureRefresh();
        var key = new PredictionTileKey(Level.OVERWORLD, -1, -2, 0);
        refresh.changed(key, 1);
        assertFalse(refresh.defer(key, 2), "an isolated edit updates immediately");
        refresh.changed(key, 20_000_001L);
        assertTrue(refresh.defer(key, 30_000_001L));
        assertFalse(refresh.defer(key, 120_000_001L), "refresh after 100 ms quiet");
        for (long t = 40_000_001L; t < 500_000_001L; t += 20_000_000L) refresh.changed(key, t);
        assertFalse(refresh.defer(key, 500_000_001L), "continuous traffic has a 500 ms maximum delay");
        refresh.started(key);
        refresh.changed(key, 510_000_001L);
        assertFalse(refresh.defer(key, 510_000_002L), "a new burst starts independently");
        refresh.changed(key, 520_000_001L);
        refresh.retain(java.util.Set.of());
        assertFalse(refresh.defer(key, 520_000_002L));
    }
}

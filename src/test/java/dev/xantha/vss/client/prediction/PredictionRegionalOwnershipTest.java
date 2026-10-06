package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionRegionalOwnershipTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void storedColumnsSuppressWholeTileWorkWithoutANetworkAckAndMissingRestoresIt() throws Exception {
        var index = index();
        index.clear();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 1);
        try (var manager = new PredictionTileManager(Level.OVERWORLD,
                ClientTerrainSampler.custom(42, profile, (x, z) -> 64),
                new PredictionMemoryBudget(256 * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null)) {
            var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, -1, -1, 0);
            int span = manager.layout().tileBlocks(0) / 16;
            long settled = System.nanoTime() - ExactCoverageGate.SETTLE_NANOS;
            for (int z = -span; z < 0; z++) for (int x = -span; x < 0; x++) index.confirm(Level.OVERWORLD, x, z, settled);
            assertFalse(manager.isAuthoritative(-1, -1), "local storage is independent of VSS deliveries");
            assertTrue(manager.isRenderAuthoritative(-1, -1));
            assertFalse(manager.fullyVoxyOwned(key, 8192), "index edge must keep depth-tested fallback");
            for (int z = -span - 1; z <= 0; z++) for (int x = -span - 1; x <= 0; x++)
                index.confirm(Level.OVERWORLD, x, z, settled);
            assertTrue(manager.fullyVoxyOwned(key, 8192));
            assertFalse(manager.fullyVoxyOwned(key, 0), "disabled Voxy cannot own terrain");
            assertFalse(manager.fullyVoxyOwned(key, 128), "all possible tile heights must be in range before retirement");

            var tile = PredictionCoverageWorkTest.tile(-1, -1, 0, manager.layout());
            ready(manager).put(key, tile);
            desired(manager).add(key);
            assertTrue(effectivelyDesired(manager, key));
            manager.refreshVoxyOwnership(List.of(key), 512);
            assertFalse(ready(manager).containsKey(key), "fully covered prediction leaves GPU residency via the snapshot");
            assertFalse(effectivelyDesired(manager, key), "covered work cannot publish through the grace period");
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class,
                    int.class, int.class, boolean.class, boolean.class);
            enqueue.setAccessible(true);
            enqueue.invoke(manager, key, 0, 0, false, false);
            assertEquals(0, manager.pendingCount(), "covered terrain must not start new generation");

            index.remove(Level.OVERWORLD, -1, -1);
            assertFalse(manager.fullyVoxyOwned(key, 8192), "one missing chunk retains the entire mixed tile");
            manager.refreshVoxyOwnership(List.of(key), 512);
            assertTrue(effectivelyDesired(manager, key), "a definite miss permits fallback generation again");
            index.confirm(Level.OVERWORLD, -1, -1, System.nanoTime());
            assertFalse(manager.fullyVoxyOwned(key, 8192), "new delivery must settle before transfer");
        } finally { index.clear(); }
    }

    @Test void boxQueriesPreservePartialCoverageAcrossPagesAndDimensions() {
        var index = new PredictionExactCoverageIndex();
        long now = ExactCoverageGate.SETTLE_NANOS;
        for (int z = -17; z <= 17; z++) for (int x = -17; x <= 17; x++) index.confirm(Level.OVERWORLD, x, z, 0);
        assertTrue(index.ownsBox(Level.OVERWORLD, -17, -17, 17, 17, now));
        assertFalse(index.ownsBox(Level.NETHER, -17, -17, 17, 17, now));
        assertFalse(index.ownsBox(Level.OVERWORLD, -18, -17, 17, 17, now));
        index.remove(Level.OVERWORLD, 0, 0);
        assertFalse(index.ownsBox(Level.OVERWORLD, -17, -17, 17, 17, now));
        index.confirm(Level.OVERWORLD, 0, 0, now);
        assertFalse(index.ownsBox(Level.OVERWORLD, -17, -17, 17, 17, now));
        assertTrue(index.ownsBox(Level.OVERWORLD, -17, -17, 17, 17, now * 2));
    }

    @Test void maskDistinguishesStableInteriorFromMissingOrUnsettledNeighbours() {
        var index = new PredictionExactCoverageIndex();
        long now = ExactCoverageGate.SETTLE_NANOS;
        for (int z = -17; z <= -13; z++) for (int x = -17; x <= -13; x++)
            index.confirm(Level.OVERWORLD, x, z, 0);
        var mask = index.snapshot(Level.OVERWORLD, -16, -16, 32, now);
        assertEquals(255, value(mask, -16, -16), "interior crosses sparse page boundaries");
        assertEquals(128, value(mask, -17, -16), "outer positive edge preserves fallback");
        assertEquals(0, value(mask, -18, -16));
        index.remove(Level.OVERWORLD, -15, -15);
        mask = index.snapshot(Level.OVERWORLD, -16, -16, 32, now);
        assertEquals(128, value(mask, -16, -16), "diagonal hole also needs a transition");
        index.confirm(Level.OVERWORLD, -15, -15, now);
        assertEquals(128, value(index.snapshot(Level.OVERWORLD, -16, -16, 32, now), -16, -16));
        assertEquals(255, value(index.snapshot(Level.OVERWORLD, -16, -16, 32, now * 2), -16, -16));
    }

    private static int value(PredictionExactCoverageMask.Snapshot mask, int x, int z) {
        return mask.columns()[(z - mask.originZ()) * mask.size() + x - mask.originX()] & 255;
    }

    @Test void highAltitudeAndRangeEdgesPreserveFallback() {
        assertEquals(0, PredictionVoxyOwnership.radiusBlocks(0));
        assertEquals(480, PredictionVoxyOwnership.radiusBlocks(32));
        assertTrue(PredictionVoxyOwnership.containsBox(0, 100, 0, 480, -64, -64, -64, 64, 320, 64));
        assertFalse(PredictionVoxyOwnership.containsBox(0, 1000, 0, 480, -64, -64, -64, 64, 320, 64));
        assertFalse(PredictionVoxyOwnership.containsBox(450, 100, 0, 480, -64, -64, -64, 64, 320, 64));
    }

    private static PredictionExactCoverageIndex index() throws Exception {
        var field = ClientPredictionState.class.getDeclaredField("exactCoverage"); field.setAccessible(true);
        return (PredictionExactCoverageIndex) field.get(null);
    }
    @SuppressWarnings("unchecked") private static Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile> ready(PredictionTileManager manager) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField("ready"); field.setAccessible(true);
        return (Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field.get(manager);
    }
    @SuppressWarnings("unchecked") private static Set<PredictionTileManager.PredictionTileKey> desired(PredictionTileManager manager) throws Exception {
        var field = PredictionTileManager.class.getDeclaredField("desiredKeys"); field.setAccessible(true);
        return (Set<PredictionTileManager.PredictionTileKey>) field.get(manager);
    }
    private static boolean effectivelyDesired(PredictionTileManager manager, PredictionTileManager.PredictionTileKey key) throws Exception {
        var method = PredictionTileManager.class.getDeclaredMethod("effectivelyDesired", PredictionTileManager.PredictionTileKey.class);
        method.setAccessible(true); return (boolean) method.invoke(manager, key);
    }
}

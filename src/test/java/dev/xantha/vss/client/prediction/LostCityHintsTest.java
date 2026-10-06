package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class LostCityHintsTest {
    @Test void infrastructureOnlyRegionsInvalidateAndExpandBothDepthBounds() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        var bridge = wall(10); var glass = wall(15);
        var value = LostCityPreview.EMPTY.withOverlays(List.of(
                new LostCityPreview.Overlay(90, bridge, bridge, null, null),
                new LostCityPreview.Overlay(-20, glass, glass, glass, glass)));
        var payload = new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, true, region(0, value));
        assertTrue(hints.accept(payload)); assertFalse(hints.accept(payload));
        var snapshot = hints.snapshot(0, 0, 64); assertNotNull(snapshot);
        var bounds = LostCityHints.includeBuildings(new PredictionDepthBound(40, 70), snapshot);
        assertEquals(-20, bounds.minY()); assertEquals(100, bounds.maxY());
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, true, region(0, LostCityPreview.EMPTY))));
        assertNull(hints.snapshot(0, 0, 64));
    }
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    private static LostCityPreview.Model wall(int height) {
        return new LostCityPreview.Model(new int[] {
                LostCityPreview.origin(0, 0, 0),
                LostCityPreview.extent(16, height, 0, 1),
                1
        });
    }

    private static LostCityPreview.Chunk building(int ground, int height) {
        var model = wall(height);
        return new LostCityPreview.Chunk(LostCityPreview.BUILDING, ground, true,
                1, 1, 0, 0,
                List.of(new LostCityPreview.Placement(0, model)), model);
    }

    private static List<LostCityPreview.Chunk> region(int index, LostCityPreview.Chunk value) {
        var result = new ArrayList<LostCityPreview.Chunk>(LostCityPreview.REGION_ENTRIES);
        for (int i = 0; i < LostCityPreview.REGION_ENTRIES; i++)
            result.add(i == index ? value : LostCityPreview.EMPTY);
        return result;
    }

    @Test
    void negativeCoordinatesAndSignedGroundRoundTrip() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        var value = building(-32, 42);
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), -1, -1,
                17, true, region(63, value))));
        var decoded = hints.chunk(-1, -1);
        assertEquals(LostCityPreview.BUILDING, decoded.kind());
        assertEquals(-32, decoded.ground());
        assertEquals(42, decoded.top() - decoded.ground());
        assertEquals(value, decoded);
        assertEquals(LostCityPreview.EMPTY, hints.chunk(0, 0));
        var snapshot = hints.snapshot(-64, -64, 64);
        assertNotNull(snapshot);
        assertEquals(value, snapshot.atBlock(-16, -16));
        assertEquals(value, hints.chunk(-1, -1));
    }

    @Test
    void staleSessionAndWrongDimensionCannotReplaceHints() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        var entries = region(0, building(64, 18));
        assertFalse(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0,
                16, true, entries)));
        assertFalse(hints.accept(new LostCityHintsS2CPayload(Level.NETHER.location(), 0, 0,
                17, true, entries)));
        assertNull(hints.snapshot(0, 0, 64));
    }

    @Test
    void repeatedSummaryDoesNotRebuildAndRemovalDoes() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, true);
        var response = new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17,
                true, region(0, building(64, 18)));
        assertTrue(hints.accept(response));
        assertFalse(hints.accept(response));
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0,
                17, true, region(0, LostCityPreview.EMPTY))));
        assertNull(hints.snapshot(0, 0, 64));
    }

    @Test
    void unavailableRetriesAfterBackoffAndDoesNotEraseGoodGeometry() {
        var now = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        var sent = new java.util.ArrayList<dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload>();
        var hints = new LostCityHints(Level.OVERWORLD, 17, true, now::get, sent::add);
        hints.observeArea(0, 0, 64);
        hints.tick(0, 0);
        assertEquals(1, sent.size());
        var unavailable = new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, false, List.of());
        assertFalse(hints.accept(unavailable));
        now.addAndGet(29_000_000_000L);
        hints.observeArea(0, 0, 64);
        hints.tick(0, 0);
        assertEquals(1, sent.size());
        now.addAndGet(1_000_000_000L);
        hints.tick(0, 0);
        assertEquals(2, sent.size(), "unavailable region must be requested again");
        var good = region(0, building(64, 18));
        assertTrue(hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17, true, good)));
        assertFalse(hints.accept(unavailable));
        assertEquals(good.get(0), hints.chunk(0, 0));
    }

    @Test
    void successfulEmptyRegionDoesNotRetry() {
        var now = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        var sent = new java.util.ArrayList<dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload>();
        var hints = new LostCityHints(Level.OVERWORLD, 17, true, now::get, sent::add);
        hints.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0, 17,
                true, region(0, LostCityPreview.EMPTY)));
        hints.observeArea(0, 0, 64);
        now.addAndGet(60_000_000_000L);
        hints.tick(0, 0);
        assertTrue(sent.isEmpty());
    }

    @Test
    void missingModAndEmptyRegionHaveNoCityGeometry() {
        LostCityHints hints = new LostCityHints(Level.OVERWORLD, 17, false);
        hints.observeArea(0, 0, 64);
        assertNull(hints.snapshot(0, 0, 64));
        var present = new LostCityHints(Level.OVERWORLD, 17, true);
        assertFalse(present.accept(new LostCityHintsS2CPayload(Level.OVERWORLD.location(), 0, 0,
                17, true, region(0, LostCityPreview.EMPTY))));
        assertNull(present.snapshot(0, 0, 64));
    }

    @Test
    void cityBuildingsExtendTileDepthAndHaveDistinctCacheIdentities() {
        var low = building(64, 18);
        var high = building(64, 24);
        var lowTile = new LostCityPreview.Tile(0, 0, 1, List.of(low));
        var highTile = new LostCityPreview.Tile(0, 0, 1, List.of(high));
        assertEquals(new PredictionDepthBound(40, 82),
                LostCityHints.includeBuildings(new PredictionDepthBound(40, 70), lowTile));
        byte[] base = new byte[32];
        assertArrayEquals(base, PredictionMeshCodec.withCityBuildings(base, null));
        assertArrayEquals(PredictionMeshCodec.withCityBuildings(base, lowTile),
                PredictionMeshCodec.withCityBuildings(base, lowTile));
        assertFalse(java.util.Arrays.equals(PredictionMeshCodec.withCityBuildings(base, lowTile),
                PredictionMeshCodec.withCityBuildings(base, highTile)));
    }
}

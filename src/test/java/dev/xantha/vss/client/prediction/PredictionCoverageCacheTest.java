package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.PositionUtil;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionCoverageCacheTest {
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    @Test
    void neighbouringCoordinatesDoNotCollapseIntoXorCollisionTrees() {
        for (int spacing : new int[]{1, 16, 256}) {
            HashSet<Integer> hashes = new HashSet<>();
            for (int z = -128; z < 128; z++) {
                for (int x = -128; x < 128; x++) {
                    hashes.add(key(x * spacing, z * spacing).hashCode());
                }
            }
            // The old record hash produced only 256 hashes for 65,536 keys.
            assertTrue(hashes.size() > 64_000, "coordinate hash distribution at spacing " + spacing);
        }
    }

    @Test
    void exactCoverageKeysStillSeparateCoordinatesAndDimensions() {
        var otherDimension = ResourceKey.<Level>create(Registries.DIMENSION,
                ResourceLocation.withDefaultNamespace("the_nether"));
        var data = new ConcurrentHashMap<ClientPredictionState.CellKey, String>();
        data.put(key(-1, 1), "overworld");
        data.put(new ClientPredictionState.CellKey(otherDimension, PositionUtil.packPosition(-1, 1)), "nether");
        data.put(key(1, -1), "other position");
        assertEquals("overworld", data.get(key(-1, 1)));
        assertEquals("nether", data.get(new ClientPredictionState.CellKey(otherDimension, PositionUtil.packPosition(-1, 1))));
        assertEquals("other position", data.get(key(1, -1)));
        assertEquals(key(-1, 1).hashCode(), key(-1, 1).hashCode());
        assertNull(data.get(key(Integer.MIN_VALUE, Integer.MAX_VALUE)));
    }

    @Test
    void primitiveDecisionCacheDistinguishesMissFromExactAndLodZero() {
        var cache = PredictionRenderer.newDecisionCache();
        assertEquals(Byte.MIN_VALUE, cache.get(0L));
        cache.put(0L, (byte) 0);
        cache.put(PositionUtil.packPosition(-1, 1), (byte) -1);
        cache.put(PositionUtil.packPosition(1, -1), (byte) 19);
        assertEquals(0, cache.get(0L));
        assertEquals(-1, cache.get(PositionUtil.packPosition(-1, 1)));
        assertEquals(19, cache.get(PositionUtil.packPosition(1, -1)));
        assertEquals(Byte.MIN_VALUE, cache.get(PositionUtil.packPosition(17, 17)));
        cache.clear();
        assertEquals(Byte.MIN_VALUE, cache.get(0L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void backgroundPublicationCannotChangeTheCurrentFramesCoverageOrMeshIdentity() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                ResourceLocation.withDefaultNamespace("overworld"), 42, -64, 384, "noise", "minecraft:overworld", 1);
        var sampler = ClientTerrainSampler.custom(42, profile, (x, z) -> 64);
        try (var manager = new PredictionTileManager(DIMENSION, sampler,
                new PredictionMemoryBudget(256 * PredictionMemoryBudget.MIB, 0, () -> Long.MAX_VALUE, System::nanoTime), null)) {
            var readyField = PredictionTileManager.class.getDeclaredField("ready");
            readyField.setAccessible(true);
            var ready = (java.util.Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) readyField.get(manager);
            var parent = tile(1, 1);
            ready.put(parent.key(), parent);
            manager.markCoverageDirty(parent.key());
            var frame = manager.renderSnapshot();
            var child = tile(0, 2);
            ready.put(child.key(), child);
            manager.markCoverageDirty(child.key());
            assertSame(parent, frame.coveringTile(0, 0, 0), "a child absent from this draw list cannot hide its parent");
            assertEquals(1, frame.tiles().size());
            var next = manager.renderSnapshot();
            assertSame(child, next.coveringTile(0, 0, 0));
            assertNotEquals(frame.epoch(parent.key()), next.epoch(parent.key()));
            var decorated = tile(0, 3);
            ready.put(decorated.key(), decorated);
            manager.markCoverageDirty(decorated.key());
            assertSame(child, next.coveringTile(0, 0, 0), "a same-key surface upgrade cannot invalidate the drawn mesh by identity");
            assertSame(decorated, manager.renderSnapshot().coveringTile(0, 0, 0));
            ready.clear();
            manager.markCoverageDirty(child.key());
        }
    }

    private static PredictionTileManager.PredictionTile tile(int lod, long revision) {
        return new PredictionTileManager.PredictionTile(new PredictionTileManager.PredictionTileKey(DIMENSION, 0, 0, lod),
                new int[0], new int[0], new ClientColumnSample[0], null, new PredictionDepthBound(64, 64),
                0, revision, 64, 1 << lod);
    }

    private static ClientPredictionState.CellKey key(int x, int z) {
        return new ClientPredictionState.CellKey(DIMENSION, PositionUtil.packPosition(x, z));
    }
}

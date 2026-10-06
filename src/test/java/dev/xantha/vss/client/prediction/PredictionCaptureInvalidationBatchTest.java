package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.common.PositionUtil;
import dev.xantha.vss.config.VSSClientConfig;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PredictionCaptureInvalidationBatchTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @org.junit.jupiter.api.AfterEach void drain() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void oneDirtyPacketAdvancesOverlappingTilesOnceAndLaterEditsAdvanceAgain() throws Exception {
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 1024;
        var managers = stateMap("MANAGERS");
        Object previous = managers.get(Level.OVERWORLD);
        var states = stateMap("cellStates");
        var oldStates = new HashMap<>(states);
        try (var manager = manager(null)) {
            managers.put(Level.OVERWORLD, manager);
            var tile = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 0);
            Set<PredictionTileManager.PredictionTileKey> pending = field(manager, "pending");
            Set<PredictionTileManager.PredictionTileKey> desired = field(manager, "desiredKeys");
            Map<PredictionTileManager.PredictionTileKey, Long> epochs = field(manager, "captureEpochs");
            PredictionCaptureRefresh refresh = field(manager, "captureRefresh");
            Map<Long, Long> versions = field(manager, "captureVersions");
            Map<Long, dev.xantha.vss.api.VoxelColumnData> latest = field(manager, "latestCaptures");
            VssLodSampleCache samples = field(manager, "sampleCache");
            pending.add(tile); desired.add(tile);
            long a = PositionUtil.packPosition(0, 0), b = PositionUtil.packPosition(1, 0);
            versions.put(a, 7L); versions.put(b, 9L);
            var empty = new dev.xantha.vss.api.VoxelColumnData(
                    new dev.xantha.vss.api.VoxelColumnData.SectionData[0], 1L, true);
            latest.put(a, empty); latest.put(b, empty);
            var sample = PredictionSimpleVegetationTest.sample(120);
            samples.put(PositionUtil.packPosition(0, 0), sample);
            samples.put(PositionUtil.packPosition(15, 15), sample);
            samples.put(PositionUtil.packPosition(16, 0), sample);
            samples.put(PositionUtil.packPosition(31, 15), sample);
            var current = PredictionTileManager.class.getDeclaredMethod("captureCurrent",
                    PredictionTileManager.PredictionTileKey.class, long.class, long.class);
            current.setAccessible(true);
            assertEquals(true, current.invoke(manager, tile, 0L, 0L));

            ClientPredictionState.onDirtyColumns(Level.OVERWORLD, new long[]{a, b, a});
            assertEquals(1L, epochs.get(tile).longValue());
            assertEquals(8L, versions.get(a).longValue());
            assertEquals(10L, versions.get(b).longValue());
            assertTrue(latest.isEmpty());
            assertEquals(0, samples.size(), "both chunks, including opposite corners, must expire");
            assertTrue(manager.isAuthoritative(0, 0));
            assertFalse(refresh.defer(tile, System.nanoTime()), "one packet is one refresh, not a repeated burst");
            assertEquals(false, current.invoke(manager, tile, 0L, 0L), "the old build must be stale immediately");

            manager.invalidate(new long[]{a, b});
            assertEquals(2L, epochs.get(tile).longValue(), "later edits must not be suppressed");
            assertEquals(false, current.invoke(manager, tile, 1L, 0L));
            manager.capturedTerrainChanged(0, 0);
            assertEquals(3L, epochs.get(tile).longValue(), "a new capture commit has its own epoch");
            manager.invalidate(0, 0);
            assertEquals(4L, epochs.get(tile).longValue());
        } finally {
            if (previous == null) managers.remove(Level.OVERWORLD); else managers.put(Level.OVERWORLD, previous);
            states.clear(); states.putAll(oldStates);
            VSSClientConfig.CONFIG.predictionDistanceBlocks = distance;
        }
    }

    @Test void sparseAncestorsStillIgnoreBatchesBetweenGridPoints() throws Exception {
        int distance = VSSClientConfig.CONFIG.predictionDistanceBlocks;
        VSSClientConfig.CONFIG.predictionDistanceBlocks = 65536;
        try (var manager = manager(null)) {
            var tile = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 6);
            Set<PredictionTileManager.PredictionTileKey> pending = field(manager, "pending");
            Map<PredictionTileManager.PredictionTileKey, Long> epochs = field(manager, "captureEpochs");
            pending.add(tile);
            assertFalse(PredictionTileManager.captureIntersectsGrid(tile, manager.layout(), 1, 1));
            manager.invalidate(new long[]{PositionUtil.packPosition(1, 1), PositionUtil.packPosition(2, 2)});
            assertTrue(epochs.isEmpty());
            manager.invalidate(new long[]{PositionUtil.packPosition(1, 1), PositionUtil.packPosition(4, 4)});
            assertEquals(1L, epochs.get(tile).longValue());
        } finally { VSSClientConfig.CONFIG.predictionDistanceBlocks = distance; }
    }

    @Test void diskBatchExpandsEveryDependencyOnceAndRejectsLaterLeasesAgain() throws Exception {
        var columns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(new long[]{
                PositionUtil.packPosition(-1, -1), PositionUtil.packPosition(0, -1), PositionUtil.packPosition(-1, -1)});
        var expected = new HashSet<>(PredictionDiskCache.affected(-1, -1));
        var other = PredictionDiskCache.affected(0, -1);
        int separate = expected.size() + other.size();
        expected.addAll(other);
        assertEquals(expected, PredictionDiskCache.affected(columns));
        assertTrue(expected.size() < separate, "adjacent dirty columns share terrain and decoration dependencies");
        var expanded = new HashSet<>(expected);
        for (var key : expected) if (key.kind() == 0) expanded.add(PredictionDiskCache.Key.mesh(key));
        try (var disk = new PredictionDiskCache(directory, 77);
             var terrain = disk.lease(PredictionDiskCache.Key.terrain(-1, -1, 0));
             var mesh = disk.lease(PredictionDiskCache.Key.mesh(terrain.key));
             var surface = disk.lease(PredictionDiskCache.Key.surface(-1, -1, 0));
             var unrelated = disk.lease(PredictionDiskCache.Key.terrain(1000, 1000, 0))) {
            Object shared = field(disk, "shared");
            long before = (Long) field(shared, "revision");
            disk.invalidateChunks(columns);
            assertEquals(expanded.size(), (Long) field(shared, "revision") - before);
            assertFalse(terrain.valid()); assertFalse(mesh.valid()); assertFalse(surface.valid());
            assertTrue(unrelated.valid());
            assertFalse(disk.writeTerrain(terrain, new ClientColumnSample[]{PredictionSimpleVegetationTest.sample(120)}));
            try (var replacement = disk.lease(terrain.key)) {
                assertTrue(replacement.valid());
                disk.invalidateChunks(columns);
                assertFalse(replacement.valid(), "a second edit must invalidate a newly acquired lease");
                assertEquals(expanded.size() * 2L, (Long) field(shared, "revision") - before);
            }
        }
    }

    @Test void captureDiskBatchKeepsDecorationAndSparseAncestorsUntilTheirGridChanges() {
        var between = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(new long[]{
                PositionUtil.packPosition(1, 1), PositionUtil.packPosition(2, 2)});
        try (var disk = new PredictionDiskCache(directory, 77);
             var nearby = disk.lease(PredictionDiskCache.Key.terrain(0, 0, 0));
             var nearbyMesh = disk.lease(PredictionDiskCache.Key.mesh(nearby.key));
             var sparse = disk.lease(PredictionDiskCache.Key.terrain(0, 0, 6));
             var surface = disk.lease(PredictionDiskCache.Key.surface(1, 1, 0))) {
            disk.invalidateCaptures(between);
            assertFalse(nearby.valid()); assertFalse(nearbyMesh.valid());
            assertTrue(sparse.valid(), "captures between sample points must preserve the persisted ancestor");
            assertTrue(surface.valid(), "authoritative captures do not invalidate reusable decoration");
            disk.invalidateCapture(4, 4);
            assertFalse(sparse.valid(), "the next capture on the ancestor grid must invalidate it");
            assertTrue(surface.valid());
        }
    }

    private static PredictionTileManager manager(PredictionDiskCache disk) {
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                Level.OVERWORLD.location(), 42, -64, 384, "noise", "minecraft:overworld", 77);
        return new PredictionTileManager(Level.OVERWORLD, new ClientTerrainSampler(42, profile),
                new PredictionMemoryBudget(256L * PredictionMemoryBudget.MIB, 0,
                        () -> Long.MAX_VALUE, System::nanoTime, 1), disk);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> stateMap(String name) throws Exception {
        var field = ClientPredictionState.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }
}

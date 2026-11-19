package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** A complete registry with the same blocks, but different connection-local numbers. */
class PredictionSessionRegistryTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void awaitClose() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    private static RegistrySnapshot snapshot() { return new RegistrySnapshot(BuiltInRegistries.BLOCK, false); }
    private static void apply(RegistrySnapshot snapshot) {
        assertTrue(RegistryManager.applySnapshot(Map.of(BuiltInRegistries.BLOCK.key().location(), snapshot), false).isEmpty());
    }
    private static RegistrySnapshot swapped(RegistrySnapshot snapshot) {
        int grass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        int trapdoor = BuiltInRegistries.BLOCK.getId(Blocks.OAK_TRAPDOOR);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var ids = snapshot.getIds();
            buffer.writeVarInt(ids.size());
            ids.int2ObjectEntrySet().forEach(entry -> {
                int id = entry.getIntKey();
                buffer.writeVarInt(id);
                buffer.writeResourceLocation(ids.get(id == grass ? trapdoor : id == trapdoor ? grass : id));
            });
            buffer.writeVarInt(0);
            return RegistrySnapshot.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
    private static PredictionCacheMappings mappings() {
        return new PredictionCacheMappings(List.of("minecraft:plains"), List.of(), List.of());
    }
    private static ClientColumnSample sample(int block) {
        return new ClientColumnSample(64, 64, 0, block, 0, 0, 0, 0, 0, ClientColumnSample.FLAG_CAPTURED,
                0, block, block, 60, 40, 20, -64,
                PredictionColumnVolume.ofRuns(new int[]{60, 64, block, 0}));
    }
    private static void assertBlock(ClientColumnSample sample, int block) {
        assertNotNull(sample);
        assertEquals(block, sample.topBlockIndex());
        assertEquals(block, sample.underBlockIndex());
        assertEquals(block, sample.deepBlockIndex());
        assertEquals(block, sample.volume().block(0));
    }

    @Test void materialRulesFollowTheCurrentConnectionWithoutRetainingNumericIds() {
        var original = snapshot();
        var remapped = swapped(original);
        PredictionMaterialPalette.grassBlockIndex(); // Warm the old implementation's static palette.
        try {
            apply(remapped);
            assertSame(Blocks.GRASS_BLOCK, BuiltInRegistries.BLOCK.byId(PredictionMaterialPalette.grassBlockIndex()));
            assertSame(Blocks.OAK_LEAVES, BuiltInRegistries.BLOCK.byId(
                    PredictionMaterialPalette.representativeBlock("forest", false, false, true)));
            assertSame(Blocks.STONE, BuiltInRegistries.BLOCK.byId(PredictionMaterialPalette.stoneIndex()));
        } finally { apply(original); }
    }

    @Test void twoSessionMappingsRemainIndependentAcrossRemapAndRestore() throws Exception {
        var original = snapshot(); var remapped = swapped(original);
        int oldGrass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        var old = mappings();
        try {
            apply(remapped); var current = mappings();
            int newGrass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
            assertNotEquals(oldGrass, newGrass);
            assertEquals("minecraft:grass_block", old.blockName(oldGrass));
            assertEquals(oldGrass, old.blockId("minecraft:grass_block"));
            assertEquals(newGrass, current.blockId("minecraft:grass_block"));
            apply(original);
            assertEquals(newGrass, current.blockId("minecraft:grass_block"));
            assertEquals("minecraft:grass_block", current.blockName(newGrass));
            assertThrows(java.io.IOException.class, () -> old.blockName(Integer.MAX_VALUE));
            assertThrows(java.io.IOException.class, () -> old.blockId("missing:removed_block"));
        } finally { apply(original); }
    }

    @Test void queuedTerrainWriteUsesItsOriginalSessionEvenAfterFinishWritesAndRemap() throws Exception {
        var original = snapshot(); var remapped = swapped(original);
        int grass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        var field = PredictionDiskCache.class.getDeclaredField("COMMITS"); field.setAccessible(true);
        var commits = (ExecutorService) field.get(null);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        commits.execute(() -> {
            entered.countDown();
            try { release.await(20, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var key = PredictionDiskCache.Key.terrain(0, 0, 0);
        var disk = new PredictionDiskCache(directory, 42, mappings());
        try {
            CompletableFuture<Boolean> written;
            try (var lease = disk.lease(key)) {
                written = disk.writeTerrainLater(lease, new PredictionDiskCache.TerrainData(
                        new ClientColumnSample[]{sample(grass)}, Long.MIN_VALUE, null, null, null));
            }
            disk.finishWrites();
            apply(remapped);
            release.countDown();
            assertTrue(written.get(15, TimeUnit.SECONDS));
            commits.submit(() -> {}).get(15, TimeUnit.SECONDS);
            // A new connection decodes the stored name to its own new numeric ID.
            try (var restored = new PredictionDiskCache(directory, 42, mappings()); var lease = restored.lease(key)) {
                assertBlock(restored.readTerrain(lease, 1)[0], BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK));
            }
        } finally { release.countDown(); disk.close(); apply(original); }
    }

    @Test void delayedSampleStoreClosePersistsTheOwningSessionsNames() {
        var original = snapshot(); var remapped = swapped(original);
        var file = directory.resolve("captures.smp");
        int grass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        var old = new PredictionSampleStore(file, 42, mappings());
        old.put(1, sample(grass));
        try {
            apply(remapped); old.close();
            try (var restored = new PredictionSampleStore(file, 42, mappings())) {
                assertBlock(restored.get(1), BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK));
            }
        } finally { old.close(); apply(original); }
    }

    @Test void capturedLeavesAndMipsUseTheDiskSessionsMapping() {
        var original = snapshot(); var remapped = swapped(original);
        int grass = BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK);
        var columns = new ClientColumnSample[256]; Arrays.fill(columns, sample(grass));
        var key = new PredictionDiskCache.Key(3, 0, 0, 0);
        var parentKey = new PredictionDiskCache.Key(3, 0, 0, 1);
        var leaf = new PredictionCapturedTerrain.Node(columns, null);
        try (var disk = new PredictionDiskCache(directory, 42, mappings())) {
            apply(remapped);
            try (var lease = disk.lease(key)) {
                assertTrue(disk.writeCapturedNode(lease, leaf));
                // Even reads through an old cache keep the old session's numeric meaning.
                assertBlock(disk.readCapturedNode(lease).columns[0], grass);
            }
            try (var lease = disk.lease(parentKey)) {
                assertTrue(disk.writeCapturedNode(lease, new PredictionCapturedTerrain.Node(null,
                        new PredictionCapturedTerrain.Summary[]{leaf.summary, null, null, null})));
                assertEquals(grass, disk.readCapturedNode(lease).summary.material().topBlockIndex());
            }
            disk.flush();
            try (var restored = new PredictionDiskCache(directory, 42, mappings()); var lease = restored.lease(key)) {
                assertBlock(restored.readCapturedNode(lease).columns[0], BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK));
                try (var parentLease = restored.lease(parentKey)) {
                    assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK),
                            restored.readCapturedNode(parentLease).summary.material().topBlockIndex());
                }
            }
        } finally { apply(original); }
    }
}

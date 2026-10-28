package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PredictionVoxelCacheTest {
    @TempDir Path directory;
    private final PredictionTileKey key = new PredictionTileKey(Level.OVERWORLD,-2,1,1);
    private final byte[] identity = new byte[32];
    private PredictionDiskCache.Key diskKey() { return PredictionDiskCache.Key.voxels(key.tileX(),key.tileZ(),key.lod()); }
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() throws Exception { PredictionCacheTestFiles.awaitBackgroundClose(); }

    @Test void completeVoxelRoundTripPreservesStatesFeaturesBiomesAndRefinementInputs() throws Exception {
        var original = tile(key,32,true);
        var before = new PredictionCacheMappings(List.of("minecraft:plains","minecraft:forest"),List.of(),List.of());
        var after = new PredictionCacheMappings(List.of("minecraft:forest","minecraft:plains"),List.of(),List.of());
        try (var disk = new PredictionDiskCache(directory,77,before); var lease = disk.lease(diskKey())) {
            assertTrue(disk.writeVoxelsLater(lease,original,3,identity).get(5,TimeUnit.SECONDS));
        }
        try (var disk = new PredictionDiskCache(directory,77,after); var lease = disk.lease(diskKey())) {
            var saved = disk.readVoxels(lease,key,3,identity); assertNotNull(saved);
            var restored = saved.restore(99,false);
            assertArrayEquals(original.heights(),restored.heights());
            assertArrayEquals(original.groundHeights(),restored.groundHeights());
            assertEquals(original.depthBound(),restored.depthBound());
            assertEquals(original.data().voxyTile.features(),saved.voxels().features());
            assertArrayEquals(original.data().voxyTile.columns(),saved.voxels().columns());
            assertSame(saved.voxels().columns()[0],saved.voxels().columns()[saved.voxels().columns().length-1],
                    "Disk restore shares equal immutable columns without changing voxel content");
            assertTrue(saved.voxels().surfaceComplete()); assertEquals(1,saved.voxels().cityFeatures());
            assertEquals(1,restored.samples()[0].biomeIndex(),"sample IDs must remap by stable biome name");
            assertEquals(99,restored.revision()); assertNull(restored.data().parentLod);
            assertEquals(1,restored.data().retainedSampleObjects);
            assertEquivalentSections(original.data().voxyTile,saved.voxels());
        }
    }

    @Test void legacyPreviewVoxelRecordIsRejectedWhileFullPlacementCacheSurvives() throws Exception {
        var surfaceKey = PredictionDiskCache.Key.surface(0, 0, 31);
        var fullTree = Map.of(new net.minecraft.core.BlockPos(1, 65, 1), Blocks.OAK_LOG.defaultBlockState(),
                new net.minecraft.core.BlockPos(1, 66, 1), Blocks.OAK_LEAVES.defaultBlockState());
        try (var disk = new PredictionDiskCache(directory, 77); var lease = disk.lease(diskKey())) {
            assertTrue(disk.writeVoxelsLater(lease, tile(key, 32, false), 3, identity).get(5, TimeUnit.SECONDS));
            try (var surface = disk.lease(surfaceKey)) { assertTrue(disk.writeSurface(surface, fullTree, true)); }
            disk.flush();
            byte[] raw;
            try (var in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(
                    PredictionCacheTestFiles.read(disk, diskKey())))) { raw = in.readAllBytes(); }
            java.nio.ByteBuffer.wrap(raw).putInt(4, 9);
            var encoded = new java.io.ByteArrayOutputStream();
            try (var out = new java.util.zip.DeflaterOutputStream(encoded)) { out.write(raw); }
            PredictionCacheTestFiles.write(disk, diskKey(), encoded.toByteArray());
            assertNull(disk.readVoxels(lease, key, 3, identity), "Old finished tiles can contain artificial preview crowns");
            try (var surface = disk.lease(surfaceKey)) { assertEquals(fullTree, disk.readSurface(surface)); }
        }
    }

    @Test void fineAndCompleteResultsCannotBeReplacedByLateCoarseOrIncompleteWrites() throws Exception {
        try (var disk = new PredictionDiskCache(directory,77); var lease = disk.lease(diskKey()); var gate = new Gate()) {
            var fine = disk.writeVoxelsLater(lease,tile(key,32,true),3,identity);
            assertFalse(disk.writeVoxelsLater(lease,tile(key,8,true),3,identity).join());
            assertFalse(disk.writeVoxelsLater(lease,tile(key,32,false),3,identity).join());
            gate.release(); assertTrue(fine.get(5,TimeUnit.SECONDS));
            assertFalse(disk.writeVoxelsLater(lease,tile(key,16,true),3,identity).join());
            assertEquals(32,disk.readVoxels(lease,key,3,identity).voxels().axis());
            // Generation settings form a new content identity, not a lower-quality result.
            assertTrue(disk.writeVoxelsLater(lease,tile(key,8,false),1,identity).get(5,TimeUnit.SECONDS));
            assertNotNull(disk.readVoxels(lease,key,1,identity));
        }
    }

    @Test void restoringFinerTerrainRetainsAlreadyCompletedResidentVegetation() {
        var previous=tile(key,16,true);
        var saved=PredictionVoxelCache.Data.snapshot(tile(key,64,false));
        var restored=saved.restore(99,false,previous);
        assertEquals(64,restored.cellAxis());assertTrue(restored.data().voxyTile.surfaceComplete());
        assertEquals(previous.data().voxyTile.features(),restored.data().voxyTile.features());
        assertEquals(previous.depthBound(),restored.depthBound());
    }

    @Test void heightOnlyBackendWithoutBiomeIndicesStillRestoresTheNamedDisplayBiome() throws Exception {
        var mappings=new PredictionCacheMappings(List.of(),List.of(),List.of());
        try(var disk=new PredictionDiskCache(directory,77,mappings);var lease=disk.lease(diskKey())) {
            assertTrue(disk.writeVoxelsLater(lease,tile(key,32,true),3,identity).get(5,TimeUnit.SECONDS));
            var saved=disk.readVoxels(lease,key,3,identity);assertNotNull(saved);
            assertEquals("minecraft:plains",saved.voxels().columns()[0].biome());
            assertEquals(0,saved.samples()[0].biomeIndex());
        }
    }

    @Test void pendingCompletionCoalescesAndCopiesMutableSampleAndHeightArrays() throws Exception {
        try (var disk = new PredictionDiskCache(directory,77); var lease = disk.lease(diskKey()); var gate = new Gate()) {
            var partial = disk.writeVoxelsLater(lease,tile(key,32,false),3,identity);
            var complete = tile(key,32,true);
            var result = disk.writeVoxelsLater(lease,complete,3,identity);
            complete.heights()[0]=999; complete.samples()[0]=PredictionVoxelFixtures.sample(999);
            assertFalse(partial.join()); assertTrue(disk.diagnostics().contains("deferredEncodes=0"));
            gate.release(); assertTrue(result.get(5,TimeUnit.SECONDS));
            var saved=disk.readVoxels(lease,key,3,identity);
            assertEquals(64,saved.heights()[0]); assertEquals(64,saved.samples()[0].surfaceY());
            assertTrue(saved.voxels().surfaceComplete());
        }
    }

    @Test void invalidationRejectsQueuedOldVoxelsAndOnlyPublishesTheReplacement() throws Exception {
        try (var disk=new PredictionDiskCache(directory,77); var gate=new Gate()) {
            CompletableFuture<Boolean> old;
            try (var lease=disk.lease(diskKey())) { old=disk.writeVoxelsLater(lease,tile(key,32,true),3,identity); }
            disk.invalidate(List.of(PredictionDiskCache.Key.terrain(key.tileX(),key.tileZ(),key.lod())));
            CompletableFuture<Boolean> replacement;
            try (var lease=disk.lease(diskKey())) {
                assertNull(disk.readVoxels(lease,key,3,identity));
                replacement=disk.writeVoxelsLater(lease,tile(key,16,false),3,identity);
            }
            disk.finishWrites();
            gate.release(); assertFalse(old.get(5,TimeUnit.SECONDS)); assertTrue(replacement.get(5,TimeUnit.SECONDS));
            try(var reopened=new PredictionDiskCache(directory,77);var lease=reopened.lease(diskKey())) {
                assertEquals(16,reopened.readVoxels(lease,key,3,identity).voxels().axis());
            }
        }
    }

    @Test void worldCloseDrainsAcceptedVoxelsOffThreadBeforeReopeningTheDirectory() throws Exception {
        try(var disk=new PredictionDiskCache(directory,77);var gate=new Gate();var lease=disk.lease(diskKey())) {
            var result=disk.writeVoxelsLater(lease,tile(key,32,true),3,identity);
            disk.finishWrites();
            assertFalse(result.isDone(),"the client must return while disk IO is blocked");
            assertFalse(disk.writeVoxelsLater(lease,tile(key,64,true),3,identity).join());
            gate.release();
            try(var reopened=new PredictionDiskCache(directory,77);var restored=reopened.lease(diskKey())) {
                assertTrue(result.get(5,TimeUnit.SECONDS));
                assertNotNull(reopened.readVoxels(restored,key,3,identity));
            }
        }
    }

    @Test void voxelOnlyRecordIsDiscoveredByTheExistingCacheRestoreLane() throws Exception {
        var terrain=PredictionDiskCache.Key.terrain(key.tileX(),key.tileZ(),key.lod());
        try(var disk=new PredictionDiskCache(directory,77);var lease=disk.lease(diskKey())) {
            assertTrue(disk.writeVoxelsLater(lease,tile(key,64,true),3,identity).get(5,TimeUnit.SECONDS));
        }
        try(var disk=new PredictionDiskCache(directory,77)) {
            disk.probeTerrain(List.of(terrain)); disk.flush();
            assertEquals(64,disk.cachedTerrainAxis(terrain));
            disk.invalidateChunk(-16,8); disk.flush();
            try(var lease=disk.lease(diskKey())) { assertNull(disk.readVoxels(lease,key,3,identity)); }
        }
    }

    @Test void changedSettingsProfileCatalogOrTruncatedDataFallBackToGeneration() throws Exception {
        try(var disk=new PredictionDiskCache(directory,77,null,"a".repeat(64));var lease=disk.lease(diskKey())) {
            assertTrue(disk.writeVoxelsLater(lease,tile(key,32,true),3,identity).get(5,TimeUnit.SECONDS));
            assertNull(disk.readVoxels(lease,key,1,identity));
            var other=identity.clone();other[0]=1;assertNull(disk.readVoxels(lease,key,3,other));
        }
        try(var disk=new PredictionDiskCache(directory,77,null,"b".repeat(64));var lease=disk.lease(diskKey())) {
            assertNull(disk.readVoxels(lease,key,3,identity));
            assertTrue(disk.writeVoxelsLater(lease,tile(key,32,true),3,identity).get(5,TimeUnit.SECONDS));
            byte[] bytes=PredictionCacheTestFiles.read(disk,diskKey());
            PredictionCacheTestFiles.write(disk,diskKey(),Arrays.copyOf(bytes,bytes.length-7));
            assertNull(disk.readVoxels(lease,key,3,identity));
        }
    }

    static PredictionTile tile(PredictionTileKey key,int axis,boolean complete) {
        int span=64<<key.lod(),step=span/axis;
        var sample=PredictionVoxelFixtures.sample(64);
        var samples=new ClientColumnSample[(axis+1)*(axis+1)];Arrays.fill(samples,sample);
        var heights=new int[samples.length];Arrays.fill(heights,64);
        var ground=heights.clone();Arrays.fill(ground,60);
        var water=Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL,3);
        var column=new PredictionVoxySource.Column(List.of(new PredictionVoxySource.Run(-64,60,Blocks.STONE.defaultBlockState()),
                new PredictionVoxySource.Run(60,64,water)),"minecraft:plains");
        var columns=new PredictionVoxySource.Column[axis*axis];Arrays.fill(columns,column);
        var leaves=Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true);
        var features=List.of(new PredictionVoxySource.Box(2,64,2,8,70,8,Blocks.OAK_PLANKS.defaultBlockState()),
                new PredictionVoxySource.Box(10,70,10,16,76,16,leaves));
        var voxels=new PredictionVoxySource.Tile(key,key.tileX()*span,key.tileZ()*span,span,axis,step,
                columns,features,256+columns.length*160L+features.size()*64L,complete,1);
        var data=PredictionVoxelData.forVoxy(voxels);data.retainedSampleObjects=1;
        return new PredictionTile(key,heights,ground,samples,data,new PredictionDepthBound(-64,76),0,1,axis,step);
    }

    private void assertEquivalentSections(PredictionVoxySource.Tile original,PredictionVoxySource.Tile restored) {
        try(var a=new PredictionVoxySource();var b=new PredictionVoxySource()) {
            a.put(original);b.put(restored);
            long pos=dev.xantha.vss.compat.VoxyPredictionBridge.position(key.lod(),key.tileX()*2,0,key.tileZ()*2);
            dev.xantha.vss.compat.VoxyPredictionBridge.Mapper mapper=(state,biome)->((long)(state.hashCode()&0xfffff)<<27);
            assertArrayEquals(a.section(pos,mapper),b.section(pos,mapper));
        }
    }

    private static final class Gate implements AutoCloseable {
        final CountDownLatch release=new CountDownLatch(1);
        final Future<?> task;
        Gate() throws Exception {
            var f=PredictionDiskCache.class.getDeclaredField("COMMITS");f.setAccessible(true);
            var entered=new CountDownLatch(1);
            task=((ExecutorService)f.get(null)).submit(()->{entered.countDown();release.await();return null;});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
        }
        void release() {release.countDown();}
        @Override public void close() throws Exception {release();task.get(5,TimeUnit.SECONDS);}
    }
}

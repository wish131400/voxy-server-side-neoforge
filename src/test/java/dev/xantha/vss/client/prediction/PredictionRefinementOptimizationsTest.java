package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;

class PredictionRefinementOptimizationsTest {
    @BeforeAll static void bootstrap() { PredictionVegetationTest.bootstrap(); }
    @AfterAll static void restore() { PredictionVegetationTest.restoreTags(); }

    @Test void cachedDisplayUsesSourceIdentityAndCurrentAuthoritativeChunks() {
        var cache = new PredictionVegetationDisplayCache();
        var blocks = Map.of(new BlockPos(-1, 80, 1), Blocks.OAK_LEAVES.defaultBlockState(),
                new BlockPos(2, 80, 1), Blocks.OAK_LOG.defaultBlockState());
        // The source chunk may place blocks over a neighboring chunk's boundary.
        var sources = List.of(new PredictionVegetationDisplayCache.Source(0, blocks));
        var original = cache.tile(-16, 0, 32, 1, true, sources, key -> false);
        assertSame(original, cache.tile(-16, 0, 32, 1, true, sources, key -> false));
        var captured = cache.tile(-16, 0, 32, 1, true, sources,
                key -> key == PredictionVegetationDisplayCache.chunkKey(-1, 0));
        assertEquals(Map.of(new BlockPos(2, 80, 1), Blocks.OAK_LOG.defaultBlockState()), captured.blocks());
        assertSame(captured, cache.tile(-16, 0, 32, 1, true, sources,
                key -> key == PredictionVegetationDisplayCache.chunkKey(-1, 0)));
        assertNotSame(original, captured);
        var replacement = List.of(new PredictionVegetationDisplayCache.Source(0,
                Map.of(new BlockPos(3, 90, 1), Blocks.BIRCH_LEAVES.defaultBlockState())));
        assertNotEquals(original.blocks(), cache.tile(-16, 0, 32, 1, true, replacement, key -> false).blocks());
        cache.invalidate(0, 0);
        assertNotSame(captured, cache.tile(-16, 0, 32, 1, true, sources, key -> false));
        assertTrue(cache.diagnostics().contains("hits=2"));
    }

    @Test void displayCacheTracksTagReloadAndRemainsBounded() {
        var cache = new PredictionVegetationDisplayCache();
        var sources = List.of(new PredictionVegetationDisplayCache.Source(0,
                Map.of(new BlockPos(0, 80, 0), Blocks.OAK_LEAVES.defaultBlockState())));
        var tile = cache.tile(0, 0, 16, 1, true, sources, key -> false);
        PredictionVegetationTraits.invalidate();
        assertNotSame(tile, cache.tile(0, 0, 16, 1, true, sources, key -> false));
        for (int i = 1; i < 200; i++) cache.tile(i * 16, 0, 16, 1, true, sources, key -> false);
        assertTrue(cache.diagnostics().contains("entries=64,"), cache.diagnostics());
        long bytes = Long.parseLong(cache.diagnostics().split("bytes=")[1].split("[,}]")[0]);
        assertTrue(bytes <= PredictionVegetationDisplayCache.MAX_BYTES);
    }

    @Test void immutableDecorationSignatureDoesNotChangeTileEquality() throws Exception {
        var mutable = new HashMap<BlockPos, BlockState>();
        mutable.put(new BlockPos(1, 80, 1), Blocks.OAK_LOG.defaultBlockState());
        var tile = PredictionVegetation.Tile.of(mutable, 0, 0, 16, 1, 1);
        var equal = PredictionVegetation.Tile.of(mutable, 0, 0, 16, 1, 1);
        byte[] signature = tile.signatureCache().data(tile);
        mutable.clear();
        assertEquals(1, tile.blocks().size());
        assertSame(signature, tile.signatureCache().data(tile));
        assertArrayEquals(PredictionMeshCodec.decorationBytes(equal), signature);
        assertEquals(equal, tile);
        assertEquals(equal.hashCode(), tile.hashCode());
        assertThrows(UnsupportedOperationException.class, () -> tile.cell(17).clear());
    }

    @Test void baseIdentityIncludesSmallAndTrailingChanges() {
        var samples = samples(2);
        byte[] resources = new byte[32];
        int[] colors = new int[samples.length], water = new int[samples.length];
        var original = PredictionMeshCodec.baseSignature(resources, samples, colors, colors, water, 63, 0, 1, true);
        water[water.length - 1] = 0x123456;
        assertFalse(Arrays.equals(original,
                PredictionMeshCodec.baseSignature(resources, samples, colors, colors, water, 63, 0, 1, true)));
    }

    @Test void surfaceConcurrencyUsesConfiguredDetailCapacityAndReservesPreviewWorker() {
        assertEquals(6, PredictionWorkOrder.surfaceBuildLimit(8, 6));
        assertEquals(1, PredictionWorkOrder.surfaceBuildLimit(1, 6));
        assertEquals(2, PredictionWorkOrder.surfaceBuildLimit(8, 2));
        assertEquals(7, PredictionWorkOrder.detailBuildLimit(8, true, false));
        assertEquals(3, PredictionWorkOrder.detailBuildLimit(8, false, true));
    }

    @Test @SuppressWarnings("unchecked") void completedSurfacePublishesWhileAnotherSurfaceIsStillRunning() throws Exception {
        var config = dev.xantha.vss.config.VSSClientConfig.CONFIG;
        int oldWorkers = config.predictionRefinementWorkers;
        config.predictionRefinementWorkers = 3;
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
                "noise", "minecraft:overworld", 123L);
        var ground = samples(2)[0];
        var sampler = new ClientTerrainSampler(42, profile) {
            @Override public ClientColumnSample sample(int x, int z) { return ground; }
            @Override int surfaceColorForLod(int x, int y, int z, boolean preview) {
                if (x < 0) {
                    entered.countDown();
                    try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("surface gate timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                }
                return 0xff123456;
            }
            @Override int foliageColorForLod(int x, int y, int z, boolean preview) { return 0xff123456; }
        };
        var budget = new PredictionMemoryBudget(1024L * PredictionMemoryBudget.MIB, 0,
                () -> Long.MAX_VALUE, System::nanoTime, 4);
        try (var manager = new PredictionTileManager(net.minecraft.world.level.Level.OVERWORLD, sampler, budget, null)) {
            var ready = (Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field(manager, "ready");
            var desired = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "desiredKeys");
            var surfaces = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "surfaceDesired");
            ((java.util.concurrent.atomic.AtomicInteger) field(manager, "mediumSinceSurface")).set(16);
            var preview = PredictionTileManager.class.getDeclaredField("previewWorkPending"); preview.setAccessible(true); preview.set(manager, true);
            var a = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
            var b = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 2, 0, 0);
            int span = manager.layout().tileBlocks(0), axis = manager.layout().cellAxis(0);
            for (var key : List.of(a, b)) {
                desired.add(key); surfaces.add(key);
                var terrain = samples(axis + 1);
                var mesh = PredictionMeshBuilder.build(terrain, null, 63, 0, span / axis, axis + 1, false);
                ready.put(key, new PredictionTileManager.PredictionTile(key, new int[0], new int[0], terrain,
                        mesh, new PredictionDepthBound(64, 64), 0, 1, axis, span / axis));
            }
            var enqueue = PredictionTileManager.class.getDeclaredMethod("enqueue", PredictionTileManager.PredictionTileKey.class,
                    int.class, int.class, boolean.class); enqueue.setAccessible(true);
            enqueue.invoke(manager, a, 0, 0, true);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), manager.surfaceDiagnostics());
            enqueue.invoke(manager, b, 0, 0, true);
            var finished = (Set<PredictionTileManager.PredictionTileKey>) field(manager, "surfaceReady");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!finished.contains(b) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(finished.contains(b), manager.surfaceDiagnostics());
            assertFalse(finished.contains(a), "first surface is still running while the faster surface is visible");
            assertEquals(1, release.getCount());
        } finally { release.countDown(); config.predictionRefinementWorkers = oldWorkers; }
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    @Test void segmentedTreesPreserveEveryFaceAboveFormerWholeTileLimit() throws Exception {
        int span = 64, grid = 65;
        var blocks = new HashMap<BlockPos, BlockState>();
        for (int z = 0; z < span; z += 2) for (int x = 0; x < span; x += 2)
            for (int y = 80; y < 112; y += 2) blocks.put(new BlockPos(x, y, z), Blocks.OAK_LEAVES.defaultBlockState());
        var tile = PredictionVegetation.Tile.of(blocks, 0, 0, span, 1, 1);
        var samples = samples(grid);
        assertThrows(PredictionMemoryBudget.MeshLimitException.class, () ->
                PredictionMeshBuilder.build(samples, null, 63, 0, 1, grid, null, null, 0, 0, tile));
        var result = build(samples, grid, tile);
        assertTrue(result.vertexCount() > 262_144);
        assertEquals(0, result.positions.length, "runtime mesh retains compact quads, not all triangles");
        assertEquals(blocks, tile.blocks());
        var packed = result.packed();
        var faces = new HashSet<String>();
        for (int q = 0; q < packed.quadCount(); q++) {
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            float minX = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                minX = Math.min(minX, packed.x(q, corner)); minZ = Math.min(minZ, packed.z(q, corner));
                minY = Math.min(minY, packed.y(q, corner)); maxY = Math.max(maxY, packed.y(q, corner));
            }
            if (minY <= 64) continue;
            int face = packed.normalY(q, 0) > .5F ? 0 : packed.normalZ(q, 0) < -.5F ? 1
                    : packed.normalZ(q, 0) > .5F ? 2 : packed.normalX(q, 0) < -.5F ? 3 : 4;
            int x = Math.round(minX) - (face == 4 ? 1 : 0);
            int z = Math.round(minZ) - (face == 2 ? 1 : 0);
            int y = Math.round(face == 0 ? maxY - 1 : minY);
            assertTrue(faces.add(x + ":" + y + ":" + z + ":" + face), "no duplicate tree faces");
        }
        assertEquals(blocks.size() * 5, faces.size());
        for (var pos : blocks.keySet()) for (int face = 0; face < 5; face++)
            assertTrue(faces.contains(pos.getX() + ":" + pos.getY() + ":" + pos.getZ() + ":" + face));
        var key = new PredictionTileManager.PredictionTileKey(net.minecraft.world.level.Level.OVERWORLD, 0, 0, 0);
        result.prepareGpuPayload(new PredictionTileManager.PredictionTile(key, new int[0], new int[0], samples,
                result, new PredictionDepthBound(64, 112), 1, 1, span, 1));
        byte[] identity = new byte[32];
        var restored = PredictionMeshCodec.decode(PredictionMeshCodec.encode(result, identity), identity, span);
        assertNotNull(restored);
        assertArrayEquals(result.gpuPayload().restoreWords(), restored.gpuPayload().restoreWords());
        System.out.println("SEGMENTED_FOREST blocks=" + blocks.size() + " vertices=" + result.vertexCount()
                + " quads=" + result.gpuPayload().terrainQuadCount());
    }

    @Test void segmentedMeshMatchesExistingTerrainFluidAndBoundaryFaces() {
        int grid = 65;
        var samples = samples(grid);
        for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
            int top = 60 + (x + z) % 5;
            samples[z * grid + x] = new ClientColumnSample(top, 65, 0, ClientColumnSample.NO_BLOCK,
                    0, 0, 0, 0, 1, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                    ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        }
        var existing = PredictionMeshBuilder.build(samples, null, 63, 0xff123456, 1, grid, false).packed();
        var result = PredictionMeshBuilder.buildForRendering(samples, null, 63, 0xff123456, 1, grid, null, null, 0, 0, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY,
                null, () -> true).packed();
        assertEquals(faceKeys(existing, false), faceKeys(result, false));
        assertEquals(faceKeys(existing, true), faceKeys(result, true));
        for (int i = 0; i < result.cellAxis() * result.cellAxis(); i++) {
            assertTrue(result.quadForCell(i) >= 0);
            assertTrue(result.waterQuadForCell(i) >= 0);
        }
        assertThrows(java.util.concurrent.CancellationException.class, () ->
                PredictionMeshBuilder.buildForRendering(samples, null, 63, 0, 1, grid, null, null,
                        0, 0, PredictionVegetation.Tile.EMPTY, PredictionSimpleVegetation.Result.EMPTY, null, () -> false));
    }

    private static Set<String> faceKeys(PredictionQuadMesh mesh, boolean water) {
        var result = new HashSet<String>();
        for (int q = 0; q < (water ? mesh.waterQuadCount() : mesh.quadCount()); q++) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < 4; c++) {
                float x = water ? mesh.waterX(q, c) : mesh.x(q, c);
                float y = water ? mesh.waterY(q, c) : mesh.y(q, c);
                float z = water ? mesh.waterZ(q, c) : mesh.z(q, c);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            // Greedy rectangles may split at segment boundaries; compare unit faces.
            for (int z = 0; z < Math.max(1, Math.round(maxZ - minZ)); z++)
                for (int x = 0; x < Math.max(1, Math.round(maxX - minX)); x++)
                    result.add((minX + x) + ":" + minY + ":" + (minZ + z) + ":" + maxY
                            + ":" + (maxX == minX) + ":" + (maxZ == minZ) + ":" + (!water && mesh.terrainWall(q))
                            + ":" + (water ? mesh.waterNormalX(q, 0) : mesh.normalX(q, 0))
                            + ":" + (water ? mesh.waterNormalZ(q, 0) : mesh.normalZ(q, 0)));
        }
        return result;
    }

    static ClientColumnSample[] samples(int grid) {
        var samples = new ClientColumnSample[grid * grid];
        Arrays.fill(samples, new ClientColumnSample(64, 64, 0, ClientColumnSample.NO_BLOCK,
                0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        return samples;
    }

    static PredictionMesh build(ClientColumnSample[] samples, int grid, PredictionVegetation.Tile tile) {
        return PredictionMeshBuilder.buildForRendering(samples, null, 63, 0, 1, grid, null, null,
                0, 0, tile, PredictionSimpleVegetation.Result.EMPTY, null, () -> true);
    }
}

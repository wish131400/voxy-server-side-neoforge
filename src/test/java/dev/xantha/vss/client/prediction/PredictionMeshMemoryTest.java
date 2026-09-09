package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionMeshMemoryTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test
    void discardingTrianglesPreservesEveryGpuWordIncludingWaterWallsAndVegetation() {
        int grid = 17;
        for (int spacing : new int[] {1, 4, 8, 16}) {
            ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
            for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++) {
                int y = x < 6 ? 40 : 70 + z / 3;
                samples[z * grid + x] = column(y, x < 6 ? 1 : 0,
                        x > 8 && z % 5 == 0 ? ClientColumnSample.FLAG_TREE_HERE : 0);
            }
            PredictionMesh mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB2336699,
                    spacing, grid, true, new PredictionFeatureStampCache(), null, null, 0, 0);
            PredictionPackedMesh before = PredictionPackedMesh.pack(tile(mesh, spacing));
            long originalBytes = mesh.retainedHeapBytes();
            PredictionMesh compact = mesh.compactForRendering();
            PredictionPackedMesh after = PredictionPackedMesh.pack(tile(compact, spacing));
            assertArrayEquals(before.quads(), after.quads());
            assertEquals(before.downFaces(), after.downFaces());
            for (int group = 0; group < VssLodFaceGroup.COUNT; group++) {
                assertEquals(before.terrainRangeFirst(group), after.terrainRangeFirst(group));
                assertEquals(before.terrainRangeCount(group), after.terrainRangeCount(group));
                assertEquals(before.waterRangeFirst(group), after.waterRangeFirst(group));
                assertEquals(before.waterRangeCount(group), after.waterRangeCount(group));
            }
            assertEquals(mesh.vertexCount(), compact.vertexCount());
            assertTrue(compact.retainedHeapBytes() < originalBytes * 0.6D,
                    "at least 40% of retained model heap should be released");
            System.out.println("mesh memory spacing=" + spacing + ": before=" + originalBytes
                    + ", after=" + compact.retainedHeapBytes() + ", quads=" + after.quadCount());
        }
    }

    @Test
    void underwaterTerrainCarriesWaterDepthLightWithoutDarkeningTheWaterSurface() {
        int grid = 17;
        ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
        Arrays.fill(samples, column(53, 1, 0));
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB2336699,
                1, grid, true, null, null, null, 0, 0);
        int[] heights = new int[grid * grid];
        Arrays.fill(heights, 53);
        var tile = new PredictionTileManager.PredictionTile(
                new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), 0, 0, 0),
                heights, heights, samples, mesh.compactForRendering(), new PredictionDepthBound(53, 64), 0, 1, 16, 1);
        var packed = PredictionPackedMesh.pack(tile);
        assertTrue(packed.terrainQuadCount() > 0);
        assertTrue(packed.quadCount() > packed.terrainQuadCount());
        int[] data = packed.quads();
        for (int q = 0; q < packed.quadCount(); q++) for (int word : new int[]{7, 9, 10, 11}) {
            int loss = data[q * 12 + word] >>> 28;
            if (q < packed.terrainQuadCount()) assertTrue(loss >= 10, "submerged ground loses sky light with depth");
            else assertEquals(0, loss, "the exposed water surface remains lit by the sky");
        }
    }

    @Test
    void packingAllocatesFinalPayloadWithoutPerQuadObjectArrays() {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var samples = new ClientColumnSample[65 * 65];
        for (int z = 0; z < 65; z++) for (int x = 0; x < 65; x++)
            samples[z * 65 + x] = column(64 + (x + z) % 2 * 8, 0, 0);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB2336699, 1, 65, false);
        var tile = tile(mesh, 1);
        PredictionPackedMesh packed = null;
        for (int i = 0; i < 8; i++) packed = PredictionPackedMesh.pack(tile);
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 8; i++) packed = PredictionPackedMesh.pack(tile);
        long perBuild = (bean.getThreadAllocatedBytes(thread) - before) / 8;
        long payload = packed.quads().length * 4L;
        assertTrue(packed.quadCount() > 8_000);
        assertTrue(perBuild < payload + 65_536,
                "packing should allocate the flat payload plus fixed scratch, not arrays per quad: " + perBuild);
        System.out.println("packing allocation: quads=" + packed.quadCount() + ", payloadBytes=" + payload
                + ", allocatedBytesPerBuild=" + perBuild);
    }

    @Test
    void pathologicalDenseVegetationStopsBeforeGrowingUnboundedVertexArrays() {
        ClientColumnSample[] dense = new ClientColumnSample[66 * 66];
        Arrays.fill(dense, column(70, 0, ClientColumnSample.FLAG_TREE_HERE));
        var blocks = new java.util.HashMap<net.minecraft.core.BlockPos,
                net.minecraft.world.level.block.state.BlockState>();
        for (int y = 72; y < 104; y += 2) for (int z = 0; z < 64; z += 2)
            for (int x = 0; x < 64; x += 2) blocks.put(new net.minecraft.core.BlockPos(x, y, z),
                    net.minecraft.world.level.block.Blocks.MUSHROOM_STEM.defaultBlockState());
        var vegetation = PredictionVegetation.Tile.of(blocks, 0, 0, 64, 1, 1);
        assertThrows(PredictionMemoryBudget.MeshLimitException.class, () ->
                PredictionMeshBuilder.build(dense, null, 63, 0xB2336699, 1, 66, true,
                        new PredictionFeatureStampCache(), null, null, 0, 0, vegetation));
        var bounded = PredictionVegetation.boundedTile(blocks, 0, 0, 64, 1, 1);
        assertFalse(bounded.cells().isEmpty(), "budgeted forest must retain vegetation");
        var mesh = PredictionMeshBuilder.build(dense, null, 63, 0xB2336699, 1, 66, true,
                null, null, null, 0, 0, bounded);
        assertTrue(mesh.vertexCount() > 64 * 64 * 6);
        assertTrue(mesh.vertexCount() < 262_144, "forest simplification must preserve terrain within the cap");
    }

    private static PredictionTileManager.PredictionTile tile(PredictionMesh mesh, int spacing) {
        int grid = mesh.cellAxis() + 1;
        int[] heights = new int[grid * grid];
        Arrays.fill(heights, 70);
        return new PredictionTileManager.PredictionTile(
                new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), 0, 0, 0),
                heights, heights, new ClientColumnSample[grid * grid], mesh,
                new PredictionDepthBound(40, 100), 0, 1, mesh.cellAxis(), spacing);
    }

    private static ClientColumnSample column(int y, int fluid, int flags) {
        return new ClientColumnSample(y, fluid == 1 ? 63 : y, 0,
                PredictionMaterialPalette.grassBlockIndex(), 0, 1, 48, 16, fluid, flags, 1,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}

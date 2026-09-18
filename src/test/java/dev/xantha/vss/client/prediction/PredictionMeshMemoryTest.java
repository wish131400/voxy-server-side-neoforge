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

    @Test void constantFaceNormalsUseOneVectorAndPreserveNonuniformCornersExactly() {
        float[] normals = new float[24];
        for (int c = 0; c < 4; c++) {
            normals[c * 3 + 1] = 1;
            normals[12 + c * 3] = -1;
        }
        assertArrayEquals(new float[]{0,1,0,-1,0,0}, PredictionQuadMesh.compactNormals(normals, 2));
        normals[18] = -.75f;
        assertSame(normals, PredictionQuadMesh.compactNormals(normals, 2), "smooth model normals must remain lossless");
    }

    @Test void compactNormalAccessPreservesEveryPackedGpuWord() throws Exception {
        ClientColumnSample[] samples = new ClientColumnSample[17 * 17];
        for (int z = 0; z < 17; z++) for (int x = 0; x < 17; x++)
            samples[z * 17 + x] = column(x < 8 ? 53 : 64 + z % 4, x < 8 ? 1 : 0, 0);
        var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB2336699, 1, 17, false);
        var tile = tile(mesh, 1);
        var quads = mesh.packed();
        var compact = PredictionPackedMesh.pack(tile);
        long compactBytes = quads.retainedHeapBytes();
        for (String name : new String[]{"normals", "waterNormals"}) {
            var field = PredictionQuadMesh.class.getDeclaredField(name); field.setAccessible(true);
            float[] values = (float[]) field.get(quads);
            int count = name.equals("normals") ? quads.quadCount() : quads.waterQuadCount();
            assertEquals(count * 3, values.length);
            float[] expanded = new float[count * 12];
            for (int q = 0; q < count; q++) for (int c = 0; c < 4; c++)
                System.arraycopy(values, q * 3, expanded, q * 12 + c * 3, 3);
            field.set(quads, expanded);
        }
        assertArrayEquals(PredictionPackedMesh.pack(tile).quads(), compact.quads());
        assertEquals((long)(quads.quadCount() + quads.waterQuadCount()) * 36,
                quads.retainedHeapBytes() - compactBytes);
        System.out.println("NORMAL_COMPACTION savedBytes=" + (quads.retainedHeapBytes() - compactBytes)
                + ", quads=" + (quads.quadCount() + quads.waterQuadCount()));
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

    @Test void publishedMeshesKeepOneVisualPayloadAcrossFluidsVegetationAndWideTiles() {
        for (int spacing : new int[]{1, 2, 8, 1024}) {
            int grid = 17;
            var samples = new ClientColumnSample[grid * grid];
            for (int z = 0; z < grid; z++) for (int x = 0; x < grid; x++)
                samples[z * grid + x] = column(x < 6 ? 45 : 64 + (x + z) % 3 * 8,
                        x < 6 ? 1 : 0, x > 9 && z % 5 == 0 ? ClientColumnSample.FLAG_TREE_HERE : 0);
            var mesh = PredictionMeshBuilder.build(samples, null, 63, 0xB2336699, spacing, grid,
                    true, new PredictionFeatureStampCache(), null, null, 0, 0);
            assertPublishedCompaction(mesh, spacing);
        }
    }

    static void assertPublishedCompaction(PredictionMesh source, int spacing) {
        var original = source.packed();
        var expected = PredictionPackedMesh.pack(tile(source, spacing));
        var compact = source.compactForRendering();
        long before = compact.retainedHeapBytes() + expected.retainedHeapBytes();
        var tile = tile(compact, spacing);
        compact.prepareGpuPayload(tile);
        var actual = compact.gpuPayload();
        assertArrayEquals(expected.quads(), actual.quads(), "every leaf/model/fluid vertex and material must survive");
        assertSame(actual, PredictionPackedMesh.pack(tile), "reupload must reuse the sole visual payload");
        compact.prepareGpuPayload(tile);
        assertSame(actual, compact.gpuPayload());
        var summary = compact.seamMesh();
        for (int cell = 0; cell < original.cellAxis() * original.cellAxis(); cell++) {
            int q = original.quadForCell(cell);
            assertEquals(q >= 0, summary.hasTop(cell));
            if (q >= 0) {
                assertEquals(Float.floatToRawIntBits(original.y(q, 0)), Float.floatToRawIntBits(summary.topY(cell)));
                assertEquals(original.color(q, 0), summary.topColor(cell));
            }
        }
        assertThrows(IllegalStateException.class, compact::packed, "do not lazily reconstruct discarded geometry");
        long after = compact.retainedHeapBytes();
        assertTrue(after < before, "summary must replace, not duplicate, the full CPU view");
        System.out.println("PUBLISHED_MESH spacing=" + spacing + ",quads=" + actual.quadCount()
                + ",beforeBytes=" + before + ",afterBytes=" + after + ",savedBytes=" + (before - after)
                + ",seamBytes=" + summary.retainedHeapBytes());
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

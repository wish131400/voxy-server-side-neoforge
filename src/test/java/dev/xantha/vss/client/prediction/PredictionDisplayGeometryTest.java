package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictionDisplayGeometryTest {
    @Test void leafAndFacadeLayersReduceGeometryKeepFootprintAndRestoreSource() {
        for (int flags : new int[]{PredictionPackedMesh.FLAG_CUTOUT, 0}) {
            int[] words = grid(flags), original = words.clone();
            int[] first = {0, 0, 0, 0, 0}, count = {1024, 0, 0, 0, 0};
            int[][] retained = new int[4][5];
            for (int[] level : retained) level[0] = 1024;
            PredictionSpatialOrder.arrange(words, first, count, new boolean[256]);
            original = words.clone();
            var display = PredictionDisplayGeometry.build(words, first, count, retained, 64);
            assertNotNull(display.ranges());
            assertArrayEquals(original, Arrays.copyOf(display.words(), original.length));
            for (int tier = 1; tier <= 3; tier++) {
                var range = display.ranges()[tier][1];
                System.out.println("DISPLAY_LOD kind=" + flags + " tier=" + tier + " quads=1024->" + range.quads + " runs=" + range.first.length);
                assertTrue(range.quads <= 410, "small coplanar leaves/facades must coalesce: " + flags + "/" + tier + "=" + range.quads);
                double area = 0;
                for (int i = 0; i < range.first.length; i++) for (int q = range.first[i]; q < range.first[i] + range.count[i]; q++) {
                    int p = q * 12;
                    int x0 = display.words()[p] & 65535, x1 = display.words()[p] >>> 16;
                    int z0 = display.words()[p + 2] & 65535, z1 = display.words()[p + 3] & 65535;
                    area += Math.abs((x1 - x0) * (z1 - z0));
                    assertEquals(flags, display.words()[p + 6]);
                }
                assertEquals(1024, area, "surface outline and holes must remain exact");
            }
        }
    }

    @Test void customModelTerrainWallAndGradientLightingKeepOriginalGeometry() {
        for (int flags : new int[]{PredictionPackedMesh.FLAG_MODEL_UV, PredictionPackedMesh.FLAG_UNSHADED,
                PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE, 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT}) {
            var result = build(grid(flags)); assertNull(result.ranges());
        }
        int[] walls = grid(0);
        for (int q = 0; q < 1024; q++) walls[q * 12 + 9] |= 1 << 27;
        assertNull(build(walls).ranges());
    }

    @Test void publishingCompressionAndCacheRebuildDoNotDuplicateFarLayers() {
        int[] words = grid(PredictionPackedMesh.FLAG_CUTOUT);
        var mesh = PredictionPackedMesh.terrainRecords(words, 64);
        int[] original = mesh.quads().clone(), source = mesh.cacheWords();
        assertEquals(1024 * 12, source.length);
        assertTrue(original.length > source.length);
        mesh.prepareGpuStorage(); mesh.prepareStorage();
        assertArrayEquals(original, mesh.restoreWords());
        int[] first = new int[5], count = {1024, 0, 0, 0, 0};
        var restored = new PredictionPackedMesh(mesh.cacheWords(), 64, 1024, first, count,
                new int[5], new int[5], false, 0);
        assertArrayEquals(original, restored.quads());
        for (int tier = 0; tier <= 3; tier++) {
            var a = mesh.drawRanges(false, VssLodFaceGroup.ALL, tier);
            var b = restored.drawRanges(false, VssLodFaceGroup.ALL, tier);
            assertArrayEquals(a.first, b.first); assertArrayEquals(a.count, b.count);
        }
    }

    @Test void finishedDiskRecordStoresOnlySourceAndRebuildsFarLayers() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        var payload = PredictionPackedMesh.terrainRecords(grid(PredictionPackedMesh.FLAG_CUTOUT), 64);
        var fixture = PredictionMeshCodecTest.fixture(64);
        var mesh = PredictionMesh.restored(1024 * 6, 0, payload, fixture.seamMesh());
        byte[] signature = new byte[32];
        byte[] bytes = PredictionMeshCodec.encode(mesh, signature);
        var restored = PredictionMeshCodec.decode(bytes, signature, 64);
        assertNotNull(restored);
        assertArrayEquals(payload.quads(), restored.gpuPayload().quads());
        assertEquals(1024 * 12, restored.gpuPayload().cacheWords().length);
        assertTrue(restored.gpuPayload().hasDisplayLod());
    }

    private static PredictionDisplayGeometry.Result build(int[] words) {
        int[] first = new int[5], count = {1024, 0, 0, 0, 0};
        int[][] retained = new int[4][5]; for (int[] level : retained) level[0] = 1024;
        return PredictionDisplayGeometry.build(words, first, count, retained, 64);
    }

    static int[] grid(int flags) {
        int[] words = new int[1024 * 12];
        for (int q = 0; q < 1024; q++) {
            PredictionSpatialOrderTest.quad(words, q, q % 32, q / 32, 80, flags);
            for (int offset : new int[]{7, 9, 10, 11}) words[q * 12 + offset] = 0x556633;
            words[q * 12 + 8] = q % 32 + q / 32 * 64;
        }
        return words;
    }
}

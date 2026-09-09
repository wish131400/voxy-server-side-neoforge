package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PredictionSurfaceRegressionTest {
    private static final int GRID = 9;
    private static final int ICE_COLOR = 0xFFB7D7EC;
    private static final int DIRT_COLOR = 0xFF6F4A32;

    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void undergroundSamplesNeverAddAPredictionCeilingOrExpandSurfaceBounds() {
        ClientColumnSample cavern = new ClientColumnSample(96, 96, 0,
                PredictionMaterialPalette.stoneIndex(), 0, 0, 0, 0, 0, 0, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                80, 64, 40, -48);
        ClientColumnSample[] samples = filled(cavern);
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            PredictionMesh mesh = build(samples, null, step);
            assertEquals((GRID - 1) * (GRID - 1) * 6, mesh.vertexCount(),
                    "only the exterior surface belongs to prediction");
            for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
                assertEquals(96, mesh.y(vertex));
                assertEquals(1, mesh.normalY(vertex));
            }
            assertFalse(pack(mesh, step).downFaces());
        }
        assertEquals(new PredictionDepthBound(96, 96), PredictionDepthBound.fromSamples(samples));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void mergedCliffsRemainRectangularInEveryDirection(int step) {
        for (boolean alongX : new boolean[]{false, true}) {
            for (boolean reverse : new boolean[]{false, true}) {
                ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
                for (int z = 0; z < GRID; z++) {
                    for (int x = 0; x < GRID; x++) {
                        boolean high = ((alongX ? z : x) < 4) != reverse;
                        samples[z * GRID + x] = column(high ? 100 : 64,
                                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK);
                    }
                }
                PredictionMesh mesh = build(samples, null, step);
                PredictionQuadMesh quads = mesh.packed();
                int merged = 0;
                float totalArea = 0;
                for (int q = 0; q < quads.quadCount(); q++) {
                    if (quads.normalY(q, 0) != 0) continue;
                    assertEquals(quads.x(q, 0), quads.x(q, 3), "wall start X");
                    assertEquals(quads.z(q, 0), quads.z(q, 3), "wall start Z");
                    assertEquals(quads.x(q, 1), quads.x(q, 2), "wall end X");
                    assertEquals(quads.z(q, 1), quads.z(q, 2), "wall end Z");
                    float length = Math.abs(quads.x(q, 1) - quads.x(q, 0))
                            + Math.abs(quads.z(q, 1) - quads.z(q, 0));
                    totalArea += length * (quads.y(q, 0) - quads.y(q, 2));
                    if (length > step) merged++;
                }
                assertTrue(merged > 0, "continuous walls must still merge");
                assertEquals((GRID - 1) * step * 36.0F, totalArea);
                assertPackedRectangles(pack(mesh, step));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8})
    void refinementPreservesAnIceColumnBesideDirt(int step) {
        int ice = BuiltInRegistries.BLOCK.getId(Blocks.ICE);
        int dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        ClientColumnSample[] samples = filled(column(64, dirt, dirt));
        int[] colors = new int[samples.length];
        Arrays.fill(colors, DIRT_COLOR);
        samples[4 * GRID + 4] = column(96, ice, ice);
        colors[4 * GRID + 4] = ICE_COLOR;
        int[] original = colors.clone();
        PredictionMesh mesh = build(samples, colors, step);
        int first = mesh.cellOffsets[4 * (GRID - 1) + 4];
        assertEquals(96.0F, mesh.y(first), "a distinct material is not a height outlier");
        for (int vertex = first; vertex < first + 6; vertex++) {
            assertEquals(ICE_COLOR & 0xFFFFFF, mesh.color(vertex) & 0xFFFFFF,
                    "ice top must keep its own appearance at step " + step);
        }
        assertArrayEquals(original, colors, "mesh building must not change caller appearance data");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void finestRetainsHeightAndCoarseSmoothingPreservesTintAndInput(int step) {
        int dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        ClientColumnSample[] samples = filled(column(64, dirt, dirt));
        samples[4 * GRID + 4] = column(96, dirt, dirt);
        int[] colors = new int[samples.length];
        Arrays.fill(colors, DIRT_COLOR);
        colors[4 * GRID + 4] = 0xFF886655;
        int[] original = colors.clone();
        PredictionMesh mesh = build(samples, colors, step);
        int first = mesh.cellOffsets[4 * (GRID - 1) + 4];
        assertEquals(step == 1 ? 96.0F : 64.0F, mesh.y(first),
                "finest block geometry must retain its sampled height");
        assertEquals(0x886655, mesh.color(first) & 0xFFFFFF);
        assertArrayEquals(original, colors);
    }

    @Test
    void iceTopBandDoesNotBlendIntoTheDirtBelow() {
        int ice = BuiltInRegistries.BLOCK.getId(Blocks.ICE);
        int dirt = BuiltInRegistries.BLOCK.getId(Blocks.DIRT);
        ClientColumnSample[] samples = filled(column(64, ice, dirt));
        samples[4 * GRID + 4] = column(65, ice, dirt);
        PredictionMesh mesh = build(samples, null, 1);
        int cell = 4 * (GRID - 1) + 4;
        int start = mesh.cellOffsets[cell] + 6;
        int end = mesh.cellOffsets[cell] + mesh.cellCounts[cell];
        assertTrue(start < end);
        for (int v = start; v < end; v += 6) {
            for (int corner = 1; corner < 6; corner++) {
                assertEquals(mesh.color(v), mesh.color(v + corner),
                        "one ice face must keep one material tint and sprite");
            }
        }
    }

    @Test
    void unknownIceStrataRetainIceInsteadOfInventingDirt() {
        int ice = BuiltInRegistries.BLOCK.getId(Blocks.ICE);
        ClientColumnSample[] samples = filled(column(64, ice, ClientColumnSample.NO_BLOCK));
        samples[4 * GRID + 4] = column(68, ice, ClientColumnSample.NO_BLOCK);
        int[] colors = new int[samples.length];
        Arrays.fill(colors, ICE_COLOR);
        PredictionMesh mesh = build(samples, colors, 1);
        int cell = 4 * (GRID - 1) + 4;
        for (int v = mesh.cellOffsets[cell] + 6;
                v < mesh.cellOffsets[cell] + mesh.cellCounts[cell]; v++) {
            int color = mesh.color(v);
            assertTrue((color & 255) > (color >> 16 & 255), "unknown ice strata must stay blue");
        }
    }

    @Test
    void isolatedFrozenSurfaceSurvivesDryNeighbours() {
        ClientColumnSample[] samples = filled(column(60, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_BLOCK));
        samples[4 * GRID + 4] = fluid(63, true);
        PredictionMesh mesh = build(samples, null, 1);
        int cell = 4 * (GRID - 1) + 4;
        assertTrue(mesh.waterCells[cell], "a frozen cell must not expose its brown bed at the bank");
        assertEquals(63.0F, mesh.waterY(mesh.waterOffsets[cell]));
        assertEquals(1.0F, mesh.waterNormalY(mesh.waterOffsets[cell]));
    }

    @Test
    void fluidHeightAndKindBelongToTheirOwnColumn() {
        ClientColumnSample[] samples = filled(fluid(63, false));
        samples[4 * GRID + 5] = fluid(70, true);
        PredictionMesh mesh = build(samples, null, 1);
        int first = mesh.waterOffsets[4 * (GRID - 1) + 4];
        assertEquals(63 - PredictionMeshBuilder.FLUID_SURFACE_DROP, mesh.waterY(first), 0.0001F);
        assertEquals(1.0F / 3.0F, mesh.waterNormalY(first), 0.0001F);
    }

    @Test
    void mergedIceBanksAreRectanglesWithWallUvs() {
        ClientColumnSample[] samples = filled(fluid(63, true));
        for (int z = 0; z < GRID; z++) {
            samples[z * GRID + GRID - 1] = column(60, ClientColumnSample.NO_BLOCK,
                    ClientColumnSample.NO_BLOCK);
        }
        PredictionMesh mesh = build(samples, null, 2);
        PredictionQuadMesh quads = mesh.packed();
        int merged = 0;
        for (int q = 0; q < quads.waterQuadCount(); q++) {
            if (quads.waterNormalX(q, 0) == 0 && quads.waterNormalZ(q, 0) == 0) continue;
            assertEquals(quads.waterX(q, 0), quads.waterX(q, 3));
            assertEquals(quads.waterZ(q, 0), quads.waterZ(q, 3));
            assertEquals(quads.waterX(q, 1), quads.waterX(q, 2));
            assertEquals(quads.waterZ(q, 1), quads.waterZ(q, 2));
            if (quads.waterCoverageUsesLocalPosition(q)) merged++;
        }
        assertTrue(merged > 0);
        int[] words = pack(mesh, 2).quads();
        int banks = 0;
        for (int q = 0; q < words.length; q += 12) {
            int attr = words[q + 6];
            if ((attr & PredictionPackedMesh.FLAG_BANK) == 0) continue;
            assertEquals(3, attr >> PredictionPackedMesh.FLAGS_FLUID_SHIFT & 3);
            assertEquals(0, attr & (PredictionPackedMesh.FLAG_UNSHADED | PredictionPackedMesh.FLAG_UV_Y_POS),
                    "ice normal Y is a fluid kind, not diagonal vegetation geometry");
            assertNotEquals(0, attr >> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3);
            banks++;
        }
        assertTrue(banks > 0);
    }

    private static void assertPackedRectangles(PredictionPackedMesh packed) {
        int[] words = packed.quads();
        for (int q = 0; q < words.length; q += 12) {
            if ((words[q + 6] >> PredictionPackedMesh.FLAGS_AXIS_SHIFT & 3) == 0) continue;
            assertEquals(words[q] & 0xFFFF, words[q + 1] >>> 16);
            assertEquals(words[q] >>> 16, words[q + 1] & 0xFFFF);
            assertEquals(words[q + 2] & 0xFFFF, words[q + 3] >>> 16);
            assertEquals(words[q + 2] >>> 16, words[q + 3] & 0xFFFF);
        }
    }

    private static PredictionPackedMesh pack(PredictionMesh mesh, int spacing) {
        var dimension = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"));
        var tile = new PredictionTileManager.PredictionTile(
                new PredictionTileManager.PredictionTileKey(dimension, 0, 0, 0),
                new int[0], new int[0], new ClientColumnSample[0], mesh,
                new PredictionDepthBound(60, 100), 0L, 1L, mesh.cellAxis(), spacing);
        return PredictionPackedMesh.pack(tile);
    }

    private static PredictionMesh build(ClientColumnSample[] samples, int[] colors, int step) {
        return PredictionMeshBuilder.build(samples, colors, 63, 0xB22D78C5, step, GRID, false);
    }

    private static ClientColumnSample[] filled(ClientColumnSample sample) {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        Arrays.fill(samples, sample);
        return samples;
    }

    private static ClientColumnSample column(int height, int top, int under) {
        return new ClientColumnSample(height, height, 0, top, 0, 0, 0, 0, 0, 0, 0,
                under, under, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private static ClientColumnSample fluid(int height, boolean ice) {
        return new ClientColumnSample(60, height, 0, ClientColumnSample.NO_BLOCK, 0, 0, 0, 0,
                1, ice ? ClientColumnSample.FLAG_ICE : 0, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}

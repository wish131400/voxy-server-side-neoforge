package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionHandoffRegressionTest {
    private static final int GRID = 17;

    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void continuousWaterUsesMarginSamplesWithoutInventingTileBanks() {
        ClientColumnSample[] ocean = filled(column(40, 63, 1, 0, 0, 0, 0));
        for (int step : new int[]{1, 4, 8, 16}) {
            PredictionMesh mesh = build(ocean, step, null);
            assertEquals(256 * 6, mesh.waterVertexCount());
            for (int v = 0; v < mesh.waterVertexCount(); v++) {
                assertEquals(0, mesh.waterNormalX(v));
                assertEquals(0, mesh.waterNormalZ(v));
            }
        }
    }

    @Test
    void shorelineBanksStopAtNeighbourGroundAndOneBlockDepth() {
        ClientColumnSample[] samples = filled(column(60, 60, 0, 0, 0, 0, 0));
        samples[8 * GRID + 8] = column(40, 63, 1, 0, 0, 0, 0);
        PredictionMesh mesh = build(samples, 8, null);
        assertTrue(mesh.waterVertexCount() > 6);
        for (int v = 0; v < mesh.waterVertexCount(); v++) {
            assertTrue(mesh.waterY(v) >= 62, "water cannot hang as a deep curtain");
        }
    }

    @Test
    void waterColoursStayLocalInsteadOfUsingTheDimensionFallback() {
        ClientColumnSample[] samples = filled(column(40, 63, 1, 0, 0, 0, 0));
        int[] colors = new int[samples.length];
        for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
            colors[z * GRID + x] = x < 8 ? 0xB2336699 : 0xB2669933;
        }
        PredictionMesh mesh = build(samples, 4, colors);
        assertEquals(colors[0], mesh.waterColor(mesh.waterOffsets[0]));
        assertEquals(colors[8], mesh.waterColor(mesh.waterOffsets[8]));
    }

    @Test
    void placedVegetationRendersWithoutAFocusAtEveryDetailTier() {
        ClientColumnSample[] forest = filled(column(64, 64, 0, 1, 48, 0, 0));
        for (int z = 0; z < GRID; z += 4) for (int x = 0; x < GRID; x += 4) {
            forest[z * GRID + x] = column(64, 64, 0, 1, 48, ClientColumnSample.FLAG_TREE_HERE, 0);
        }
        for (int step : new int[]{2, 4, 8}) {
            int size = step / 2;
            var blocks = java.util.Map.of(new net.minecraft.core.BlockPos(4, 68, 4),
                    net.minecraft.world.level.block.Blocks.MUSHROOM_STEM.defaultBlockState());
            var plants = PredictionVegetation.Tile.of(blocks, 0, 0, 16 * step, step, size);
            PredictionMesh mesh = PredictionMeshBuilder.build(forest, null, 63, 0xB22D78C5,
                    step, GRID, true, null, null, null, 0, 0, plants);
            assertTrue(mesh.vertexCount() > 256 * 6, "forest missing at spacing " + step);
            assertEquals(256 * 6 + 30, mesh.vertexCount(), "one placed voxel emits five exterior faces");
        }
        assertEquals(256 * 6, build(forest, 16, null).vertexCount());
    }

    @Test
    void biomeDensityNeverInventsTreesInEmptyColumnsAtAnySpacing() {
        ClientColumnSample[] empty = filled(column(64, 64, 0, 1, 255, 0, 0));
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            assertEquals(256 * 6, build(empty, step, null).vertexCount(),
                    "an absent tree must stay absent at spacing " + step);
        }
    }

    @Test
    void groundHintsCannotManufacturePlants() {
        ClientColumnSample[] grass = filled(column(64, 64, 0, 0, 0, 0, 1));
        assertEquals(256 * 6, build(grass, 4, null).vertexCount());
        assertEquals(256 * 6, build(grass, 8, null).vertexCount());
    }

    private static PredictionMesh build(ClientColumnSample[] samples, int step, int[] waterColors) {
        return PredictionMeshBuilder.build(samples, null, 63, 0xB22D78C5, step, GRID,
                true, new PredictionFeatureStampCache(), null, waterColors, 2304, 3904);
    }

    private static ClientColumnSample[] filled(ClientColumnSample sample) {
        ClientColumnSample[] samples = new ClientColumnSample[GRID * GRID];
        Arrays.fill(samples, sample);
        return samples;
    }

    private static ClientColumnSample column(int y, int fluidY, int fluid, int tree, int density,
                                             int flags, int ground) {
        return new ClientColumnSample(y, fluidY, 0, PredictionMaterialPalette.grassBlockIndex(),
                0, tree, density, tree == 0 ? 0 : 8, fluid, flags, ground,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}

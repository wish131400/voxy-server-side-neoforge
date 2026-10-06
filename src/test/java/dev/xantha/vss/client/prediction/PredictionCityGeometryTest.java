package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.worldgen.LostCityPreview;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionCityGeometryTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void cityGroundDoesNotMutateSamplesOrSharedColors() {
        int stone = net.minecraft.world.level.block.Block.getId(Blocks.STONE.defaultBlockState());
        var city = new LostCityPreview.Chunk(LostCityPreview.BUILDING, 32, true,
                stone, stone, 0, 0, List.of(), null);
        var tile = new LostCityPreview.Tile(0, 0, 1, List.of(city));
        var sample = new ClientColumnSample(80, ClientColumnSample.NO_SPAN, 0,
                stone, 0, 0, 0, 0, 0, 0, 0, stone, stone,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        ClientColumnSample[] original = new ClientColumnSample[4];
        java.util.Arrays.fill(original, sample);
        int[] colors = {0xFF123456, 0xFF234567, 0xFF345678, 0xFF456789};
        int[] beforeColors = colors.clone();

        var result = PredictionCityGeometry.ground(original, tile,
                0, 0, 16, 2, colors);

        assertNotSame(original, result.samples());
        assertEquals(sample, original[0]);
        assertArrayEquals(beforeColors, colors);
        assertEquals(33, result.samples()[0].surfaceY());
        assertTrue(result.samples()[0].cityGround());
        assertTrue(result.samples()[0].surfaceOnly());
        var resident = new PredictionTileManager.PredictionTile(null, new int[4], new int[4],
                result.samples(), null, new PredictionDepthBound(0, 100), 0, 1, 1, 16);
        assertNull(PredictionTileManager.retainedSample(resident, 1, 1, 8, true, false));
        assertNull(PredictionTileManager.retainedSample(resident, 1, 1, 8, false, true));
        assertFalse(result.samples()[0].reusableFor(false));
        assertTrue(sample.reusableForDisplay(), "raw terrain is still reusable");
    }

    @Test
    void invalidSummaryBlockStateFallsBackToStone() {
        var city = new LostCityPreview.Chunk(LostCityPreview.BUILDING, 32, true,
                1_048_576, 1_048_576, 0, 0, List.of(), null);
        var tile = new LostCityPreview.Tile(0, 0, 1, List.of(city));
        var sample = new ClientColumnSample(80, ClientColumnSample.NO_SPAN, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        var result = PredictionCityGeometry.ground(
                new ClientColumnSample[] {sample}, tile, 0, 0, 16, 1, null);
        assertEquals(33, result.samples()[0].surfaceY());
        assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.STONE),
                result.samples()[0].topBlockIndex());
    }

    @Test
    void facadeFacesBelongToExactlyOneCellIncludingNegativeChunkBorders() {
        var wall = new LostCityPreview.Model(new int[] {
                LostCityPreview.origin(0, 0, 16), LostCityPreview.extent(16, 12, 0, 2), 1,
                LostCityPreview.origin(16, 0, 0), LostCityPreview.extent(0, 12, 16, 4), 1,
                LostCityPreview.origin(0, 12, 0), LostCityPreview.extent(16, 0, 16, 0), 1
        });
        var building = new LostCityPreview.Chunk(LostCityPreview.BUILDING, 64, false,
                1, 1, 0, 0, List.of(new LostCityPreview.Placement(0, wall)), wall);
        var tile = new LostCityPreview.Tile(-1, -1, 1, List.of(building));
        for (int step : new int[] {1, 4, 16}) {
            int axis = 16 / step, roof = 0, side = 0;
            var geometry = new PredictionCityGeometry(tile, -16, -16, axis, step);
            for (int cell = 0; cell < axis * axis; cell++) for (var face : geometry.faces(cell)) {
                assertTrue(face.x() >= 0 && face.z() >= 0);
                if (face.direction() == 0) roof += face.dx() * face.dz();
                else side += face.dy() * Math.max(face.dx(), face.dz());
            }
            assertEquals(256, roof);
            assertEquals(384, side);
        }
    }

    @Test
    void capturedGroundIsNeverFlattened() {
        var city = new LostCityPreview.Chunk(LostCityPreview.ROAD, 32, true,
                1, 1, 8, 15, List.of(), null);
        var sample = new ClientColumnSample(80, ClientColumnSample.NO_SPAN, 0,
                1, 0, 0, 0, 0, 0, ClientColumnSample.FLAG_CAPTURED, 0, 1, 1,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        var result = PredictionCityGeometry.ground(new ClientColumnSample[] {sample},
                new LostCityPreview.Tile(0, 0, 1, List.of(city)), 0, 0, 16, 1, null);
        assertEquals(sample, result.samples()[0]);
    }
}

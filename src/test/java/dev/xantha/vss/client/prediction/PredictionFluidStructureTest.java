package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionFluidStructureTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void solidVillageFoundationDisplacesOnlyItsOwnWater() {
        for (int step : new int[]{1, 2, 4}) {
            var mesh = build(step, Map.of(new BlockPos(0, 62, 0), Blocks.SANDSTONE.defaultBlockState()));
            assertEquals(4 * step * step - 1, area(mesh), 1e-5);
            assertEquals(area(mesh), packedArea(mesh), 1e-5, "greedy merging cannot refill a clipped building footprint");
            assertEquals(step == 1 ? 0 : 1, mesh.waterCounts[0] > 0 ? 1 : 0);
        }
    }

    @Test void sampledLavaRendersEvenWithoutAnOceanSeaLevel() {
        var samples = new ClientColumnSample[9];
        Arrays.fill(samples,new ClientColumnSample(60,63,0,0,0,0,0,0,2,0,0,0,0,
                ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN,ClientColumnSample.NO_SPAN));
        var mesh = PredictionMeshBuilder.build(samples,null,Integer.MIN_VALUE,0xffd9572b,1,3,
                false,null,null,null,0,0,PredictionVegetation.Tile.EMPTY);
        assertEquals(4,area(mesh),1e-5,"Fluids are sampled independently of the dimension's sea level");
        assertTrue(mesh.waterVertexCount()>0);
    }

    @Test void waterloggedLowerSlabKeepsWaterButUpperSlabDisplacesSurface() {
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        assertEquals(3, area(build(1, Map.of(new BlockPos(0, 62, 0), Blocks.OAK_SLAB.defaultBlockState()))),
                1e-5, "a dry slab replaces the fluid block, unlike a waterlogged slab");
        assertEquals(4, area(build(1, Map.of(new BlockPos(0, 62, 0), slab))), 1e-5);
        assertEquals(3, area(build(1, Map.of(new BlockPos(0, 62, 0),
                slab.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP)))), 1e-5);
        assertEquals(4, area(build(1, Map.of(new BlockPos(0, 65, 0), Blocks.SANDSTONE.defaultBlockState()))),
                1e-5, "a bridge above the water does not drain the river");
    }

    @Test void wallsRemainWhenStructureCoversEntireTop() {
        var tile = PredictionVegetation.Tile.of(Map.of(new BlockPos(0, 63, 0),
                Blocks.SANDSTONE.defaultBlockState()), 0, 0, 2, 1, 1);
        var clip = new PredictionFluidOcclusion(tile, 2, 1);
        assertTrue(clip.visible(0, 0, 63, new PredictionFluidOcclusion.Rect(0, 0, 1, 1)).isEmpty());
        assertEquals(1, clip.visible(0, 1, 0, new PredictionFluidOcclusion.Rect(0, 62, 1, 62.875)).size());
    }

    @Test void fluidHeightSurvivesGpuPackingAtEveryTileScale() {
        for (int step : new int[]{1, 2, 128, 4096, 32768}) {
            PredictionMesh mesh = build(step, Map.of());
            var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
            var key = new PredictionTileManager.PredictionTileKey(dimension, 0, 0, 0);
            var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0],
                    new ClientColumnSample[0], mesh, new PredictionDepthBound(60, 63),
                    0L, 1L, mesh.cellAxis(), step);
            var packed = PredictionPackedMesh.pack(tile);
            int[] words = packed.quads();
            for (int q = packed.terrainQuadCount(); q < packed.quadCount(); q++) {
                int at = q * 12;
                assertTrue((words[at + 6] & PredictionPackedMesh.FLAG_FLUID_FINE_Y) != 0);
                for (int c = 0; c < 4; c++) {
                    int y = (words[at + 4 + c / 2] >>> ((c & 1) * 16)) & 0xFFFF;
                    float decoded = (y - 32768) / 16F;
                    assertEquals(63 - PredictionMeshBuilder.FLUID_SURFACE_DROP, decoded, .016F);
                    assertTrue(decoded < 63, "water must not snap onto a solid top at spacing " + step);
                }
            }
        }
    }

    private static PredictionMesh build(int step, Map<BlockPos, BlockState> blocks) {
        var samples = new ClientColumnSample[9];
        Arrays.fill(samples, new ClientColumnSample(60, 63, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN));
        var vegetation = PredictionVegetation.Tile.of(blocks, 0, 0, 2 * step, step, 1);
        return PredictionMeshBuilder.build(samples, null, 63, 0xB22D78C5, step, 3,
                true, null, null, null, 0, 0, vegetation);
    }

    private static double area(PredictionMesh mesh) {
        double area = 0;
        for (int v = 0; v < mesh.waterVertexCount(); v += 6) {
            if (mesh.waterNormalX(v) == 0 && mesh.waterNormalZ(v) == 0)
                area += (mesh.waterX(v + 1) - mesh.waterX(v)) * (mesh.waterZ(v + 5) - mesh.waterZ(v));
        }
        return area;
    }

    private static double packedArea(PredictionMesh mesh) {
        var packed = mesh.packed();
        double area = 0;
        for (int q = 0; q < packed.waterQuadCount(); q++) {
            if (packed.waterNormalX(q, 0) == 0 && packed.waterNormalZ(q, 0) == 0)
                area += (packed.waterX(q, 1) - packed.waterX(q, 0)) * (packed.waterZ(q, 3) - packed.waterZ(q, 0));
        }
        return area;
    }
}

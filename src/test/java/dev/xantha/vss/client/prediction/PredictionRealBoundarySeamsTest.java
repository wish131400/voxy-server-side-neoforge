package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionRealBoundarySeamsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }







    @Test void missingGrassStrataDoNotRepeatGrassAndExplicitMaterialsArePreserved() {
        int grass = PredictionMaterialPalette.grassBlockIndex();
        var sample = new ClientColumnSample(64, 64, 0, grass, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        assertEquals(PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.wallUnderBlock(sample));
        assertEquals(PredictionMaterialPalette.stoneIndex(), PredictionMaterialPalette.wallDeepBlock(sample));
        int sand = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(net.minecraft.world.level.block.Blocks.SAND);
        var explicit = new ClientColumnSample(64, 64, 0, grass, 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_CAPTURED, 0, sand, sand,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
        assertEquals(sand, PredictionMaterialPalette.wallUnderBlock(explicit));
        assertEquals(sand, PredictionMaterialPalette.wallDeepBlock(explicit));
    }

    static ClientColumnSample ground(int height) {
        return new ClientColumnSample(height, height, 0, ClientColumnSample.NO_BLOCK,
                0, 0, 0, 0, 0, ClientColumnSample.FLAG_CAPTURED, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    @Test void caveAirAndBuriedSurfaceMaterialsCannotPaintTheWholeCliff() {
        var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
        for (var block : new net.minecraft.world.level.block.Block[]{
                net.minecraft.world.level.block.Blocks.AIR, net.minecraft.world.level.block.Blocks.CAVE_AIR,
                net.minecraft.world.level.block.Blocks.WATER, net.minecraft.world.level.block.Blocks.SHORT_GRASS,
                net.minecraft.world.level.block.Blocks.GRASS_BLOCK, net.minecraft.world.level.block.Blocks.PODZOL}) {
            int id = registry.getId(block);
            var sample = new ClientColumnSample(100, 100, 0, PredictionMaterialPalette.grassBlockIndex(),
                    0, 0, 0, 0, 0, ClientColumnSample.FLAG_SURFACE_ONLY, 0, id, id,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            assertEquals(PredictionMaterialPalette.dirtIndex(), PredictionMaterialPalette.wallUnderBlock(sample), block.toString());
            assertEquals(PredictionMaterialPalette.stoneIndex(), PredictionMaterialPalette.wallDeepBlock(sample), block.toString());
        }
        for (var block : new net.minecraft.world.level.block.Block[]{
                net.minecraft.world.level.block.Blocks.SANDSTONE, net.minecraft.world.level.block.Blocks.RED_TERRACOTTA,
                net.minecraft.world.level.block.Blocks.NETHERRACK, net.minecraft.world.level.block.Blocks.END_STONE}) {
            int id = registry.getId(block);
            var sample = new ClientColumnSample(100, 100, 0, id, 0, 0, 0, 0, 0,
                    ClientColumnSample.FLAG_SURFACE_ONLY, 0, id, id,
                    ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
            assertEquals(id, PredictionMaterialPalette.wallUnderBlock(sample));
            assertEquals(id, PredictionMaterialPalette.wallDeepBlock(sample));
        }
    }


}

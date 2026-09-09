package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionAppearanceRegressionTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void treeHintPreservesStoneSoilAndSnowMaterials() {
        for (var block : new net.minecraft.world.level.block.Block[]{
                Blocks.STONE, Blocks.PODZOL, Blocks.DIRT, Blocks.SNOW_BLOCK}) {
            int id = BuiltInRegistries.BLOCK.getId(block);
            ClientColumnSample plain = column(id, 0);
            ClientColumnSample forest = column(id, ClientColumnSample.FLAG_TREE_HERE);
            assertEquals(id, PredictionMaterialPalette.surfaceBlock(forest), block.toString());
            assertEquals(PredictionMaterialPalette.colorFor(plain, 0x7FB238),
                    PredictionMaterialPalette.colorFor(forest, 0x7FB238), block.toString());
        }
    }

    @Test
    void whiteRegistryMultiplierNeverBecomesAWhiteMissingTexture() throws Exception {
        Field field = PredictionMaterialPalette.class.getDeclaredField("atlasColors");
        field.setAccessible(true);
        Object old = field.get(null);
        int id = BuiltInRegistries.BLOCK.getId(Blocks.STONE);
        int[] tints = new int[BuiltInRegistries.BLOCK.size()];
        tints[id] = 0xFFFFFFFF;
        try {
            field.set(null, tints);
            assertEquals(0xFF8A8A8A, PredictionMaterialPalette.colorFor(column(id, 0), 0x7FB238));
            assertEquals(0xFF776655, PredictionMaterialPalette.colorForBlockId(id, 0xFF776655, 0));
        } finally {
            field.set(null, old);
        }
    }

    @Test
    void baseTileInvalidatesItsActualAncestorsOnBothSidesOfZero() {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(),
                new ClientTerrainSampler(42L, PROFILE))) {
            for (int coordinate : new int[]{3, -3}) {
                var child = key(coordinate, coordinate, 0);
                manager.markCoverageDirty(child);
                for (int lod = 1; lod < manager.layout().levelCount(); lod++) {
                    assertTrue(manager.coverageFamilyEpoch(key(coordinate >> lod, coordinate >> lod, lod)) > 0,
                            "64-block base tile must invalidate its parent at lod " + lod);
                }
            }
            assertEquals(0L, manager.coverageFamilyEpoch(key(6, 6, 1)),
                    "the old 16-chunk base span invalidated the wrong parent");
        } finally {
            VSSClientConfig.CONFIG.rememberTerrain = remember;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void lateParentInvalidatesResidentFinerFallback() throws Exception {
        boolean remember = VSSClientConfig.CONFIG.rememberTerrain;
        VSSClientConfig.CONFIG.rememberTerrain = false;
        try (PredictionTileManager manager = new PredictionTileManager(PROFILE.levelKey(),
                new ClientTerrainSampler(42L, PROFILE))) {
            Field field = PredictionTileManager.class.getDeclaredField("ready");
            field.setAccessible(true);
            Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile> ready =
                    (Map<PredictionTileManager.PredictionTileKey, PredictionTileManager.PredictionTile>) field.get(manager);
            var child = key(-3, 3, 0);
            var unrelated = key(10, 10, 0);
            ready.put(child, emptyTile(child));
            ready.put(unrelated, emptyTile(unrelated));
            manager.markCoverageDirty(key(-2, 1, 1));
            assertEquals(1L, manager.coverageFamilyEpoch(child));
            assertEquals(0L, manager.coverageFamilyEpoch(unrelated));
        } finally {
            VSSClientConfig.CONFIG.rememberTerrain = remember;
        }
    }

    private static PredictionTileManager.PredictionTileKey key(int x, int z, int lod) {
        return new PredictionTileManager.PredictionTileKey(PROFILE.levelKey(), x, z, lod);
    }

    private static PredictionTileManager.PredictionTile emptyTile(PredictionTileManager.PredictionTileKey key) {
        return new PredictionTileManager.PredictionTile(key, new int[0], new int[0],
                new ClientColumnSample[0], null, new PredictionDepthBound(64, 64), 0L, 1L, 64, 1);
    }

    private static ClientColumnSample column(int block, int flags) {
        return new ClientColumnSample(64, 64, 0, block, 0, 1, 12, 12, 0, flags, 0,
                ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
}

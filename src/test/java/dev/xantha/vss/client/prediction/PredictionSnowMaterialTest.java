package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PredictionSnowMaterialTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void reset() throws Exception {
        VssLodSpriteTable.close();
        field(PredictionMaterialPalette.class, "atlasColors").set(null, null);
    }

    @Test void vanillaSnowSideAndDirtIgnoreGreenBiomeTintWhileLeavesRetainIt() throws Exception {
        int[] colors = new int[BuiltInRegistries.BLOCK.size()];
        colors[BuiltInRegistries.BLOCK.getId(Blocks.GRASS_BLOCK)] = 0xff55bb22;
        colors[BuiltInRegistries.BLOCK.getId(Blocks.OAK_LEAVES)] = 0xff55bb22;
        field(PredictionMaterialPalette.class, "atlasColors").set(null, colors);
        field(VssLodSpriteTable.class, "runtimeReady").set(null, true);
        // Vanilla grass_block_snow inherits cube_bottom_top: no face has tintindex.
        try (var model = getClass().getResourceAsStream("/assets/minecraft/models/block/grass_block_snow.json");
             var parent = getClass().getResourceAsStream("/assets/minecraft/models/block/cube_bottom_top.json")) {
            assertNotNull(model); assertNotNull(parent);
            assertTrue(new String(model.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("cube_bottom_top"));
            assertFalse(new String(parent.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("tintindex"));
        }
        var snow = Blocks.GRASS_BLOCK.defaultBlockState().setValue(SnowyDirtBlock.SNOWY, true);
        int average = install(snow, "grass_block_snow", -1);
        assertEquals(average, PredictionMaterialPalette.colorForState(snow, 0xffffffff, 0xff00ff00, 1),
                "the snow rim and brown dirt must not receive the grass provider's green multiplier");
        var dirt = Blocks.DIRT.defaultBlockState();
        assertEquals(install(dirt, "dirt", -1), PredictionMaterialPalette.colorForState(dirt, 0xffffffff, 0xff00ff00, 1));
        var leaves = Blocks.OAK_LEAVES.defaultBlockState();
        int leafAverage = install(leaves, "oak_leaves", 0);
        assertEquals(0xff000000 | (leafAverage & 0x0000ff00),
                PredictionMaterialPalette.colorForState(leaves, 0xffffffff, 0xff00ff00, 1));
        VssLodSpriteTable.close();
        assertTrue(((Map<?, ?>)field(VssLodSpriteTable.class, "TINT_BY_STATE_FACE").get(null)).isEmpty());
    }

    @Test void onlySnowCoveredExposedGroundUsesSnowyState() {
        var snow = sample(Blocks.GRASS_BLOCK, ClientColumnSample.FLAG_SNOW, 0);
        assertTrue(PredictionMaterialPalette.groundSideState(snow).getValue(SnowyDirtBlock.SNOWY));
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.DIRT), PredictionMaterialPalette.wallUnderBlock(snow));
        assertEquals(BuiltInRegistries.BLOCK.getId(Blocks.STONE), PredictionMaterialPalette.wallDeepBlock(snow));
        assertFalse(PredictionMaterialPalette.groundSideState(sample(Blocks.GRASS_BLOCK, 0, 0)).getValue(SnowyDirtBlock.SNOWY));
        assertFalse(PredictionMaterialPalette.groundSideState(sample(Blocks.GRASS_BLOCK, ClientColumnSample.FLAG_SNOW, 1)).getValue(SnowyDirtBlock.SNOWY));
        assertFalse(PredictionMaterialPalette.groundSideState(sample(Blocks.GRASS_BLOCK,
                ClientColumnSample.FLAG_SNOW | ClientColumnSample.FLAG_NO_SURFACE, 0)).getValue(SnowyDirtBlock.SNOWY));
        assertEquals(Blocks.STONE.defaultBlockState(), PredictionMaterialPalette.groundSideState(sample(Blocks.STONE, ClientColumnSample.FLAG_SNOW, 0)));
        assertTrue(PredictionMaterialPalette.groundSideState(sample(Blocks.PODZOL, ClientColumnSample.FLAG_SNOW, 0)).getValue(SnowyDirtBlock.SNOWY));
    }

    @SuppressWarnings("unchecked")
    private int install(BlockState state, String name, int tint) throws Exception {
        try (var input = getClass().getResourceAsStream("/assets/minecraft/textures/block/" + name + ".png")) {
            assertNotNull(input);
            try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/" + name),
                    new FrameSize(16, 16), NativeImage.read(input), ResourceMetadata.EMPTY)) {
                var sprite = new Sprite(contents);
                var quad = new BakedQuad(new int[32], tint, Direction.NORTH, sprite, true);
                int row = VssLodSpriteTable.registerSprite(VssLodSpriteTable.selectedFace(state, 1, quad));
                ((Map<Long, Integer>) field(VssLodSpriteTable.class, "INDEX_BY_STATE_FACE").get(null))
                        .put(((long)Block.getId(state) << 3) | 1, row);
                return VssLodSpriteTable.averageForState(state, 1);
            }
        }
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static ClientColumnSample sample(Block block, int flags, int fluid) {
        return new ClientColumnSample(90, fluid == 0 ? 90 : 93, 0, BuiltInRegistries.BLOCK.getId(block),
                0, 0, 0, 0, fluid, flags, 0, BuiltInRegistries.BLOCK.getId(Blocks.DIRT),
                BuiltInRegistries.BLOCK.getId(Blocks.STONE), ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }
    private static class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, 0, 0); }
    }
}

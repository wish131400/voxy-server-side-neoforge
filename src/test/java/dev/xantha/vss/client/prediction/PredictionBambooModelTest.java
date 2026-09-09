package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class PredictionBambooModelTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @BeforeEach @AfterEach void clear() { VssLodSpriteTable.close(); }

    @Test void actualVanillaBambooKeepsThinStalkLeafPlanesAndPerFaceUvs() throws Exception {
        for (int age = 0; age <= 1; age++) {
            String modelName = "bamboo1_age" + age;
            try (var content = contents("bamboo_stalk")) {
                var quads = bake(modelName, new Sprite(content));
                var faces = PredictionBlockModel.faces(Blocks.BAMBOO.defaultBlockState(), new BlockPos(-27, 64, 39), 0xFF10FF10, quads);
                assertEquals(5, faces.size(), "four stalk sides and top, no underside");
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (var face : faces) for (int c = 0; c < 4; c++) {
                    min = Math.min(min, face.positions()[c * 3]); max = Math.max(max, face.positions()[c * 3]);
                }
                assertEquals((age == 0 ? 2F : 3F) / 16, max - min, .00001,
                        "collision boxes and full-width crossed plants are not bamboo's rendered model");
                var side = quads.stream().filter(q -> q.getDirection() == Direction.NORTH).findFirst().orElseThrow();
                int row = VssLodSpriteTable.registerModelFace(side, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.BAMBOO));
                assertTrue(VssLodSpriteTable.modelFace(row));
                assertEquals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.BAMBOO),
                        VssLodSpriteTable.materialBlocks()[row], "Iris receives bamboo material ids for baked faces");
                float[] uv = VssLodSpriteTable.modelUvs(row);
                float umin = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
                float umax = Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6]));
                assertEquals((age == 0 ? 2F : 3F) / 16, umax - umin, .02,
                        "stalk side uses only the correct narrow strip of its texture");
                assertEquals(row, VssLodSpriteTable.registerModelFace(side, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.BAMBOO)));
                assertEquals(5, faces.stream().filter(f -> f.normal()[1] >= 0).count());
            }
        }
        for (String leaves : List.of("bamboo_small_leaves", "bamboo_large_leaves")) {
            try (var content = contents(leaves)) {
                var faces = PredictionBlockModel.faces(Blocks.BAMBOO.defaultBlockState(), BlockPos.ZERO, 0xFF71A74D,
                        bake(leaves, new Sprite(content)));
                assertEquals(4, faces.size(), "two original leaf planes with both sides");
                for (var face : faces) {
                    int row = face.color() >>> 24;
                    assertFalse(VssLodSpriteTable.modelShade(row));
                    assertTrue(VssLodSpriteTable.isCutout(row));
                    assertEquals(VssLodSpriteTable.modelColor(row, 0xFFFFFF), face.color(),
                            "the original green texture cannot be tinted as generic tree leaves");
                }
            }
        }
    }

    @Test void denseBambooBudgetKeepsWholeThinStalksInsteadOfDroppingTheEntireForest() {
        var blocks = new java.util.HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) for (int y = 64; y < 76; y++)
            blocks.put(new BlockPos(x, y, z), Blocks.BAMBOO.defaultBlockState());
        var tile = PredictionVegetation.boundedTile(blocks, 0, 0, 64, 1, 1);
        assertFalse(tile.blocks().isEmpty());
        assertTrue(tile.blocks().size() * 54 < 131_072, "budget includes multipart geometry");
        for (var position : tile.blocks().keySet()) for (int y = 64; y < 76; y++)
            assertTrue(tile.blocks().containsKey(new BlockPos(position.getX(), y, position.getZ())));
        assertTrue(tile.cells().values().stream().flatMap(List::stream).allMatch(voxel -> voxel.size() == 1));
    }

    private static List<BakedQuad> bake(String name, TextureAtlasSprite sprite) throws Exception {
        try (var input = PredictionBambooModelTest.class.getResourceAsStream("/assets/minecraft/models/block/" + name + ".json")) {
            assertNotNull(input);
            var model = BlockModel.fromStream(new InputStreamReader(input, StandardCharsets.UTF_8));
            var bakery = new FaceBakery();
            List<BakedQuad> quads = new ArrayList<>();
            for (var element : model.getElements()) for (var face : element.faces.entrySet())
                quads.add(bakery.bakeQuad(element.from, element.to, face.getValue(), sprite,
                        face.getKey(), net.minecraft.client.resources.model.BlockModelRotation.X0_Y0, element.rotation, element.shade));
            return quads;
        }
    }

    private static SpriteContents contents(String name) throws Exception {
        try (var input = PredictionBambooModelTest.class.getResourceAsStream("/assets/minecraft/textures/block/" + name + ".png")) {
            assertNotNull(input);
            return new SpriteContents(ResourceLocation.withDefaultNamespace("block/" + name), new FrameSize(16, 16),
                    NativeImage.read(input), ResourceMetadata.EMPTY);
        }
    }
    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, 0, 0); }
    }
}

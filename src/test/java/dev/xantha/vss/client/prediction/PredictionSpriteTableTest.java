package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PredictionSpriteTableTest {
    @BeforeEach
    @AfterEach
    void clearTable() {
        VssLodSpriteTable.close();
    }

    @Test
    void materialMappingRevisionChangesForNewModelRowsAndResourceReload() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        long initial = VssLodSpriteTable.materialRevision();
        try (SpriteContents contents = contents("stone", 0xFF808080)) {
            var sprite = new Sprite(contents, 0);
            var quad = new net.minecraft.client.renderer.block.model.BakedQuad(new int[32], -1,
                    net.minecraft.core.Direction.UP, sprite, true);
            int block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(net.minecraft.world.level.block.Blocks.STONE);
            int row = VssLodSpriteTable.registerModelFace(quad, block);
            long revision = VssLodSpriteTable.materialRevision();
            assertTrue(revision > initial);
            assertEquals(block, VssLodSpriteTable.materialBlocks()[row]);
            assertEquals(row, VssLodSpriteTable.registerModelFace(quad, block));
            assertEquals(revision, VssLodSpriteTable.materialRevision());
            VssLodSpriteTable.close();
            assertTrue(VssLodSpriteTable.materialRevision() > revision);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualVanillaTerracottaAverageReachesTheMeshBeforeGpuUpload() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var input = getClass().getResourceAsStream("/assets/minecraft/textures/block/orange_terracotta.png")) {
            assertNotNull(input, "vanilla texture must be present in the test runtime");
            NativeImage image = NativeImage.read(input);
            try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/orange_terracotta"),
                    new FrameSize(16, 16), image, ResourceMetadata.EMPTY)) {
                int row = VssLodSpriteTable.registerSprite(new Sprite(contents, 0));
                var field = VssLodSpriteTable.class.getDeclaredField("INDEX_BY_BLOCK");
                field.setAccessible(true);
                var indices = (java.util.Map<Integer, Integer>) field.get(null);
                int block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getId(net.minecraft.world.level.block.Blocks.ORANGE_TERRACOTTA);
                indices.put(block, row);
                int average = VssLodSpriteTable.averageColorArgb(block);
                // Orange terracotta is approximately RGB(162,84,38), not white or grass tint.
                assertEquals(162, average >> 16 & 255, 2);
                assertEquals(84, average >> 8 & 255, 2);
                assertEquals(38, average & 255, 2);
                assertEquals(average, PredictionMaterialPalette.colorForIndex(block, 0xFFFFFFFF));
            }
        }
    }

    @Test
    void repeatedFacesDoNotConsumeRowsNeededByOtherMaterials() {
        try (SpriteContents stone = contents("stone", 0xFF808080)) {
            TextureAtlasSprite sprite = new Sprite(stone, 0);
            for (int i = 0; i < 400; i++) {
                assertEquals(1, VssLodSpriteTable.registerSprite(sprite));
            }
        }
        try (SpriteContents ice = contents("ice", 0xFFECCCA0)) {
            assertEquals(2, VssLodSpriteTable.registerSprite(new Sprite(ice, 16)));
        }
    }

    @Test
    void spriteCapacityDoesNotWrapOrBlockAnExistingMaterial() {
        for (int i = 1; i <= 254; i++) {
            try (SpriteContents contents = contents("material_" + i, 0xFF808080)) {
                assertEquals(i, VssLodSpriteTable.registerSprite(new Sprite(contents, 0)));
            }
        }
        try (SpriteContents existing = contents("material_1", 0xFF808080);
             SpriteContents overflow = contents("overflow", 0xFF808080)) {
            assertEquals(1, VssLodSpriteTable.registerSprite(new Sprite(existing, 0)));
            assertEquals(VssLodSpriteTable.FLAT,
                    VssLodSpriteTable.registerSprite(new Sprite(overflow, 0)));
        }
    }

    @Test
    void atlasReloadClearsRowsAndAllowsReinitialization() throws Exception {
        try (SpriteContents oldImage = contents("stone", 0xFF808080);
             SpriteContents newImage = contents("stone", 0xFFCCCCCC)) {
            TextureAtlasSprite oldSprite = new Sprite(oldImage, 0);
            TextureAtlasSprite newSprite = new Sprite(newImage, 16);
            assertTrue(VssLodSpriteTable.refresh(oldSprite));
            assertEquals(1, VssLodSpriteTable.registerSprite(oldSprite));
            var ready = VssLodSpriteTable.class.getDeclaredField("runtimeReady");
            var broken = VssLodSpriteTable.class.getDeclaredField("broken");
            ready.setAccessible(true);
            broken.setAccessible(true);
            ready.set(null, true);
            broken.set(null, true);
            assertFalse(VssLodSpriteTable.refresh(oldSprite));
            assertTrue(VssLodSpriteTable.refresh(newSprite));
            assertFalse(ready.getBoolean(null), "reload must reseed the material palette");
            assertTrue(VssLodSpriteTable.available(), "a new atlas can recover a failed table");
            assertEquals(1, VssLodSpriteTable.registerSprite(newSprite));
        }
    }

    @Test void staticFireCopiesTransparentFirstFrameAndResetsOnReload() throws Exception {
        for (int kind = 0; kind < 2; kind++) {
            NativeImage image = new NativeImage(16, 32, false);
            image.fillRect(0, 0, 16, 16, kind == 0 ? 0xFF2090FF : 0xFFFFE050);
            image.fillRect(0, 16, 16, 16, 0xFF00FF00);
            image.setPixelRGBA(0, 0, 0);
            try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/test_fire_" + kind),
                    new FrameSize(16,16),image,ResourceMetadata.EMPTY)) {
                int row = VssLodSpriteTable.registerStaticFire(new Sprite(contents,0),kind);
                assertTrue(VssLodSpriteTable.isCutout(row));
                var field = VssLodSpriteTable.class.getDeclaredField("STATIC_FIRE");
                field.setAccessible(true);
                var snapshots = (java.util.Map<Integer,int[]>) field.get(null);
                assertEquals(0,snapshots.get(kind)[0]);
                assertEquals(kind == 0 ? 0xFF2090FF : 0xFFFFE050,snapshots.get(kind)[1]);
                image.setPixelRGBA(1,0,0xFF00FF00);
                assertNotEquals(0xFF00FF00,snapshots.get(kind)[1],"animation cannot modify the baked snapshot");
            }
        }
        VssLodSpriteTable.close();
        var field = VssLodSpriteTable.class.getDeclaredField("STATIC_FIRE"); field.setAccessible(true);
        assertTrue(((java.util.Map<?,?>)field.get(null)).isEmpty());
    }

    private static SpriteContents contents(String name, int abgr) {
        NativeImage image = new NativeImage(16, 16, false);
        image.fillRect(0, 0, 16, 16, abgr);
        return new SpriteContents(ResourceLocation.withDefaultNamespace("block/" + name),
                new FrameSize(16, 16), image, ResourceMetadata.EMPTY);
    }

    @Test void persistedMaterialSurvivesDifferentRowOrderAndPreservesModelUvsAndBlock() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try(var contents=contents("stone",0xff808080);var other=contents("dirt",0xff404040)) {
            var sprite=new Sprite(contents,0);
            int block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(net.minecraft.world.level.block.Blocks.STONE);
            var quad=new net.minecraft.client.renderer.block.model.BakedQuad(new int[32],-1,net.minecraft.core.Direction.UP,sprite,false);
            int row=VssLodSpriteTable.registerModelFace(quad,block);
            float[] uv=VssLodSpriteTable.modelUvs(row);
            var bytes=new java.io.ByteArrayOutputStream();
            VssLodSpriteTable.writeMaterial(new java.io.DataOutputStream(bytes),row);
            VssLodSpriteTable.close();VssLodSpriteTable.registerSprite(new Sprite(other,16));
            int restored=VssLodSpriteTable.readMaterial(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),name->sprite);
            assertNotEquals(row,restored);assertArrayEquals(uv,VssLodSpriteTable.modelUvs(restored));
            assertEquals(block,VssLodSpriteTable.materialBlocks()[restored]);assertFalse(VssLodSpriteTable.modelShade(restored));
            assertThrows(java.io.IOException.class,()->VssLodSpriteTable.readMaterial(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())),name->null));
        }
    }

    private static class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents, int x) {
            super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, x, 0);
        }
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual vanilla colour providers and textures, through the production mesh path. */
class PredictionTreeTintTest {
    private static final int BIOME_TINT = 0xFF71A74D;
    @TempDir Path directory;
    private Object previousTints;
    private java.util.List<BlockState> previousStateIds;
    private int previousNextId;

    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @BeforeEach @SuppressWarnings("unchecked") void prepare() throws Exception {
        previousTints = field(PredictionMaterialPalette.class, "atlasColors").get(null);
        // Bare Bootstrap does not run NeoForge's block-state id bake. Without
        // it, all states alias id 0 in the production sprite-state cache.
        previousStateIds = new java.util.ArrayList<>((java.util.List<BlockState>)
                field(net.minecraft.core.IdMapper.class, "idToT").get(Block.BLOCK_STATE_REGISTRY));
        previousNextId = field(net.minecraft.core.IdMapper.class, "nextId").getInt(Block.BLOCK_STATE_REGISTRY);
        for (Block block : BuiltInRegistries.BLOCK)
            for (BlockState state : block.getStateDefinition().getPossibleStates())
                if (Block.BLOCK_STATE_REGISTRY.getId(state) == -1) Block.BLOCK_STATE_REGISTRY.add(state);
        VssLodSpriteTable.close();
        field(VssLodSpriteTable.class, "runtimeReady").set(null, true);
    }

    @AfterEach @SuppressWarnings("unchecked") void restore() throws Exception {
        field(PredictionMaterialPalette.class, "atlasColors").set(null, previousTints);
        VssLodSpriteTable.close();
        var mapper = Block.BLOCK_STATE_REGISTRY;
        ((java.util.List<?>) field(net.minecraft.core.IdMapper.class, "idToT").get(mapper)).clear();
        ((Map<?, ?>) field(net.minecraft.core.IdMapper.class, "tToId").get(mapper)).clear();
        for (int i = 0; i < previousStateIds.size(); i++)
            if (previousStateIds.get(i) != null) mapper.addMapping(previousStateIds.get(i), i);
        field(net.minecraft.core.IdMapper.class, "nextId").setInt(mapper, previousNextId);
    }

    @Test void vanillaSignedLeafColoursAreValidTints() throws Exception {
        BlockColors registry = vanillaColors();
        int[] tints = PredictionMaterialPalette.collectAtlasColors(registry, null);
        assertTrue(FoliageColor.getEvergreenColor() < -1);
        assertTrue(FoliageColor.getBirchColor() < -1);
        assertEquals(0xFF619961, tints[id(Blocks.SPRUCE_LEAVES)]);
        assertEquals(0xFF80A755, tints[id(Blocks.BIRCH_LEAVES)]);
        assertEquals(FoliageColor.getDefaultColor(), tints[id(Blocks.OAK_LEAVES)]);
        for (Block untinted : new Block[]{Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES,
                Blocks.FLOWERING_AZALEA_LEAVES, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG}) {
            assertEquals(0, tints[id(untinted)], untinted.toString());
        }
    }

    @Test void unavailableProviderDoesNotEraseOtherTintsOrRejectBlack() {
        BlockColors registry = new BlockColors();
        registry.register((state, level, pos, tint) -> { throw new IllegalStateException("needs block entity"); }, Blocks.STONE);
        registry.register((state, level, pos, tint) -> FoliageColor.getEvergreenColor(), Blocks.SPRUCE_LEAVES);
        registry.register((state, level, pos, tint) -> 0, Blocks.OAK_LEAVES);
        registry.register((state, level, pos, tint) -> 0x00765432, Blocks.BIRCH_LEAVES);
        int[] tints = PredictionMaterialPalette.collectAtlasColors(registry, null);
        assertEquals(0, tints[id(Blocks.STONE)]);
        assertEquals(FoliageColor.getEvergreenColor(), tints[id(Blocks.SPRUCE_LEAVES)]);
        assertEquals(0xFF000000, tints[id(Blocks.OAK_LEAVES)]);
        assertEquals(0xFF765432, tints[id(Blocks.BIRCH_LEAVES)]);
        assertEquals(0, tints[id(Blocks.CHERRY_LEAVES)]);
    }

    @Test void realLeafTexturesUseSpeciesOrBiomeTintOnEveryFace() throws Exception {
        installVanillaTints();
        Block[] leaves = {Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.OAK_LEAVES,
                Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
                Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES,
                Blocks.FLOWERING_AZALEA_LEAVES};
        for (Block block : leaves) {
            int row = installTexture(block);
            int average = VssLodSpriteTable.averageColorArgb(id(block));
            int tint = block == Blocks.SPRUCE_LEAVES ? 0xFF619961 : block == Blocks.BIRCH_LEAVES ? 0xFF80A755
                    : block == Blocks.CHERRY_LEAVES || block == Blocks.AZALEA_LEAVES
                    || block == Blocks.FLOWERING_AZALEA_LEAVES ? 0xFFFFFFFF : BIOME_TINT;
            int expected = multiply(average, tint);
            for (int face = 0; face <= 4; face++) {
                assertEquals(row, VssLodSpriteTable.indexForState(block.defaultBlockState(), face));
                assertEquals(expected, PredictionMaterialPalette.colorForState(block.defaultBlockState(), -1, BIOME_TINT, face), block + " face=" + face);
                assertEquals(expected, PredictionMaterialPalette.colorForBlockId(id(block), -1, BIOME_TINT, face));
            }
            assertEquals(expected, PredictionMaterialPalette.colorFor(column(block), BIOME_TINT));
        }
        for (Block fixed : new Block[]{Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES}) {
            assertEquals(PredictionMaterialPalette.colorForState(fixed.defaultBlockState(), -1, BIOME_TINT),
                    PredictionMaterialPalette.colorForState(fixed.defaultBlockState(), -1, 0xFFFF00FF),
                    "a different biome cannot turn a fixed species tint into another colour");
        }
    }

    @Test void cachedTreesKeepTintInCoarseAndRefinedPackedMeshes() throws Exception {
        installVanillaTints();
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<Integer, Integer> expectedBySprite = new HashMap<>();
        Block[] blocks = {Blocks.SPRUCE_LEAVES, Blocks.BIRCH_LEAVES, Blocks.OAK_LEAVES, Blocks.CHERRY_LEAVES};
        int[] tints = {0xFF619961, 0xFF80A755, BIOME_TINT, 0xFFFFFFFF};
        for (int i = 0; i < blocks.length; i++) {
            int row = installTexture(blocks[i]);
            expectedBySprite.put(row, multiply(VssLodSpriteTable.averageColorArgb(id(blocks[i])), tints[i]));
            states.put(new BlockPos(i * 8, 72, 0), blocks[i].defaultBlockState());
        }
        var key = PredictionDiskCache.Key.surface(0, 0, 1);
        try (var cache = new PredictionDiskCache(directory, 123); var lease = cache.lease(key)) {
            assertTrue(cache.writeSurface(lease, states));
        }
        Map<BlockPos, BlockState> restored;
        try (var cache = new PredictionDiskCache(directory, 123); var lease = cache.lease(key)) {
            restored = cache.readSurface(lease);
            assertEquals(states, restored, "disk stores species states, not the old baked grey colours");
        }
        for (int spacing : new int[]{1, 2, 4, 8}) {
            int grid = 32 / spacing + 1;
            ClientColumnSample[] samples = new ClientColumnSample[grid * grid];
            Arrays.fill(samples, column(Blocks.STONE));
            int[] foliage = new int[samples.length];
            Arrays.fill(foliage, BIOME_TINT);
            var vegetation = PredictionVegetation.Tile.of(restored, 0, 0, 32, spacing, Math.max(1, spacing / 2));
            var mesh = PredictionMeshBuilder.build(samples, null, 63, 0, spacing, grid, true,
                    null, foliage, null, 0, 0, vegetation);
            var dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
            var tile = new PredictionTileManager.PredictionTile(
                    new PredictionTileManager.PredictionTileKey(dimension, 0, 0, 0),
                    new int[0], new int[0], new ClientColumnSample[0], mesh,
                    new PredictionDepthBound(64, 80), 0L, 1L, mesh.cellAxis(), spacing);
            int[] packed = PredictionPackedMesh.pack(tile).quads();
            Map<Integer, Integer> faces = new HashMap<>();
            for (int q = 0; q < packed.length; q += PredictionPackedMesh.STRIDE_INTS) {
                int sprite = packed[q + 6] & PredictionPackedMesh.FLAG_SPRITE_MASK;
                Integer expected = expectedBySprite.get(sprite);
                if (expected == null) continue;
                faces.merge(sprite, 1, Integer::sum);
                for (int corner : new int[]{7, 9, 10, 11})
                    assertEquals(expected & 0xFFFFFF, packed[q + corner] & 0xFFFFFF, "spacing=" + spacing + " sprite=" + sprite);
            }
            for (int sprite : expectedBySprite.keySet())
                assertEquals(5, faces.getOrDefault(sprite, 0), "top and four sides must keep tint at spacing=" + spacing);
        }
    }

    private static void installVanillaTints() throws Exception {
        field(PredictionMaterialPalette.class, "atlasColors").set(null,
                PredictionMaterialPalette.collectAtlasColors(vanillaColors(), null));
    }

    private static BlockColors vanillaColors() throws Exception {
        // NeoForge posts a colour-registration event after the vanilla
        // providers are installed. Supply an empty loaded mod list for it.
        var previous = net.neoforged.fml.ModList.get();
        try {
            var empty = net.neoforged.fml.ModList.of(java.util.List.of(), java.util.List.of());
            var loaded = net.neoforged.fml.ModList.class.getDeclaredMethod("setLoadedMods", java.util.List.class);
            loaded.setAccessible(true);
            loaded.invoke(empty, java.util.List.of());
            return BlockColors.createDefault();
        } finally {
            field(net.neoforged.fml.ModList.class, "INSTANCE").set(null, previous);
        }
    }

    /** Register raw vanilla pixels without requiring a running game's model renderer. */
    @SuppressWarnings("unchecked")
    private int installTexture(Block block) throws Exception {
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
        try (var input = getClass().getResourceAsStream("/assets/minecraft/textures/block/" + name + ".png")) {
            assertNotNull(input, name);
            try (var contents = new SpriteContents(ResourceLocation.withDefaultNamespace("block/" + name),
                    new FrameSize(16, 16), NativeImage.read(input), ResourceMetadata.EMPTY)) {
                int row = VssLodSpriteTable.registerSprite(new Sprite(contents));
                ((Map<Integer, Integer>) field(VssLodSpriteTable.class, "INDEX_BY_BLOCK").get(null)).put(id(block), row);
                var blockFaces = (Map<Long, Integer>) field(VssLodSpriteTable.class, "SIDE_INDEX_BY_FACE_BLOCK").get(null);
                var stateFaces = (Map<Long, Integer>) field(VssLodSpriteTable.class, "INDEX_BY_STATE_FACE").get(null);
                for (int face = 0; face <= 4; face++) {
                    blockFaces.put(((long) id(block) << 3) | face, row);
                    stateFaces.put(((long) Block.getId(block.defaultBlockState()) << 3) | face, row);
                }
                return row;
            }
        }
    }

    private static ClientColumnSample column(Block block) {
        return new ClientColumnSample(64, 64, 0, id(block), 0, 0, 0, 0, 0,
                ClientColumnSample.FLAG_SURFACE_ONLY, 0, ClientColumnSample.NO_BLOCK, ClientColumnSample.NO_BLOCK,
                ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN, ClientColumnSample.NO_SPAN);
    }

    private static int multiply(int pixel, int tint) {
        int r = (pixel >> 16 & 255) * (tint >> 16 & 255) / 255;
        int g = (pixel >> 8 & 255) * (tint >> 8 & 255) / 255;
        int b = (pixel & 255) * (tint & 255) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int id(Block block) { return BuiltInRegistries.BLOCK.getId(block); }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS, contents, 256, 256, 0, 0); }
    }
}

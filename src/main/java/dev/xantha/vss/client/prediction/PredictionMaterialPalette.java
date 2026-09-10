package dev.xantha.vss.client.prediction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stable client-side material table for predicted columns. the resolves a
 * baked block appearance before writing its quad buffer; VSS has no block
 * section at prediction time, so this table provides a deterministic
 * representative appearance without touching client chunk storage.
 */
public final class PredictionMaterialPalette {
    public static final int MATERIAL_OPAQUE = 0;
    public static final int MATERIAL_CUTOUT = 1;
    public static final int MATERIAL_TRANSLUCENT = 2;
    public static final int MATERIAL_EMISSIVE = 3;
    private static volatile int[] blockIds;
    private static volatile int[] atlasColors;
    private static volatile Object atlasLevel;

    private PredictionMaterialPalette() {
    }

    /**
     * Column colour with the composition: only blocks whose baked
     * sprite is biome-tinted by BlockColors receive the column's grass
     * colour; fixed tints (such as spruce/birch leaves) retain their registry
     * multiplier, and every untinted
     * material keeps its own texture-derived colour at full strength.
     * Per-face-layer tint resolution keeps this, so a mountain stays
     * grey next to a green meadow; multiplying the grass tint into every
     * material flattened whole regions into a single hue, which read as
     * "no detail".  The texture average comes from the atlas sprite table
     * (row 1, alpha-weighted); registry colours are tint multipliers only.
     */
    public static int colorFor(ClientColumnSample sample, int biomeFallback) {
        int block = surfaceBlock(sample);
        int blockColor = textureAverageColor(block);
        int tint = tintForBlock(block, biomeFallback);
        boolean tinted = tint != 0 && tint != 0xFFFFFFFF;
        if (tinted && blockColor != 0) {
            // Grey-base average × biome grass colour: the grey texture
            // average of e.g. the grass block top carries luminance detail,
            // the biome colour supplies hue — exactly the vanilla pipeline.
            return 0xFF000000 | multiply(blockColor, tint);
        }
        if (blockColor != 0) {
            return 0xFF000000 | blockColor;
        }
        if (tinted) {
            // No atlas average available yet: retain the correct tint family.
            return 0xFF000000 | tint;
        }
        int fallback = colorForIndex(block, biomeFallback);
        return fallback != 0 ? fallback
                : 0xFF000000 | (biomeFallback != 0 ? biomeFallback : 0x7FB238);
    }

    /** Non-white does not imply biome tint: spruce/birch have fixed leaf colours. */
    static int tintForBlock(int id, int biomeTint) {
        int registryTint = atlasColor(id);
        if (registryTint == 0 || registryTint == 0xFFFFFFFF || biomeTint == 0) return registryTint;
        Block block = BuiltInRegistries.BLOCK.byId(id);
        // Follow the biome providers in vanilla BlockColors. Other vanilla
        // providers include fixed leaf colours, lily pads and stem/redstone
        // colours; their valid ARGB multipliers must not become foliage green.
        boolean biome = block == Blocks.OAK_LEAVES || block == Blocks.JUNGLE_LEAVES
                || block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES
                || block == Blocks.MANGROVE_LEAVES || block == Blocks.VINE
                || block == Blocks.GRASS_BLOCK || block == Blocks.FERN
                || block == Blocks.SHORT_GRASS || block == Blocks.LARGE_FERN
                || block == Blocks.TALL_GRASS || block == Blocks.POTTED_FERN
                || block == Blocks.SUGAR_CANE;
        // Preserve the existing biome fallback for modded colour providers.
        if (!biome && block != null)
            biome = !BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft");
        return biome ? biomeTint : registryTint;
    }

    /** Alpha-weighted atlas average of the block's particle sprite, ARGB. */
    private static int textureAverageColor(int block) {
        if (block == ClientColumnSample.NO_BLOCK) {
            return 0;
        }
        // Resolve the same baked up-face sprite that the GPU detail path
        // samples before falling back to a hand-written palette.  Prediction
        // workers can finish before the first render-frame table rebuild, so
        // merely querying the average used to return zero and permanently
        // bake the guessed colour into the tile.
        int average = atlasAverage(block, 0);
        if (average != 0) {
            return average;
        }
        // BlockColors is a multiplier, never a substitute texture average.
        return 0;
    }

    /** Per-channel multiply of two ARGB colours (white preserves). */
    private static int multiply(int a, int b) {
        if (b == 0) {
            return a;
        }
        int r = ((a >> 16 & 0xFF) * (b >> 16 & 0xFF)) / 255;
        int g = ((a >> 8 & 0xFF) * (b >> 8 & 0xFF)) / 255;
        int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
        return r << 16 | g << 8 | bl;
    }

    static int waterColor(int biomeTint) {
        int average = VssLodSpriteTable.fluidAverageColorArgb(1);
        return (biomeTint & 0xFF000000) | multiply(average == 0 ? 0xFFFFFF : average, biomeTint);
    }

    /** Resolves a block-id colour without a column, used by wall strata.
     *  Prefers the atlas texture average, then the fallback palette. */
    public static int colorForIndex(int block, int fallback) {
        return colorForIndex(block, fallback, 0);
    }

    /** Resolves the average for a concrete face (0 = top). */
    public static int colorForIndex(int block, int fallback, int face) {
        int average = atlasAverage(block, face);
        if (average != 0) return average;
        int[] ids = ids();
        int color = block == ids[0] ? 0xFF7FB238
                : block == ids[1] ? 0xFF8A8A8A
                : block == ids[2] ? 0xFFE4D59B
                : block == ids[3] ? 0xFFB95D3B
                : block == ids[4] ? 0xFF6F4A32
                : block == ids[5] ? 0xFF5A3B26
                : block == ids[6] ? 0xFF3F7FBE
                : block == ids[7] ? 0xFFD9572B
                : block == ids[8] ? 0xFFB7D7EC
                : block == ids[9] ? 0xFF5E8A45 : 0;
        return color == 0 ? fallback : color;
    }

    private static int atlasAverage(int block, int face) {
        if (block == ClientColumnSample.NO_BLOCK || block < 0) {
            return 0;
        }
        int average = VssLodSpriteTable.averageColorArgb(block, face);
        if (average != 0) {
            return average;
        }
        try {
            if (face == 0) {
                VssLodSpriteTable.indexForBlock(block);
            } else if (face < 0) {
                VssLodSpriteTable.sideIndexForBlock(block);
            } else {
                VssLodSpriteTable.sideIndexForBlock(block, face);
            }
            return VssLodSpriteTable.averageColorArgb(block, face);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Resolves an actual configured-feature block state through the same
     *  atlas average used for terrain samples.  Biome-tinted feature blocks
     *  (leaves, plants) compose the texture average with the column's biome
     *  tint when one is supplied, falling back to the registry tint — the
     *  colour the detail shader normalises against. */
    public static int colorForState(net.minecraft.world.level.block.state.BlockState state,
                                    int fallback) {
        return colorForState(state, fallback, 0);
    }

    public static int colorForState(net.minecraft.world.level.block.state.BlockState state,
                                    int fallback, int biomeTint) {
        return colorForState(state, fallback, biomeTint, 0);
    }

    /** Face-aware feature colour used to normalise atlas sampling. */
    public static int colorForState(net.minecraft.world.level.block.state.BlockState state,
                                    int fallback, int biomeTint, int face) {
        if (state == null) return fallback;
        int id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock());
        int average = VssLodSpriteTable.averageForState(state, face);
        int tint = tintForBlock(id, biomeTint);
        return average == 0 ? colorForBlockId(id, fallback, biomeTint, face)
                : 0xFF000000 | (tint == 0 ? average : multiply(average, tint));
    }

    /** Block-id flavour of {@link #colorForState(BlockState, int, int)}. */
    public static int colorForBlockId(int id, int fallback, int biomeTint) {
        return colorForBlockId(id, fallback, biomeTint, 0);
    }

    /** Block-id flavour of the face-aware colour resolver. */
    public static int colorForBlockId(int id, int fallback, int biomeTint, int face) {
        if (id == ClientColumnSample.NO_BLOCK || id < 0) {
            return fallback;
        }
        int average = atlasAverage(id, face);
        int tint = tintForBlock(id, biomeTint);
        if (average != 0 && tint != 0) {
            return 0xFF000000 | multiply(average, tint);
        }
        if (average != 0) return 0xFF000000 | average;
        // A tint multiplier alone is not the block's visible surface. In
        // particular the default white tint must not turn missing textures
        // into white geometry before the atlas is ready.
        return fallback;
    }

    /**
     * Sprite-table row for a feature block state, for mesh workers packing
     * the detail-texture alpha byte.  Unresolvable states keep the flat
     * colour path (FLAT == the opaque alpha the flat path expects).
     */
    public static int spriteIndexForState(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return VssLodSpriteTable.FLAT;
        return VssLodSpriteTable.indexForBlock(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(state.getBlock()));
    }

    /** The grass-block id: tree columns texture their ground quad with it
     *  instead of the forest's leaf representative. */
    static int grassBlockIndex() {
        return ids()[0];
    }

    /**
     * Returns the material represented by the visible column top.  Tree hints
     * describe a feature that is rendered separately; they must never turn
     * the ground quad into a leaf-coloured surface.  the keeps the
     * sampled ground appearance and emits the tree as its own face group.
     */
    static int surfaceBlock(ClientColumnSample sample) {
        if (sample != null && sample.hasSurface() && sample.snow() && !sample.hasFluid()
                && supportsSnow(sample.topBlockIndex())) return ids()[8];
        return groundBlock(sample);
    }

    /** Snow covers the up face; the ground's side strata retain their own material. */
    static int groundBlock(ClientColumnSample sample) {
        if (sample == null) {
            return ClientColumnSample.NO_BLOCK;
        }
        int block = sample.topBlockIndex();
        if (block == ids()[9] && (sample.flags() & ClientColumnSample.FLAG_TREE_HERE) != 0
                && !sample.snow() && sample.fluid() == 0) {
            return grassBlockIndex();
        }
        return block;
    }

    static int wallUnderBlock(ClientColumnSample sample) {
        int under = sample.underBlockIndex();
        if (surfaceSoil(under)) return dirtIndex();
        if (solidWallMaterial(under)) return under;
        int top = groundBlock(sample);
        return surfaceSoil(top) || !solidWallMaterial(top) ? dirtIndex() : top;
    }

    static int wallDeepBlock(ClientColumnSample sample) {
        int deep = sample.deepBlockIndex();
        // A sample seven blocks below the top can hit a cave or a second
        // exposed grass surface. Neither describes the entire cliff below it.
        if (surfaceSoil(deep)) return stoneIndex();
        if (solidWallMaterial(deep)) return deep;
        int top = groundBlock(sample);
        return surfaceSoil(top) || !solidWallMaterial(top) ? stoneIndex() : wallUnderBlock(sample);
    }

    private static boolean surfaceSoil(int block) {
        if (block < 0 || block == ClientColumnSample.NO_BLOCK) return false;
        Block value = BuiltInRegistries.BLOCK.byId(block);
        return value == Blocks.GRASS_BLOCK || value == Blocks.MYCELIUM || value == Blocks.PODZOL
                || value == Blocks.DIRT_PATH;
    }

    private static boolean solidWallMaterial(int block) {
        if (block < 0 || block == ClientColumnSample.NO_BLOCK) return false;
        Block value = BuiltInRegistries.BLOCK.byId(block);
        if (value == null) return false;
        BlockState state = value.defaultBlockState();
        return !state.isAir() && state.getFluidState().isEmpty() && state.canOcclude();
    }

    static boolean supportsSnow(int blockId) {
        if (blockId < 0 || blockId == ClientColumnSample.NO_BLOCK) return false;
        Block block = BuiltInRegistries.BLOCK.byId(blockId);
        if (block == null) return false;
        BlockState state = block.defaultBlockState();
        if (state.is(net.minecraft.tags.BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)) return false;
        if (state.is(net.minecraft.tags.BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)) return true;
        return Block.isFaceFull(state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO), net.minecraft.core.Direction.UP);
    }

    public static int representativeBlock(String biomePath, boolean snow, boolean lava,
                                           boolean tree) {
        int[] ids = ids();
        if (lava) return ids[5];
        if (snow) return ids[8];
        String path = biomePath == null ? "" : biomePath;
        if (path.contains("desert") || path.contains("beach")) {
            return ids[2];
        }
        if (path.contains("badlands") || path.contains("mesa")) {
            return ids[3];
        }
        if (path.contains("mushroom") || path.contains("mycelium")) {
            return id("mycelium");
        }
        if (tree) return ids[9];
        return ids[0];
    }

    public static int logIndex() { return id("oak_log"); }

    public static int dirtIndex() { return ids()[4]; }
    public static int stoneIndex() { return ids()[1]; }

    public static int materialClass(ClientColumnSample sample) {
        if (sample == null) return MATERIAL_OPAQUE;
        if (sample.fluid() != 0) return MATERIAL_TRANSLUCENT;
        if ((sample.flags() & ClientColumnSample.FLAG_TREE_HERE) != 0) return MATERIAL_CUTOUT;
        return MATERIAL_OPAQUE;
    }

    /** Refreshes tint colors from the active client block-color registry. */
    public static void refreshAtlas(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level == null || atlasLevel == level && atlasColors != null) return;
        atlasColors = collectAtlasColors(net.minecraft.client.Minecraft.getInstance().getBlockColors(), level);
        atlasLevel = level;
    }

    /** Snapshot on the client thread; mesh workers never query client chunks. */
    static int[] collectAtlasColors(BlockColors blockColors, BlockAndTintGetter level) {
        int[] colors = new int[BuiltInRegistries.BLOCK.size()];
        int failures = 0;
        for (int blockId = 0; blockId < colors.length; blockId++) {
            Block block = BuiltInRegistries.BLOCK.byId(blockId);
            if (block == null) continue;
            try {
                int color = blockColors.getColor(block.defaultBlockState(), level, BlockPos.ZERO, 0);
                // BlockColors uses exactly -1 for no tint. Vanilla fixed leaf
                // colours are signed ARGB ints (e.g. spruce = 0xFF619961).
                if (color != -1) colors[blockId] = 0xFF000000 | color;
            } catch (RuntimeException failure) {
                // A provider needing a live block entity must not discard all
                // already resolved vanilla tints. Summarise once per snapshot.
                failures++;
            }
        }
        if (failures != 0 && dev.xantha.vss.config.VSSClientConfig.CONFIG.debugLogging)
            dev.xantha.vss.common.VSSLogger.debug("VSS prediction tint snapshot: skipped " + failures + " unavailable block colour providers");
        return colors;
    }

    private static int atlasColor(int block) {
        int[] colors = atlasColors;
        if (colors == null) return 0;
        return block >= 0 && block < colors.length ? colors[block] : 0;
    }

    private static int id(String path) {
        try {
            net.minecraft.world.level.block.Block block =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            ResourceLocation.withDefaultNamespace(path));
            return block == null ? Integer.MIN_VALUE :
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(block);
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Representative terrain blocks used to seed the sprite table before
     * any tile mesh builds, so the coarse (averaged) colour path resolves
     * real texture averages instead of registry fallbacks.  The vegetation
     * entries let feature geometry (tree stamps, canopy boxes, ground
     * plants) pack real sprite indices on worker threads without waiting
     * for a first registration.
     */
    static int[] seedBlockIds() {
        int[] base = ids();
        int[] vegetation = {id("oak_log"), id("spruce_log"), id("birch_log"),
                id("jungle_log"), id("spruce_leaves"), id("birch_leaves"),
                id("jungle_leaves"), id("short_grass"), id("grass"), id("fern"),
                id("dandelion"), id("poppy")};
        int[] all = new int[base.length + vegetation.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(vegetation, 0, all, base.length, vegetation.length);
        return all;
    }

    /** First resolvable id from a candidate path list, or NO_BLOCK. */
    static int firstId(String... paths) {
        for (String path : paths) {
            int candidate = id(path);
            if (candidate != Integer.MIN_VALUE) {
                return candidate;
            }
        }
        return ClientColumnSample.NO_BLOCK;
    }

    /** Ground plant block for procedural (stamp-free) vegetation crosses. */
    static int groundPlantIndex() {
        return firstId("short_grass", "grass", "fern");
    }

    /** Yellow flower block for procedural ground feature variety. */
    static int flowerIndex() {
        return firstId("dandelion", "poppy");
    }

    /** Log block matching a tree species kind, for procedural trunks. */
    static int logIndexForKind(int kind) {
        return switch (kind) {
            case 2 -> firstId("spruce_log");
            case 3 -> firstId("birch_log");
            case 4 -> firstId("jungle_log");
            default -> firstId("oak_log");
        };
    }

    /** Leaves block matching a tree species kind, for procedural canopies. */
    static int leavesIndexForKind(int kind) {
        return switch (kind) {
            case 2 -> firstId("spruce_leaves");
            case 3 -> firstId("birch_leaves");
            case 4 -> firstId("jungle_leaves");
            default -> firstId("oak_leaves");
        };
    }

    static int[] ids() {
        int[] result = blockIds;
        if (result != null) return result;
        synchronized (PredictionMaterialPalette.class) {
            result = blockIds;
            if (result == null) {
                result = new int[]{id("grass_block"), id("stone"), id("sand"),
                        id("red_terracotta"), id("dirt"), id("netherrack"),
                        id("water"), id("lava"), id("snow_block"), id("oak_leaves")};
                blockIds = result;
            }
        }
        return result;
    }
}

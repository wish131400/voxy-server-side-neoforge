package dev.xantha.vss.client.prediction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
/** Registry material rules shared by sampling and Voxy voxel publication. */
public final class PredictionMaterialPalette {
    private PredictionMaterialPalette() { }
    private static volatile int[] blockIds;
    static int grassBlockIndex() {
        return ids()[0];
    }

    static int surfaceBlock(ClientColumnSample sample) {
        if (sample != null && sample.hasSurface() && sample.snow() && !sample.hasFluid()
                && supportsSnow(sample.topBlockIndex())) return ids()[8];
        return groundBlock(sample);
    }

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

    static BlockState groundSideState(ClientColumnSample sample) {
        int block = groundBlock(sample);
        if (block < 0 || block == ClientColumnSample.NO_BLOCK) return null;
        BlockState state = BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
        var snowy = net.minecraft.world.level.block.SnowyDirtBlock.SNOWY;
        if (sample.hasSurface() && sample.snow() && !sample.hasFluid() && state.hasProperty(snowy))
            state = state.setValue(snowy, true);
        return state;
    }

    static int wallUnderBlock(ClientColumnSample sample) {
        int under = sample.underBlockIndex();
        if (surfaceSoil(under)) return dirtIndex();
        if (solidWallMaterial(under)) return under;
        int top = groundBlock(sample);
        // Known translucent tops (ice, glass) keep their own material on the
        // wall instead of inventing a dirt stratum that was never sampled.
        // A wholly unknown top keeps the classic synthetic strata bands.
        if (nonAirWallTop(top) && !surfaceSoil(top)) return top;
        return dirtIndex();
    }

    static int wallDeepBlock(ClientColumnSample sample) {
        int deep = sample.deepBlockIndex();
        // A sample seven blocks below the top can hit a cave or a second
        // exposed grass surface. Neither describes the entire cliff below it.
        if (surfaceSoil(deep)) return stoneIndex();
        if (solidWallMaterial(deep)) return deep;
        int top = groundBlock(sample);
        if (nonAirWallTop(top) && !surfaceSoil(top)) return wallUnderBlock(sample);
        return stoneIndex();
    }

    private static boolean nonAirWallTop(int block) {
        if (block < 0 || block == ClientColumnSample.NO_BLOCK) return false;
        var state = BuiltInRegistries.BLOCK.byId(block).defaultBlockState();
        return !state.isAir() && state.getFluidState().isEmpty();
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

    static int firstId(String... paths) {
        for (String path : paths) {
            int candidate = id(path);
            if (candidate != Integer.MIN_VALUE) {
                return candidate;
            }
        }
        return ClientColumnSample.NO_BLOCK;
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

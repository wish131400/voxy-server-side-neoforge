package dev.xantha.vss.client.prediction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable state classification, replaced when registry tags change. */
final class PredictionVegetationTraits {
    record Traits(boolean woody, boolean vegetation, boolean solid, boolean renderable) { }
    private static volatile Map<BlockState, Traits> cache = new ConcurrentHashMap<>();
    private static volatile long generation;

    static Traits of(BlockState state) {
        var states = cache;
        var traits = states.get(state);
        return traits != null ? traits : states.computeIfAbsent(state, PredictionVegetationTraits::classify);
    }
    static long generation() { return generation; }
    static synchronized void invalidate() { cache = new ConcurrentHashMap<>(); generation++; }

    private static Traits classify(BlockState state) {
        boolean woody = state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK) || state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM)
                || state.is(Blocks.NETHER_WART_BLOCK) || state.is(Blocks.WARPED_WART_BLOCK);
        boolean vegetation = woody || state.getBlock() instanceof net.minecraft.world.level.block.GrowingPlantBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                && !state.is(Blocks.LILY_PAD) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH) || state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.BAMBOO)
                || state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS);
        boolean solid = !PredictionVegetation.fire(state) && (woody || !vegetation || state.is(Blocks.CACTUS));
        boolean renderable = !state.isAir() && !state.is(Blocks.BARRIER) && !state.is(Blocks.STRUCTURE_BLOCK)
                && !state.is(Blocks.STRUCTURE_VOID) && !state.is(Blocks.JIGSAW)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
        return new Traits(woody, vegetation, solid, renderable);
    }

    private PredictionVegetationTraits() { }
}

package dev.xantha.vss.client.prediction.feature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

/** Runs a configured feature in the isolated VSS stamp level. */
public final class FeatureSimulator {
    public static final BlockPos ORIGIN = new BlockPos(0, FeatureStampLevel.GROUND_Y, 0);
    public static final List<BlockState> GROUNDS = List.of(
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SAND.defaultBlockState(),
            Blocks.RED_SAND.defaultBlockState(), Blocks.CRIMSON_NYLIUM.defaultBlockState(),
            Blocks.WARPED_NYLIUM.defaultBlockState(), Blocks.MYCELIUM.defaultBlockState(),
            Blocks.PODZOL.defaultBlockState(), Blocks.MUD.defaultBlockState(),
            Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.END_STONE.defaultBlockState(),
            Blocks.NETHERRACK.defaultBlockState(), Blocks.SOUL_SOIL.defaultBlockState(),
            Blocks.STONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState());
    private static volatile List<BlockState> solidPool;

    private FeatureSimulator() { }

    public static Map<BlockPos, BlockState> simulate(ConfiguredFeature<?, ?> feature,
                                                      RegistryAccess access,
                                                      ChunkGenerator generator,
                                                      BlockState ground,
                                                      long seed) {
        FeatureStampLevel level = new FeatureStampLevel(seed, access, ground);
        boolean placed = feature.place(level, generator, RandomSource.create(seed), ORIGIN);
        return placed ? Map.copyOf(level.placed()) : Map.of();
    }

    /** Tries the feature's ground predicates and a bounded solid-state pool. */
    public static Optional<Result> simulateOnAnyGround(ConfiguredFeature<?, ?> feature,
                                                        RegistryAccess access,
                                                        ChunkGenerator generator,
                                                        long seed) {
        BlockState first = GROUNDS.get(0);
        FeatureStampLevel probe = new FeatureStampLevel(seed, access, first);
        if (feature.place(probe, generator, RandomSource.create(seed), ORIGIN)
                && placedAboveGround(probe.placed())) {
            return Optional.of(new Result(first, Map.copyOf(probe.placed())));
        }
        LinkedHashSet<BlockState> candidates = new LinkedHashSet<>();
        Set<Predicate<BlockState>> predicates = probe.groundPredicates();
        if (!predicates.isEmpty()) {
            for (BlockState state : solidPool()) {
                if (predicates.stream().allMatch(predicate -> predicate.test(state))) candidates.add(state);
            }
        }
        candidates.addAll(GROUNDS);
        int attempts = 0;
        for (BlockState ground : candidates) {
            if (ground.equals(first) || !isSolid(ground) || attempts++ >= 32) continue;
            Map<BlockPos, BlockState> placed;
            try {
                placed = simulate(feature, access, generator, ground, seed);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (placedAboveGround(placed)) return Optional.of(new Result(ground, placed));
        }
        return Optional.empty();
    }

    private static boolean placedAboveGround(Map<BlockPos, BlockState> placed) {
        return placed.keySet().stream().anyMatch(pos -> pos.getY() >= FeatureStampLevel.GROUND_Y);
    }

    private static List<BlockState> solidPool() {
        List<BlockState> result = solidPool;
        if (result != null) return result;
        ArrayList<BlockState> states = new ArrayList<>(GROUNDS);
        BuiltInRegistries.BLOCK.stream().map(Block::defaultBlockState)
                .filter(FeatureSimulator::isSolid)
                .filter(state -> !GROUNDS.contains(state))
                .forEach(states::add);
        solidPool = result = List.copyOf(states);
        return result;
    }

    public static boolean isSolid(BlockState state) {
        return state != null && !state.isAir()
                && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    public record Result(BlockState ground, Map<BlockPos, BlockState> placed) { }
}

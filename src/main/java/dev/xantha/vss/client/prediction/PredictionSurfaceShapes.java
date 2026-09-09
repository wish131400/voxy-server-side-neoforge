package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Vanilla state shapes for surface blocks, excluding underside faces in the mesher. */
final class PredictionSurfaceShapes {
    private static final AABB UNIT = new AABB(0, 0, 0, 1, 1, 1);
    private static final Map<BlockState, List<AABB>> SHAPES = new java.util.concurrent.ConcurrentHashMap<>();

    private PredictionSurfaceShapes() { }

    static List<AABB> boxes(BlockState state, int size) {
        if (size > 1) return List.of(new AABB(0, 0, 0, size, size, size));
        return SHAPES.computeIfAbsent(state, value -> {
            if (PredictionVegetation.woody(value)) return List.of(UNIT);
            try {
                var boxes = value.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
                return boxes.size() > 16 ? List.of(UNIT) : List.copyOf(boxes);
            } catch (RuntimeException unsupported) {
                return List.of(UNIT);
            }
        });
    }

    static boolean occludes(BlockState state) {
        var shape = boxes(state, 1);
        return shape.size() == 1 && shape.getFirst().equals(UNIT);
    }
}

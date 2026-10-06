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
    private record Shape(List<AABB> boxes, boolean occludes) {
        Shape(List<AABB> boxes) { this(boxes, boxes.size() == 1 && boxes.get(0).equals(UNIT)); }
    }
    private static volatile Map<BlockState, Shape> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private PredictionSurfaceShapes() { }

    static List<AABB> boxes(BlockState state, int size) {
        if (size > 1) return List.of(new AABB(0, 0, 0, size, size, size));
        return shape(state).boxes();
    }

    private static Shape shape(BlockState state) {
        var current = cache;
        var existing = current.get(state);
        if (existing != null) return existing;
        return current.computeIfAbsent(state, value -> new Shape(readBoxes(value)));
    }

    private static List<AABB> readBoxes(BlockState state) {
        if (PredictionVegetation.woody(state)) return List.of(UNIT);
        try {
            var boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
            return boxes.size() > 16 ? List.of(UNIT) : List.copyOf(boxes);
        } catch (RuntimeException unsupported) {
            return List.of(UNIT);
        }
    }

    static boolean occludes(BlockState state) { return shape(state).occludes(); }

    static void invalidate() { cache = new java.util.concurrent.ConcurrentHashMap<>(); }
}

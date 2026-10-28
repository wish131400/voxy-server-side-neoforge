package dev.xantha.vss.client.prediction;

import java.util.IdentityHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Block-light estimate for a padded prediction volume in dimensions without skylight. */
final class PredictionVoxyLighting {
    static final int REACH = 15;
    final byte[] opacity;
    private final int width, step;
    private final IdentityHashMap<BlockState, Byte> materials = new IdentityHashMap<>();

    PredictionVoxyLighting(int width, int step) {
        this.width = width; this.step = step;
        opacity = new byte[width * width * width];
    }

    byte opacity(BlockState state) {
        return materials.computeIfAbsent(state, s -> (byte) Math.min(REACH,
                Math.max(1, s.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) * step));
    }

    void propagate(long[] data) {
        boolean emitting = false;
        for (long voxel : data) if ((voxel >>> 60) > 1) { emitting = true; break; }
        if (!emitting) return;
        var queue = new Queue(data.length);
        for (int i = 0; i < data.length; i++) if ((data[i] >>> 60) > 1) queue.add(i);
        int area = width * width, visits = 0;
        while (queue.size > 0) {
            if ((visits++ & 4095) == 0 && Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            int i = queue.remove(), x = i % width, z = i / width % width, y = i / area;
            int light = (int) (data[i] >>> 60);
            if (x > 0) spread(data, queue, i - 1, light);
            if (x + 1 < width) spread(data, queue, i + 1, light);
            if (z > 0) spread(data, queue, i - width, light);
            if (z + 1 < width) spread(data, queue, i + width, light);
            if (y > 0) spread(data, queue, i - area, light);
            if (y + 1 < width) spread(data, queue, i + area, light);
        }
    }

    private void spread(long[] data, Queue queue, int index, int light) {
        // Air also attenuates by world distance, so a coarse voxel is not a one-block step.
        int next = light - Math.max(step, opacity[index]);
        if (next <= (int) (data[index] >>> 60)) return;
        data[index] = (data[index] & ~(15L << 60)) | (long) next << 60;
        if (next > 1) queue.add(index);
    }

    /** A cell can occur only once in the queue, including when a brighter source reaches it. */
    private static final class Queue {
        final int[] indices;
        final boolean[] queued;
        int head, tail, size;
        Queue(int cells) { indices = new int[cells]; queued = new boolean[cells]; }
        void add(int index) {
            if (queued[index]) return;
            queued[index] = true; indices[tail] = index;
            if (++tail == indices.length) tail = 0;
            size++;
        }
        int remove() {
            int index = indices[head];
            if (++head == indices.length) head = 0;
            size--; queued[index] = false; return index;
        }
    }
}

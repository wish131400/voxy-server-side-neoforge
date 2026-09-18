package dev.xantha.vss.client.prediction;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Per-thread "last (coordinate, value)" memo for a decoded density tree,
 * mirroring the Rust core's {@code Scratch::memo} (density.rs:941).
 *
 * <p>The Rust preview and column searches run in {@code Mode::Raw}. In that
 * mode every one of the five vanilla cache markers falls through to a plain
 * recursive evaluation (density.rs:764-884: {@code Once}/{@code Cell} require
 * Slice|Cell|Block, {@code Cache2d}/{@code Flat} require {@code mode != Raw},
 * {@code Interpolated} requires Block|Cell), so {@code memo} is the only cache
 * the native path actually uses. This class gives the Java path the same one.
 *
 * <p>{@link DensityFunction#compute} is a pure function of the block
 * coordinate, so a coordinate match is always a valid hit and no invalidation
 * is needed. The Rust core relies on the same property and only clears its
 * memo when external state changes (density.rs:982 {@code set_beard}).
 *
 * <p>Both router roots are wrapped through <em>one</em> shared identity map so
 * subtrees common to {@code initial_density_without_jaggedness} and
 * {@code final_density} share a single slot. Minecraft's density graph is a
 * DAG, so a shared subexpression is reached again from a sibling parent and a
 * single-slot memo catches it.
 */
final class DensityMemo {
    private final ThreadLocal<Slots> slots;
    private final int capacity;

    private DensityMemo(int capacity) {
        this.capacity = capacity;
        this.slots = ThreadLocal.withInitial(() -> new Slots(capacity));
    }

    /**
     * Wraps both router roots so they share node ids and memo slots. Returns
     * the wrapped roots in the same order. A null root is passed through.
     */
    static DensityFunction[] wrapRoots(DensityFunction... roots) {
        // Diagnostic hook: "off" restores the unwrapped router so a single run
        // can compare both paths on identical coordinates.
        if ("off".equals(System.getProperty("vss.densityMemo"))) {
            return roots;
        }
        try {
            return wrapTransformableRoots(roots);
        } catch (UnsupportedOperationException | ClassCastException unsupported) {
            // Compiled graphs can reject replacing their runtime cache nodes
            // (C2ME's IFastCacheLike, for example). Older DFC versions reject
            // the visitor explicitly; newer generated constructors CHECKCAST
            // the replacement and throw ClassCastException. This memo is optional:
            // preserve every original root, including those already visited,
            // so a failed optimization cannot disable the worldgen context.
            dev.xantha.vss.common.VSSLogger.debug("VSS density memo skipped: graph rejects transformation ("
                    + unsupported.getMessage() + "); retaining original density functions");
            return roots;
        }
    }

    private static DensityFunction[] wrapTransformableRoots(DensityFunction[] roots) {
        // mapAll rebuilds every node (see DensityFunctions.Marker.mapAll), so
        // identity is useless here: the same subexpression reaches the visitor
        // as a fresh object each time. Vanilla's NoiseChunk.wrap solves this
        // with a structural-equality map, and so do we. Pass one therefore
        // counts the key space pass two will actually use.
        Map<DensityFunction, Boolean> seen = new HashMap<>();
        for (DensityFunction root : roots) {
            if (root == null) continue;
            root.mapAll(function -> {
                if (!(function instanceof NonMemoizable)) {
                    seen.putIfAbsent(function, Boolean.TRUE);
                }
                return function;
            });
        }
        DensityMemo memo = new DensityMemo(seen.size());
        // Pass two keeps one wrapper per structurally equal node, so a shared
        // subexpression keeps a single memo slot instead of being expanded.
        Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();
        DensityFunction[] result = new DensityFunction[roots.length];
        for (int i = 0; i < roots.length; i++) {
            DensityFunction root = roots[i];
            result[i] = root == null ? null : root.mapAll(function -> memo.wrap(function, wrapped));
        }
        return result;
    }

    private DensityFunction wrap(DensityFunction function,
                                 Map<DensityFunction, DensityFunction> wrapped) {
        if (function instanceof Memoized || function instanceof NonMemoizable) {
            return function;
        }
        return wrapped.computeIfAbsent(function,
                node -> new Memoized(node, wrapped.size(), this));
    }

    /**
     * Implemented by runtime density nodes whose value depends on thread-local
     * state rather than only on the block coordinate. Memoising such a node
     * would pin whatever state it was first sampled under.
     */
    interface NonMemoizable {
    }

    int capacity() {
        return capacity;
    }

    /** Per-thread slot arrays; one entry per node id. */
    private static final class Slots {
        private final long[] coords;
        private final double[] values;

        Slots(int capacity) {
            this.coords = new long[Math.max(1, capacity)];
            this.values = new double[Math.max(1, capacity)];
            java.util.Arrays.fill(this.coords, EMPTY);
        }
    }

    /** Coordinate key that can never collide with a real sample. */
    private static final long EMPTY = Long.MIN_VALUE;

    /**
     * Packs a block coordinate into one long. x and z get 26 signed bits
     * (|value| < 33.5M, beyond the ±30M world border) and y gets 12 bits
     * (-2048..2047, beyond any dimension height).
     */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFF);
    }

    /**
     * A node that answers from the per-thread memo when the coordinate repeats.
     * Implements {@link DensityFunction.SimpleFunction} so {@code mapAll}
     * terminates at this node instead of recursing into the delegate again.
     */
    private static final class Memoized implements DensityFunction.SimpleFunction {
        private final DensityFunction delegate;
        private final int id;
        private final DensityMemo owner;

        Memoized(DensityFunction delegate, int id, DensityMemo owner) {
            this.delegate = delegate;
            this.id = id;
            this.owner = owner;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            Slots slots = owner.slots.get();
            long key = pack(context.blockX(), context.blockY(), context.blockZ());
            if (slots.coords[id] == key) {
                return slots.values[id];
            }
            double value = delegate.compute(context);
            slots.coords[id] = key;
            slots.values[id] = value;
            return value;
        }

        @Override
        public double minValue() {
            return delegate.minValue();
        }

        @Override
        public double maxValue() {
            return delegate.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Runtime density memo wrapper");
        }

        @Override
        public String toString() {
            return "memo(" + delegate + ")";
        }
    }
}

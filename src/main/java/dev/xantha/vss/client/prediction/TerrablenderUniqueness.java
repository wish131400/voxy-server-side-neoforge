package dev.xantha.vss.client.prediction;

import java.util.List;

/**
 * Deterministic replay of TerraBlender's positional region uniqueness noise.
 *
 * <p>TerraBlender 4.x resolves which region a biome coordinate belongs to
 * through a zoom-layer stack ({@code LayeredNoiseUtil.createZoomedArea}): an
 * initial weighted-random pick over the registered regions, one fuzzy zoom,
 * three normal zooms, and {@code regionSize} further normal zooms. Every draw
 * runs through the old vanilla biome-layer RNG ({@code LinearCongruentialGenerator}
 * plus {@code AreaContext.initRandom}). The layer constants, draw order and
 * mode selection below are transcribed from TerraBlender 4.1.0.8 so the
 * prediction resolves the same region the live server does.</p>
 */
final class TerrablenderUniqueness {
    /** One entry of the uniqueness layer: region registration index and weight. */
    record Region(int index, int weight) {
    }

    /** Region grid: {@code get(x, z)} maps quart biome coordinates to a region index. */
    interface Tree {
        int get(int x, int z);
    }

    private TerrablenderUniqueness() {
    }

    /**
     * Mirrors {@code LayeredNoiseUtil.createZoomedArea}: initial layer with
     * salt 1, one fuzzy zoom with salt 2000, three normal zooms from salt
     * 2001, and {@code regionSize} normal zooms from salt 1001.
     */
    static Tree build(long worldSeed, int regionSize, List<Region> regions) {
        Tree tree = new Initial(worldSeed, 1L, List.copyOf(regions));
        tree = new Zoom(tree, worldSeed, 2000L, true);
        for (int i = 0; i < 3; i++) {
            tree = new Zoom(tree, worldSeed, 2001L + i, false);
        }
        for (int i = 0; i < regionSize; i++) {
            tree = new Zoom(tree, worldSeed, 1001L + i, false);
        }
        return tree;
    }

    /** Vanilla {@code net.minecraft.util.LinearCongruentialGenerator}. */
    private static long lcg(long seed, long operand) {
        seed = seed * (seed * 6364136223846793005L + 1442695040888963407L);
        return seed + operand;
    }

    /** TerraBlender {@code AreaContext.mixSeed}. */
    private static long mixSeed(long seed, long salt) {
        long l = lcg(salt, salt);
        l = lcg(l, salt);
        l = lcg(l, salt);
        long mixed = lcg(seed, l);
        mixed = lcg(mixed, l);
        return lcg(mixed, l);
    }

    /**
     * Per-pixel draw sequence. TerraBlender keeps one mutable context per
     * layer; a fresh cursor per pixel reproduces the same draws without
     * sharing state across sampling threads.
     */
    private static final class Cursor {
        private final long seed;
        private long rval;

        Cursor(long mixedSeed, long x, long z) {
            this.seed = mixedSeed;
            long l = mixedSeed;
            l = lcg(l, x);
            l = lcg(l, z);
            l = lcg(l, x);
            l = lcg(l, z);
            this.rval = l;
        }

        int nextRandom(int bound) {
            int value = Math.floorMod(this.rval >> 24, bound);
            this.rval = lcg(this.rval, this.seed);
            return value;
        }

        int random(int first, int second) {
            return nextRandom(2) == 0 ? first : second;
        }

        int random(int first, int second, int third, int fourth) {
            return switch (nextRandom(4)) {
                case 0 -> first;
                case 1 -> second;
                case 2 -> third;
                default -> fourth;
            };
        }
    }

    /** Initial weighted pick over the registered regions. */
    private static final class Initial implements Tree {
        private final long seed;
        private final List<Region> regions;
        private final int totalWeight;

        Initial(long worldSeed, long salt, List<Region> regions) {
            this.seed = mixSeed(worldSeed, salt);
            this.regions = regions;
            int total = 0;
            for (Region region : regions) total += region.weight();
            this.totalWeight = total;
        }

        @Override
        public int get(int x, int z) {
            if (totalWeight == 0) {
                return 0;
            }
            Cursor cursor = new Cursor(seed, x, z);
            int pick = cursor.nextRandom(totalWeight);
            for (Region region : regions) {
                pick -= region.weight();
                if (pick < 0) {
                    return region.index();
                }
            }
            return 0;
        }
    }

    /** {@code ZoomLayer.NORMAL} / {@code ZoomLayer.FUZZY}. */
    private static final class Zoom implements Tree {
        private final Tree parent;
        private final long seed;
        private final boolean fuzzy;

        Zoom(Tree parent, long worldSeed, long salt, boolean fuzzy) {
            this.parent = parent;
            this.seed = mixSeed(worldSeed, salt);
            this.fuzzy = fuzzy;
        }

        @Override
        public int get(int x, int z) {
            int first = parent.get(x >> 1, z >> 1);
            // ZoomLayer.apply seeds the quadrant corners, not the pixel itself.
            Cursor cursor = new Cursor(seed, x & -2, z & -2);
            int halfX = x & 1;
            int halfZ = z & 1;
            if (halfX == 0 && halfZ == 0) {
                return first;
            }
            int second = parent.get(x >> 1, (z + 1) >> 1);
            int pick = cursor.random(first, second);
            if (halfX == 0) {
                return pick;
            }
            int third = parent.get((x + 1) >> 1, z >> 1);
            pick = cursor.random(first, third);
            if (halfZ == 0) {
                return pick;
            }
            int fourth = parent.get((x + 1) >> 1, (z + 1) >> 1);
            return fuzzy ? cursor.random(first, third, second, fourth)
                    : modeOrRandom(cursor, first, third, second, fourth);
        }

        /** {@code ZoomLayer.modeOrRandom}; arguments follow the call site order. */
        private static int modeOrRandom(Cursor cursor, int a, int b, int c, int d) {
            if (b == c && c == d) return b;
            if (a == b && b == c) return a;
            if (a == b && b == d) return a;
            if (a == c && c == d) return a;
            if (a == b && c != d) return a;
            if (a == c && b != d) return a;
            if (a == d && b != c) return a;
            if (b == c && a != d) return b;
            if (b == d && a != c) return b;
            if (c == d && a != b) return c;
            return cursor.random(a, b, c, d);
        }
    }
}

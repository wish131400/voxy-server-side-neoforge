package dev.xantha.vss.common;

/**
 * Generates chunk offsets in Chebyshev-ring order around the origin.
 *
 * <p>The low 32 bits store signed 16-bit dx/dz values. The high 32 bits store
 * squared Euclidean distance, preserving the legacy packed representation used
 * by {@code LodRequestManager}.
 */
public final class ChebyshevRingOffsets {
    private ChebyshevRingOffsets() {
    }

    public static int count(int distance) {
        if (distance < 0) {
            throw new IllegalArgumentException("distance must be non-negative");
        }

        long side = (long) distance * 2L + 1L;
        long count = side * side;
        if (count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("distance is too large: " + distance);
        }
        return (int) count;
    }

    public static long[] generate(int distance) {
        long[] offsets = new long[count(distance)];
        int index = 0;
        offsets[index++] = encode(0, 0);
        for (int ring = 1; ring <= distance; ring++) {
            index = appendRing(offsets, index, ring);
        }
        return offsets;
    }

    private static int appendRing(long[] offsets, int index, int ring) {
        for (int dx = -ring; dx <= ring; dx++) {
            offsets[index++] = encode(dx, -ring);
        }
        for (int dz = -ring + 1; dz <= ring; dz++) {
            offsets[index++] = encode(ring, dz);
        }
        for (int dx = ring - 1; dx >= -ring; dx--) {
            offsets[index++] = encode(dx, ring);
        }
        for (int dz = ring - 1; dz >= -ring + 1; dz--) {
            offsets[index++] = encode(-ring, dz);
        }
        return index;
    }

    public static long offsetAt(int index) {
        int ring = ringForIndex(index);
        if (ring == 0) {
            return encode(0, 0);
        }

        int position = index - firstIndexForRing(ring);
        int topLength = ring * 2 + 1;
        if (position < topLength) {
            return encode(-ring + position, -ring);
        }

        position -= topLength;
        int sideLength = ring * 2;
        if (position < sideLength) {
            return encode(ring, -ring + 1 + position);
        }

        position -= sideLength;
        if (position < sideLength) {
            return encode(ring - 1 - position, ring);
        }

        position -= sideLength;
        return encode(-ring, ring - 1 - position);
    }

    public static int ringForIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        return (int) Math.ceil((Math.sqrt(index + 1.0D) - 1.0D) / 2.0D);
    }

    public static long encode(int dx, int dz) {
        long distanceSquared = (long) dx * dx + (long) dz * dz;
        return distanceSquared << 32
                | (long) (dx & 0xFFFF) << 16
                | (long) (dz & 0xFFFF);
    }

    public static int decodeX(long offset) {
        return (short) ((offset >>> 16) & 0xFFFF);
    }

    public static int decodeZ(long offset) {
        return (short) (offset & 0xFFFF);
    }

    public static int ring(long offset) {
        return Math.max(Math.abs(decodeX(offset)), Math.abs(decodeZ(offset)));
    }

    public static int firstIndexForRing(int ring) {
        if (ring < 0) {
            throw new IllegalArgumentException("ring must be non-negative");
        }
        if (ring == 0) {
            return 0;
        }
        long side = 2L * ring - 1L;
        long index = side * side;
        if (index > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ring is too large: " + ring);
        }
        return (int) index;
    }
}

package dev.xantha.vss.client.prediction;

/** Optional scoped focus used when a player is looking through a telescope. */
public record VssLodFocus(double x, double z, double radius, double pixelsPerBlock) {
    public VssLodFocus(double x, double z, double radius) {
        this(x, z, radius, 0.0D);
    }

    public VssLodFocus {
        if (!Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(radius) || radius < 0.0D
                || !Double.isFinite(pixelsPerBlock) || pixelsPerBlock < 0.0D) {
            throw new IllegalArgumentException("invalid LOD focus");
        }
    }

    public boolean contains(double tileCenterX, double tileCenterZ) {
        double dx = tileCenterX - x;
        double dz = tileCenterZ - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    public boolean intersects(double minX, double minZ, double maxX, double maxZ) {
        return contains(Math.max(minX, Math.min(maxX, x)), Math.max(minZ, Math.min(maxZ, z)));
    }

    double selectionScale(double baseScale) {
        return pixelsPerBlock > 0.0D ? Math.max(baseScale, pixelsPerBlock) : baseScale * 8.0D;
    }
}

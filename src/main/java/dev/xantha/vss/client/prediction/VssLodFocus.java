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

    static VssLodFocus stabilize(VssLodFocus previous, VssLodFocus current) {
        if (previous == null || current == null) return current;
        // Less than one eighth of a fine tile cannot move the useful central
        // patch out of view, but previously invalidated every scoped family.
        return Math.hypot(previous.x - current.x, previous.z - current.z) < 8
                && Math.abs(previous.radius - current.radius) < 32
                && Math.abs(previous.pixelsPerBlock - current.pixelsPerBlock)
                    < Math.max(16, previous.pixelsPerBlock * .03) ? previous : current;
    }
}

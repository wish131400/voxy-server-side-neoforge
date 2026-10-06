package dev.xantha.vss.client.prediction;

/** Spatial ownership is stable across Voxy traversal and mesh replacement. */
final class PredictionVoxyOwnership {
    private PredictionVoxyOwnership() { }

    static int radiusBlocks(int voxyRadiusChunks) {
        return Math.max(0, voxyRadiusChunks - 2) * 16;
    }

    /** A complete tile may be retired only when its entire possible volume is in range. */
    static boolean containsBox(double cameraX, double cameraY, double cameraZ, double radius,
                               double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ) {
        double dx = Math.max(Math.abs(minX - cameraX), Math.abs(maxX - cameraX));
        double dy = Math.max(Math.abs(minY - cameraY), Math.abs(maxY - cameraY));
        double dz = Math.max(Math.abs(minZ - cameraZ), Math.abs(maxZ - cameraZ));
        return radius > 0 && dx * dx + dy * dy + dz * dz < radius * radius;
    }
}

package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;

/** Projection math shared by the quadtree planner and diagnostics. */
public final class VssLodProjection {
    private VssLodProjection() {
    }

    record ViewRay(net.minecraft.world.phys.Vec3 origin, net.minecraft.world.phys.Vec3 direction) { }

    static ViewRay centerRay(Matrix4f projection, Matrix4f modelView, net.minecraft.world.phys.Vec3 camera) {
        // Include the same bob/tilt/zoom transform as the rendered image.
        // Two interior clip points also work with reversed/infinite depth.
        var inverse = new org.joml.Matrix4d(projection).mul(new org.joml.Matrix4d(modelView)).invert();
        var a = inverse.transformProject(new org.joml.Vector3d(0, 0, -0.5));
        var b = inverse.transformProject(new org.joml.Vector3d(0, 0, 0.5));
        var direction = new net.minecraft.world.phys.Vec3(b.x - a.x, b.y - a.y, b.z - a.z).normalize();
        if (b.lengthSquared() < a.lengthSquared()) direction = direction.scale(-1);
        return new ViewRay(camera.add(a.x, a.y, a.z), direction);
    }

    static double selectionScale(Matrix4f projection, int viewportHeight) {
        return Math.max(1, viewportHeight) * Math.abs(projection.m11()) * 0.5D;
    }

    static double unscopedScale(double fovDegrees, int viewportHeight) {
        return Math.max(1, viewportHeight)
                / (2.0D * Math.tan(Math.toRadians(Math.max(1.0D, fovDegrees)) * 0.5D));
    }

    public static double projectedSize(double tileBlocks, double distance,
                                       double pixelsPerBlock) {
        if (!(tileBlocks > 0.0D) || !(pixelsPerBlock > 0.0D)) {
            return 0.0D;
        }
        return tileBlocks * pixelsPerBlock / Math.max(1.0D, distance);
    }

    public static boolean shouldSubdivide(double tileBlocks, double distance,
                                          double pixelsPerBlock, double threshold) {
        return projectedSize(tileBlocks, distance, pixelsPerBlock) > threshold;
    }

    static double vanillaDepthToDistance(double depth, MatrixData projection) {
        double vanillaNdc = depth * 2.0D - 1.0D;
        return projection.vanillaB() / (vanillaNdc + projection.vanillaA());
    }

    static double distanceToVanillaDepth(double distance, MatrixData projection) {
        double vanillaNdc = -projection.vanillaA() + projection.vanillaB() / distance;
        return vanillaNdc * 0.5D + 0.5D;
    }

    static double distanceToReversedDepth(double distance, double nearPlane) {
        return nearPlane / distance;
    }

    static double reversedDepthToDistance(double depth, double nearPlane) {
        return nearPlane / depth;
    }

    /**
     * Builds the infinite reversed-depth projection and its matching
     * frustum matrix. The two vanilla coefficients are retained so depth can
     * be converted in both directions without assuming a fixed render
     * distance or projection convention.
     */
    public static MatrixData of(Matrix4f vanilla) {
        if (vanilla == null) {
            throw new IllegalArgumentException("vanilla projection is required");
        }
        float vanillaA = -vanilla.m22() / vanilla.m23();
        float vanillaB = vanilla.m32() + vanillaA * vanilla.m33();

        // Minecraft includes view bob, hurt tilt and portal effects in this matrix.
        // Preserve their clip W: infinite reversed depth (near = 1) needs Z = 2 - W.
        Matrix4f matrix = new Matrix4f(vanilla)
                .m02(-vanilla.m03())
                .m12(-vanilla.m13())
                .m22(-vanilla.m23())
                .m32(2.0F - vanilla.m33());
        Matrix4f culling = new Matrix4f(vanilla)
                .m02(0.0F)
                .m12(0.0F)
                .m22(0.0F)
                .m32(0.0F);
        return new MatrixData(matrix, culling, vanillaA, vanillaB);
    }

    public record MatrixData(Matrix4f matrix, Matrix4f culling,
                             float vanillaA, float vanillaB) {
    }
}

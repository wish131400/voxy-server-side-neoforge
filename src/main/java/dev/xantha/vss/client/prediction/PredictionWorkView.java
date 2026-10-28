package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Immutable camera-relative LOD planes. Scheduling never changes render ownership. */
final class PredictionWorkView {
    static final int VISIBLE = 0, BUFFER = 1, RECENT = 2, BACKGROUND = 3;
    private final double x, y, z, dx, dy, dz;
    private final double[] planes, bufferedPlanes;
    private final boolean rendered;
    private final long observedAt;
    private final double pixelScale;

    private PredictionWorkView(double x, double y, double z, Vec3 direction,
                               Matrix4f clip, boolean rendered, double pixelScale) {
        this.x = x; this.y = y; this.z = z;
        this.dx = direction.x; this.dy = direction.y; this.dz = direction.z;
        this.planes = planes(clip, 1);
        this.bufferedPlanes = planes(clip, 1 / 1.2);
        this.rendered = rendered;
        this.pixelScale = pixelScale;
        this.observedAt = System.nanoTime();
    }

    static PredictionWorkView of(double x, double y, double z, double dx, double dy, double dz,
                                 double verticalFov, double aspect) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0) || !Double.isFinite(length) || !(aspect > 0)) return null;
        Vec3 direction = new Vec3(dx / length, dy / length, dz / length);
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(Math.max(1, Math.min(120, verticalFov))),
                (float) aspect, .1F, 1024F);
        Vec3 up = Math.abs(direction.y) > .99 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) direction.x, (float) direction.y,
                (float) direction.z, (float) up.x, (float) up.y, (float) up.z);
        return new PredictionWorkView(x, y, z, direction,
                new Matrix4f(VssLodProjection.of(projection).culling()).mul(view), false, 0);
    }

    static PredictionWorkView rendered(Matrix4f projection, Matrix4f modelView,
                                       Vec3 camera, Vec3 direction) {
        return rendered(projection, modelView, camera, direction, 0);
    }

    static PredictionWorkView rendered(Matrix4f projection, Matrix4f modelView,
                                       Vec3 camera, Vec3 direction, double pixelScale) {
        return new PredictionWorkView(camera.x, camera.y, camera.z, direction,
                new Matrix4f(VssLodProjection.of(projection).culling()).mul(modelView), true, pixelScale);
    }

    double projectedSize(PredictionTileKey key, VssLodLayout layout, double minY, double maxY, double fallbackScale) {
        double span = layout.tileBlocks(key.lod());
        // Clip-W is eye depth, including bob/tilt. Euclidean distance would under-refine screen edges.
        double a = planes[16], b = planes[17], c = planes[18];
        double nearest = a * ((key.tileX() + (a < 0 ? 1D : 0D)) * span - x)
                + b * ((b < 0 ? maxY : minY) - y)
                + c * ((key.tileZ() + (c < 0 ? 1D : 0D)) * span - z) + planes[19];
        return VssLodProjection.projectedSize(span, nearest, Math.max(pixelScale, fallbackScale));
    }

    double nearestDepth(net.minecraft.world.phys.AABB box) {
        double a = planes[16], b = planes[17], c = planes[18];
        return a * ((a < 0 ? box.maxX : box.minX) - x)
                + b * ((b < 0 ? box.maxY : box.minY) - y)
                + c * ((c < 0 ? box.maxZ : box.minZ) - z) + planes[19];
    }

    boolean freshRender(long now) {
        return rendered && now - observedAt < 1_000_000_000L;
    }

    boolean hasProjection() { return rendered && pixelScale>0; }

    /** Exact geometry identity; observation age is deliberately not part of visibility. */
    boolean sameGeometry(PredictionWorkView other) {
        return other != null && x == other.x && y == other.y && z == other.z
                && dx == other.dx && dy == other.dy && dz == other.dz
                && pixelScale == other.pixelScale && rendered == other.rendered
                && java.util.Arrays.equals(planes, other.planes)
                && java.util.Arrays.equals(bufferedPlanes, other.bufferedPlanes);
    }

    boolean changedFrom(PredictionWorkView previous) {
        if (previous == null || rendered != previous.rendered) return true;
        if (Math.abs(pixelScale - previous.pixelScale) > Math.max(1, previous.pixelScale) * .05) return true;
        double ax = x - previous.x, ay = y - previous.y, az = z - previous.z;
        if (ax * ax + ay * ay + az * az >= 64) return true;
        double turn=Math.min(Math.toRadians(3),Math.atan(64/Math.max(1,pixelScale)));
        if (dx * previous.dx + dy * previous.dy + dz * previous.dz < Math.cos(turn)) return true;
        for (int i = 0; i < planes.length; i += 4) {
            double a = planes[i] - previous.planes[i], b = planes[i + 1] - previous.planes[i + 1];
            double c = planes[i + 2] - previous.planes[i + 2];
            if (a * a + b * b + c * c > .0025
                    || Math.abs(planes[i + 3] - previous.planes[i + 3]) > 2) return true;
        }
        return false;
    }

    boolean cutFrom(PredictionWorkView previous) {
        return previous != null && (Math.hypot(x - previous.x, z - previous.z) > 256
                || Math.abs(y - previous.y) > 256
                || dx * previous.dx + dy * previous.dy + dz * previous.dz < 0);
    }

    boolean foreground(PredictionTileKey key, VssLodLayout layout, VssLodFocus focus,
                       double minY, double maxY) {
        return band(key, layout, focus, minY, maxY) <= BUFFER;
    }

    int band(PredictionTileKey key, VssLodLayout layout, VssLodFocus focus,
             double minY, double maxY) {
        if (PredictionWorkOrder.scoped(key, layout, focus)) return VISIBLE;
        double span = layout.tileBlocks(key.lod());
        double minX = key.tileX() * span - x - 8, maxX = (key.tileX() + 1D) * span - x + 8;
        double minZ = key.tileZ() * span - z - 8, maxZ = (key.tileZ() + 1D) * span - z + 8;
        if (intersects(planes, minX, minY - y - 8, minZ, maxX, maxY - y + 8, maxZ)) return VISIBLE;
        return intersects(bufferedPlanes, minX, minY - y - 8, minZ, maxX, maxY - y + 8, maxZ)
                ? BUFFER : BACKGROUND;
    }

    private static boolean intersects(double[] planes, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        for (int i = 0; i < planes.length; i += 4) {
            double a = planes[i], b = planes[i + 1], c = planes[i + 2];
            if (a * (a < 0 ? minX : maxX) + b * (b < 0 ? minY : maxY)
                    + c * (c < 0 ? minZ : maxZ) + planes[i + 3] < 0) return false;
        }
        return true;
    }

    private static double[] planes(Matrix4f m, double scale) {
        // Four side planes plus clip W: no vanilla far-distance limit.
        double[] p = {
            m.m03() + scale * m.m00(), m.m13() + scale * m.m10(), m.m23() + scale * m.m20(), m.m33() + scale * m.m30(),
            m.m03() - scale * m.m00(), m.m13() - scale * m.m10(), m.m23() - scale * m.m20(), m.m33() - scale * m.m30(),
            m.m03() + scale * m.m01(), m.m13() + scale * m.m11(), m.m23() + scale * m.m21(), m.m33() + scale * m.m31(),
            m.m03() - scale * m.m01(), m.m13() - scale * m.m11(), m.m23() - scale * m.m21(), m.m33() - scale * m.m31(),
            m.m03(), m.m13(), m.m23(), m.m33()
        };
        for (int i = 0; i < p.length; i += 4) {
            double length = Math.sqrt(p[i] * p[i] + p[i + 1] * p[i + 1] + p[i + 2] * p[i + 2]);
            if (!(length > 0) || !Double.isFinite(length)) {
                p[i] = p[i + 1] = p[i + 2] = p[i + 3] = 0;
            } else for (int j = 0; j < 4; j++) p[i + j] /= length;
        }
        return p;
    }
}

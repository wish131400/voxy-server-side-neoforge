package dev.xantha.vss.client.prediction;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The five-sided tile partition.  A tile does not need all of its
 * vertical faces when the camera is outside that tile; keeping the partition
 * in the GPU cache lets the renderer skip those faces without rebuilding the
 * mesh as the view turns.
 */
final class VssLodFaceGroup {
    static final int HORIZONTAL = 0;
    static final int NORTH = 1;
    static final int SOUTH = 2;
    static final int WEST = 3;
    static final int EAST = 4;
    static final int COUNT = 5;
    static final int ALL = (1 << COUNT) - 1;

    private VssLodFaceGroup() {
    }

    /** Maps an axis-aligned mesh normal to the face group. */
    static int ofNormal(float normalX, float normalY, float normalZ) {
        float x = Math.abs(normalX);
        float y = Math.abs(normalY);
        float z = Math.abs(normalZ);
        if (y >= x && y >= z) {
            return HORIZONTAL;
        }
        if (z >= x) {
            return normalZ < 0.0F ? NORTH : SOUTH;
        }
        return normalX < 0.0F ? WEST : EAST;
    }

    /**
     * Fluid normals store the fluid kind in Y, so a side bank must be
     * classified from X/Z even for ice where the encoded Y value is 1.
     */
    static int ofFluidNormal(float normalX, float normalY, float normalZ) {
        float x = Math.abs(normalX);
        float z = Math.abs(normalZ);
        if (x > 0.5F || z > 0.5F) {
            if (z >= x) {
                return normalZ < 0.0F ? NORTH : SOUTH;
            }
            return normalX < 0.0F ? WEST : EAST;
        }
        return HORIZONTAL;
    }

    /** Returns the tile faces that can face the camera, matching the vanilla face shading convention. */
    static int visibleMask(AABB bounds, Vec3 camera) {
        if (bounds == null || camera == null) {
            return ALL;
        }
        int mask = 0;
        if (camera.y > bounds.minY) {
            mask |= 1 << HORIZONTAL;
        }
        if (camera.z < bounds.maxZ) {
            mask |= 1 << NORTH;
        }
        if (camera.z > bounds.minZ) {
            mask |= 1 << SOUTH;
        }
        if (camera.x < bounds.maxX) {
            mask |= 1 << WEST;
        }
        if (camera.x > bounds.minX) {
            mask |= 1 << EAST;
        }
        return mask;
    }
}

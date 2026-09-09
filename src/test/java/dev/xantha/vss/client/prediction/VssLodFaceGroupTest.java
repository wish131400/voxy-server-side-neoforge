package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VssLodFaceGroupTest {
    @Test
    void normalDirectionsUseStandardGroups() {
        assertEquals(VssLodFaceGroup.HORIZONTAL,
                VssLodFaceGroup.ofNormal(0.0F, 1.0F, 0.0F));
        assertEquals(VssLodFaceGroup.NORTH,
                VssLodFaceGroup.ofNormal(0.0F, 0.0F, -1.0F));
        assertEquals(VssLodFaceGroup.SOUTH,
                VssLodFaceGroup.ofNormal(0.0F, 0.0F, 1.0F));
        assertEquals(VssLodFaceGroup.WEST,
                VssLodFaceGroup.ofNormal(-1.0F, 0.0F, 0.0F));
        assertEquals(VssLodFaceGroup.EAST,
                VssLodFaceGroup.ofNormal(1.0F, 0.0F, 0.0F));
    }

    @Test
    void fluidBankUsesItsHorizontalNormalWhenYStoresIce() {
        assertEquals(VssLodFaceGroup.NORTH,
                VssLodFaceGroup.ofFluidNormal(0.0F, 1.0F, -1.0F));
        assertEquals(VssLodFaceGroup.EAST,
                VssLodFaceGroup.ofFluidNormal(1.0F, 1.0F, 0.0F));
        assertEquals(VssLodFaceGroup.HORIZONTAL,
                VssLodFaceGroup.ofFluidNormal(0.0F, 1.0F, 0.0F));
    }

    @Test
    void outsideCameraSeesOnlyTheFacingTileSides() {
        AABB bounds = new AABB(0.0D, 0.0D, 0.0D, 16.0D, 256.0D, 16.0D);

        int mask = VssLodFaceGroup.visibleMask(bounds, new Vec3(32.0D, 64.0D, 8.0D));

        assertEquals((1 << VssLodFaceGroup.HORIZONTAL)
                        | (1 << VssLodFaceGroup.NORTH)
                        | (1 << VssLodFaceGroup.SOUTH)
                        | (1 << VssLodFaceGroup.EAST),
                mask);
        assertEquals(0, mask & (1 << VssLodFaceGroup.WEST));
    }
}

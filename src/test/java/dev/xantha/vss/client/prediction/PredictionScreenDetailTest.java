package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionScreenDetailTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void farVisibleSurfaceUsesOrdinaryPlanBeyondTheOldRadiusAndTurnsWithCamera() {
        var layout = VssLodLayout.of(32768, 6, true, true);
        var frontView = PredictionWorkView.of(0, 137, 0, 1, 0, 0, 70, 16D / 9);
        var rearView = PredictionWorkView.of(0, 137, 0, -1, 0, 0, 70, 16D / 9);
        var front = plan(layout, frontView, 1400, 768);
        var rear = plan(layout, rearView, 1400, 768);
        var target = owner(front, layout, 1800, 32);
        var unseen = owner(rear, layout, 1800, 32);
        System.out.println("SCREEN_PLAN front=" + target + ",rear=" + unseen + ",leaves=" + front.size());
        assertTrue(PredictionWorkOrder.surfaceTile(target, layout),
                "visible tree-sized features beyond 768 blocks need plant-capable ordinary leaves");
        assertTrue(unseen.lod() > target.lod(), "unseen regions keep the skeleton instead of spending fine subdivision slots");
        assertTrue(PredictionScreenDetail.surfaceVisible(frontView, target, layout, 0, 137, 0, 1400, -64, 384));
        assertFalse(PredictionScreenDetail.surfaceVisible(rearView, target, layout, 0, 137, 0, 1400, -64, 384));
        assertTrue(front.size() <= 1024 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
        for (int angle = 0; angle < 360; angle += 15) for (double fraction : new double[]{.1, .5, .98})
            owner(front, layout, layout.maxDistanceBlocks() * fraction * Math.cos(Math.toRadians(angle)),
                    layout.maxDistanceBlocks() * fraction * Math.sin(Math.toRadians(angle)));
    }

    @Test void confirmedHiddenRegionsYieldPlanningSlotsToExposedScreenRegions() {
        var layout = VssLodLayout.of(32768, 6, true, true);
        var view = PredictionWorkView.of(0, 137, 0, 1, 0, 0, 70, 16D / 9);
        // Larger plant-capable leaves need fewer splits. Use enough projected
        // detail to exhaust the budget and exercise occlusion priority.
        var keys = PredictionLodPlanner.plan(Level.OVERWORLD, 0, 137, 0, layout, null, 5200, -64, 384,
                768, key -> true, key -> false, view, key -> key.tileZ() >= 0);
        var exposed = owner(keys, layout, 1800, -100);
        var hidden = owner(keys, layout, 1800, 100);
        assertTrue(PredictionWorkOrder.surfaceTile(exposed, layout),
                "exposed surface keeps plant-capable ordinary planning slots");
        assertTrue(hidden.lod() > exposed.lod(), "same-distance hidden surface yields the bounded subdivision budget");
    }

    @Test void insignificantSurfaceStopsWhileLargeTerrainCellsStillRequestRefinement() {
        var layout = VssLodLayout.of(32768, 6, true, true);
        var view = PredictionWorkView.of(0, 137, 0, 1, 0, 0, 70, 16D / 9);
        var far = new PredictionTileKey(Level.OVERWORLD, 60, 0, 1);
        assertFalse(PredictionScreenDetail.surfaceVisible(view, far, layout, 0, 137, 0, 700, -64, 384));
        assertEquals(8, PredictionScreenDetail.targetAxis(30, layout));
        assertEquals(32, PredictionScreenDetail.targetAxis(180, layout));
        assertEquals(64, PredictionScreenDetail.targetAxis(300, layout));
        var coarse = new PredictionTileKey(Level.OVERWORLD, 1, 0, 5);
        double footprint = PredictionScreenDetail.projected(view, coarse, layout, 0, 137, 0, 1400, -64, 384);
        assertTrue(footprint / 8 > 6, "distance alone cannot declare a large coarse cell unimportant");
        assertEquals(64, PredictionScreenDetail.targetAxis(footprint, layout));
    }

    @Test void realProjectionScaleAndEyeDepthControlTargetsIncludingViewportResize() {
        var layout = VssLodLayout.of(8192, 6, true, true);
        var key = new PredictionTileKey(Level.OVERWORLD, 0, -20, 1);
        var projection = new Matrix4f().perspective((float)Math.toRadians(70), 1.7F, .1F, 128);
        var small = PredictionWorkView.rendered(projection, new Matrix4f(), Vec3.ZERO, new Vec3(0, 0, -1), 500);
        var large = PredictionWorkView.rendered(projection, new Matrix4f(), Vec3.ZERO, new Vec3(0, 0, -1), 2000);
        assertEquals(4, large.projectedSize(key, layout, -64, 384, 1)
                / small.projectedSize(key, layout, -64, 384, 1), .0001);
        assertTrue(large.changedFrom(small), "viewport pixel changes must refresh admission even with unchanged FOV");
        var down = PredictionWorkView.of(0, 10000, 0, 0, -1, 0, 70, 16D / 9);
        assertFalse(PredictionScreenDetail.surfaceVisible(down, new PredictionTileKey(Level.OVERWORLD, 0, 0, 1),
                layout, 0, 10000, 0, 1000, -64, 384));
    }

    private static List<PredictionTileKey> plan(VssLodLayout layout, PredictionWorkView view, double pixels, int surfaceRadius) {
        return PredictionLodPlanner.plan(Level.OVERWORLD, 0, 137, 0, layout, null, pixels, -64, 384,
                surfaceRadius, key -> true, key -> false, view);
    }

    private static PredictionTileKey owner(List<PredictionTileKey> keys, VssLodLayout layout, double x, double z) {
        var owners = keys.stream().filter(key -> {
            int span = layout.tileBlocks(key.lod());
            return Math.floor(x / span) == key.tileX() && Math.floor(z / span) == key.tileZ();
        }).toList();
        assertEquals(1, owners.size(), "each location must keep exactly one planned owner: " + x + "," + z);
        return owners.get(0);
    }
}

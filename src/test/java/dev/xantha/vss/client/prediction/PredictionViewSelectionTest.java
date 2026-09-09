package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PredictionViewSelectionTest {
    private static final DimensionProfile PROFILE = new DimensionProfile(
            ResourceLocation.withDefaultNamespace("overworld"), 42L, -64, 384,
            "noise", "minecraft:overworld", 1L);

    @BeforeAll
    static void bootstrapMinecraft() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
    }

    @Test
    void horizontalAndUpwardScopedViewsCanHitMountains() {
        ClientTerrainSampler mountain = new ClientTerrainSampler(42L, PROFILE) {
            @Override public int surfaceY(int x, int z) { return z >= 600 ? 240 : 64; }
        };
        for (Vec3 direction : new Vec3[]{new Vec3(0, 0, 1), new Vec3(0, .05, 1).normalize()}) {
            VssLodFocus focus = ClientPredictionState.pickViewFocus(mountain,
                    new Vec3(0, 171, 0), direction, 4096, .08, 9000);
            assertNotNull(focus);
            assertEquals(600, focus.z(), 4);
            assertTrue(focus.radius() > 192);
            assertEquals(9000, focus.selectionScale(1000));
        }
    }

    @Test
    void skyMissDoesNotRefineAnArbitraryHorizonDisc() {
        ClientTerrainSampler plain = new ClientTerrainSampler(42L, PROFILE) {
            @Override public int surfaceY(int x, int z) { return 64; }
        };
        assertNull(ClientPredictionState.pickViewFocus(plain, new Vec3(0, 171, 0),
                new Vec3(0, .1, 1).normalize(), 4096, .08, 9000));
    }

    @Test
    void projectionScaleUsesTheRenderedZoomAndFramebuffer() {
        Matrix4f normal = new Matrix4f().setPerspective((float) Math.toRadians(70), 1.8F, .05F, 4096);
        Matrix4f scoped = new Matrix4f().setPerspective((float) Math.toRadians(7), 1.8F, .05F, 4096);
        assertEquals(VssLodProjection.unscopedScale(70, 2100),
                VssLodProjection.selectionScale(normal, 2100), .001);
        assertTrue(VssLodProjection.selectionScale(scoped, 2100)
                > VssLodProjection.selectionScale(normal, 2100) * 10);
    }

    @Test
    void focusTouchesAParentEvenWhenItsCenterIsOutside() {
        VssLodFocus focus = new VssLodFocus(100, 100, 192, 9000);
        assertFalse(focus.contains(2048, 2048));
        assertTrue(focus.intersects(0, 0, 4096, 4096));
        assertFalse(focus.intersects(4096, 4096, 8192, 8192));
    }

    @Test
    void ordinaryViewPlansVegetationInEveryDirectionWithinBudget() {
        var dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.withDefaultNamespace("overworld"));
        VssLodLayout layout = VssLodLayout.of(4096, 6, true, false);
        var plan = PredictionLodPlanner.plan(dimension, 2317, 171, 3921, layout, null,
                VssLodProjection.unscopedScale(70, 2100));
        assertTrue(plan.size() <= 1024 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
        for (int angle = 0; angle < 360; angle += 30) {
            double x = 2317 + Math.cos(Math.toRadians(angle)) * 1000;
            double z = 3921 + Math.sin(Math.toRadians(angle)) * 1000;
            var tile = plan.stream().filter(key -> contains(layout, key, x, z)).findFirst();
            assertTrue(tile.isPresent(), "unplanned visible direction " + angle);
            assertTrue(tile.get().lod() <= 3, "no vegetation tier in direction " + angle + ": " + tile.get());
        }
    }

    @Test
    void scopedTargetIsPlannedAcrossParentTileBoundaries() {
        VssLodLayout layout = VssLodLayout.of(4096, 6, true, false);
        for (int angle = 0; angle < 360; angle += 45) {
            double x = 2317 + Math.cos(Math.toRadians(angle)) * 2600;
            double z = 3921 + Math.sin(Math.toRadians(angle)) * 2600;
            VssLodFocus focus = new VssLodFocus(x, z, 380, 9000);
            var plan = PredictionLodPlanner.plan(PROFILE.levelKey(), 2317, 171, 3921,
                    layout, focus, 1500);
            assertTrue(plan.size() <= 2048 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
            var target = plan.stream().filter(key -> contains(layout, key, x, z)).findFirst();
            assertTrue(target.isPresent(), "scoped target missing at angle " + angle);
            assertTrue(target.get().lod() <= 1, "scoped target not refined at angle " + angle);
        }
    }

    @Test
    void telescopeCanReachBlockDetailAtEveryDistanceInsideTheHorizon() {
        var layout = VssLodLayout.of(65536, 6, true, false);
        for (int distance : new int[]{8192, 32768, 65530}) {
            var focus = new VssLodFocus(distance, 0, 4096, 9000);
            var plan = PredictionLodPlanner.plan(PROFILE.levelKey(), 0, 170, 0, layout, focus,
                    1500, -64, 320, 768, ignored -> true);
            assertTrue(plan.size() <= 2048 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
            assertEquals(0, plan.stream().filter(key -> contains(layout, key, distance, 0)).findFirst().orElseThrow().lod(),
                    "distance cannot impose a coarser telescope floor: " + distance);
            var target = plan.stream().filter(key -> contains(layout, key, distance, 0)).findFirst().orElseThrow();
            assertTrue(PredictionWorkOrder.priority(target, layout, (double) distance * distance, 0, false, focus)
                    < PredictionWorkOrder.priority(target, layout, (double) distance * distance, 0, false),
                    "scoped work needs execution slots as well as planning slots");
            assertTrue(plan.stream().anyMatch(key -> contains(layout, key, 0, 0)), "near coverage must remain planned");
        }
    }

    @Test
    void telescopeRefinesTheFull64ChunkRadiusWhileKeepingNearbyDetail() {
        var layout = VssLodLayout.of(65536, 6, true, false);
        for (int distance : new int[]{8192, 32768}) {
            var focus = new VssLodFocus(distance + 29, -19, 192, 9000);
            assertEquals(1024, PredictionWorkOrder.surfaceFocus(focus).radius());
            var plan = PredictionLodPlanner.plan(PROFILE.levelKey(), 0, 170, 0, layout, focus,
                    1500, -64, 320, 768, ignored -> true);
            assertTrue(plan.size() <= 2048 + PredictionLodPlanner.MAX_BAND_LEAVES + PredictionTransitionPlan.MAX_EXTRA_LEAVES);
            for (int dz = -1024; dz <= 1024; dz += 64) for (int dx = -1024; dx <= 1024; dx += 64) {
                if (dx * dx + dz * dz > 1024 * 1024) continue;
                double x = focus.x() + dx, z = focus.z() + dz;
                var tile = plan.stream().filter(key -> contains(layout, key, x, z)).findFirst().orElseThrow();
                assertEquals(0, tile.lod(), "entire scoped radius must reach block detail, offset=" + dx + "," + dz);
                assertTrue(PredictionWorkOrder.surfaceEligible(tile, layout, 0, 0, 768, focus));
            }
            for (int dz = -192; dz <= 192; dz += 64) for (int dx = -192; dx <= 192; dx += 64) {
                final int x = dx, z = dz;
                var near = plan.stream().filter(key -> contains(layout, key, x, z)).findFirst().orElseThrow();
                assertTrue(near.lod() <= 1, "near ground must not be starved by the enlarged telescope patch");
            }
        }
    }

    @Test
    void rayChecksTheLastPointOfThePredictionHorizon() {
        var mountain = new ClientTerrainSampler(42L, PROFILE) {
            @Override public int surfaceY(int x, int z) { return z >= 65530 ? 240 : 64; }
        };
        var focus = ClientPredictionState.pickViewFocus(mountain, new Vec3(0, 170, 0),
                new Vec3(0, 0, 1), 65536, .08, 9000);
        assertNotNull(focus);
        assertEquals(65530, focus.z(), 4);
    }

    @Test void crosshairRayHitsTheRenderedCenterAtHighAltitudeAndSteepPitch() {
        var plain = new ClientTerrainSampler(42L, PROFILE) {
            @Override public int surfaceY(int x, int z) { return 64; }
        };
        for (int height : new int[]{100, 1000, 5000}) for (double pitch : new double[]{10, 45, 85})
            for (float fov : new float[]{70, 7}) {
                var camera = new Vec3(-245, height, -44);
                var projection = new Matrix4f().perspective((float) Math.toRadians(fov), 1.8F, .05F, 65536)
                        .translate(.03F, -.04F, 0).rotateZ(.015F).rotateX(.01F);
                var model = new Matrix4f().rotateX((float) Math.toRadians(pitch));
                var ray = VssLodProjection.centerRay(projection, model, camera);
                var focus = ClientPredictionState.pickViewFocus(plain, ray.origin(), ray.direction(), 65536, .08, 9000);
                assertNotNull(focus);
                var clip = new org.joml.Matrix4d(projection).mul(new org.joml.Matrix4d(model)).transformProject(
                        new org.joml.Vector3d(focus.x() - camera.x, 64 - camera.y, focus.z() - camera.z));
                assertEquals(0, clip.x, .002, "horizontal crosshair alignment");
                assertEquals(0, clip.y, .002, "pitch=" + pitch + ", height=" + height + ", fov=" + fov);
            }
    }

    private static boolean contains(VssLodLayout layout, PredictionTileManager.PredictionTileKey key,
                                    double x, double z) {
        int span = layout.tileBlocks(key.lod());
        return x >= key.tileX() * (double) span && x < (key.tileX() + 1.0) * span
                && z >= key.tileZ() * (double) span && z < (key.tileZ() + 1.0) * span;
    }
}

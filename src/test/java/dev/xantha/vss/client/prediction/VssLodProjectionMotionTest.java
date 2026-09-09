package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class VssLodProjectionMotionTest {
    @Test
    void walkingAndSprintingKeepDepthAlignedWithTheBobbingView() {
        for (float amplitude : new float[] {0.0F, 0.1F, 0.2F}) {
            for (int phase = 0; phase < 32; phase++) {
                Matrix4f effects = walkingBob(phase * (float) Math.PI / 16.0F, amplitude);
                assertDepthAndScreenAlignment(effects);
            }
        }
    }

    @Test
    void damageTiltAndPortalDistortionKeepDepthAligned() {
        for (float direction : new float[] {0.0F, 45.0F, 90.0F, 180.0F}) {
            Matrix4f hurt = new Matrix4f().rotateY(radians(-direction))
                    .rotateZ(radians(-14.0F)).rotateY(radians(direction));
            assertDepthAndScreenAlignment(hurt.mul(walkingBob(0.7F, 0.15F)));
        }
        Matrix4f portal = walkingBob(0.7F, 0.15F)
                .rotate(1.2F, 0.0F, (float) Math.sqrt(0.5D), (float) Math.sqrt(0.5D))
                .scale(1.4F, 1.0F, 1.0F)
                .rotate(-1.2F, 0.0F, (float) Math.sqrt(0.5D), (float) Math.sqrt(0.5D));
        assertDepthAndScreenAlignment(portal);
    }

    @Test
    void viewTranslationKeepsTheSameNearPlane() {
        Matrix4f effects = new Matrix4f().translate(0.1F, -0.2F, 0.4F)
                .rotateX(radians(1.0F));
        Matrix4f reversed = VssLodProjection.of(perspective().mul(effects)).matrix();
        for (float distance : new float[] {0.75F, 1.0F, 1.25F, 64.0F}) {
            Vector4f point = new Vector4f(0.1F, 0.2F, -distance, 1.0F)
                    .mul(new Matrix4f(effects).invert());
            Vector4f clip = point.mul(reversed, new Vector4f());
            assertEquals(1.0D / distance, depth(clip), 2.0E-6D);
            assertEquals(distance >= 1.0F, clip.z <= clip.w + 1.0E-6F);
        }
    }

    @Test
    void visibleTerrainStaysInsideGpuClipVolumeAtEveryWalkingPhase() {
        int checked = 0;
        for (float pitch : new float[] {-30.0F, -15.0F, 0.0F, 15.0F, 30.0F}) {
            Matrix4f view = new Matrix4f().rotateX(radians(pitch));
            for (float amplitude : new float[] {0.0F, 0.1F, 0.2F}) {
                for (int phase = 0; phase < 32; phase++) {
                    Matrix4f vanilla = perspective().mul(walkingBob(
                            phase * (float) Math.PI / 16.0F, amplitude));
                    VssLodProjection.MatrixData lod = VssLodProjection.of(vanilla);
                    FrustumIntersection culling = new FrustumIntersection(
                            new Matrix4f(lod.culling()).mul(view));
                    for (float distance : new float[] {128.0F, 512.0F, 2048.0F, 8192.0F, 32768.0F}) {
                        for (float height : new float[] {-96.0F, -32.0F, 0.0F, 96.0F}) {
                            Vector4f world = new Vector4f(distance * 0.1F, height, -distance, 1.0F);
                            Vector4f camera = world.mul(view, new Vector4f());
                            Vector4f expected = camera.mul(vanilla, new Vector4f());
                            if (expected.w <= 1.0F || Math.abs(expected.x) >= expected.w * 0.95F
                                    || Math.abs(expected.y) >= expected.w * 0.95F) {
                                continue;
                            }
                            Vector4f actual = camera.mul(lod.matrix(), new Vector4f());
                            String context = "pitch=" + pitch + ", bob=" + amplitude
                                    + ", phase=" + phase + ", distance=" + distance;
                            assertTrue(actual.z >= -actual.w && actual.z <= actual.w,
                                    "visible terrain clipped: " + context);
                            assertTrue(culling.testPoint(world.x, world.y, world.z),
                                    "visible terrain culled: " + context);
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 5000);
    }

    private static void assertDepthAndScreenAlignment(Matrix4f effects) {
        Matrix4f vanilla = perspective().mul(effects);
        Matrix4f original = new Matrix4f(vanilla);
        VssLodProjection.MatrixData lod = VssLodProjection.of(vanilla);
        assertEquals(original, vanilla);
        for (float distance : new float[] {2.0F, 64.0F, 512.0F, 4096.0F, 32768.0F}) {
            for (float vertical : new float[] {-0.5F, 0.0F, 0.5F}) {
                Vector4f point = new Vector4f(distance * 0.2F, distance * vertical, -distance, 1.0F);
                Vector4f effectView = point.mul(effects, new Vector4f());
                Vector4f vanillaClip = point.mul(vanilla, new Vector4f());
                Vector4f lodClip = point.mul(lod.matrix(), new Vector4f());
                assertEquals(vanillaClip.x, lodClip.x);
                assertEquals(vanillaClip.y, lodClip.y);
                assertEquals(vanillaClip.w, lodClip.w);
                assertEquals(1.0D / -effectView.z, depth(lodClip), 2.0E-7D,
                        "reversed depth must follow the transformed camera depth");
                double exported = VssLodProjection.distanceToVanillaDepth(-effectView.z, lod);
                assertEquals(depth(vanillaClip), exported, 2.0E-7D);
                if (distance <= 512.0F) {
                    double importedDistance = VssLodProjection.vanillaDepthToDistance(depth(vanillaClip), lod);
                    assertEquals(depth(lodClip), 1.0D / importedDistance, 2.0E-6D,
                            "prediction and imported vanilla depth must agree");
                }
            }
        }
    }

    // GameRenderer.bobView: translate, roll, then pitch, post-multiplied into projection.
    private static Matrix4f walkingBob(float phase, float amplitude) {
        return new Matrix4f().translate((float) Math.sin(phase) * amplitude * 0.5F,
                        -Math.abs((float) Math.cos(phase) * amplitude), 0.0F)
                .rotateZ(radians((float) Math.sin(phase) * amplitude * 3.0F))
                .rotateX(radians(Math.abs((float) Math.cos(phase - 0.2F) * amplitude) * 5.0F));
    }

    private static Matrix4f perspective() {
        return new Matrix4f().perspective(radians(70.0F), 16.0F / 9.0F, 0.05F, 1024.0F);
    }

    private static double depth(Vector4f clip) {
        return (double) clip.z / clip.w * 0.5D + 0.5D;
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}

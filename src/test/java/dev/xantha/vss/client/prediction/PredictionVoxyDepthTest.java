package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionVoxyDepthTest {
    @Test void borrowedDepthRequiresTheSameTargetCameraAndFrame() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var bus = BusBuilder.builder().build();
        bus.register(VSSClientNetworking.class);
        try {
            var viewport = new Viewport();
            var original = new Matrix4f(viewport.MVP).invert();
            PredictionVoxyDepth.capture(new Pipeline(), viewport, 13);
            var captured = PredictionVoxyDepth.current(13, 64, 32, Vec3.ZERO);
            assertNotNull(captured);
            assertTrue(captured.zeroToOne(), "installed Voxy uses zero-to-one clip depth");
            assertFalse(captured.reverseZ());
            viewport.MVP.identity();
            assertEquals(original, captured.inverseMvp(), "viewport reuse must not change captured depth coordinates");
            assertNull(PredictionVoxyDepth.current(14, 64, 32, Vec3.ZERO));
            assertNull(PredictionVoxyDepth.current(13, 128, 32, Vec3.ZERO));
            assertNull(PredictionVoxyDepth.current(13, 64, 32, new Vec3(0, 1, 0)));
            bus.post(new RenderFrameEvent.Pre(null));
            assertNull(PredictionVoxyDepth.current(13, 64, 32, Vec3.ZERO), "no Voxy draw this frame must not reuse old occlusion");
            PredictionVoxyDepth.capture(new Pipeline(), new Viewport(), 13);
            PredictionVoxyDepth.capture(new Object(), new Object(), 13);
            assertNull(PredictionVoxyDepth.current(13, 64, 32, Vec3.ZERO), "unknown Voxy API must invalidate the borrowed texture");
        } finally { PredictionVoxyDepth.clear(); bus.unregister(VSSClientNetworking.class); }
    }
    @Test void capturesEachPipelineConventionWithoutReusingPreviousProperties() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try {
            for (boolean zeroToOne : new boolean[]{false, true}) for (boolean reverseZ : new boolean[]{false, true}) {
                PredictionVoxyDepth.capture(new Pipeline(new Properties(zeroToOne, reverseZ)), new Viewport(), 13);
                var captured = PredictionVoxyDepth.current(13, 64, 32, Vec3.ZERO);
                assertNotNull(captured);
                assertEquals(zeroToOne, captured.zeroToOne());
                assertEquals(reverseZ, captured.reverseZ());
            }
        } finally { PredictionVoxyDepth.clear(); }
    }
    public static class Texture { public final int id = 7; }
    public static class Framebuffer { public Texture getDepthTex() { return new Texture(); } }
    public record Properties(boolean isZero2One, boolean isReverseZ) { }
    public static class Pipeline {
        public final Framebuffer fb = new Framebuffer();
        public final Properties properties;
        public Pipeline() { this(new Properties(true, false)); }
        public Pipeline(Properties properties) { this.properties = properties; }
    }
    public static class Viewport {
        public int width = 64, height = 32;
        public double cameraX, cameraY, cameraZ;
        public Matrix4f MVP = new Matrix4f().perspective(1.2F, 2, 16, 65536).rotateX(.1F);
    }
}

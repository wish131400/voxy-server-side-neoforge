package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class PredictionWorkViewTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {ClientTerrainSamplerTest.bootstrapMinecraft();}
    @Test void actualAspectRejectsTheDiagonalConeAndKeepsDistantLod() {
        var layout = VssLodLayout.of(32768, 2, true, true);
        var camera = new net.minecraft.world.phys.Vec3(0, 80, 0);
        var direction = new net.minecraft.world.phys.Vec3(0, 0, -1);
        var side = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 5, -6, 1);
        var far = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, -100, 1);
        var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(60), 1, .1F, 32F);
        var square = PredictionWorkView.rendered(projection, new org.joml.Matrix4f(), camera, direction);
        assertEquals(PredictionWorkView.BACKGROUND, square.band(side, layout, null, -64, 320));
        assertEquals(PredictionWorkView.VISIBLE, square.band(far, layout, null, -64, 320),
                "the vanilla far plane must never suppress predicted horizon generation");
        var wide = PredictionWorkView.rendered(new org.joml.Matrix4f().perspective(
                (float)Math.toRadians(60), 2, .1F, 32F), new org.joml.Matrix4f(), camera, direction);
        assertEquals(PredictionWorkView.VISIBLE, wide.band(side, layout, null, -64, 320));
    }

    @Test void nearRearIsDeferredAndCameraSnapshotsAreImmutable() {
        var layout = VssLodLayout.of(8192, 2, true, true);
        var rear = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, -2, 0, 1);
        var view = PredictionWorkView.of(0, 80, 0, 1, 0, 0, 70, 1);
        assertFalse(view.foreground(rear, layout, null, -64, 320),
                "being within the old 512-block fine radius must not make unseen upgrades foreground");
        assertTrue(view.changedFrom(PredictionWorkView.of(0, 80, 0, -1, 0, 0, 70, 1)));
        assertFalse(view.changedFrom(PredictionWorkView.of(0.1, 80, 0, 1, 0, 0, 70, 1)));
        var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(70), 1, .1F, 64);
        var model = new org.joml.Matrix4f();
        var snapshot = PredictionWorkView.rendered(projection, model, net.minecraft.world.phys.Vec3.ZERO,
                new net.minecraft.world.phys.Vec3(0, 0, -1));
        var front = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, -20, 1);
        projection.rotateY((float)Math.PI); model.rotateY((float)Math.PI);
        assertEquals(PredictionWorkView.VISIBLE, snapshot.band(front, layout, null, -64, 320));
    }

    @Test void bobTiltAndZoomNeverRejectGeometryInsideTheRenderedFrustum() {
        var layout = VssLodLayout.of(32768, 2, true, true);
        for (int phase = 0; phase < 16; phase++) {
            var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(30 + phase * 3), 1.7F, .1F, 128)
                    .translate(.1F, -.2F, 0).rotateZ((float)Math.sin(phase) * .2F);
            var model = new org.joml.Matrix4f().rotateX((float)Math.sin(phase) * .4F);
            var view = PredictionWorkView.rendered(projection, model, net.minecraft.world.phys.Vec3.ZERO,
                    new net.minecraft.world.phys.Vec3(0, 0, -1));
            var frustum = new org.joml.FrustumIntersection(new org.joml.Matrix4f(VssLodProjection.of(projection).culling()).mul(model));
            for (int x = -10; x <= 10; x++) for (int z = -30; z < -5; z++) {
                var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, x, z, 1);
                int span = layout.tileBlocks(1);
                if (frustum.testAab(x * span, -64, z * span, (x + 1) * span, 320, (z + 1) * span))
                    assertEquals(PredictionWorkView.VISIBLE, view.band(key, layout, null, -64, 320));
            }
        }
    }

    @Test void coneIncludesFrontBufferNearbyAndScopeButNotDistantRear() {
        var layout=VssLodLayout.of(8192,2,true,true);
        var view=PredictionWorkView.of(0,80,0,1,0,0,70,16D/9);
        var front=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,40,0,1);
        var rear=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-40,0,1);
        var nearby=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,-1,0,1);
        assertTrue(view.foreground(front,layout,null,-64,320));
        assertFalse(view.foreground(rear,layout,null,-64,320));
        assertTrue(view.foreground(nearby,layout,null,-64,320));
        assertTrue(view.foreground(rear,layout,new VssLodFocus(-5056,64,1024,9000),-64,320));
        var reversed=PredictionWorkView.of(0,80,0,-1,0,0,70,16D/9);
        assertTrue(reversed.foreground(rear,layout,null,-64,320));
        assertFalse(reversed.foreground(front,layout,null,-64,320));
    }

    @Test void lookingDownFromHighAltitudeUsesTheRealThreeDimensionalView() {
        var layout=VssLodLayout.of(8192,2,true,true);
        var below=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
        var down=PredictionWorkView.of(0,5020,0,0,-1,0,70,16D/9);
        var up=PredictionWorkView.of(0,5020,0,0,1,0,70,16D/9);
        assertTrue(down.foreground(below,layout,null,-64,320));
        assertFalse(up.foreground(below,layout,null,-64,320));
        assertNull(PredictionWorkView.of(0,0,0,0,0,0,70,1));
    }
}

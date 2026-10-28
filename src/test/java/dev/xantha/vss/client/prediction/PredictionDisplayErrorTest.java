package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class PredictionDisplayErrorTest {



    @Test void eyeDepthIncludesCameraRotationAndPerspectiveScreenEdges() {
        var view = PredictionWorkView.rendered(new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1.7f,.1f,8192),
                new org.joml.Matrix4f(), Vec3.ZERO, new Vec3(0,0,-1),1475);
        assertEquals(900, view.nearestDepth(new AABB(1000,-20,-1000,1200,20,-900)), .001);
        var rotated = PredictionWorkView.rendered(new org.joml.Matrix4f().perspective((float)Math.toRadians(70),1.7f,.1f,8192),
                new org.joml.Matrix4f().lookAlong(1,0,0,0,1,0), Vec3.ZERO, new Vec3(1,0,0),1475);
        assertEquals(1000, rotated.nearestDepth(new AABB(1000,-20,-1000,1200,20,-900)), .001);
        assertTrue(rotated.changedFrom(view));
    }






}

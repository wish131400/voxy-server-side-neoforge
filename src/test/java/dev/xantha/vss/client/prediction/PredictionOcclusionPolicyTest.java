package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionOcclusionPolicyTest {
    @Test void selectsMeasuredWinnerRetestsAndRejectsStaleResults() {
        var policy = new PredictionOcclusionPolicy();
        var view = new Matrix4f();
        assertTrue(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        long epoch = policy.epoch();
        for (int i = 0; i < 8; i++) policy.accept(epoch, true, 7_500_000);
        assertFalse(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 6_400_000);
        assertFalse(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
        policy.choose(view, 20, 0, 0, 256, 256, 1, 1);
        long next = policy.epoch(); assertNotEquals(epoch, next);
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 1);
        for (int i = 0; i < 8; i++) policy.accept(next, false, 8_000_000);
        assertTrue(policy.choose(view, 20, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 8; i++) policy.accept(next, true, 4_000_000);
        assertTrue(policy.choose(view, 20, 0, 0, 256, 256, 1, 1));
        for (int i = 0; i < 481; i++) policy.choose(view, 20, 0, 0, 256, 256, 1, 1);
        assertNotEquals(next, policy.epoch(), "stable views must eventually reconsider the other path");
    }

    @Test void smallTimingDifferencesKeepTheWinner() {
        var policy = new PredictionOcclusionPolicy(); var view = new Matrix4f();
        policy.choose(view, 0, 0, 0, 256, 256, 1, 1);
        long epoch = policy.epoch();
        for (int i = 0; i < 8; i++) policy.accept(epoch, true, 5_100_000);
        for (int i = 0; i < 8; i++) policy.accept(epoch, false, 5_000_000);
        assertTrue(policy.choose(view, 0, 0, 0, 256, 256, 1, 1));
    }
}

package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.networking.client.VSSClientNetworking;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.junit.jupiter.api.Test;

class PredictionFrameEventsTest {
    @Test
    void registeredClientSubscriberReplenishesUploadsEveryFrame() throws Exception {
        // Use the same subscriber that VSSClientBootstrap registers in-game.
        // Calling budget.reset() here would hide a missing event connection.
        var bus = BusBuilder.builder().build();
        bus.register(VSSClientNetworking.class);
        var field = PredictionRenderer.class.getDeclaredField("uploadBudget");
        field.setAccessible(true);
        var budget = (PredictionUploadBudget) field.get(null);
        try {
            // Simulate a preceding frame that used all its upload slots.
            budget.record(1024, 0);
            budget.record(1024, 0);
            assertFalse(budget.allows(1024));
            int uploaded = 0;
            for (int frame = 0; frame < 60; frame++) {
                bus.post(new RenderFrameEvent.Pre(null));
                for (int slot = 0; slot < 2; slot++) {
                    assertTrue(budget.allows(1024), "GPU upload stalled at frame " + frame);
                    budget.record(1024, 100_000);
                    uploaded++;
                }
                assertFalse(budget.allows(1), "all passes must share the frame limit");
                bus.post(new RenderFrameEvent.Post(null));
                assertFalse(budget.allows(1), "frame end must not reopen upload slots");
            }
            assertEquals(120, uploaded, "uploads must continue beyond the first two meshes");
        } finally {
            bus.unregister(VSSClientNetworking.class);
            budget.reset();
        }
    }
}

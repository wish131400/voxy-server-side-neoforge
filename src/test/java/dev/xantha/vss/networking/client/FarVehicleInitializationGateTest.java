package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarVehicleInitializationGateTest {

    @Test
    void poseOnlySnapshotCannotBecomeReady() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        assertFalse(gate.shouldInitialize(false, false, false));
        assertFalse(gate.isReady());
        assertEquals(FarVehicleInitializationGate.State.WAITING_FULL_DATA, gate.state());
    }

    @Test
    void fullSnapshotRequiresInitializationPayload() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        assertFalse(gate.shouldInitialize(true, false, false));
        assertTrue(gate.shouldInitialize(true, true, false));
        assertTrue(gate.shouldInitialize(true, false, true));
    }

    @Test
    void failedCandidateNeverBecomesReady() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        gate.beginInitialization();
        assertEquals(FarVehicleInitializationGate.State.INITIALIZING, gate.state());
        gate.completeInitialization(false);

        assertFalse(gate.isReady());
        assertEquals(FarVehicleInitializationGate.State.WAITING_FULL_DATA, gate.state());
    }

    @Test
    void successfulCandidateAndValidatedExternalEntityBecomeReady() {
        FarVehicleInitializationGate gate = new FarVehicleInitializationGate();

        gate.beginInitialization();
        gate.completeInitialization(true);
        assertTrue(gate.isReady());

        gate.reset();
        gate.markExternalReady();
        assertTrue(gate.isReady());
    }
}

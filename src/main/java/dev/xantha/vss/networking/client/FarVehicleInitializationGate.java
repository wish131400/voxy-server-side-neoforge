package dev.xantha.vss.networking.client;

final class FarVehicleInitializationGate {
    enum State {
        WAITING_FULL_DATA,
        INITIALIZING,
        READY
    }

    private State state = State.WAITING_FULL_DATA;

    State state() {
        return state;
    }

    boolean shouldInitialize(boolean fullData, boolean hasEntityData, boolean hasSpawnData) {
        return fullData && hasInitializationData(hasEntityData, hasSpawnData);
    }

    void beginInitialization() {
        state = State.INITIALIZING;
    }

    void completeInitialization(boolean successful) {
        state = successful ? State.READY : State.WAITING_FULL_DATA;
    }

    void markExternalReady() {
        state = State.READY;
    }

    void reset() {
        state = State.WAITING_FULL_DATA;
    }

    boolean isReady() {
        return state == State.READY;
    }

    static boolean hasInitializationData(boolean hasEntityData, boolean hasSpawnData) {
        return hasEntityData || hasSpawnData;
    }
}

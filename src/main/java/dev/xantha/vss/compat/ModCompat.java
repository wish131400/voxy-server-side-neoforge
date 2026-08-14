package dev.xantha.vss.compat;

import java.util.OptionalInt;
import net.neoforged.fml.ModList;

public final class ModCompat {
    private static volatile boolean voxyLoaded;
    private static volatile long nextInitAttemptNanos;
    private static final long INIT_RETRY_INTERVAL_NANOS = 5_000_000_000L;

    private ModCompat() {
    }

    public static void init() {
        if (voxyLoaded) {
            return;
        }
        long now = System.nanoTime();
        if (nextInitAttemptNanos != 0L && now - nextInitAttemptNanos < 0L) {
            return;
        }
        nextInitAttemptNanos = now + INIT_RETRY_INTERVAL_NANOS;
        if (ModList.get().isLoaded("voxy") || classExists("me.cortex.voxy.common.world.service.VoxelIngestService")) {
            voxyLoaded = VoxyCompat.init();
        }
    }

    public static OptionalInt getVoxyViewDistanceChunks() {
        return voxyLoaded ? VoxyCompat.getViewDistanceChunks() : OptionalInt.empty();
    }

    public static boolean isVoxyLoaded() {
        return voxyLoaded;
    }

    public static LocalColumnState getVoxyLocalColumnState(net.minecraft.world.level.Level level, int chunkX, int chunkZ) {
        return voxyLoaded ? VoxyCompat.getLocalColumnState(level, chunkX, chunkZ) : LocalColumnState.UNKNOWN;
    }

    public static void clientTick() {
        if (voxyLoaded) {
            VoxyCompat.clientTick();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, ModCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public enum LocalColumnState {
        PRESENT,
        MISSING,
        UNKNOWN
    }
}

package dev.xantha.vss.compat;

import java.util.OptionalInt;
import net.neoforged.fml.ModList;

public final class ModCompat {
    private static boolean voxyLoaded;

    private ModCompat() {
    }

    public static void init() {
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

    public static LocalColumnState getVoxyLocalColumnState(
            net.minecraft.world.level.Level level,
            int chunkX,
            int chunkZ,
            byte[] expectedSectionYs) {
        return voxyLoaded
                ? VoxyCompat.getLocalColumnState(level, chunkX, chunkZ, expectedSectionYs)
                : LocalColumnState.UNKNOWN;
    }

    public static void clientTick() {
        if (voxyLoaded) {
            VoxyCompat.clientTick();
        }
    }

    public static void resetVoxyLocalValidation() {
        if (voxyLoaded) {
            VoxyCompat.resetLocalValidation();
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

package dev.xantha.vss.client.prediction.feature;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Structure code that seeds a random from {@code level.getLevel().getSeed()} cannot run on a prediction level, whose
 * getLevel() has no server to return. Vanilla's CappedProcessor (ocean ruins' suspicious sand and gravel, and many
 * modded structures) does exactly that, so every such structure was rolled back and skipped in far terrain.
 *
 * A prediction level answers with its own world seed instead. Real generation (WorldGenRegion), every Level and every
 * accessor that is not a WorldGenLevel keep the server's seed, read through getLevel() as before.
 */
public final class PredictionLevelSeed {
    private PredictionLevelSeed() { }

    /** True for a WorldGenLevel that is neither a WorldGenRegion nor a Level: the prediction levels. */
    public static boolean ownSeed(ServerLevelAccessor level) {
        return !(level instanceof WorldGenRegion) && !(level instanceof Level) && level instanceof WorldGenLevel;
    }
}

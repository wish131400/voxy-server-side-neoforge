package dev.xantha.vss.mixin.thebeyond;

import dev.xantha.vss.client.prediction.feature.PredictionLevelDimension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(StructureStart.class)
abstract class StructureStartPredictionEndMixin {
    // Only caller: The Beyond's merged placeInChunk handler, after BeyondHandlerPatch inserted the call.
    // 1 = a VSS prediction level of the End, 0 = of any other dimension, -1 = keep The Beyond's own getLevel() check.
    @Unique
    private static int vss$predictionEnd(WorldGenLevel level) {
        ResourceLocation dimension = PredictionLevelDimension.of(level);
        if (dimension == null) return -1;
        return Level.END.location().equals(dimension) ? 1 : 0;
    }
}

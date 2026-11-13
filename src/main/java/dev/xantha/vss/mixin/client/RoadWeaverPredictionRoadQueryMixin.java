package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.feature.PredictionLevelDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelSimulatedReader;
import net.minecraft.world.level.WorldGenLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RoadWeaver resolves any level that is neither a ServerLevel nor a WorldGenRegion through getLevel(), and only then
 * answers false for a dimension other than the Overworld. On a prediction level getLevel() throws, and RoadWeaver's
 * feature hooks swallowed one exception per predicted tree, plant and template. Absent RoadWeaver, nothing applies.
 */
@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.persistence.RoadSpatialIndex", remap = false)
public abstract class RoadWeaverPredictionRoadQueryMixin {
    @Inject(method = "isNearRoad(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vss$predictionWorldGenLevel(WorldGenLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (PredictionLevelDimension.knownNonOverworldRoadQuery(level)) result.setReturnValue(false);
    }

    @Inject(method = "isNearRoad(Lnet/minecraft/world/level/LevelSimulatedReader;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void vss$predictionSimulatedReader(LevelSimulatedReader level, BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (PredictionLevelDimension.knownNonOverworldRoadQuery(level)) result.setReturnValue(false);
    }
}

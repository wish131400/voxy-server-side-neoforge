package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"com.moepus.byepregen.worldgen.feature.FastBlockPredicateOptimizer",
        "com.moepus.byepregen.Feature.FastBlockPredicateOptimizer"}, remap = false)
public abstract class ByePregenPredictionPredicateMixin {
    @Inject(method = "getState(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;III)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void vss$virtualTerrainPredicate(WorldGenLevel level, BlockPos pos,
                                                  int offsetX, int offsetY, int offsetZ,
                                                  CallbackInfoReturnable<BlockState> ci) {
        if (level instanceof FeatureStampLevel stamp) {
            ci.setReturnValue(stamp.predicateState(pos, offsetX, offsetY, offsetZ));
        }
    }
}

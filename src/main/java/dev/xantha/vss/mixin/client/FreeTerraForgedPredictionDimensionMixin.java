package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import dev.xantha.vss.client.prediction.PredictionDimensionFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FTF compares live ServerLevels for a dimension blacklist. A virtual level already knows its dimension ID. */
@Pseudo
@Mixin(targets = {"etcodehome.freeterraforged.world.worldgen.feature.placement.BlacklistDimensionFilter",
        "raccoonman.reterraforged.world.worldgen.feature.placement.BlacklistDimensionFilter"}, remap = false)
public abstract class FreeTerraForgedPredictionDimensionMixin {
    @Inject(method = "shouldPlace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void vss$predictionDimension(PlacementContext context, RandomSource random, BlockPos position,
            CallbackInfoReturnable<Boolean> result) {
        if (context.getLevel() instanceof FeatureStampLevel prediction) {
            Boolean allowed = PredictionDimensionFilter.allows(this, prediction.predictionDimension());
            if (allowed != null) result.setReturnValue(allowed);
        }
    }
}

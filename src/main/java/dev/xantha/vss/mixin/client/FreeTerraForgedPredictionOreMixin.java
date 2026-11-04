package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import java.util.Optional;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Non-ore prediction placement cannot activate a live-server ore distribution plan. */
@Pseudo
@Mixin(targets="etcodehome.freeterraforged.world.worldgen.feature.ore.DynamicOrePlacement",remap=false)
public abstract class FreeTerraForgedPredictionOreMixin {
    @Inject(method="activation",at=@At("HEAD"),cancellable=true,require=0,remap=false)
    private static void vss$nonOrePrediction(PlacementModifier modifier,PlacementContext context,
            CallbackInfoReturnable<Optional<?>> result) {
        if (context != null && context.getLevel() instanceof FeatureStampLevel
                && context.topFeature().filter(feature -> {
                    var type=feature.feature().value().feature();
                    return type==Feature.ORE || type==Feature.SCATTERED_ORE;
                }).isEmpty()) result.setReturnValue(Optional.empty());
    }
}

package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.FreeTerraForgedNativeFilters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "raccoonman.reterraforged.world.worldgen.WorldFilters", remap = false)
public abstract class FreeTerraForgedPredictionFiltersMixin {
    @Inject(method = "apply(Lraccoonman/reterraforged/world/worldgen/densityfunction/tile/Tile;Z)V", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void vss$nativePredictionFilters(@Coerce Object tile, boolean optional, CallbackInfo ci) {
        if (FreeTerraForgedNativeFilters.apply(this, tile, optional)) ci.cancel();
    }
}

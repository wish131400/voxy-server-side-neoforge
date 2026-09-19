package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.SpyglassOverlayTiming;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Gui.class)
public abstract class SpyglassOverlayTimingMixin {
    @ModifyArg(method = "renderCameraOverlays", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 0), index = 0)
    private float vss$boundScopeAnimation(float factor) {
        return SpyglassOverlayTiming.interpolation(factor);
    }
}

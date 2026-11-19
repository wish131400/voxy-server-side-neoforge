package dev.xantha.vss.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Captures the native image while it is alive, within the caller's IO scope. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.SaveLoadSystem3", remap = false)
public abstract class PredictionVoxyStorageCodecMixin { }

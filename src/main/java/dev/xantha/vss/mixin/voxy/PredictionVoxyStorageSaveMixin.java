package dev.xantha.vss.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin wraps actual native saves, after queue admission and before error handling. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.SectionSavingService", remap = false)
public abstract class PredictionVoxyStorageSaveMixin { }

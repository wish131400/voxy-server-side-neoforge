package dev.xantha.vss.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Associates the exact native storage with its original world identifier. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class PredictionVoxyStorageIdentityMixin { }

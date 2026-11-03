package dev.xantha.vss.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Method bodies are adapted by VoxyStorageTransformer after shape verification. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.config.section.SectionSerializationStorage", remap = false)
public abstract class PredictionVoxyStorageMixin { }

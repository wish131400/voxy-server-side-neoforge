package dev.xantha.vss.mixin.lostcities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin synchronizes only the legacy cache's short operations. */
@Pseudo
@Mixin(targets = "mcjty.lostcities.varia.TimedCache", remap = false)
public abstract class TimedCacheConcurrencyMixin { }

package dev.xantha.vss.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The compat plugin supplies the exact read-only context adaptation. */
@Pseudo
@Mixin(targets = {"etcodehome.freeterraforged.world.worldgen.feature.placement.poisson.FastPoissonModifier",
        "etcodehome.freeterraforged.world.worldgen.feature.placement.UndergroundFeatureEnclosure",
        "etcodehome.freeterraforged.world.worldgen.feature.RiverGasketFeature",
        "etcodehome.freeterraforged.world.worldgen.feature.DecorateSnowFeature",
        "etcodehome.freeterraforged.world.worldgen.feature.template.TemplateFeature",
        "raccoonman.reterraforged.world.worldgen.feature.placement.poisson.FastPoissonModifier",
        "raccoonman.reterraforged.world.worldgen.feature.placement.UndergroundFeatureEnclosure",
        "raccoonman.reterraforged.world.worldgen.feature.RiverGasketFeature",
        "raccoonman.reterraforged.world.worldgen.feature.DecorateSnowFeature",
        "raccoonman.reterraforged.world.worldgen.feature.template.TemplateFeature",
        "com.pandaismyname1.ultraterraforged.world.worldgen.feature.placement.poisson.FastPoissonModifier",
        "com.pandaismyname1.ultraterraforged.world.worldgen.feature.placement.UndergroundFeatureEnclosure",
        "com.pandaismyname1.ultraterraforged.world.worldgen.feature.RiverGasketFeature",
        "com.pandaismyname1.ultraterraforged.world.worldgen.feature.DecorateSnowFeature",
        "com.pandaismyname1.ultraterraforged.world.worldgen.feature.template.TemplateFeature"}, remap = false)
public abstract class FreeTerraForgedPredictionDecorationMixin { }

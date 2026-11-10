package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Worker-owned flat template capture with the same verified resource catalog as exact decoration. */
class PredictionTemplateStampLevel extends FeatureStampLevel {
    private final ClientTerrainSampler context;
    private PredictionStructureTemplates templates;

    net.minecraft.world.level.chunk.ChunkGenerator predictionGenerator() { return context.generatorContext(); }

    PredictionTemplateStampLevel(long seed, ClientTerrainSampler context, BlockState ground) {
        super(seed, context.decorationAccess(), ground);
        this.context = context;
    }

    @Override public ResourceLocation predictionDimension() { return context.profile().dimension(); }

    PredictionStructureTemplates templateManager() {
        if (templates == null) {
            try { templates = PredictionStructureTemplates.open(context.structureTemplates()); }
            catch (java.io.IOException failure) {
                throw new IllegalStateException("Prediction tree template manager unavailable", failure);
            }
        }
        return templates;
    }
}

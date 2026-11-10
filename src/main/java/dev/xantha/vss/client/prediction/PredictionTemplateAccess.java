package dev.xantha.vss.client.prediction;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** Optional features can obtain templates without reaching a live ServerLevel. */
public final class PredictionTemplateAccess {
    private PredictionTemplateAccess() { }

    public static boolean virtual(WorldGenLevel level) { return level instanceof PredictionDecorationLevel || level instanceof PredictionTemplateStampLevel; }

    public static StructureTemplateManager manager(WorldGenLevel level) {
        if (level instanceof PredictionDecorationLevel prediction) return prediction.templateManager();
        if (level instanceof PredictionTemplateStampLevel stamp) return stamp.templateManager();
        throw new IllegalArgumentException("Not a prediction template level");
    }

    public static ChunkGenerator generator(WorldGenLevel level) {
        if (level instanceof PredictionDecorationLevel prediction) return prediction.predictionGenerator();
        if (level instanceof PredictionTemplateStampLevel stamp) return stamp.predictionGenerator();
        throw new IllegalArgumentException("Not a prediction template level");
    }
}

package dev.xantha.vss.client.prediction;

import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.SpikeConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** Reuses vanilla block placement while omitting live-world post-processing. */
final class PredictionSurfaceFeatureAdapters {
    private static final LakeFeature LAKE = new LakeFeature(LakeFeature.Configuration.CODEC) {
        @Override protected void markAboveForPostProcessing(WorldGenLevel level, BlockPos pos) {
            // Prediction stores block states; real chunks own subsequent ticking.
        }
    };

    private PredictionSurfaceFeatureAdapters() { }

    static void place(PlacedFeature feature, PredictionDecorationLevel level, ChunkGenerator generator,
                      RandomSource random, BlockPos origin) {
        var configured = feature.feature().value();
        if (configured.feature() != Feature.END_SPIKE && configured.feature() != Feature.LAKE) {
            feature.placeWithBiomeCheck(level,generator,random,origin);
            return;
        }
        // BiomeFilter must see the original registered placed feature, including
        // its modifiers and global seed index, even when its block adapter differs.
        var context = new PlacementContext(level,generator,Optional.of(feature));
        Stream<BlockPos> positions = Stream.of(origin);
        for (var modifier : feature.placement())
            positions = positions.flatMap(pos -> modifier.getPositions(context,random,pos));
        positions.forEach(pos -> {
            if (configured.feature() == Feature.END_SPIKE)
                PredictionEndSpikes.place(level,random,(SpikeConfiguration)configured.config(),pos);
            else new ConfiguredFeature<>(LAKE,(LakeFeature.Configuration)configured.config()).place(level,generator,random,pos);
        });
    }
}

package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.feature.FeatureStampLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.fml.ModList;

/** Feature placement for VSS-owned virtual worlds when another mod requires a live server. */
final class PredictionConfiguredFeaturePlacement {
    private static final boolean STREAMS_PRESENT = ModList.get() != null
            && ModList.get().isLoaded("streamsreflowing");

    private PredictionConfiguredFeaturePlacement() { }

    static boolean requiresVirtualPlacement(WorldGenLevel level) {
        return STREAMS_PRESENT && level instanceof FeatureStampLevel;
    }

    static boolean streamsPresent() { return STREAMS_PRESENT; }

    static boolean place(ConfiguredFeature<?, ?> configured, WorldGenLevel level,
                         ChunkGenerator generator, RandomSource random, BlockPos origin) {
        return place(configured, level, generator, random, origin, requiresVirtualPlacement(level));
    }

    /** Package-visible seam verifies the installed-mod branch without a Minecraft launcher. */
    static boolean place(ConfiguredFeature<?, ?> configured, WorldGenLevel level,
                         ChunkGenerator generator, RandomSource random, BlockPos origin, boolean bypass) {
        if (!bypass) return configured.place(level, generator, random, origin);
        if (!(level instanceof FeatureStampLevel))
            throw new IllegalArgumentException("Direct feature replay requires a VSS virtual world");
        // ConfiguredFeature.place delegates to Feature.place(config, ...), which owns
        // ensureCanWrite and constructs the ordinary FeaturePlaceContext.
        // Streams Reflowing 2.16.2 injects a live-ServerLevel lookup into that wrapper. On our
        // isolated level, call the feature itself with the same inputs; do not change real worldgen.
        return placeDirect(configured, level, generator, random, origin);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean placeDirect(ConfiguredFeature<?, ?> configured, WorldGenLevel level,
                                       ChunkGenerator generator, RandomSource random, BlockPos origin) {
        Feature raw = configured.feature();
        return raw.place(configured.config(), level, generator, random, origin);
    }
}

package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionTemplateAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.Map;

/** OTTYG/BWG tree templates use the same verified catalog as buildings. */
@Pseudo
@Mixin(targets = {
        "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeature",
        "dev.corgitaco.ohthetreesyoullgrow.world.level.levelgen.feature.TreeFromStructureNBTFeatureV2"
}, remap = false)
public abstract class NbtTreePredictionTemplatesMixin {
    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getLevel()Lnet/minecraft/server/level/ServerLevel;"), remap = false)
    private ServerLevel vss$avoidLiveWorld(WorldGenLevel level) {
        // Only the immediately redirected getStructureManager consumes this receiver.
        return PredictionTemplateAccess.virtual(level) ? null : level.getLevel();
    }

    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getStructureManager()Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;"),
            remap = false)
    private StructureTemplateManager vss$verifiedTreeTemplates(ServerLevel level, FeaturePlaceContext<?> context) {
        return PredictionTemplateAccess.virtual(context.level())
                ? PredictionTemplateAccess.manager(context.level()) : level.getStructureManager();
    }


    // OTTYG 5.3.4+ tests height through level.getLevel().getChunkSource().getGenerator().
    // Keep the original height policy; replace only the unavailable generator lookup on virtual levels.
    @Redirect(method = "testHeightLimit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getLevel()Lnet/minecraft/server/level/ServerLevel;"),
            require = 0, remap = false)
    private static ServerLevel vss$heightLevel(WorldGenLevel level) {
        return PredictionTemplateAccess.virtual(level) ? null : level.getLevel();
    }

    @Redirect(method = "testHeightLimit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getChunkSource()Lnet/minecraft/server/level/ServerChunkCache;"),
            require = 0, remap = false)
    private static ServerChunkCache vss$heightChunks(ServerLevel level) {
        return level == null ? null : level.getChunkSource();
    }

    @Redirect(method = "testHeightLimit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;getGenerator()Lnet/minecraft/world/level/chunk/ChunkGenerator;"),
            require = 0, remap = false)
    private static ChunkGenerator vss$heightGenerator(ServerChunkCache chunks, @Coerce Object config,
            WorldGenLevel level, Map<?, ?> logs, Map<?, ?> leaves, Map<?, ?> other) {
        return PredictionTemplateAccess.virtual(level) ? PredictionTemplateAccess.generator(level) : chunks.getGenerator();
    }
}
